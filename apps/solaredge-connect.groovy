/**
 * SolarEdge Connect: Hubitat integration for SolarEdge sites, through the
 * mySolarEdge app's cloud API on monitoring.solaredge.com (e-mail and password,
 * not the public API-key Monitoring API).
 *
 * The app holds the account, one login session and one poll timer. Every tick
 * asks SolarEdge once per site for the power flow (and once for the smart
 * devices, when there are EV chargers or load controllers), and hands the
 * answers to all child devices of that site. Identical requests within a tick
 * go out once. Energy totals, battery and inverter details follow on a 5-minute
 * energy tick placed just after the inverters upload; slower data (alerts,
 * optimizer temperatures, EV charging history) on every third energy tick.
 *
 * All SolarEdge mapping lives in this file as plain functions of the API
 * answers, so the device drivers stay thin.
 *
 * Ported from com.solaredge v2.0.0 for Homey by Robin de Gruijter
 * (https://github.com/gruijter/com.solaredge), itself based on the app by
 * Emile Nijssen (Athom). Changes for Hubitat: see README.md.
 *
 * Copyright (C) 2026 Robin de Gruijter (original), 2026 Digital Homesteaders (Hubitat port)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. It is distributed WITHOUT ANY WARRANTY; see the GNU General Public
 * License (LICENSE) for details.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

definition(
    name: "SolarEdge Connect",
    namespace: "solaredge",
    author: "Digital Homesteaders",
    description: "SolarEdge inverters, meters, batteries, EV chargers and load controllers through the mySolarEdge cloud.",
    category: "Convenience",
    singleInstance: false,
    iconUrl: "",
    iconX2Url: "",
    importUrl: ""
)

preferences {
    page(name: "mainPage")
    page(name: "devicesPage")
}

// ---------------------------------------------------------------- constants

@Field static final String BASE_URL = "https://monitoring.solaredge.com"
@Field static final String LOGIN_PATH = "/solaredge-apigw/api/login"
// The mySolarEdge iOS app's headers.
@Field static final Map API_HEADERS = ["User-Agent": "SolarEdge/14 CFNetwork/1568.200.51 Darwin/24.1.0", "CLIENT-VERSION": "3.12", "Accept": "application/json"]

// Seconds per request.
@Field static final int REQUEST_TIMEOUT = 20
// Live power: default and allowed range, seconds.
@Field static final int DEFAULT_POLL = 60
@Field static final int MIN_POLL = 30
@Field static final int MAX_POLL = 300
// A tick still waiting for answers after this long is given up on: longer than the
// longest legitimate chain (login, request, re-login, retry, inverter follow-up) of
// REQUEST_TIMEOUT each, plus slack.
@Field static final long STALE_TICK_MS = 5 * 20 * 1000L + 10000L
// Every third energy tick (15 minutes) also fetches the slow data.
@Field static final int SLOW_EVERY = 3
// A fast tick this close before the energy tick is left to the energy tick (which polls too).
@Field static final long ENERGY_MERGE_MS = 15 * 1000L
// Solar forecast (https://forecast.solar, free public API, no account).
@Field static final String FORECAST_URL = "https://api.forecast.solar"
@Field static final int DEFAULT_TILT = 30
// Compass bearing the panels face; 180 = south. Forecast.Solar counts from south (-90 east, 90 west).
@Field static final int DEFAULT_BEARING = 180
// The location sent is rounded to this many decimals (about 1 km).
@Field static final int FORECAST_DECIMALS = 2
// A devices list SolarEdge last updated longer ago than this counts as stale.
@Field static final long STALE_DATA_MS = 10 * 60 * 1000L
// Hubitat log lines are kept short: raw answers are logged in pieces of this size.
@Field static final int RAW_CHUNK = 1500
// Fields left out of raw answers in the log: vehicle and network identifiers.
@Field static final List RAW_REDACT = ["macAddress", "physicalId", "siteApplianceUUID", "rfIdCardsListSupported"]
// Battery charged/discharged are integrated from power samples; not across a gap longer
// than this or 3 poll intervals (an outage, a hub restart).
@Field static final long MAX_SAMPLE_GAP_MS = 10 * 60 * 1000L
// After rejected credentials no automatic login for this long, doubling per rejection.
@Field static final long LOGIN_RETRY_MIN = 60 * 1000L
@Field static final long LOGIN_RETRY_MAX = 60 * 60 * 1000L
// After an outage ticks pause for twice the poll interval, doubling up to this.
@Field static final long BACKOFF_MAX = 30 * 60 * 1000L
// Devices show healthStatus offline after this many failed ticks in a row.
@Field static final int FAIL_LIMIT = 3
// Meter yearly totals: without an install year, look back at most this far.
@Field static final int YEARS_BACK = 10
// A past year is final this long after it ended (it has ended in every timezone).
@Field static final long YEAR_SETTLE_MS = 2 * 24 * 3600 * 1000L
// After a failed run of the yearly totals, wait this long before the next.
@Field static final long YEARS_RETRY_MS = 15 * 60 * 1000L

// Energy tick timing (lib/solarEdgeTiming.js): inverters upload every 5 minutes at a
// fixed second; the energy tick goes a margin after that moment.
@Field static final long PERIOD_MS = 5 * 60 * 1000L
@Field static final long MARGIN_MS = 20 * 1000L
@Field static final long MARGIN_STEP_MS = 15 * 1000L
@Field static final long MARGIN_DECAY_MS = 5 * 1000L
@Field static final long MAX_MARGIN_MS = 2 * 60 * 1000L
@Field static final long MAX_SPREAD_MS = 60 * 1000L
// An inverter silent this long past its upload interval has stopped for the night;
// longer than a day is not a night.
@Field static final long SILENT_MS = 5 * 60 * 1000L
@Field static final long MAX_SLEEP_MS = 24 * 3600 * 1000L

@Field static final Map DRIVERS = [
    inverter: "SolarEdge Inverter",
    meter: "SolarEdge Meter",
    battery: "SolarEdge Battery",
    ev: "SolarEdge EV Charger",
    hlc: "SolarEdge Load Controller",
]

// energyOverview period -> attribute.
@Field static final Map ENERGY_PERIODS = [LIFE_TIME: "energyLifetime", LAST_YEAR: "energyYear", LAST_MONTH: "energyMonth", LAST_DAY: "energyToday"]
@Field static final List INVERTER_STATUSES = ["production", "sleeping", "starting", "throttled", "shutting_down", "fault", "standby", "off"]
// EV connectionStatus -> [chargingState, charging].
@Field static final Map EV_CONNECTION = [
    connected: ["plugged_in", false],
    charging: ["plugged_in_charging", true],
    disconnected: ["plugged_out", false],
]
@Field static final Map EV_MODES = [AUTO: "auto", MANUAL: "manual"]
// excessPV: -1 solar surplus charging on, -2 off.
@Field static final Map EXCESS_PV = ["-1": true, "-2": false]
// Load controller state -> activationState body.
@Field static final Map HLC_STATES = [
    on: [mode: "MANUAL", level: 100],
    off: [mode: "MANUAL", level: 0],
    auto: [mode: "AUTO", level: null, duration: null],
]

// Per app instance, in memory only: the session cookies, login and backoff
// bookkeeping, and the tick in flight with its answers. Lost on a hub reboot,
// which costs one login. Nothing here goes into state, so no response bodies
// and no session are stored.
@Field static final Map RUNTIME = new ConcurrentHashMap()

// ---------------------------------------------------------------- pages

def mainPage() {
    dynamicPage(name: "mainPage", title: "SolarEdge Connect", install: true, uninstall: true) {
        section("mySolarEdge account") {
            input "username", "email", title: "E-mail", required: true, submitOnChange: true
            input "password", "password", title: "Password", required: true, submitOnChange: true
            paragraph "Kept only in this app's settings and never written to the logs. Devices don't get a copy."
            if (state.status) paragraph "<b>Status:</b> ${state.status}"
        }
        if (settings.username && settings.password) {
            section("Devices") {
                href "devicesPage", title: "Choose devices", description: selectedSummary()
                siteDevices().each { cd ->
                    paragraph "${cd.displayName}: ${cd.currentValue('power') ?: 0} W (${cd.currentValue('healthStatus') ?: 'unknown'})"
                }
            }
        }
        section("Polling") {
            input "pollSeconds", "number", title: "Live power every (seconds, ${MIN_POLL}-${MAX_POLL})", range: "${MIN_POLL}..${MAX_POLL}",
                defaultValue: DEFAULT_POLL, required: false
            paragraph "Energy totals, battery and inverter details are read every 5 minutes, just after the inverters upload; " +
                "alerts, optimizer temperatures and EV charging history every 15 minutes."
        }
        section("Solar forecast") {
            input "forecastEnable", "bool", title: "Solar forecast from Forecast.Solar", defaultValue: false, submitOnChange: true
            if (settings.forecastEnable) {
                input "forecastKwp", "decimal", title: "System size (kWp)${defaultKwp() ? ', default ' + defaultKwp() + ' from SolarEdge' : ''}", required: false
                input "forecastTilt", "number", title: "Panel tilt (degrees, 0 flat to 90 upright)", range: "0..90", defaultValue: DEFAULT_TILT, required: false
                input "forecastBearing", "number", title: "Direction the panels face (compass degrees: 90 east, 180 south, 270 west)",
                    range: "0..359", defaultValue: DEFAULT_BEARING, required: false
                paragraph "Once an hour, the hub's location (rounded to about 1 km), the tilt, direction and size go to " +
                    "api.forecast.solar, which answers with the expected solar power for today and tomorrow. Free, no account. " +
                    "The inverter then shows forecastToday, forecastTomorrow, forecastPower (now) and actualVsForecast (today so far)."
            }
        }
        section("Logging") {
            input "logEnable", "bool", title: "Debug logging (turns itself off after 30 minutes)", defaultValue: false
            if (siteDevices()) {
                input name: "dumpRaw", type: "button", title: "Log raw SolarEdge data"
                paragraph "Writes SolarEdge's current answers for each site (power flow, devices, batteries, inverters) to Logs, " +
                    "without vehicle or network identifiers. Stale or failing answers are also logged as warnings on their own."
            }
        }
    }
}

def devicesPage() {
    Long started = state.discovering as Long
    if (started && now() - started > 90000) {
        state.remove("discovering")
        started = null
        state.discoverError = state.status ?: "SolarEdge did not answer"
    }
    if (!started && (!state.discoveredAt || now() - (state.discoveredAt as Long) > 60000)) {
        startDiscovery()
        started = state.discovering as Long
    }
    Map pg = [name: "devicesPage", title: "Choose devices"]
    if (started) pg.refreshInterval = 3
    dynamicPage(pg) {
        section {
            if (started) {
                paragraph "Looking up the sites and devices on your SolarEdge account..."
            } else if (state.discoverError) {
                paragraph "Could not list devices: ${state.discoverError}"
            }
            Map found = state.discovered ?: [:]
            if (found) {
                Map options = found.collectEntries { dni, d -> [(dni): d.label] }.sort { it.value }
                input "selectedDevices", "enum", title: "Devices to add to Hubitat", options: options, multiple: true, required: false
                paragraph "Devices you untick are removed from Hubitat when you press Done."
            } else if (!started && !state.discoverError) {
                paragraph "No sites found on this account."
            }
        }
    }
}

private String selectedSummary() {
    int n = (settings.selectedDevices ?: []).size()
    n ? "${n} device${n == 1 ? '' : 's'} selected" : "Tap to pick devices"
}

// ---------------------------------------------------------------- lifecycle

def installed() {
    initialize()
}

def updated() {
    unschedule()
    initialize()
}

def uninstalled() {
    unschedule()
    RUNTIME.remove(app.id)
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

void initialize() {
    Map rt = runtime()
    // New credentials: drop the session and any login block.
    String credKey = credentialsKey()
    if (rt.credKey != credKey) {
        rt.credKey = credKey
        rt.cookies = []
    }
    // Done in the app retries now: no login hold or outage pause carries over (the
    // status after a failure says so).
    rt.loginBlockedUntil = 0L
    rt.loginRetryMs = 0L
    rt.backoffUntil = 0L
    rt.outages = 0
    if (settings.logEnable) runIn(1800, "logsOff")
    syncChildren()
    state.timing = timing()
    state.remove("forecast")  // kept in memory now (runtime forecast)
    if (settings.forecastEnable && siteDevices()) {
        runEvery1Hour("forecastRefresh")
        runIn(10, "forecastRefresh")
    } else {
        runtimeMap("forecast").clear()
        siteDevices().findAll { it.getDataValue("type") == "inverter" && it.currentValue("forecastToday") != null }.each { cd -> cd.clearForecast() }
    }
    if (siteDevices()) {
        rt.energyDue = true
        runIn(2, "tick")
        scheduleEnergyTick()
    }
}

def logsOff() {
    log.warn "SolarEdge: debug logging turned off"
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}

private void syncChildren() {
    List selected = settings.selectedDevices ?: []
    Map found = state.discovered ?: [:]
    selected.each { String dni ->
        if (getChildDevice(dni)) return
        Map d = found[dni]
        if (!d) return
        String driver = DRIVERS[d.type]
        try {
            Map data = [siteId: d.siteId as String, type: d.type]
            if (d.reporterId) data.reporterId = d.reporterId as String
            if (d.serials) data.serials = d.serials
            addChildDevice("solaredge", driver, dni, [name: driver, label: d.label, isComponent: false, data: data])
            log.info "SolarEdge: added ${d.label}"
        } catch (e) {
            log.error "SolarEdge: could not add ${d.label}: ${e.message} (is the '${driver}' driver installed?)"
        }
    }
    siteDevices().each { cd ->
        if (!(cd.deviceNetworkId in selected)) {
            log.info "SolarEdge: removing ${cd.displayName}"
            deleteChildDevice(cd.deviceNetworkId)
        }
    }
}

private List siteDevices() { getChildDevices() ?: [] }

private int pollInterval() {
    Integer s = settings.pollSeconds as Integer
    if (s == null) return DEFAULT_POLL
    return Math.max(MIN_POLL, Math.min(MAX_POLL, s))
}

/** Tells credential changes apart without keeping the credentials anywhere but settings. */
private String credentialsKey() { "${settings.username}\n${settings.password}".hashCode().toString() }

