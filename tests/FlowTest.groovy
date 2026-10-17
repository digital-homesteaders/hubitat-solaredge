/**
 * End-to-end flows through the app and the real drivers, with the hub and
 * SolarEdge faked: discovery, login, ticks that share one request per site,
 * energy and slow ticks, the meter's yearly totals, events only on change,
 * 401 and expired-session retries, rejected logins, outages and 429 backoff,
 * failed-tick health, the night rule, commands, and no credentials in logs.
 * State goes through JSON after each step (hub.settle()), as on a hub.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import static Check.check

@Field File root = new File(getClass().protectionDomain.codeSource.location.path).parentFile.parentFile
@Field Map synth = new JsonSlurper().parse(new File(root, "tests/fixtures/synthetic-site.json"))
@Field String USER = "owner@example.com"
@Field String PASS = "s3cret-Pa55"
@Field String SITE = "9999999"

@Field Hub hub
@Field def app
@Field int answered = 0          // app.calls answered so far
@Field Map replay = [:]          // request key -> fixture answer
@Field Closure override = null   // (call) -> FakeResp or null, to fake failures
@Field List unknown = []         // requests the fixture has no answer for

// Script.setProperty writes to the binding; inside the checks' closures set the fields instead.
void setProperty(String name, Object v) {
    if (metaClass.hasProperty(this, name)) metaClass.setProperty(this, name, v)
    else super.setProperty(name, v)
}

/** A fresh hub and app with credentials, and the fixture's answers by request key. */
void fresh() {
    hub = new Hub(root)
    hub.clock = synth.capturedAt as long
    app = hub.app()
    // Each hub numbers its apps from 100: forget the previous test's session.
    app.RUNTIME.remove(app.app.id)
    app.settings.username = USER
    app.settings.password = PASS
    answered = 0
    override = null
    unknown = []
    replay = [:]
    synth.calls.each { c ->
        Map a = c.args ?: [:]
        Map r
        switch (c.method) {
            case "getSites": r = app.reqSites(); break
            case "getSitePowerflow": r = app.reqPowerflow(a.siteId); break
            case "getSiteDevices": r = app.reqSiteDevices(a.siteId); break
            case "getSiteEnergyOverview": r = app.reqEnergyOverview(a.siteId); break
            case "getSiteEquipment": r = app.reqEquipment(a.siteId); break
            case "getInvertersInformation": r = app.reqInverters(a.serials); break
            case "getSiteEnergyByInverter": r = app.reqEnergyByInverter(a.siteId, a.startDate, a.inverterSerials); break
            case "getSiteBatteries": r = app.reqBatteries(a.siteId); break
            case "getSiteMeasurements": r = app.reqMeasurements(a.siteId, (a.endDate as String).take(4) as int); break
            case "getSiteDeviceApplianceSessionsHistory": r = app.reqSessions(a.siteId, a.reporterId); break
            case "getSiteCommunication": r = app.reqCommunication(a.siteId); break
            case "getSiteAlerts": r = app.reqAlerts(a.siteId); break
        }
        // The fixture's getSites is the `sites` map; the API wraps it.
        replay[r.key] = c.method == "getSites" ? [sites: c.result] : c.result
    }
}

/** The request key of an asynchttp call, as the app builds it. */
String keyOf(List call) {
    Map p = call[2]
    String q = (p.query ?: [:]).collect { k, v -> (v instanceof List ? v : [v]).collect { "${k}=${it}" }.join("&") }.join("&")
    return q ? "${p.path}?${q}" : p.path
}

FakeResp ok(def json) { new FakeResp(status: 200, data: JsonOutput.toJson(json)) }
FakeResp loginOk() { new FakeResp(status: 302, data: "", headers: ["Set-Cookie": "SPRING_SECURITY_REMEMBER_ME_COOKIE=abc; Path=/; Expires=Wed, 21 Oct 2026 07:28:00 GMT, CSRF-TOKEN=xyz; Path=/"]) }

List httpCalls() { app.calls.findAll { it[0] in ["asynchttpGet", "asynchttpPost", "asynchttpPut"] } }