private Map runtime() {
    Map rt = RUNTIME[app.id]
    if (rt == null) {
        rt = new ConcurrentHashMap([cookies: [], loginBlockedUntil: 0L, loginRetryMs: 0L, backoffUntil: 0L, outages: 0,
            waiting: new ConcurrentHashMap(), loggedOnce: new ConcurrentHashMap(), credKey: credentialsKey()])
        RUNTIME[app.id] = rt
    }
    return rt
}

// ---------------------------------------------------------------- child device calls

/** A device's Refresh: an extra tick now (skipped if one is in flight). */
void refreshNow() {
    runIn(1, "tick")
}

/** Commands from the EV charger and load controller drivers. */
void childCommand(String dni, String cmd, List args = []) {
    def cd = getChildDevice(dni)
    if (!cd) return
    String siteId = cd.getDataValue("siteId")
    String reporterId = cd.getDataValue("reporterId")
    Map activation = activationBody(cd.getDataValue("type"), cmd, args)
    if (activation == null) {
        log.warn "SolarEdge: ${cd.displayName}: unknown command ${cmd} ${args}"
        return
    }
    logDebug "${cd.displayName}: ${cmd} -> activationState ${activation}"
    send([key: "PUT ${reporterId}", method: "PUT", path: "/services/m/api/homeautomation/v1.0/${siteId}/devices/${reporterId}/activationState",
        body: JsonOutput.toJson(activation), cb: "commandDone", ctx: [dni: dni, cmd: cmd]])
}

/** The activationState body for a command, or null for an unknown one. */
Map activationBody(String type, String cmd, List args) {
    if (type == "ev") {
        if (cmd == "on") return [mode: "MANUAL", level: 100, duration: null]
        if (cmd == "off") return [mode: "MANUAL", level: 0, duration: null]
        if (cmd == "setAutomatic") return [mode: "AUTO"]
    }
    if (type == "hlc") {
        if (cmd in ["on", "off"]) return new LinkedHashMap(HLC_STATES[cmd])
        if (cmd == "setAutomatic") return new LinkedHashMap(HLC_STATES.auto)
        if (cmd == "setState" && args && HLC_STATES[args[0] as String]) return new LinkedHashMap(HLC_STATES[args[0] as String])
    }
    return null
}

def commandDone(Map result, Map ctx) {
    if (result.error) {
        log.warn "SolarEdge: ${ctx.cmd} failed: ${result.error}"
        return
    }
    log.info "SolarEdge: ${ctx.cmd} sent"
    // Read the new state back soon.
    runIn(5, "tick")
}

// ---------------------------------------------------------------- requests

// GET request descriptions. `key` is what identical requests share within a tick.
Map reqPowerflow(def siteId) { apiGet("/services/m/so/dashboard/v2/site/${siteId}/powerflow/latest", [components: "consumption,grid,storage,ev-charger"]) }
Map reqSiteDevices(def siteId) { apiGet("/services/m/api/homeautomation/v1.0/sites/${siteId}/devices") }
Map reqEnergyOverview(def siteId) { apiGet("/services/m/so/dashboard/site/${siteId}/energyOverview") }
Map reqEquipment(def siteId) { apiGet("/services/dashboard/v2/site-details/${siteId}/equipment") }
Map reqInverters(List serials) { apiGet("/services/layout/information/inverters", ["inverter-serials": serials]) }
Map reqEnergyByInverter(def siteId, String day, List serials) {
    apiGet("/services/layout/energy/site/${siteId}/by-inverter", ["start-date": day, "end-date": day, "inverter-serials": serials,
        "include-max-temperature": "true", "include-color": "true"])
}
Map reqBatteries(def siteId) { apiGet("/services/m/api/homeautomation/v1.0/storage/${siteId}/getBatteries", [triggerHF: "false"]) }
Map reqMeasurements(def siteId, int year) {
    apiGet("/services/m/so/dashboard/site/${siteId}/measurements", [period: "YEAR", "measurement-unit": "WATT_HOUR", "end-date": "${year}-12-31"])
}
Map reqSessions(def siteId, def reporterId) {
    apiGet("/services/m/api/homeautomation/v1.0/${siteId}/devices/${reporterId}/applianceSessionsHistory", [timeRange: "YEAR", endTime: "9999999999999", startTime: "0"])
}
Map reqCommunication(def siteId) { apiGet("/services/dashboard/site-details/${siteId}/communication") }
Map reqAlerts(def siteId) { apiGet("/services/dashboard/alerts/sites/${siteId}") }
Map reqSites() { apiGet("/services/m/so/sites/") }

private Map apiGet(String path, Map query = [:]) {
    String q = query.collect { k, v -> (v instanceof List ? v : [v]).collect { "${k}=${it}" }.join("&") }.join("&")
    return [key: (q ? "${path}?${q}" : path).toString(), method: "GET", path: path, query: query]
}

/**
 * Send one request; calls `req.cb(result, req.ctx)` with result [json: ...] or
 * [error: message, status: n, outage: bool]. Logs in first when there is no
 * session; a 401 or a redirect to the login page logs in again once and retries.
 */
private void send(Map req) {
    Map rt = runtime()
    if (!rt.cookies) {
        // One entry per request, so two callers of the same URL both get their answer;
        // `seq` keeps them in order (an "on" then "off" must go out in that order).
        long seq = ((rt.seq ?: 0L) as long) + 1
        rt.seq = seq
        rt.waiting[UUID.randomUUID().toString()] = req + [seq: seq]
        startLogin()
        return
    }
    Map params = [uri: BASE_URL, path: req.path, headers: API_HEADERS + [Cookie: rt.cookies.join("; ")],
        contentType: "application/json", timeout: REQUEST_TIMEOUT]
    if (req.query) params.query = req.query
    // Everything a retry needs travels with the callback; `gen` is the session it went out on.
    Map data = [key: req.key, method: req.method, path: req.path, query: req.query, body: req.body, cb: req.cb, ctx: req.ctx,
        retried: req.retried ?: false, gen: rt.cookieGen]
    logDebug "${req.method} ${req.path}"
    if (req.method == "PUT") {
        params.requestContentType = "application/json"
        params.body = req.body
        asynchttpPut("requestDone", params, data)
    } else {
        asynchttpGet("requestDone", params, data)
    }
}

def requestDone(resp, Map data) {
    Map rt = runtime()
    Map req = new LinkedHashMap(data)
    int status = statusOf(resp)
    String body = bodyOf(resp)
    def json = parseJsonText(body)
    // An expired session answers 401, or redirects to the login page (followed or not).
    boolean loggedOut = status == 401 || (status >= 300 && status < 400) || (status == 200 && json == null && looksLikeLogin(body))
    if (loggedOut) {
        // Only this session expired: a login since this request went out stays.
        boolean sameSession = rt.cookieGen == data.gen && rt.cookies
        if (sameSession) rt.cookies = []
        if (!data.retried) {
            logDebug "${data.path}: session expired, logging in again"
            req.retried = true
            send(req)
            return
        }
        // Logged out again right after a fresh login: the login didn't really work
        // (or its cookies don't). Hold logins back as after a rejection.
        // Once per session: the other requests of that session fail the same way.
        if (sameSession) holdLogins("SolarEdge didn't accept the new session (HTTP ${status})")
        deliver(req, [error: "Unauthorized", status: status, outage: false])
        return
    }
    // A command may answer 2xx with no JSON body (e.g. 204).
    if (status >= 200 && status < 300 && (json != null || data.method == "PUT")) {
        if (json == null) json = [:]
        rt.loginRetryMs = 0L
        rt.loginBlockedUntil = 0L
        deliver(req, [json: json])
        return
    }
    String code = (json instanceof Map) ? (json.error instanceof Map ? json.error.code : json.code) : null
    String msg = code ?: errorMessage(resp) ?: "HTTP ${status}"
    deliver(req, [error: msg, status: status, outage: isOutage(status)])
}