/** Answer every HTTP call the app has made, until it makes no more. Returns the calls answered. */
List serve() {
    List done = []
    while (true) {
        List all = httpCalls()
        if (answered >= all.size()) break
        List call = all[answered++]
        done << call
        FakeResp resp = override?.call(call)
        if (resp == null) {
            if (call[0] == "asynchttpPost") resp = loginOk()
            else if (call[0] == "asynchttpPut") resp = ok([status: "PASSED"])
            else if (replay.containsKey(keyOf(call))) resp = ok(replay[keyOf(call)])
            else {
                unknown << keyOf(call)
                resp = new FakeResp(status: 404, data: JsonOutput.toJson([error: [code: "NOT_FOUND"]]))
            }
        }
        app."${call[1]}"(resp, call[3])
        hub.settle()
    }
    return done
}

/** Gets in a list of answered calls whose path contains `part`. */
int gets(List calls, String part) { calls.count { it[0] == "asynchttpGet" && it[2].path.contains(part) } }

FakeDevice child(String suffix) { app.getChildDevice("solaredge-${SITE}-${suffix}") }

/** Discovery, pick every device, Done, and the first (energy) tick. */
List install() {
    app.mainPage()
    app.devicesPage()
    serve()
    app.settings.selectedDevices = new ArrayList(app.state.discovered.keySet())
    app.installed()
    hub.settle()
    app.tick()
    hub.settle()
    return serve()
}

/** A plain fast tick. */
List fastTick() {
    hub.clock += 60000
    app.tick()
    hub.settle()
    return serve()
}

Map values(FakeDevice d) { d.current }

// ---------------------------------------------------------------- the synthetic site

check("discovery: one login, the sites, then per site power flow, devices and equipment") {
    fresh()
    app.mainPage()
    app.devicesPage()
    List calls = serve()
    assert unknown == []
    assert calls.count { it[0] == "asynchttpPost" } == 1
    assert gets(calls, "so/sites/") == 1 && gets(calls, "powerflow") == 1 && gets(calls, "equipment") == 1
    assert app.state.discovered.size() == 5
    assert app.state.sites[SITE] == [name: "Synthetic site", tz: "Europe/Amsterdam", installYear: 2024]
    assert !app.state.discovering
}

check("first tick: one power flow and one devices list for five devices") {
    fresh()
    List calls = install()
    assert unknown == []
    assert app.getChildDevices().size() == 5
    assert calls.count { it[0] == "asynchttpPost" } == 0, "the discovery session is reused"
    assert gets(calls, "powerflow") == 1
    assert gets(calls, "v1.0/sites/") == 1
    // energy (first tick): overview, inverters, batteries, this year; slow: by-inverter, communication, alerts, EV history;
    // and the first past meter year
    assert gets(calls, "energyOverview") == 1 && gets(calls, "information/inverters") == 1 && gets(calls, "getBatteries") == 1
    assert gets(calls, "by-inverter") == 1 && gets(calls, "communication") == 1 && gets(calls, "alerts") == 1
    assert gets(calls, "applianceSessionsHistory") == 1
    assert gets(calls, "measurements") == 2
    assert calls.size() == 11
}

check("first tick: device values (com.solaredge's synthetic expectations)") {
    Map inv = values(child("inverter"))
    assert inv.power == 5600 && inv.alerts == 1 && inv.inverterStatus == "production" && inv.energyLifetime == 29338.528
    assert inv.healthStatus == "online"
    Map bat = values(child("battery"))
    assert bat.power == 500 && bat.battery == 62 && bat.chargingState == "charging"
    assert bat.energyCharged == 1234.567 && bat.energyDischarged == 1000 && bat.residualEnergy == 6.2 && bat.stateOfHealth == 95
    Map ev = values(child("ev-1000001479067354"))
    assert ev.power == 9509 && ev.switch == "on" && ev.chargingState == "plugged_in_charging" && ev.energyCharged == 127.456
    assert ev.sessionEnergy == 0.093 && ev.sessionDuration == 1 && ev.chargeMode == "manual" && ev.solarCharging == "off" && ev.vehicle == "Ioniq"
    Map hlc = values(child("hlc-102"))
    assert hlc.controllerState == "on" && hlc.power == 2000 && hlc.switch == "on"
    Map m = values(child("meter"))
    assert m.power == -1200 && m.loadPower == 800 && m.solarPower == 5600 && m.siteConnectivity == "online"
    assert m.energyImportedYear == 1400
    assert m.energyImported == null, "lifetime totals wait for the past years"
}