private void deliver(Map req, Map result) {
    "${req.cb}"(result, req.ctx ?: [:])
}

/** A failure of SolarEdge or the network, worth backing off for. */
boolean isOutage(int status) { status <= 0 || status == 408 || status == 429 || status >= 500 }

private boolean looksLikeLogin(String body) {
    body != null && (body.contains("j_password") || body.toLowerCase().contains("<html"))
}

private int statusOf(resp) {
    try { return resp.status as int } catch (e) { return 0 }
}

private String bodyOf(resp) {
    try {
        def d = resp.getData()
        if (d != null) return d.toString()
    } catch (e) { }
    try { return resp.getErrorData()?.toString() } catch (e) { return null }
}

private String errorMessage(resp) {
    try { return resp.hasError() ? resp.getErrorMessage() : null } catch (e) { return null }
}

private def parseJsonText(String s) {
    if (!s) return null
    String t = s.trim()
    if (!(t.startsWith("{") || t.startsWith("["))) return null
    try { return new JsonSlurper().parseText(t) } catch (e) { return null }
}

// ---------------------------------------------------------------- login

private void startLogin() {
    Map rt = runtime()
    if (!settings.username || !settings.password) {
        state.status = "Enter your mySolarEdge e-mail and password"
        failWaiting("No credentials", false)
        return
    }
    if (now() < (rt.loginBlockedUntil as long)) {
        failWaiting("Invalid e-mail and/or password", false)
        return
    }
    if (rt.putIfAbsent("loggingIn", true) != null) return
    String body = "j_username=${URLEncoder.encode(settings.username as String, 'UTF-8')}&j_password=${URLEncoder.encode(settings.password as String, 'UTF-8')}"
    Map params = [uri: BASE_URL, path: LOGIN_PATH, headers: ["User-Agent": API_HEADERS["User-Agent"]],
        requestContentType: "application/x-www-form-urlencoded", contentType: "text/html",
        body: body, timeout: REQUEST_TIMEOUT, followRedirects: false]
    // Never log the body or the credentials.
    logDebug "Logging in"
    asynchttpPost("loginDone", params, [:])
}

def loginDone(resp, Map data) {
    Map rt = runtime()
    rt.remove("loggingIn")
    int status = statusOf(resp)
    if (isOutage(status)) {
        state.status = "SolarEdge login unavailable (HTTP ${status}${errorMessage(resp) ? ', ' + errorMessage(resp) : ''})"
        log.warn "SolarEdge: ${state.status}"
        failWaiting(state.status, true, status)
        return
    }
    List cookies = cookiesOf(resp)
    String location = headerOf(resp, "Location")
    // A good login answers 302 to the portal with the session cookies; a failed one
    // redirects back to the login page (Spring: /login?error). If the hub followed
    // the redirect, a 200 with cookies and no login form also counts.
    boolean toLogin = location != null && location.toLowerCase() =~ /login|error/
    boolean ok = cookies && ((status >= 300 && status < 400 && !toLogin) || (status == 200 && !looksLikeLoginForm(bodyOf(resp))))
    if (!ok) {
        holdLogins("SolarEdge rejected the e-mail or password (HTTP ${status}${toLogin ? ', back to the login page' : ''})")
        failWaiting("Invalid e-mail and/or password", false, status)
        return
    }
    // Only a session that has carried an answer clears the login backoff (requestDone).
    logDebug "Logged in: HTTP ${status}, cookies ${cookies.collect { it.split('=')[0] }}"
    rt.cookies = cookies
    rt.cookieGen = ((rt.cookieGen ?: 0) as int) + 1
    rt.loggedInAt = now()
    state.status = "Logged in"
    takeWaiting().each { send(it) }
}

private boolean looksLikeLoginForm(String body) { body != null && body.contains("j_password") }

/** No automatic login for a while, doubling per failure from 1 min up to 1 h. */
private void holdLogins(String why) {
    Map rt = runtime()
    long delay = Math.min(rt.loginRetryMs ? (rt.loginRetryMs as long) * 2 : LOGIN_RETRY_MIN, LOGIN_RETRY_MAX)
    rt.loginRetryMs = delay
    rt.loginBlockedUntil = now() + delay
    rt.cookies = []
    state.status = "${why}. Next login in ${delay.intdiv(60000)} min, or save the settings again."
    log.warn "SolarEdge: ${state.status}"
}

/** One response header's value as text, or null. */
String headerOf(resp, String name) {
    def headers
    try { headers = resp.getHeaders() } catch (e) { return null }
    def found = null
    if (headers instanceof Map) {
        headers.each { k, v -> if (found == null && k?.toString()?.equalsIgnoreCase(name)) found = v instanceof Collection ? v[0] : v }
    } else if (headers != null) {
        headers.each { h -> if (found == null && headerText(h).toLowerCase().startsWith(name.toLowerCase() + ":")) found = h }
    }
    return found == null ? null : headerText(found).replaceFirst(/(?i)^${name}:\s*/, "")
}

/**
 * name=value of each Set-Cookie header. Hubitat hands headers as a map whose
 * values are HeaderEntry objects ("Set-Cookie: A=1; Path=/"), so headers are
 * read as text whatever their type; several cookies may also share one header.
 */
List cookiesOf(resp) {
    def headers
    try { headers = resp.getHeaders() } catch (e) { headers = null }
    List values = []
    if (headers instanceof Map) {
        headers.each { k, v ->
            if (k?.toString()?.equalsIgnoreCase("Set-Cookie")) values.addAll(v instanceof Collection ? v : [v])
        }
    } else if (headers != null) {
        headers.each { h -> if (headerText(h).toLowerCase().startsWith("set-cookie:")) values << h }
    }
    List out = []
    values.each { h ->
        String v = headerText(h).replaceFirst(/(?i)^set-cookie:\s*/, "")
        // A comma starts a new cookie only when a name= follows (Expires dates hold commas too).
        v.split(/,(?=\s*[A-Za-z0-9_.\-]+=)/).each { String c ->
            String nv = c.split(";")[0].trim()
            if (nv.contains("=") && !out.contains(nv)) out << nv
        }
    }
    return out
}

/** A header as text: a String, or a header object whose text is "Name: value". */
private String headerText(def h) {
    if (h == null) return ""
    if (h instanceof CharSequence) return h.toString()
    try {
        def n = h.name
        def v = h.value
        if (n != null && v != null) return "${n}: ${v}".toString()
    } catch (e) { }
    return h.toString()
}

/** The requests waiting for a login, oldest first, and the queue emptied. */
private List takeWaiting() {
    Map waiting = runtime().waiting
    List reqs = new ArrayList(waiting.values()).sort { it.seq as long }
    waiting.clear()
    return reqs
}

private void failWaiting(String msg, boolean outage, int status = 0) {
    takeWaiting().each { deliver(it, [error: msg, status: status, outage: outage, login: !outage]) }
}

// ---------------------------------------------------------------- ticks

/**
 * The 5-minute energy tick. Its flags live in memory, not in state: a tick's
 * answers are handled in another execution, which writes back the state it
 * loaded earlier and would erase a flag set here meanwhile (Hubitat keeps the
 * last write). With a tick still in flight, finishTick runs it straight after.
 */
def energyTick() {
    scheduleEnergyTick()
    Map rt = runtime()
    rt.energyDue = true
    rt.judgePending = true
    tick()
}

/**
 * One poll of the account. Skipped while backing off after an outage and while
 * the previous tick still waits for answers.
 */
def tick() {
    runIn(pollInterval(), "tick")
    Map rt = runtime()
    Map running = rt.tick
    if (running) {
        if (now() - (running.started as long) < STALE_TICK_MS) {
            logDebug "Tick skipped: the previous one is still running"
            return
        }
        log.warn "SolarEdge: giving up on a tick without answers"
        rt.remove("tick")
    }
    if (now() < (rt.backoffUntil as long)) {
        logDebug "Tick skipped: backing off after an outage"
        return
    }
    List devices = siteDevices()
    if (!devices) return

    // 300 s is a multiple of the poll interval, so a fast tick lands on every energy
    // tick: leave that poll to the energy tick instead of colliding with it.
    long untilEnergy = rt.nextEnergyAt ? (rt.nextEnergyAt as long) - now() : -1L
    if (!rt.energyDue && untilEnergy >= 0 && untilEnergy <= ENERGY_MERGE_MS) {
        logDebug "Tick left to the energy tick due in ${untilEnergy.intdiv(1000)} s"
        return
    }
    // The forecast lives in memory: after a hub restart or code save, ask again.
    if (settings.forecastEnable && runtimeMap("forecast").isEmpty() && now() - ((rt.forecastAskedAt ?: 0L) as long) > 10 * 60000L) {
        rt.forecastAskedAt = now()
        runIn(1, "forecastRefresh")
    }
    boolean energy = rt.remove("energyDue") as boolean
    boolean slow = false
    if (energy) {
        rt.energyCount = ((rt.energyCount ?: 0) as int) + 1
        slow = (rt.energyCount as int) % SLOW_EVERY == 1
    }
    List reqs = planTick(devices.collect { [type: it.getDataValue("type"), siteId: it.getDataValue("siteId"),
        reporterId: it.getDataValue("reporterId"), serials: it.getDataValue("serials"),
        chargingState: it.getDataValue("type") == "ev" ? it.currentValue("chargingState") : null] }, energy, slow)
    if (!reqs) return

    Map tk = new ConcurrentHashMap([started: now(), results: new ConcurrentHashMap(),
        pending: new ConcurrentHashMap(), reqs: new ConcurrentHashMap()])
    reqs.each { tk.pending[it.key] = true; tk.reqs[it.key] = it }
    rt.tick = tk
    reqs.each { sendTick(it, tk) }
}

/**
 * The requests of one tick for the devices (type, siteId, reporterId, serials),
 * identical ones merged: one powerflow per site whatever the number of devices.
 */
List planTick(List devices, boolean energy, boolean slow) {
    Map out = [:]
    Closure add = { Map r, Map extra = [:] -> if (!out[r.key]) out[r.key] = r + extra }
    Map bySite = devices.groupBy { it.siteId as String }
    long t = now()
    bySite.each { String siteId, List devs ->
        Map site = (state.sites ?: [:])[siteId] ?: [:]
        Set types = devs*.type as Set
        // Only inverters on this site, and the sun is down: the energy tick is enough.
        boolean nightSkip = types == (["inverter"] as Set) && (state.solarIdle ?: [:])[siteId] && !energy
        if (!nightSkip) add(reqPowerflow(siteId), [kind: "powerflow", siteId: siteId])
        if (devicesDue(devs, energy)) add(reqSiteDevices(siteId), [kind: "siteDevices", siteId: siteId])
        if (energy) {
            if (types.contains("inverter")) {
                add(reqEnergyOverview(siteId), [kind: "energyOverview", siteId: siteId])
                List serials = serialsOf(devs)
                if (serials) add(reqInverters(serials), [kind: "inverters", siteId: siteId])
                else add(reqEquipment(siteId), [kind: "equipment", siteId: siteId])
                if (slow && serials) add(reqEnergyByInverter(siteId, dayIn(t, site.tz), serials), [kind: "byInverter", siteId: siteId])
            }
            if (types.contains("battery")) add(reqBatteries(siteId), [kind: "batteries", siteId: siteId])
            if (types.contains("meter")) {
                int y = yearIn(t, site.tz)
                add(reqMeasurements(siteId, y), [kind: "measurements", siteId: siteId, year: y])
                // Last year again until it has settled.
                if (t < yearStart(y) + YEAR_SETTLE_MS) add(reqMeasurements(siteId, y - 1), [kind: "measurements", siteId: siteId, year: y - 1])
            }
            if (slow) {
                if (types.contains("inverter") || types.contains("meter") || types.contains("battery")) {
                    add(reqCommunication(siteId), [kind: "communication", siteId: siteId])
                    // Whether the inverters sleep decides if "not ONLINE" is an alarm.
                    List serials = serialsOf(devs) ?: (site.serials ? (site.serials as String).split(",") as List : [])
                    if (serials) add(reqInverters(serials), [kind: "inverters", siteId: siteId])
                }
                if (types.contains("inverter")) add(reqAlerts(siteId), [kind: "alerts", siteId: siteId])
                devs.findAll { it.type == "ev" && it.reporterId }.each {
                    add(reqSessions(siteId, it.reporterId), [kind: "sessions", siteId: siteId, reporterId: it.reporterId])
                }
            }
        }
        // Past meter years, one per tick until done.
        if (types.contains("meter")) {
            Integer y = nextBackfillYear(siteId, t)
            if (y != null) add(reqMeasurements(siteId, y), [kind: "measurements", siteId: siteId, year: y, backfill: true])
        }
    }
    return new ArrayList(out.values())
}

/**
 * Whether this poll needs the site's devices list. Load controllers need it every
 * poll, and so do several EV chargers on one site. One EV charger only while it
 * charges (or its state is unknown yet), for its
 * own power and the session; otherwise the power flow has their plug status, and
 * the list is read on the energy tick. A charge that starts shows in the power
 * flow first, and the next poll reads the list again.
 */
boolean devicesDue(List devs, boolean energy) {
    if (devs.any { it.type == "hlc" }) return true
    List evs = devs.findAll { it.type == "ev" }
    if (!evs) return false
    // The power flow's evCharger is one per site: with several chargers only the list tells them apart.
    if (evs.size() > 1) return true
    return energy || evs.any { it.chargingState == null || it.chargingState == "plugged_in_charging" }
}

private List serialsOf(List devs) {
    String s = devs.find { it.type == "inverter" && it.serials }?.serials
    return s ? s.split(",") as List : []
}

def tickResult(Map result, Map ctx) {
    Map rt = runtime()
    Map tk = rt.tick
    if (!tk || tk.started != ctx.started) return  // a tick given up on
    String key = ctx.key
    tk.results[key] = result
    Map req = tk.reqs[key]
    // Follow-up: the inverter serials were not known yet.
    if (req?.kind == "equipment" && result.json) {
        List serials = equipmentSerials(result.json)
        if (serials) {
            tk.serials = serials.join(",")
            Map r = reqInverters(serials) + [kind: "inverters", siteId: req.siteId]
            if (tk.pending.putIfAbsent(r.key, true) == null) {
                tk.reqs[r.key] = r
                sendTick(r, tk)
            }
        }
    }
    tk.pending.remove(key)
    if (tk.pending.isEmpty() && tk.putIfAbsent("finished", true) == null) {
        // The tick counts as running until its events are out, so no tick starts meanwhile.
        try {
            finishTick(tk)
        } finally {
            rt.remove("tick")
        }
        // An energy tick that came while this one was out goes now.
        if (rt.energyDue) runIn(1, "tick")
    }
}

/** Send a tick request; its key and the tick's start travel in ctx to tickResult. */
private void sendTick(Map r, Map tk) { send(r + [cb: "tickResult", ctx: [started: tk.started, key: r.key]]) }

/** All answers of a tick are in: back off or not, then events for every device. */
void finishTick(Map tk) {
    Map rt = runtime()
    Map results = tk.results
    List all = new ArrayList(results.values())
    int ok = all.count { it.json != null }
    boolean rateLimited = all.any { it.status == 429 }
    boolean outage = all.any { it.outage }
    if (rateLimited || (ok == 0 && outage)) {
        noteOutage(rateLimited)
    } else if (ok > 0) {
        rt.outages = 0
        rt.backoffUntil = 0L
        if (state.status != "Logged in") state.status = "Logged in"
    }
    state.failedTicks = ok > 0 ? 0 : ((state.failedTicks ?: 0) as int) + 1

    // Results by kind and site (and year / reporter).
    Map by = [:]
    tk.reqs.each { String key, Map req ->
        Map res = results[key]
        if (res == null) return
        noteAnswer(key, req, res, now())
        String k = "${req.kind}:${req.siteId}" + (req.year != null ? ":${req.year}" : "") + (req.reporterId ? ":${req.reporterId}" : "")
        k = k.toString()
        by[k] = res
        if (req.kind == "measurements") recordYear(req, res)
        if (req.kind == "inverters" && res.json) noteSnapshots(res.json)
    }

    long t = now()
    List devices = siteDevices()
    devices.each { cd ->
        String type = cd.getDataValue("type")
        String siteId = cd.getDataValue("siteId")
        if (tk.serials && type == "inverter" && !cd.getDataValue("serials")) cd.updateDataValue("serials", tk.serials)
        List ev = []
        try {
            ev = eventsFor(cd, type, siteId, by, t)
        } catch (e) {
            log.error "SolarEdge: ${cd.displayName}: ${e}"
        }
        String health = (state.failedTicks as int) >= FAIL_LIMIT ? "offline" : (ok > 0 ? "online" : null)
        if (health) ev << [name: "healthStatus", value: health]
        cd.applyEvents(ev)
    }
    // Sun down on a site: noted for the night skip.
    Map idle = state.solarIdle ?: [:]
    by.findAll { it.key.startsWith("powerflow:") && it.value.json }.each { k, res ->
        def sp = res.json.solarProduction
        idle[k.split(":")[1]] = !(sp?.isActive) || !(sp?.currentPower)
    }
    state.solarIdle = idle
}

/**
 * Warn once when a request starts failing or its answer looks stale, and say
 * once when it is back: one failing endpoint, or SolarEdge serving old data,
 * doesn't fail the tick, so nothing else would show it.
 */
private void noteAnswer(String key, Map req, Map res, long t) {
    Map stale = runtimeMap("stale")
    String what = "${req.kind}${req.year != null ? ' ' + req.year : ''} for site ${req.siteId}"
    // A failed login fails every request; its own status warning says so once.
    if (res.login) return
    noteFailure(key, what, res.error as String)
    if (res.error) return
    String why = staleReason(req.kind as String, res.json, t)
    if (why) {
        if (stale.putIfAbsent(key, why) == null) log.warn "SolarEdge: ${what} looks stale: ${why}"
    } else if (stale.remove(key) != null) {
        log.info "SolarEdge: ${what} is live again"
    }
}

/** Why an answer looks like old data, or null: the power flow not real time, the devices list not updated lately. */
String staleReason(String kind, def json, long t) {
    if (!(json instanceof Map)) return null
    List why = []
    if (kind == "powerflow") {
        if (json.isRealTime == false) why << "isRealTime false"
        if (json.isCommunicating == false) why << "isCommunicating false"
    } else if (kind == "siteDevices") {
        if (json.isUpdated == false) why << "isUpdated false"
        if (json.fieldLastUpdateTS instanceof Number && t - (json.fieldLastUpdateTS as long) > STALE_DATA_MS) {
            why << "last updated ${(t - (json.fieldLastUpdateTS as long)).intdiv(60000)} min ago"
        }
    }
    return why ? why.join(", ") : null
}

/** Log once when `what` starts failing (error set) and once when it works again (error null). */
private void noteFailure(String key, String what, String error) {
    Map failing = runtimeMap("failing")
    if (error) {
        if (failing.putIfAbsent(key, error) == null) log.warn "SolarEdge: ${what} failed: ${error} (logged again only once it works)"
    } else if (failing.remove(key) != null) {
        log.info "SolarEdge: ${what} works again"
    }
}

private Map runtimeMap(String name) {
    Map rt = runtime()
    Map m = rt[name]
    if (m == null) {
        rt.putIfAbsent(name, new ConcurrentHashMap())
        m = rt[name]
    }
    return m
}

// ---------------------------------------------------------------- solar forecast

/** SolarEdge's peak power (kWp) of the first site, the default system size. */
private def defaultKwp() {
    (state.sites ?: [:]).values().find { it.peakPower instanceof Number }?.peakPower
}

/** Ask Forecast.Solar for each site with an inverter; once an hour (runEvery1Hour) and at setup. */
void forecastRefresh() {
    if (!settings.forecastEnable) return
    runtime().forecastAskedAt = now()
    def lat = location?.latitude
    def lon = location?.longitude
    if (lat == null || lon == null) {
        logOnce("forecast no location", "solar forecast needs the hub's location (Settings > Location)")
        return
    }
    List siteIds = siteDevices().findAll { it.getDataValue("type") == "inverter" }*.getDataValue("siteId").unique()
    siteIds.each { String siteId ->
        Map site = (state.sites ?: [:])[siteId] ?: [:]
        def kwp = settings.forecastKwp ?: site.peakPower
        if (!(kwp instanceof Number) || kwp <= 0) {
            logOnce("forecast no kwp ${siteId}", "solar forecast for site ${siteId} needs the system size (kWp) in the app settings")
            return
        }
        String path = forecastPath(lat as double, lon as double, (settings.forecastTilt != null ? settings.forecastTilt : DEFAULT_TILT) as int,
            (settings.forecastBearing != null ? settings.forecastBearing : DEFAULT_BEARING) as int, kwp as double)
        logDebug "Forecast for site ${siteId}: GET ${FORECAST_URL}${path}"
        asynchttpGet("forecastDone", [uri: FORECAST_URL, path: path, query: [time: "iso8601"], headers: [Accept: "application/json"],
            contentType: "application/json", timeout: REQUEST_TIMEOUT], [siteId: siteId])
    }
}