check("fast tick: only the power flow and the devices list, plus the next past meter year") {
    List calls = fastTick()
    assert unknown == []
    assert calls.size() == 3
    assert gets(calls, "powerflow") == 1 && gets(calls, "v1.0/sites/") == 1
    assert calls.find { it[2].path.contains("measurements") }[2].query["end-date"] == "2024-12-31"
    // 2024 is the install year: the totals are complete.
    Map m = values(child("meter"))
    assert m.energyImported == 4400 && m.energyExported == 8300 && m.energy == 4400
    assert app.state.years[SITE].done
}

check("unchanged values send no events; past years are not fetched again") {
    int before = app.getChildDevices().sum { it.events.size() }
    List calls = fastTick()
    assert calls.size() == 2
    assert app.getChildDevices().sum { it.events.size() } == before
}

check("energy tick: energy and battery data; slow data only every third") {
    List counts = (1..3).collect {
        hub.clock += 300000
        app.energyTick()
        hub.settle()
        List calls = serve()
        [energy: gets(calls, "energyOverview"), batteries: gets(calls, "getBatteries"), slow: gets(calls, "alerts") + gets(calls, "applianceSessionsHistory")]
    }
    assert counts*.energy == [1, 1, 1]
    assert counts*.batteries == [1, 1, 1]
    // The first energy tick (at install) was slow; the fourth is the next.
    assert counts*.slow == [0, 0, 2]
    assert unknown == []
}

check("requests per day for this site (60 s, all five devices)") {
    app.state.years = [(SITE): [done: true]]
    int fast = app.planTick([[type: "inverter", siteId: SITE, serials: "X"], [type: "meter", siteId: SITE], [type: "battery", siteId: SITE],
        [type: "ev", siteId: SITE, reporterId: "1"], [type: "hlc", siteId: SITE, reporterId: "2"]], false, false).size()
    assert fast == 2
    // 1440 ticks a day, of which 288 energy ticks (+4) and 96 slow ones (+4 more): about 4,700.
    int perDay = 1440 * 2 + 288 * 4 + 96 * 4
    println "    about ${perDay} requests a day (com.solaredge 1.8.1: ~86,000 for three devices)"
    assert perDay < 5000
}

// ---------------------------------------------------------------- overlap and retries

check("a tick never overlaps the previous one") {
    fresh()
    install()
    hub.clock += 60000
    app.tick()
    int sent = httpCalls().size()
    app.tick()
    assert httpCalls().size() == sent
    serve()
    // Given up on after STALE_TICK_MS, then a new tick goes out.
    hub.clock += 60000
    app.tick()
    int sent2 = httpCalls().size()
    hub.clock += 71000
    app.tick()
    assert httpCalls().size() > sent2
    serve()
}

check("401: log in again once and retry; a second 401 is an error, not a loop") {
    fresh()
    install()
    int logins = app.callsNamed("asynchttpPost").size()
    Set seen = [] as Set
    override = { call -> call[0] == "asynchttpGet" && call[2].path.contains("powerflow") && seen.add(call[3].retried) && !call[3].retried ? new FakeResp(status: 401, data: "") : null }
    List calls = fastTick()
    assert app.callsNamed("asynchttpPost").size() == logins + 1
    assert gets(calls, "powerflow") == 2
    assert child("inverter").current.healthStatus == "online"

    override = { call -> call[0] == "asynchttpGet" ? new FakeResp(status: 401, data: "") : null }
    fastTick()
    assert app.callsNamed("asynchttpPost").size() == logins + 2, "one more login for the tick, then errors, no loop"
}

check("an expired session that redirects to the login page counts as logged out") {
    fresh()
    install()
    int logins = app.callsNamed("asynchttpPost").size()
    boolean once = true
    override = { call ->
        if (call[0] == "asynchttpGet" && call[2].path.contains("powerflow") && once) { once = false; return new FakeResp(status: 200, data: "<html><form><input name='j_password'></form></html>") }
        null
    }
    fastTick()
    assert app.callsNamed("asynchttpPost").size() == logins + 1
    assert child("meter").current.healthStatus == "online"
}