/** /estimate/lat/lon/tilt/azimuth/kWp, the location rounded to about 1 km, the azimuth counted from south. */
String forecastPath(double lat, double lon, int tilt, int bearing, double kwp) {
    int az = ((((bearing - 180) % 360) + 540) % 360) - 180
    Closure r = { double v -> new BigDecimal(v).setScale(FORECAST_DECIMALS, BigDecimal.ROUND_HALF_UP).toPlainString() }
    return "/estimate/${r(lat)}/${r(lon)}/${Math.max(0, Math.min(90, tilt))}/${az}/${new BigDecimal(kwp).setScale(2, BigDecimal.ROUND_HALF_UP).stripTrailingZeros().toPlainString()}".toString()
}

def forecastDone(resp, Map data) {
    String siteId = data.siteId as String
    int status = statusOf(resp)
    def json = parseJsonText(bodyOf(resp))
    String key = "forecast:${siteId}".toString()
    String what = "solar forecast for site ${siteId}"
    if (status != 200 || !(json instanceof Map) || !(json.result instanceof Map)) {
        noteFailure(key, what, (json instanceof Map ? json.message?.text : null) ?: errorMessage(resp) ?: "HTTP ${status}")
        return
    }
    noteFailure(key, what, null)
    String tz = json.message?.info?.timezone ?: ((state.sites ?: [:])[siteId]?.tz) ?: location?.timeZone?.ID
    // In memory, not state: this runs in its own execution, which would write back the
    // state it loaded and undo a poll's battery totals saved meanwhile (or lose this).
    runtimeMap("forecast")[siteId] = parseForecast(json.result, tz) + [fetchedAt: now()]
    logDebug "Forecast for site ${siteId}: ${runtimeMap('forecast')[siteId].days}"
    // Shown right away, not only on the next poll.
    siteDevices().findAll { it.getDataValue("type") == "inverter" && it.getDataValue("siteId") == siteId }.each { cd ->
        cd.applyEvents(forecastEvents(siteId, now(), cd.currentValue("energyToday")))
    }
}

/**
 * The parts of a Forecast.Solar `result` kept: Wh per day, and the power curve
 * (W at moments) per day. Times come as ISO 8601 (time=iso8601) or as local
 * "yyyy-MM-dd HH:mm:ss" in the forecast's timezone. The energy so far is worked
 * out from the power curve, not from watt_hours, whose running total isn't
 * documented to start over each day.
 */
Map parseForecast(Map result, String tz) {
    java.text.SimpleDateFormat local = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    local.setTimeZone(zone(tz))
    Closure ms = { String k ->
        Long v = parseIso(k)
        if (v != null && k.contains("T")) return v
        try { return local.parse(k).time } catch (e) { return null }
    }
    Map watts = [:]
    (result.watts ?: [:]).each { k, v ->
        Long at = ms(k as String)
        if (at != null && v instanceof Number) {
            String day = dayIn(at, tz)
            if (!watts[day]) watts[day] = []
            watts[day] << [at, v as double]
        }
    }
    watts.each { day, pts -> pts.sort { it[0] } }
    Map days = [:]
    (result.watt_hours_day ?: [:]).each { k, v -> if (v instanceof Number) days[(k as String).take(10)] = v as double }
    return [tz: tz, days: days, watts: watts]
}

/** The day after a "yyyy-MM-dd" date, by the calendar (not +24 h, which DST days break). */
String nextDay(String day) {
    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd")
    f.setTimeZone(TimeZone.getTimeZone("UTC"))
    return f.format(new Date(f.parse(day).time + 24 * 3600 * 1000L))
}

/**
 * The inverter's forecast events at `t`: today's and tomorrow's forecast (kWh),
 * the forecast power now (W), the forecast energy so far today (kWh: the power
 * curve integrated up to now, scaled to the day's total), and the actual energy
 * today as % of that (once the forecast so far is 0.1 kWh or more).
 */
List forecastEvents(String siteId, long t, def actualToday) {
    if (!settings.forecastEnable) return []
    Map fc = runtimeMap("forecast")[siteId]
    if (!fc) return []
    String day = dayIn(t, fc.tz)
    String next = nextDay(day)
    List out = []
    if (fc.days[day] != null) out << ev("forecastToday", round((fc.days[day] as double) / 1000, 2), "kWh")
    if (fc.days[next] != null) out << ev("forecastTomorrow", round((fc.days[next] as double) / 1000, 2), "kWh")
    // Only forecasts for today count for now and so far (an old one says nothing about today).
    if (fc.days[day] == null) return out
    List pts = fc.watts[day] ?: []
    out << ev("forecastPower", (Math.round(interpolate(pts, t, 0d, 0d) / 10) * 10) as int, "W")
    double whole = integrate(pts, Long.MAX_VALUE)
    double soFar = whole > 0 ? integrate(pts, t) / whole * (fc.days[day] as double) : 0d
    out << ev("forecastSoFar", round(soFar / 1000, 2), "kWh")
    if (actualToday instanceof Number && soFar >= 100) {
        out << ev("actualVsForecast", Math.round((actualToday as double) * 1000 / soFar * 100) as int, "%")
    }
    return out
}

/** Wh under a power curve ([ms, W] points) up to `t`: trapezoids, the last one cut at t. */
private double integrate(List pts, long t) {
    double wh = 0d
    for (int i = 1; i < pts.size(); i++) {
        long t0 = pts[i - 1][0] as long
        long t1 = pts[i][0] as long
        if (t <= t0) break
        double w0 = pts[i - 1][1] as double
        double w1 = pts[i][1] as double
        long end = Math.min(t, t1)
        double wEnd = t1 == t0 ? w1 : w0 + (w1 - w0) * (end - t0) / (t1 - t0)
        wh += (w0 + wEnd) / 2 * (end - t0) / 3600000d
    }
    return wh
}

/** Linear between [ms, value] points; `before` / `after` outside them. */
private double interpolate(List pts, long t, double before, double after) {
    if (!pts) return before
    if (t <= (pts[0][0] as long)) return before
    if (t >= (pts[-1][0] as long)) return after
    for (int i = 1; i < pts.size(); i++) {
        long t1 = pts[i][0] as long
        if (t <= t1) {
            long t0 = pts[i - 1][0] as long
            double v0 = pts[i - 1][1] as double
            double v1 = pts[i][1] as double
            return t1 == t0 ? v1 : v0 + (v1 - v0) * (t - t0) / (t1 - t0)
        }
    }
    return after
}

// ---------------------------------------------------------------- raw data

def appButtonHandler(String btn) {
    if (btn == "dumpRaw") dumpRaw()
}

/** Ask for each site's current answers and log them as they come (rawDone). */
void dumpRaw() {
    Map bySite = siteDevices().groupBy { it.getDataValue("siteId") }
    log.info "SolarEdge: logging raw data for ${bySite.size()} site${bySite.size() == 1 ? '' : 's'}"
    bySite.each { String siteId, List devs ->
        List types = devs*.getDataValue("type")
        List reqs = [reqPowerflow(siteId) + [ctx: [kind: "powerflow"]], reqSiteDevices(siteId) + [ctx: [kind: "siteDevices"]]]
        if ("battery" in types) reqs << reqBatteries(siteId) + [ctx: [kind: "batteries"]]
        String serials = devs.find { it.getDataValue("serials") }?.getDataValue("serials") ?: (state.sites ?: [:])[siteId]?.serials
        if (serials) reqs << reqInverters(serials.split(",") as List) + [ctx: [kind: "inverters"]]
        reqs.each { send(it + [cb: "rawDone", ctx: it.ctx + [siteId: siteId]]) }
    }
}

def rawDone(Map result, Map ctx) {
    String what = "raw ${ctx.kind} site ${ctx.siteId}"
    if (result.error) {
        log.warn "SolarEdge: ${what} failed: ${result.error}"
        return
    }
    String why = staleReason(ctx.kind as String, result.json, now())
    String text = JsonOutput.toJson(redact(result.json))
    List parts = []
    for (int i = 0; i < text.length(); i += RAW_CHUNK) parts << text.substring(i, Math.min(text.length(), i + RAW_CHUNK))
    log.info "SolarEdge: ${what}${why ? ' (looks stale: ' + why + ')' : ''}, ${text.length()} chars in ${parts.size()} part${parts.size() == 1 ? '' : 's'}"
    parts.eachWithIndex { String part, int i -> log.info "SolarEdge: ${what} ${i + 1}/${parts.size()}: ${part}" }
}

/** A copy without vehicle and network identifiers (RAW_REDACT, and the vehicle's id). */
def redact(def v, String parent = null) {
    if (v instanceof Map) {
        Map out = [:]
        v.each { k, x ->
            String key = k as String
            out[key] = (key in RAW_REDACT || (parent == "applianceData" && key == "id")) ? "(hidden)" : redact(x, key)
        }
        return out
    }
    if (v instanceof List) return v.collect { redact(it, parent) }
    return v
}

private void noteOutage(boolean rateLimited) {
    Map rt = runtime()
    if (now() < (rt.backoffUntil as long)) return
    rt.outages = (rt.outages as int) + 1
    long delay = Math.min(pollInterval() * 1000L * (1L << Math.min(rt.outages as int, 12)), BACKOFF_MAX)
    rt.backoffUntil = now() + delay
    state.status = "SolarEdge ${rateLimited ? 'rate limit (HTTP 429)' : 'unreachable'}, pausing polls for ${delay.intdiv(1000)} s"
    log.warn "SolarEdge: ${state.status}"
}