check("rejected login: blocked 1 min, doubling up to 1 h, no login attempts while blocked") {
    fresh()
    override = { call -> call[0] == "asynchttpPost" ? new FakeResp(status: 200, data: "<html><input name='j_password'></html>") : null }
    app.mainPage()
    app.devicesPage()
    serve()
    assert app.state.status.contains("rejected")
    Map rt = app.RUNTIME[app.app.id]
    assert rt.loginRetryMs == 60000
    // Within the block: no login request.
    hub.clock += 30000
    app.state.discoveredAt = null
    app.devicesPage()
    serve()
    assert app.callsNamed("asynchttpPost").size() == 1
    List delays = []
    6.times {
        hub.clock = rt.loginBlockedUntil + 1
        app.state.remove("discovering")
        app.state.discoveredAt = null
        app.devicesPage()
        serve()
        delays << rt.loginRetryMs
    }
    assert delays == [120000, 240000, 480000, 960000, 1920000, 3600000].collect { it as long }
}

check("a login that fails on SolarEdge's side is an outage, not a rejection") {
    fresh()
    override = { call -> call[0] == "asynchttpPost" ? new FakeResp(status: 503, data: "") : null }
    app.devicesPage()
    serve()
    Map rt = app.RUNTIME[app.app.id]
    assert !rt.loginRetryMs
    assert app.state.status.contains("unavailable")
}

check("outage: back off, doubling, and devices offline only after 3 failed ticks") {
    fresh()
    install()
    Map rt = app.RUNTIME[app.app.id]
    override = { call -> new FakeResp(status: 0, data: null, errorMessage: "connect timed out") }
    fastTick()
    assert rt.backoffUntil == hub.clock + 120000
    assert child("inverter").current.healthStatus == "online", "one failed tick is no problem yet"
    // Skipped while backing off.
    int sent = httpCalls().size()
    hub.clock += 60000
    app.tick()
    assert httpCalls().size() == sent
    hub.clock = rt.backoffUntil + 1
    app.tick(); hub.settle(); serve()
    assert rt.backoffUntil == hub.clock + 240000
    hub.clock = rt.backoffUntil + 1
    app.tick(); hub.settle(); serve()
    assert child("inverter").current.healthStatus == "offline"
    assert child("ev-1000001479067354").current.healthStatus == "offline"
    // Back: online again, backoff cleared.
    override = null
    hub.clock = rt.backoffUntil + 1
    app.tick(); hub.settle(); serve()
    assert child("inverter").current.healthStatus == "online"
    assert rt.backoffUntil == 0
}

check("429: back off even when other requests succeed") {
    fresh()
    install()
    Map rt = app.RUNTIME[app.app.id]
    override = { call -> call[0] == "asynchttpGet" && call[2].path.endsWith("/devices") ? new FakeResp(status: 429, data: "") : null }
    fastTick()
    assert rt.backoffUntil > hub.clock
    assert app.state.status.contains("429")
}

// ---------------------------------------------------------------- meter years

check("meter years: an error aborts the run and nothing is stored; no data ends it") {
    fresh()
    override = { call -> call[0] == "asynchttpGet" && call[2].path.contains("measurements") && call[2].query["end-date"] == "2025-12-31" ? new FakeResp(status: 500, data: "") : null }
    install()
    Map ys = app.state.years[SITE]
    assert !ys.data?.containsKey("2025") && !ys.done && ys.retryAt
    assert child("meter").current.energyImported == null
    List calls = fastTick()
    assert gets(calls, "measurements") == 0, "waits before the next run"
    override = null
    hub.clock = ys.retryAt + 1
    fastTick()
    fastTick()
    assert app.state.years[SITE].done
    assert child("meter").current.energyImported == 4400
}

check("meter years: hard floor without an install year, no data stops early") {
    fresh()
    app.state.sites = [(SITE): [name: "x", tz: "UTC", installYear: null]]
    long t = hub.clock
    // Years 2016..2025 are candidates; nothing older is ever asked.
    List asked = []
    Integer y
    Map ys = [data: [:]]
    while ((y = app.nextBackfillYear(SITE, t)) != null) {
        asked << y
        ys.data[y as String] = [0, 0]
        app.state.years = [(SITE): ys]
    }
    assert asked == (2025..2016).toList()
    // "No data" for 2023 ends the walk.
    app.state.years = [(SITE): [data: ["2025": [1, 1], "2024": [1, 1]]]]
    app.recordYear([siteId: SITE, year: 2023, backfill: true], [json: [summary: null]])
    assert app.state.years[SITE].done && app.nextBackfillYear(SITE, t) == null
}

// ---------------------------------------------------------------- night