/** The events for one device from the tick's answers. */
List eventsFor(def cd, String type, String siteId, Map by, long t) {
    Closure j = { k -> by[k.toString()]?.json }
    Map site = (state.sites ?: [:])[siteId] ?: [:]
    String dni = cd.deviceNetworkId
    // The site's inverters asleep for the night: a site that isn't ONLINE is then expected.
    // Without this tick's inverter data, a site whose solar was idle on the last power flow counts.
    List live = new ArrayList((j("inverters:${siteId}")?.serialToLiveData ?: [:]).values())
    boolean asleep = live ? isAsleep(live, t, timing().cadenceMs as Long) : ((state.solarIdle ?: [:])[siteId] as boolean)
    switch (type) {
        case "inverter":
            List inv = inverterEvents(j("powerflow:${siteId}"), [overview: j("energyOverview:${siteId}"), inverters: j("inverters:${siteId}"),
                byInverter: j("byInverter:${siteId}"), communication: j("communication:${siteId}"), alerts: j("alerts:${siteId}"),
                cadenceMs: timing().cadenceMs, nowMs: t, scale: location.temperatureScale])
            // On energy ticks only, together with the energyToday they compare against.
            Map todayEv = inv.find { it.name == "energyToday" }
            if (!todayEv) return inv
            return inv + forecastEvents(siteId, t, todayEv.value)
        case "meter":
            int y = yearIn(t, site.tz)
            return meterEvents(j("powerflow:${siteId}"), [communication: j("communication:${siteId}"), totals: meterTotals(siteId, y), asleep: asleep])
        case "battery":
            return batteryEvents(dni, j("powerflow:${siteId}"), j("batteries:${siteId}"), j("communication:${siteId}"), asleep, t)
        case "ev":
            String rid = cd.getDataValue("reporterId")
            return evEvents(dni, rid, j("powerflow:${siteId}"), j("siteDevices:${siteId}"), j("sessions:${siteId}:${rid}"))
        case "hlc":
            return hlcEvents(cd.getDataValue("reporterId"), j("siteDevices:${siteId}"))
    }
    return []
}

// ---------------------------------------------------------------- energy tick timing

private Map timing() {
    Map tm = state.timing ? new LinkedHashMap(state.timing) : [:]
    if (tm.phaseMs == null) tm.phaseMs = 0L
    if (tm.marginMs == null) tm.marginMs = MARGIN_MS
    tm.snapshots = tm.snapshots ? new LinkedHashMap(tm.snapshots) : [:]
    return tm
}

private void scheduleEnergyTick() {
    long delay = delayToNextTick(now(), (state.timing?.phaseMs ?: 0) as long)
    runtime().nextEnergyAt = now() + delay
    runInMillis(delay, "energyTick")
}

/** An inverter's latest upload moment for each serial in a getInvertersInformation answer. */
private void noteSnapshots(Map info) {
    (info.serialToLiveData ?: [:]).each { serial, live ->
        Long at = parseIso(live?.lastMeasurement)
        if (at != null) noteSnapshot(serial as String, at, now())
    }
}

/**
 * The energy tick follows the inverters' upload moments (app.js noteSnapshot):
 * a tick that found the old upload again while a new one was due came too early.
 */
void noteSnapshot(String serial, long at, long nowMs) {
    Map tm = timing()
    Map snaps = tm.snapshots ?: [:]
    Long previous = snaps[serial] as Long
    snaps[serial] = at
    tm.snapshots = snaps
    if (previous && at > previous) tm.cadenceMs = learnCadence(tm.cadenceMs as Long, at - previous)
    Map rt = runtime()
    if (rt.judgePending && previous && judgesTick(previous, at, nowMs, (tm.cadenceMs ?: PERIOD_MS) as long)) {
        rt.remove("judgePending")
        long margin = adaptMargin(tm.marginMs as long, at > previous)
        if (margin != (tm.marginMs as long)) {
            logDebug "Energy tick margin ${(tm.marginMs as long).intdiv(1000)}s -> ${margin.intdiv(1000)}s"
            tm.marginMs = margin
        }
    }
    long phase = tickPhase(snaps.values().collect { it as long }, tm.marginMs as long)
    boolean moved = phase != (tm.phaseMs as long)
    tm.phaseMs = phase
    // Hubitat only saves a state value that is assigned again.
    state.timing = tm
    if (!moved) return
    logDebug "Energy tick moved to ${phase.intdiv(1000)}s after each 5-minute mark"
    scheduleEnergyTick()
}

/** The site's upload interval after a new snapshot: the shortest seen, at least the period. */
long learnCadence(Long cadenceMs, long gapMs) {
    Math.max(PERIOD_MS, Math.min(cadenceMs ?: Long.MAX_VALUE, gapMs))
}

/** Whether a tick that found snapshot `atMs` says anything about the margin. */
boolean judgesTick(long previousMs, long atMs, long nowMs, long cadenceMs = PERIOD_MS) {
    long age = nowMs - atMs
    if (age >= cadenceMs + PERIOD_MS) return false
    return atMs > previousMs || age >= cadenceMs
}

/** The tick's position after the period boundary, from the inverters' snapshot moments; 0 when unknown or spread out. */
long tickPhase(List snapshotMs, long marginMs = MARGIN_MS, long periodMs = PERIOD_MS) {
    List phases = snapshotMs.findAll { it != null }.collect { (((it as long) % periodMs) + periodMs) % periodMs }.sort()
    if (!phases) return 0
    long widestGap = (phases[0] as long) + periodMs - (phases[-1] as long)
    long last = phases[-1] as long
    for (int i = 1; i < phases.size(); i++) {
        long gap = (phases[i] as long) - (phases[i - 1] as long)
        if (gap > widestGap) {
            widestGap = gap
            last = phases[i - 1] as long
        }
    }
    if (periodMs - widestGap > MAX_SPREAD_MS) return 0
    return (last + marginMs) % periodMs
}

long adaptMargin(long marginMs, boolean gotNew) {
    gotNew ? Math.max(MARGIN_MS, marginMs - MARGIN_DECAY_MS) : Math.min(MAX_MARGIN_MS, marginMs + MARGIN_STEP_MS)
}

/** Time until the next moment `phaseMs` after a period boundary, at least a second away. */
long delayToNextTick(long nowMs, long phaseMs, long periodMs = PERIOD_MS) {
    long next = Math.floorDiv(nowMs - phaseMs, periodMs) * periodMs + phaseMs + periodMs
    return next - nowMs < 1000 ? next - nowMs + periodMs : next - nowMs
}

/**
 * Whether the inverters stopped uploading after their output fell to 0 W: asleep
 * for the night, not cut off while producing, and not in fault.
 */
boolean isAsleep(List live, long nowMs, Long cadenceMs = null) {
    if (!live) return false
    long cadence = cadenceMs ?: PERIOD_MS
    return live.every { item ->
        Long at = parseIso(item.lastMeasurement)
        if (at == null) return false
        long silent = nowMs - at
        item.pAc_W == 0 && item.inverterStatus?.toString()?.toLowerCase() != "fault" &&
            silent >= cadence + SILENT_MS && silent < MAX_SLEEP_MS
    }
}

// ---------------------------------------------------------------- meter yearly totals

/**
 * Past years are walked back one per tick, from last year down to a hard floor:
 * the install year, else YEARS_BACK years ago, or the first year with data once
 * SolarEdge said "no data" for the one before. Any other error aborts the run
 * without storing anything, and it starts again after YEARS_RETRY_MS. Finished
 * years stay in state; a gap (e.g. last year, missed around New Year) is asked
 * for again.
 */
Integer nextBackfillYear(String siteId, long t) {
    Map ys = ((state.years ?: [:])[siteId] ?: [:])
    if (ys.retryAt && t < (ys.retryAt as long)) return null
    return missingYear(siteId, t)
}

/** The most recent past year without totals, down to the floor; null when complete. */
Integer missingYear(String siteId, long t) {
    Map site = (state.sites ?: [:])[siteId] ?: [:]
    Map ys = ((state.years ?: [:])[siteId] ?: [:])
    int current = yearIn(t, site.tz)
    int floor = site.installYear ? (site.installYear as int) : current - YEARS_BACK
    if (ys.firstYear) floor = Math.max(floor, ys.firstYear as int)
    Map data = ys.data ?: [:]
    for (int y = current - 1; y >= floor; y--) {
        if (!data.containsKey(y as String)) return y
    }
    return null
}

private void recordYear(Map req, Map res) {
    String siteId = req.siteId as String
    int year = req.year as int
    Map years = state.years ?: [:]
    Map ys = years[siteId] ? new LinkedHashMap(years[siteId]) : [:]
    Map data = ys.data ? new LinkedHashMap(ys.data) : [:]
    Map site = (state.sites ?: [:])[siteId] ?: [:]
    int current = yearIn(now(), site.tz)
    def summary = res.json?.summary
    boolean noData = (res.json != null && summary == null) || (res.error && (res.error.contains("INVALID_ARGUMENTS") || res.error.contains("empty_summary")))
    if (year == current) {
        if (summary) ys.current = [year: year, imported: (summary.import ?: 0) as double, exported: (summary.export ?: 0) as double]
    } else if (summary) {
        data[year as String] = [(summary.import ?: 0) as double, (summary.export ?: 0) as double]
    } else if (noData && req.backfill) {
        // The site's first year was the one after; nothing older to ask.
        ys.firstYear = year + 1
        log.info "SolarEdge: meter totals for site ${siteId}: no data before ${year + 1}"
    } else if (req.backfill) {
        ys.retryAt = now() + YEARS_RETRY_MS
        log.warn "SolarEdge: meter totals for ${year} failed (${res.error}); trying again in 15 min"
    }
    ys.data = data
    years[siteId] = ys
    state.years = years
}

/** Lifetime import/export in Wh once every past year is in, plus this year's; null before. */
Map meterTotals(String siteId, int currentYear) {
    Map ys = (state.years ?: [:])[siteId]
    if (ys?.current?.year != currentYear) return null
    Map out = [yearImported: ys.current.imported, yearExported: ys.current.exported]
    if (missingYear(siteId, now()) != null) return out
    double imp = ys.current.imported as double
    double exp = ys.current.exported as double
    int floor = ys.firstYear ? (ys.firstYear as int) : 0
    (ys.data ?: [:]).each { y, v ->
        if ((y as int) >= floor && (y as int) < currentYear) {
            imp += v[0] as double
            exp += v[1] as double
        }
    }
    return out + [imported: imp, exported: exp]
}

// ---------------------------------------------------------------- discovery

private void startDiscovery() {
    state.discovering = now()
    state.remove("discoverError")
    runtime().disc = new ConcurrentHashMap([pending: new ConcurrentHashMap(), found: new ConcurrentHashMap(), started: state.discovering])
    send(reqSites() + [cb: "discoverSites", ctx: [run: state.discovering]])
}

/** Whether a discovery answer belongs to the run in progress (an older run's answers share its keys). */
private boolean currentRun(Map ctx) { runtime().disc?.started == ctx.run }

def discoverSites(Map result, Map ctx) {
    if (!currentRun(ctx)) return
    if (!result.json) {
        state.remove("discovering")
        state.discoverError = result.error ?: "no answer"
        return
    }
    List sites = sitesOf(result.json)
    Map info = [:]
    sites.each { s ->
        Long installed = parseIso(s.installationDate)
        if (s.installationDate != null && installed == null) logOnce("install ${s.id}", "site ${s.id}: unknown installationDate format ${s.installationDate}")
        info[s.id as String] = [name: s.name, tz: s.timezone, installYear: installed != null ? yearIn(installed, s.timezone) : null,
            peakPower: s.peakPower instanceof Number ? s.peakPower : null]
    }
    state.sites = info
    Map disc = runtime().disc
    if (!sites) {
        state.remove("discovering")
        state.discovered = [:]
        state.discoveredAt = now()
        return
    }
    List reqs = []
    sites.each { s ->
        reqs << reqPowerflow(s.id) + [ctx: [kind: "powerflow", siteId: s.id as String, run: ctx.run]]
        reqs << reqSiteDevices(s.id) + [ctx: [kind: "siteDevices", siteId: s.id as String, run: ctx.run]]
        reqs << reqEquipment(s.id) + [ctx: [kind: "equipment", siteId: s.id as String, run: ctx.run]]
    }
    reqs.each { disc.pending[it.key] = true }
    reqs.each { send(it + [cb: "discoverPart", ctx: it.ctx + [key: it.key]]) }
}

/** The getSites answer as a list: { sites: { id: site } } or a list. */
List sitesOf(def json) {
    def s = json instanceof Map && json.containsKey("sites") ? json.sites : json
    if (s instanceof Map) return new ArrayList(s.values())
    return s instanceof List ? s : []
}

def discoverPart(Map result, Map ctx) {
    Map disc = runtime().disc
    if (!disc || !currentRun(ctx)) return
    disc.found[(ctx.kind + ":" + ctx.siteId) as String] = result.json ?: [:]
    disc.pending.remove(ctx.key)
    if (!disc.pending.isEmpty() || disc.putIfAbsent("finished", true) != null) return
    Map found = [:]
    (state.sites ?: [:]).each { String siteId, Map site ->
        found.putAll(offersFor(siteId, site.name as String, disc.found["powerflow:" + siteId], disc.found["siteDevices:" + siteId],
            disc.found["equipment:" + siteId]))
    }
    state.discovered = found
    state.discoveredAt = now()
    state.remove("discovering")
    runtime().remove("disc")
}

/**
 * The devices to offer for a site. The inverter is always offered (at night
 * the power flow reports production inactive, which is no reason to hide it);
 * a meter when the grid is active, a battery when storage is reported.
 */
Map offersFor(String siteId, String siteName, Map pf, Map devicesResp, Map equipment) {
    Map out = [:]
    String name = siteName ?: "Site ${siteId}"
    List serials = equipmentSerials(equipment)
    if (serials) {
        Map sites = state.sites ?: [:]
        if (sites[siteId]) {
            sites[siteId] = sites[siteId] + [serials: serials.join(",")]
            state.sites = sites
        }
    }
    out["solaredge-${siteId}-inverter"] = [type: "inverter", siteId: siteId, label: "${name} Inverter", serials: serials ? serials.join(",") : null]
    if (pf?.grid?.isActive) out["solaredge-${siteId}-meter"] = [type: "meter", siteId: siteId, label: "${name} Meter"]
    if (pf?.storage?.isActive || pf?.storage?.chargeLevel instanceof Number) out["solaredge-${siteId}-battery"] = [type: "battery", siteId: siteId, label: "${name} Battery"]
    // EV chargers are listed under devicesByType.EV_CHARGER whatever their type (EV_CHARGER_SA, KEBA_CHARGER).
    Set evIds = ((devicesResp?.devicesByType?.EV_CHARGER ?: [])*.reporterId.findAll { it != null }*.toString()) as Set
    allSiteDevices(devicesResp).each { d ->
        if (d.reporterId == null) return
        if (d.reporterId.toString() in evIds || d.type?.toString()?.startsWith("EV_CHARGER")) {
            out["solaredge-${siteId}-ev-${d.reporterId}"] = [type: "ev", siteId: siteId, reporterId: d.reporterId as String, label: d.name ?: "EV Charger"]
        } else if (d.type == "ON_OFF") {
            out["solaredge-${siteId}-hlc-${d.reporterId}"] = [type: "hlc", siteId: siteId, reporterId: d.reporterId as String, label: d.name ?: "Load Controller"]
        }
    }
    return out
}

List equipmentSerials(Map equipment) {
    (equipment?.inverters?.details ?: []).collectMany { it.serialNumbers ?: [] }.collect { it as String }
}

/** Every device of a getSiteDevices answer: `devices` and all lists in `devicesByType`, once per reporterId. */
List allSiteDevices(def resp) {
    if (!(resp instanceof Map)) return []
    List all = (resp.devices ?: []) + ((resp.devicesByType ?: [:]).values().flatten())
    Set seen = [] as Set
    return all.findAll { d ->
        def id = d?.reporterId ?: d?.info?.serialNumber
        if (id == null) return true
        seen.add(id as String)
    }
}

// ---------------------------------------------------------------- mapping

private Integer watts(def kw) { kw instanceof Number ? Math.round((kw as double) * 1000) as Integer : null }
private BigDecimal kwh(def wh) { wh instanceof Number ? (wh as BigDecimal).divide(1000G).setScale(3, BigDecimal.ROUND_HALF_UP).stripTrailingZeros() : null }
private BigDecimal round(def v, int decimals) { v instanceof Number ? (v as BigDecimal).setScale(decimals, BigDecimal.ROUND_HALF_UP).stripTrailingZeros() : null }
private Map ev(String name, def value, String unit = null) { unit ? [name: name, value: value, unit: unit] : [name: name, value: value] }

/**
 * Inverter: solar power, never unavailable at night or while idle (0 W then);
 * energy totals, phase voltages, status and the hottest optimizer on energy ticks.
 */
List inverterEvents(Map pf, Map x = [:]) {
    List out = []
    if (pf) {
        def sp = pf.solarProduction
        Integer p = watts(sp?.currentPower)
        out << ev("power", p ?: 0, "W")
    }
    (x.overview?.energyProducedOverviewList ?: []).each { item ->
        String attr = ENERGY_PERIODS[item.timePeriod]
        BigDecimal v = kwh(item.energy)
        if (attr && v != null) {
            out << ev(attr, v, "kWh")
            if (attr == "energyLifetime") out << ev("energy", v, "kWh")
        }
    }
    List live = []
    if (x.inverters) {
        List phases = (x.inverters.serialToPhaseMeasurements ?: [:]).values().flatten()
        List volts = []
        [1, 2, 3].each { int n ->
            List lines = phases.findAll { it.lineNumber_3ph == n && it.v_ac_V instanceof Number }
            if (!lines) return
            double avg = lines.sum { it.v_ac_V as double } / lines.size()
            volts << avg
            out << ev("voltageL${n}", round(avg, 1), "V")
        }
        if (volts) out << ev("voltage", round(volts.sum() / volts.size(), 1), "V")
        live = new ArrayList((x.inverters.serialToLiveData ?: [:]).values())
        List reported = live*.inverterStatus.findAll { it instanceof String }
        String status = (reported.find { it.toLowerCase() == "fault" } ?: (reported ? reported[0] : null))?.toLowerCase()
        if (isAsleep(live, x.nowMs as long, x.cadenceMs as Long)) out << ev("inverterStatus", "sleeping")
        else if (status) out << ev("inverterStatus", status in INVERTER_STATUSES ? status : "unknown")
    }
    if (x.byInverter) {
        List temps = (x.byInverter.inverters ?: []).collectMany { it.optimizers ?: [] }.collect { celsius(it.temperature) }.findAll { it != null }
        if (temps) {
            double c = temps.max()
            out << ev("optimizerTemperatureMax", x.scale == "F" ? round(c * 9 / 5 + 32, 1) : round(c, 1), "°${x.scale ?: 'C'}")
        }
    }
    if (x.alerts?.totalAlertsCount instanceof Number) out << ev("alerts", x.alerts.totalAlertsCount)
    if (x.communication?.connectivityStatus instanceof String) {
        boolean offline = x.communication.connectivityStatus != "ONLINE" && !isAsleep(live, x.nowMs as long, x.cadenceMs as Long)
        out << ev("siteConnectivity", offline ? "offline" : "online")
    }
    return out
}

/** Optimizer temperature in °C; Fahrenheit readings converted. */
private Double celsius(def reading) {
    if (!(reading?.temperature instanceof Number)) return null
    double v = reading.temperature as double
    return reading.temperatureUnit?.toString()?.toUpperCase() == "FAHRENHEIT" ? (v - 32) * 5 / 9 : v
}

/**
 * Meter: grid power (import +, export -) as `power`, and the split of the site's
 * power: gridPower, solarPower, loadPower, batteryPower (charging +). Lifetime
 * import/export once the yearly totals are complete, this year's always.
 */
List meterEvents(Map pf, Map x = [:]) {
    List out = []
    if (pf) {
        def g = pf.grid
        Integer grid = watts(g?.currentPower)
        if (grid != null && g?.status?.toString()?.toLowerCase() == "export") grid = -grid
        if (grid == null && g?.containsKey("currentPower") && g.isActive) grid = 0
        if (grid != null) {
            out << ev("power", grid, "W")
            out << ev("gridPower", grid, "W")
            out << ev("gridStatus", grid > 0 ? "import" : grid < 0 ? "export" : "idle")
        }
        if (pf.consumption) out << ev("loadPower", watts(pf.consumption.currentPower) ?: 0, "W")
        if (pf.solarProduction) out << ev("solarPower", watts(pf.solarProduction.currentPower) ?: 0, "W")
        if (pf.storage) {
            Integer b = watts(pf.storage.currentPower) ?: 0
            if (pf.storage.status?.toString()?.toLowerCase() == "discharging") b = -b
            out << ev("batteryPower", b, "W")
        }
    }
    Map tot = x.totals
    if (tot?.imported != null) {
        out << ev("energy", kwh(tot.imported), "kWh")
        out << ev("energyImported", kwh(tot.imported), "kWh")
        out << ev("energyExported", kwh(tot.exported), "kWh")
    }
    if (tot?.yearImported != null) {
        out << ev("energyImportedYear", kwh(tot.yearImported), "kWh")
        out << ev("energyExportedYear", kwh(tot.yearExported), "kWh")
    }
    if (x.communication?.connectivityStatus instanceof String) {
        out << ev("siteConnectivity", x.communication.connectivityStatus == "ONLINE" || x.asleep ? "online" : "offline")
    }
    return out
}

/** Battery: power (charging +), charge level, state; energies and health from getBatteries. */
/**
 * Battery: power (charging +), charge level and state from the power flow, with
 * charged/discharged integrated from that power (batteryFlow); stored energy and
 * health from getBatteries.
 */