check("inverter-only site at night: fast ticks skipped, energy ticks still poll, never unavailable") {
    fresh()
    replay[app.reqPowerflow(SITE).key] = [solarProduction: [isActive: false, currentPower: null], grid: [isActive: false]]
    app.mainPage()
    app.devicesPage()
    serve()
    app.settings.selectedDevices = ["solaredge-${SITE}-inverter".toString()]
    app.installed()
    hub.settle()
    app.tick(); hub.settle(); serve()
    assert child("inverter").current.power == 0
    assert child("inverter").current.healthStatus == "online"
    List calls = fastTick()
    assert calls == []
    hub.clock += 300000
    app.energyTick(); hub.settle()
    calls = serve()
    assert gets(calls, "powerflow") == 1
    assert child("inverter").current.healthStatus == "online"
}

// ---------------------------------------------------------------- commands, settings, logs

check("commands: EV and load controller send activationState, then read back") {
    fresh()
    install()
    child("ev-1000001479067354").on()
    child("hlc-102").setState("auto")
    List puts = app.callsNamed("asynchttpPut")
    assert puts.size() == 2
    assert puts[0][2].path.endsWith("/9999999/devices/1000001479067354/activationState")
    assert new JsonSlurper().parseText(puts[0][2].body) == [mode: "MANUAL", level: 100, duration: null]
    assert new JsonSlurper().parseText(puts[1][2].body) == [mode: "AUTO", level: null, duration: null]
    serve()
    assert app.callsNamed("runIn").any { it[1] == 5 && it[2] == "tick" }
}

check("poll interval: default 60 s, clamped to 30-300") {
    fresh()
    assert app.pollInterval() == 60
    app.settings.pollSeconds = 10
    assert app.pollInterval() == 30
    app.settings.pollSeconds = 900
    assert app.pollInterval() == 300
    app.settings.pollSeconds = 120
    app.tick()
    assert app.callsNamed("runIn").last()[1] == 120
}

check("debug logging turns itself off after 30 minutes (app and drivers)") {
    fresh()
    install()
    app.settings.logEnable = true
    app.updated()
    assert app.callsNamed("runIn").any { it[1] == 1800 && it[2] == "logsOff" }
    app.logsOff()
    assert app.settings.logEnable == "false"
    FakeDevice inv = child("inverter")
    inv.driver.settings.logEnable = true
    inv.updated()
    assert inv.driver.callsNamed("runIn").any { it[1] == 1800 && it[2] == "logsOff" }
    inv.logsOff()
    assert inv.driver.settings.logEnable == "false"
}

check("credentials: only in the app's settings; never in logs, state or device data") {
    fresh()
    app.settings.logEnable = true
    install()
    fastTick()
    app.getChildDevices().each { it.driver.settings.logEnable = true }
    fastTick()
    List texts = app.log.lines*.get(1) + app.getChildDevices().collectMany { it.driver.log.lines*.get(1) }
    assert texts.size() > 10
    assert !texts.any { it.contains(USER) || it.contains(PASS) || it.contains("owner") }
    assert !JsonOutput.toJson(app.state).contains(PASS) && !JsonOutput.toJson(app.state).contains(USER)
    app.getChildDevices().each { d ->
        assert !d.data.values().any { it?.contains(PASS) || it?.contains(USER) }
        assert !d.driver.settings.values().any { it?.toString()?.contains(PASS) }
    }
    // The login body carries them, nothing else does.
    assert app.callsNamed("asynchttpPost").every { it[2].body.contains("j_password=") }
    assert app.callsNamed("asynchttpGet").every { !JsonOutput.toJson(it[2]).contains(PASS) }
}

check("state stays small: no response bodies") {
    String s = JsonOutput.toJson(app.state)
    assert s.length() < 3000
    assert !s.contains("energyProducedOverviewList") && !s.contains("devicesByType")
}

check("new credentials drop the session") {
    Map rt = app.RUNTIME[app.app.id]
    assert rt.cookies
    app.settings.password = "changed"
    app.updated()
    assert !rt.cookies
}

check("cookies: several in one header, Expires dates kept whole") {
    assert app.cookiesOf(loginOk()) == ["SPRING_SECURITY_REMEMBER_ME_COOKIE=abc", "CSRF-TOKEN=xyz"]
    assert app.cookiesOf(new FakeResp(status: 302, headers: ["set-cookie": ["A=1; Path=/", "B=2"]])) == ["A=1", "B=2"]
    assert app.cookiesOf(new FakeResp(status: 302, headers: [:])) == []
}

Check.finish()