List batteryEvents(String dni, Map pf, Map batteriesResp, Map communication = null, boolean asleep = false, Long nowMs = null) {
    List out = []
    def st = pf?.storage
    if (st) {
        String status = st.status instanceof String ? st.status.toLowerCase() : null
        Integer p = watts(st.currentPower)
        if (p != null && status == "discharging") p = -p
        out << ev("power", p ?: 0, "W")
        Map flow = batteryFlow(dni, p ?: 0, nowMs ?: now())
        // In 0.01 kWh steps: the totals (kept unrounded) move a little on every poll,
        // and an event for each 0.004 kWh is noise.
        BigDecimal discharged = round(flow.discharged, 2)
        out << ev("energyCharged", round(flow.charged, 2), "kWh")
        out << ev("energyDischarged", discharged, "kWh")
        out << ev("energy", discharged, "kWh")
        if (st.chargeLevel instanceof Number) out << ev("battery", Math.round(st.chargeLevel as double) as int, "%")
        if (status in ["charging", "discharging", "idle"]) out << ev("chargingState", status)
    }
    List bats = batteriesResp?.devicesByType?.BATTERY ?: []
    if (bats) {
        Double plate = sumOf(bats, "namePlateEnergy")
        // kWh below a nameplate of 100, else Wh; kept from the last answer that had one.
        Map units = state.units ?: [:]
        double toKwh = (units[dni] ?: 0.001) as double
        if (plate != null && plate > 0) {
            toKwh = plate < 100 ? 1d : 0.001d
            units[dni] = toKwh
            state.units = units
        }
        // chargeEnergy/dischargeEnergy are not used: 0.0 on a real two-battery site that
        // cycles daily, and negative on another (2026-10-17/18).
        Double remaining = sumOf(bats, "remainingEnergy")
        Double full = sumOf(bats, "fullPackEnergy")
        if (remaining != null) out << ev("residualEnergy", round(remaining * toKwh, 3), "kWh")
        if (full != null && plate) out << ev("stateOfHealth", Math.min(100, Math.round(full / plate * 100) as int), "%")
    }
    if (communication?.connectivityStatus instanceof String) {
        out << ev("siteConnectivity", communication.connectivityStatus == "ONLINE" || asleep ? "online" : "offline")
    }
    return out
}

/**
 * Charged and discharged kWh so far, integrated from the battery power samples
 * (W, charging positive) of each poll. Kept in state, so the totals survive
 * restarts; a gap longer than MAX_SAMPLE_GAP_MS or 3 poll intervals isn't counted.
 */
Map batteryFlow(String dni, int powerW, long t) {
    Map all = state.flow ?: [:]
    Map f = all[dni] ? new LinkedHashMap(all[dni]) : [charged: 0d, discharged: 0d]
    long gap = Math.max(MAX_SAMPLE_GAP_MS, 3L * pollInterval() * 1000L)
    if (f.t != null && t > (f.t as long) && t - (f.t as long) <= gap) {
        List kwh = batteryFlowKwh(f.w as double, powerW as double, (t - (f.t as long)) / 1000d)
        f.charged = (f.charged as double) + (kwh[0] as double)
        f.discharged = (f.discharged as double) + (kwh[1] as double)
    }
    f.t = t
    f.w = powerW
    all[dni] = f
    state.flow = all
    return f
}

/**
 * [charged, discharged] kWh between two power samples: trapezoids, and when the
 * sign changes the interval is split where the power crosses zero, so charging
 * never cancels discharging.
 */
List batteryFlowKwh(double previousW, double currentW, double seconds) {
    double hours = seconds / 3600d
    if (previousW >= 0 && currentW >= 0) return [(previousW + currentW) / 2 * hours / 1000, 0d]
    if (previousW <= 0 && currentW <= 0) return [0d, -(previousW + currentW) / 2 * hours / 1000]
    double zeroAt = previousW / (previousW - currentW)
    double before = previousW / 2 * zeroAt * hours / 1000
    double after = currentW / 2 * (1 - zeroAt) * hours / 1000
    return [Math.max(before, 0d) + Math.max(after, 0d), -Math.min(before, 0d) - Math.min(after, 0d)]
}

private Double sumOf(List items, String key) {
    List vals = items.collect { it[key] }
    if (!vals || vals.any { !(it instanceof Number) }) return null
    return vals.sum { it as double } as double
}

/**
 * A counter that never falls, from one that can (a reset, old sessions leaving
 * a rolling year): a fall adds the difference to an offset. A single low
 * reading is held and only counted when the next one is low too.
 */
double monotonic(String dni, String name, double reading) {
    Map all = state.counters ?: [:]
    Map c = all["${dni}:${name}"] ?: [:]
    Double last = c.last as Double
    double offset = (c.offset ?: 0) as double
    double result
    if (last != null && reading < last) {
        if (!c.falling) {
            c.falling = true
            result = last + offset
            all["${dni}:${name}"] = c
            state.counters = all
            return Math.round(result * 1000) / 1000d
        }
        offset += last - reading
        c.offset = offset
    }
    c.falling = false
    c.last = reading
    all["${dni}:${name}"] = c
    state.counters = all
    return Math.round((reading + offset) * 1000) / 1000d
}

/** The device's entry in a getSiteDevices answer. */
private Map deviceIn(def resp, String reporterId) {
    allSiteDevices(resp).find { it.reporterId?.toString() == reporterId }
}

/**
 * EV charger: Switch is charging on/off; power from the charger's own reading,
 * else the site power flow; session values; total charged from the charging
 * history (slow tick). No "discharged": SolarEdge chargers only charge.
 */
List evEvents(String dni, String reporterId, Map pf, Map devicesResp, Map sessions = null) {
    List out = []
    def flow = pf?.evCharger
    Map charger = deviceIn(devicesResp, reporterId)
    // The power flow has one evCharger per site: the charger's own entry comes first.
    String conn = charger?.connectionStatus ?: flow?.connectionStatus
    if (conn) {
        List cs = EV_CONNECTION[conn.toString().toLowerCase()]
        if (cs) {
            out << ev("chargingState", cs[0])
            out << ev("switch", cs[1] ? "on" : "off")
        } else {
            logOnce("ev status ${conn}", "unknown EV connection status ${conn}")
        }
    }
    Integer power = null
    if (charger) {
        def sub = (charger.chargerStatusSubTitle ?: []).find { it.subStatusTitle == "CURRENT_POWER_WH" }
        String status = (charger.connectionStatus ?: "").toString().toUpperCase()
        // CURRENT_POWER_WH is W despite its name; 0 while charging is no reading.
        if (sub?.numericValue instanceof Number) power = Math.round(sub.numericValue as double) as Integer
        if (status == "CHARGING" && power == 0) power = null
        if (power == null && status && status != "CHARGING") power = 0
        if (charger.sessionEnergy instanceof Number) out << ev("sessionEnergy", kwh(charger.sessionEnergy), "kWh")
        if (charger.sessionDuration instanceof Number) out << ev("sessionDuration", Math.round((charger.sessionDuration as double) / 60) as int, "min")
        if (EV_MODES[charger.activationMode]) out << ev("chargeMode", EV_MODES[charger.activationMode])
        def pv = EXCESS_PV[charger.excessPV?.toString()]
        if (pv != null) out << ev("solarCharging", pv ? "on" : "off")
        if (charger.applianceData?.alias instanceof String) out << ev("vehicle", charger.applianceData.alias)
    }
    if (power == null && flow) power = flow.currentPower == null ? 0 : watts(flow.currentPower)
    if (power != null) out << ev("power", power, "W")
    if (sessions?.applianceDetails instanceof List) {
        double wh = 0
        sessions.applianceDetails.each { d ->
            def s = (d?.statistics ?: []).find { it.subjectEnum == "CONSUMED_ENERGY" }
            if (s?.totalValue instanceof Number) wh += s.totalValue as double
        }
        BigDecimal total = round(monotonic(dni, "charged", wh / 1000), 3)
        out << ev("energy", total, "kWh")
        out << ev("energyCharged", total, "kWh")
    }
    return out
}

/**
 * Load controller: controllerState on/off/auto; Switch follows the relay; power
 * from its meter, else rated power x level (estimated).
 */
List hlcEvents(String reporterId, Map devicesResp) {
    if (devicesResp == null) return []
    Map d = deviceIn(devicesResp, reporterId)
    if (!d) {
        logOnce("hlc missing ${reporterId}", "load controller ${reporterId} is no longer in SolarEdge's device list")
        return []
    }
    List out = []
    def level = d.status?.level
    if (d.activationMode == "AUTO") out << ev("controllerState", "auto")
    else if (d.activationMode == "MANUAL" && level == 0) out << ev("controllerState", "off")
    else if (d.activationMode == "MANUAL" && level == 100) out << ev("controllerState", "on")
    if (level instanceof Number) out << ev("switch", level > 0 ? "on" : "off")
    def measured = d.status?.activePowerMeter
    def rated = d.config?.ratedPower
    Integer power = null
    if (measured instanceof Number) power = Math.round(measured as double) as Integer
    else if (rated instanceof Number && level instanceof Number) power = Math.round((rated as double) * (level as double) / 100) as Integer
    if (power != null) {
        out << ev("power", power, "W")
        out << ev("powerSource", measured instanceof Number ? "measured" : "estimated")
    }
    return out
}

// ---------------------------------------------------------------- helpers

/** Epoch ms of an ISO 8601 time ("2026-10-15T11:50:28Z", with or without fraction or offset) or date. */
Long parseIso(def s) {
    // Epoch ms, as some SolarEdge answers give times.
    if (s instanceof Number) return (s as long) > 0 ? s as long : null
    if (!(s instanceof String) || !s) return null
    String t = s.trim().replaceFirst(/\.\d+/, "")
    try {
        if (t ==~ /\d{4}-\d{2}-\d{2}/) {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd")
            f.setTimeZone(TimeZone.getTimeZone("UTC"))
            return f.parse(t).time
        }
        if (!(t ==~ /.*(Z|[+-]\d{2}:?\d{2})$/)) t += "Z"
        return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").parse(t.replaceFirst(/Z$/, "+00:00")).time
    } catch (e) {
        return null
    }
}

private TimeZone zone(String tz) { tz ? TimeZone.getTimeZone(tz) : (location?.timeZone ?: TimeZone.getDefault()) }
int yearIn(long t, String tz) { new Date(t).format("yyyy", zone(tz)) as int }
String dayIn(long t, String tz) { new Date(t).format("yyyy-MM-dd", zone(tz)) }
private long yearStart(int year) {
    Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    c.clear()
    c.set(year, 0, 1)
    return c.timeInMillis
}

private void logOnce(String key, String msg) {
    Map seen = runtime().loggedOnce
    if (seen.putIfAbsent(key, true) == null) log.info "SolarEdge: ${msg}"
}

private void logDebug(String msg) {
    if (settings.logEnable) log.debug "SolarEdge: ${msg}"
}
