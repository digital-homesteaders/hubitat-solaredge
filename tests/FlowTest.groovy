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
@Field int lastEnergyTicks = 0   // energy ticks in the last simulated day
@Field List lastDay = []         // its requests

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

/** Answer every HTTP call the app has made (at most `limit`), until it makes no more. Returns the calls answered. */
List serve(int limit = Integer.MAX_VALUE) {
    List done = []
    while (done.size() < limit) {
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
    assert app.state.sites[SITE] == [name: "Synthetic site", tz: "Europe/Amsterdam", installYear: 2024, peakPower: 8, serials: "7E000000-00"]
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
    assert bat.energyCharged == 0 && bat.energyDischarged == 0 && bat.residualEnergy == 6.2 && bat.stateOfHealth == 95
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

check("first tick: each device's summary line, with units") {
    assert child("battery").current.summary == "Charging 500 W · 62 % · 6.2 kWh left · charged 0.0 kWh · discharged 0.0 kWh · health 95 %"
    assert child("ev-1000001479067354").current.summary == "Charging 9.5 kW · session 0.1 kWh, 1 min · manual mode · Ioniq · 127 kWh charged in the past year"
    assert child("hlc-102").current.summary == "On (manual) · 2.0 kW (estimated)"
    assert child("meter").current.summary == "Exporting 1.2 kW to the grid · solar 5.6 kW · home 800 W · battery charging 500 W · this year imported 1,400 kWh, exported 2,700 kWh"
    String inv = child("inverter").current.summary
    assert inv.startsWith("Producing 5.6 kW · today 7.1 kWh · month 177 kWh · year 4,346 kWh · lifetime 29,339 kWh · ")
    assert inv =~ / \d{3}\.\d V · optimizers up to 100 °F · 1 alert$/
    // Only when it changes, and not logged as a description.
    assert child("battery").events.count { it.name == "summary" } == 1
}

check("summary: night inverter, idle grid, unplugged charger") {
    FakeDevice d = hub.device("SolarEdge Inverter", "x-inv")
    d.applyEvents([[name: "power", value: 0, unit: "W"], [name: "inverterStatus", value: "sleeping"], [name: "energyToday", value: 31.25, unit: "kWh"]])
    assert d.current.summary == "Sleeping · today 31.3 kWh"
    FakeDevice m = hub.device("SolarEdge Meter", "x-m")
    m.applyEvents([[name: "gridPower", value: 0], [name: "power", value: 0], [name: "batteryPower", value: -10000], [name: "siteConnectivity", value: "offline"]])
    assert m.current.summary == "No grid exchange · battery discharging 10.0 kW · site offline"
    FakeDevice e = hub.device("SolarEdge EV Charger", "x-ev")
    e.applyEvents([[name: "chargingState", value: "plugged_out"], [name: "power", value: 0], [name: "chargeMode", value: "auto"], [name: "solarCharging", value: "on"]])
    assert e.current.summary == "Unplugged · auto mode · solar charging on"
    FakeDevice b = hub.device("SolarEdge Battery", "x-b")
    b.applyEvents([[name: "chargingState", value: "discharging"], [name: "power", value: -10000], [name: "battery", value: 35], [name: "energyDischarged", value: 9.818]])
    assert b.current.summary == "Discharging 10.0 kW · 35 % · discharged 9.8 kWh"
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
    assert app.missingYear(SITE, hub.clock) == null
}

check("unchanged values send no events; past years are not fetched again") {
    // The battery charges at 500 W, so only its charged total moves.
    int before = app.getChildDevices().sum { it.events.size() }
    List calls = fastTick()
    assert calls.size() == 2
    assert child("battery").events.last().name == "energyCharged"
    assert app.getChildDevices().sum { it.events.size() } == before + 1, "the summary still reads 0.0 kWh: no event"
    // 500 W for two 60 s polls since the first one: 0.017 kWh, shown as 0.02
    assert child("battery").current.energyCharged == 0.02
}

check("an energy tick that lands on a tick in flight still runs (state written back by its answers)") {
    fresh()
    install()
    hub.clock += 300000
    app.tick()                                    // the fast tick that lands on the energy tick
    hub.settle()
    Map loaded = new JsonSlurper().parseText(JsonOutput.toJson(app.state))
    hub.clock += 500
    app.energyTick()                              // finds that tick in flight
    hub.settle()
    // Its answers run in another execution, which loaded state before the energy tick and
    // writes all of it back at the end (Hubitat keeps the last write).
    app.state = loaded
    serve()
    assert app.callsNamed("runIn").last()[1] == 1 && app.callsNamed("runIn").last()[2] == "tick", "deferred energy tick runs straight after"
    app.tick(); hub.settle()
    List calls = serve()
    assert gets(calls, "getBatteries") == 1 && gets(calls, "energyOverview") == 1
}

check("a fast tick due just before the energy tick is left to it") {
    fresh()
    install()
    Map rt = app.runtime()
    rt.nextEnergyAt = hub.clock + 70000
    hub.clock += 60000
    int sent = httpCalls().size()
    app.tick()
    assert httpCalls().size() == sent
    hub.clock += 10000
    app.energyTick(); hub.settle()
    assert gets(serve(), "getBatteries") == 1
}

check("energy tick: energy and battery data; slow data only every third") {
    fresh()
    install()
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

/** Requests over 24 simulated hours (60 s polls, energy ticks every 5 min), after the install and the past years. */
int requestsPerDay(List picked) {
    fresh()
    app.mainPage()
    app.devicesPage()
    serve()
    app.settings.selectedDevices = picked.collect { "solaredge-${SITE}-${it}".toString() }
    app.installed()
    hub.settle()
    app.tick(); hub.settle(); serve()
    fastTick()
    fastTick()
    // Optimizer temperatures for any day: the fixture's. The inverter uploads every
    // 5 minutes at :28, as on a real site, so the energy tick settles just after it.
    Map byInverter = synth.calls.find { it.method == "getSiteEnergyByInverter" }.result
    Map inverters = synth.calls.find { it.method == "getInvertersInformation" }.result
    override = { call ->
        if (call[2].path?.contains("by-inverter")) return ok(byInverter)
        if (call[2].path?.contains("information/inverters")) {
            long at = Math.floorDiv(hub.clock - 28000L, 300000L) * 300000L + 28000L
            String iso = new Date(at).format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))
            Map live = new JsonSlurper().parseText(JsonOutput.toJson(inverters))
            live.serialToLiveData.values().each { it.lastMeasurement = iso }
            return ok(live)
        }
        null
    }
    // Follow the app's own timers (the latest runIn/runInMillis of each wins, as on a hub).
    // On a tie the fast tick goes first: the case that used to lose the energy tick.
    long end = hub.clock + 24 * 3600 * 1000L
    int before = httpCalls().size()
    int energyTicks = 0
    while (true) {
        List r = app.calls.findAll { it[0] == "runIn" && it[2] == "tick" }.last()
        List e = app.calls.findAll { it[0] == "runInMillis" && it[2] == "energyTick" }.last()
        long tickAt = (r[4] as long) + (r[1] as long) * 1000
        long energyAt = (e[4] as long) + (e[1] as long)
        long next = Math.min(tickAt, energyAt)
        if (next > end) break
        hub.clock = next
        if (energyAt < tickAt) {
            energyTicks++
            app.energyTick()
        } else {
            app.tick()
        }
        hub.settle()
        serve()
    }
    assert unknown == []
    assert energyTicks in 288..289
    lastEnergyTicks = energyTicks
    lastDay = httpCalls().drop(before)
    return lastDay.size()
}

check("requests per day, measured: inverter, meter and battery (60 s)") {
    int n = requestsPerDay(["inverter", "meter", "battery"])
    int e = lastEnergyTicks
    int slow = gets(lastDay, "alerts")
    println "    inverter + meter + battery: ${n} requests a day, ${e} energy ticks (com.solaredge 1.8.1: ~86,000)"
    // One power flow a minute, even with a fast tick landing on every energy tick;
    // a couple more while the energy tick first moves to the inverter upload.
    int flows = gets(lastDay, "powerflow")
    assert flows in 1440..1444
    // Every energy tick: overview, inverters, batteries, this year's meter totals.
    ["energyOverview", "information/inverters", "getBatteries", "measurements"].each { assert gets(lastDay, it) == e, it }
    // Every third: optimizer temperatures, site connectivity, alerts.
    assert slow in [e.intdiv(3), e.intdiv(3) + 1]
    ["by-inverter", "communication"].each { assert gets(lastDay, it) == slow, it }
    assert n == flows + e * 4 + slow * 3
    assert n < 2900
}

check("requests per day, measured: all five devices (60 s)") {
    int n = requestsPerDay(["inverter", "meter", "battery", "ev-1000001479067354", "hlc-102"])
    int e = lastEnergyTicks
    int slow = gets(lastDay, "alerts")
    println "    all five devices: ${n} requests a day"
    // plus a devices list every poll and the EV history on slow ticks
    int flows = gets(lastDay, "powerflow")
    assert flows in 1440..1444 && gets(lastDay, "v1.0/sites/") == flows
    assert gets(lastDay, "applianceSessionsHistory") == slow
    assert n == flows * 2 + e * 4 + slow * 4
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
    hub.clock += 111000
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
    // A session that dies right after login holds logins back: the next ticks don't log in.
    Map rt = app.RUNTIME[app.app.id]
    assert rt.loginRetryMs == 60000 && app.state.status.contains("new session")
    hub.clock += 30000
    app.tick(); hub.settle(); serve()
    assert app.callsNamed("asynchttpPost").size() == logins + 2
    // After the block: one cycle (login, the 401, one re-login), then held for twice as long.
    hub.clock = rt.loginBlockedUntil + 1
    app.tick(); hub.settle(); serve()
    assert app.callsNamed("asynchttpPost").size() == logins + 4
    assert rt.loginRetryMs == 120000
    // Working again: the backoff clears.
    override = null
    hub.clock = rt.loginBlockedUntil + 1
    fastTick()
    assert rt.loginRetryMs == 0 && child("inverter").current.healthStatus == "online"
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

check("a login redirected back to the login page is a rejection, not a session") {
    fresh()
    override = { call -> call[0] == "asynchttpPost" ? new FakeResp(status: 302, data: "", headers: [
        "Set-Cookie": new FakeHeaderEntry(name: "Set-Cookie", value: "JSESSIONID=abc; Path=/"),
        "Location": new FakeHeaderEntry(name: "Location", value: "https://monitoring.solaredge.com/solaredge-web/p/login?error=true")]) : null }
    app.devicesPage()
    List calls = serve()
    assert calls.count { it[0] == "asynchttpGet" } == 0
    assert app.state.status.contains("rejected") && app.state.status.contains("login page")
    assert app.RUNTIME[app.app.id].loginRetryMs == 60000
    // A 302 to the portal is a good login.
    fresh()
    override = { call -> call[0] == "asynchttpPost" ? new FakeResp(status: 302, data: "", headers: [
        "Set-Cookie": new FakeHeaderEntry(name: "Set-Cookie", value: "JSESSIONID=abc; Path=/"),
        "Location": new FakeHeaderEntry(name: "Location", value: "https://monitoring.solaredge.com/solaredge-web/p/home")]) : null }
    app.devicesPage()
    serve()
    assert app.state.discovered.size() == 5
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
    assert !ys.data?.containsKey("2025") && app.missingYear(SITE, hub.clock) == 2025 && ys.retryAt
    assert child("meter").current.energyImported == null
    List calls = fastTick()
    assert gets(calls, "measurements") == 0, "waits before the next run"
    override = null
    hub.clock = ys.retryAt + 1
    fastTick()
    fastTick()
    assert app.missingYear(SITE, hub.clock) == null
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
    hub.settle()
    assert app.state.years[SITE].firstYear == 2024 && app.nextBackfillYear(SITE, t) == null
}

check("meter years: last year missed around New Year is fetched later, totals wait for it") {
    fresh()
    install()
    fastTick()
    assert child("meter").current.energyImported == 4400
    // The hub was off over New Year: 2026 never got into the past years.
    hub.clock = Date.parse("yyyy-MM-dd'T'HH:mm:ssX", "2027-01-10T12:00:00Z").time
    assert app.missingYear(SITE, hub.clock) == 2026
    replay[app.reqMeasurements(SITE, 2027).key] = [summary: [import: 10000, export: 20000]]
    replay[app.reqMeasurements(SITE, 2026).key] = [summary: [import: 1500000, export: 2900000]]
    app.energyTick(); hub.settle()
    List calls = serve()
    assert calls.any { it[2].path.contains("measurements") && it[2].query["end-date"] == "2026-12-31" }
    assert app.missingYear(SITE, hub.clock) == null
    // 2024 + 2025 + 2026 + 2027 so far
    assert child("meter").current.energyImported == 1200 + 1800 + 1500 + 10
    assert unknown == []
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

check("requests waiting for a login: same URL from two callers, both answered") {
    fresh()
    install()
    Map rt = app.RUNTIME[app.app.id]
    rt.cookies = []
    FakeDevice ev = child("ev-1000001479067354")
    ev.on()
    ev.off()
    hub.settle()
    serve()
    List puts = app.callsNamed("asynchttpPut")
    assert puts.size() == 2
    assert puts.collect { new JsonSlurper().parseText(it[2].body).level } == [100, 0]
    // A discovery and a tick both waiting for the power flow.
    rt.cookies = []
    app.state.discoveredAt = null
    app.devicesPage()
    hub.clock += 60000
    app.tick()
    hub.settle()
    List calls = serve()
    assert gets(calls, "powerflow") == 2
    assert app.state.discovered.size() == 5 && !app.state.discovering
    assert app.RUNTIME[app.app.id].tick == null, "the tick finished"
}

check("commands answered 204 without a body count as sent") {
    fresh()
    install()
    override = { call -> call[0] == "asynchttpPut" ? new FakeResp(status: 204, data: "") : null }
    child("hlc-102").off()
    serve()
    assert app.log.at("info").any { it.contains("off sent") }
    assert !app.log.at("warn").any { it.contains("failed") }
    assert app.callsNamed("runIn").last()[1] == 5
}

check("a tick is only given up on after the longest legitimate chain") {
    fresh()
    install()
    hub.clock += 60000
    app.tick()
    int sent = httpCalls().size()
    hub.clock += 100000
    app.tick()
    assert httpCalls().size() == sent, "still within login + request + re-login + retry + follow-up"
    hub.clock += 11000
    app.tick()
    assert httpCalls().size() > sent
    serve()
}

check("discovery restarted while the old one is out: old answers are ignored") {
    fresh()
    app.devicesPage()
    serve(2)                                // login + sites; the three per-site calls are now out
    int oldEnd = httpCalls().size()
    assert app.state.discovering && oldEnd - answered == 3
    hub.clock += 100000                     // the page gives up and starts again
    app.devicesPage()
    hub.settle()
    assert httpCalls().size() == oldEnd + 1, "the new run asks for the sites"
    // serve() answers the old run's calls first: they must not finish the new run.
    serve(3)
    assert app.state.discovering, "old answers finished the new run"
    serve()
    assert app.state.discovered.size() == 5
    assert !app.state.discovering
}

check("discovery copes with installationDate as epoch ms or an unknown format") {
    [1709287200000L, "1st March 2024", null].eachWithIndex { def inst, int i ->
        fresh()
        replay[app.reqSites().key] = [sites: [(SITE): [id: SITE as long, name: "Synthetic site", timezone: "Europe/Amsterdam", installationDate: inst]]]
        app.devicesPage()
        serve()
        assert app.state.discovered.size() == 5, inst
        assert app.state.sites[SITE].installYear == (i == 0 ? 2024 : null)
    }
}

check("stale and failing answers: one warning each, and one line when they're back") {
    fresh()
    String pf = app.reqPowerflow(SITE).key
    String devs = app.reqSiteDevices(SITE).key
    Map live = new LinkedHashMap(replay[pf])
    replay[devs] = replay[devs] + [fieldLastUpdateTS: hub.clock]
    install()
    Closure warns = { app.log.at("warn").findAll { it.contains("site ${SITE}") } }
    assert warns() == []
    // SolarEdge stops serving live data, and the devices list starts failing.
    replay[pf] = live + [isRealTime: false]
    override = { call -> call[2].path?.endsWith("/devices") ? new FakeResp(status: 500, data: "") : null }
    fastTick()
    fastTick()
    assert warns() == ["SolarEdge: powerflow for site ${SITE} looks stale: isRealTime false".toString(),
        "SolarEdge: siteDevices for site ${SITE} failed: HTTP 500 (logged again only once it works)".toString()]
    // Back to normal.
    replay[pf] = live
    replay[devs] = replay[devs] + [fieldLastUpdateTS: hub.clock + 60000]
    override = null
    fastTick()
    List infos = app.log.at("info").findAll { it.contains("site ${SITE}") }
    assert infos.contains("SolarEdge: powerflow for site ${SITE} is live again".toString())
    assert infos.contains("SolarEdge: siteDevices for site ${SITE} works again".toString())
    // The devices list goes untouched for half an hour: stale.
    hub.clock += 30 * 60000L
    fastTick()
    assert warns().last() ==~ /SolarEdge: siteDevices for site 9999999 looks stale: last updated 3\d min ago/
}

check("Log raw SolarEdge data: each answer logged in pieces, identifiers hidden") {
    fresh()
    install()
    app.mainPage()
    assert app.declaredInputs.containsKey("dumpRaw")
    int before = httpCalls().size()
    app.appButtonHandler("dumpRaw")
    List calls = serve()
    assert calls.size() == httpCalls().size() - before
    assert gets(calls, "powerflow") == 1 && gets(calls, "v1.0/sites/") == 1 && gets(calls, "getBatteries") == 1 && gets(calls, "information/inverters") == 1
    List lines = app.log.at("info").findAll { it.contains("raw siteDevices site ${SITE}") }
    assert lines[0] ==~ /SolarEdge: raw siteDevices site 9999999 \(looks stale: last updated \d+ min ago\), \d+ chars in \d+ parts/
    int n = (lines[0] =~ /in (\d+) parts/)[0][1] as int
    assert n > 1 && lines.size() == n + 1
    String text = lines.drop(1).collect { it.replaceFirst(/^SolarEdge: raw siteDevices site 9999999 \d+\/\d+: /, "") }.join("")
    Map back = new JsonSlurper().parseText(text)
    assert back.devicesByType.EV_CHARGER[0].applianceData.alias == "Ioniq"
    assert !text.contains("XXX-0000") && !text.contains("xxxxxxxx-xxxx")
    assert app.log.at("info").any { it.startsWith("SolarEdge: raw powerflow site ${SITE}, ") }
    // It doesn't disturb the poll: no tick is left running.
    assert app.runtime().tick == null
}

check("EV charger unplugged: devices list on energy ticks only; a charge in the power flow brings it back") {
    fresh()
    String pf = app.reqPowerflow(SITE).key
    String devs = app.reqSiteDevices(SITE).key
    Map unplugged = new JsonSlurper().parseText(JsonOutput.toJson(replay[devs]))
    unplugged.devicesByType.EV_CHARGER[0].connectionStatus = "DISCONNECTED"
    unplugged.devicesByType.EV_CHARGER[0].chargerStatusSubTitle = []
    replay[devs] = unplugged
    Map flow = replay[pf]
    replay[pf] = flow + [evCharger: [isActive: true, connectionStatus: "disconnected", currentPower: null]]
    app.mainPage(); app.devicesPage(); serve()
    app.settings.selectedDevices = ["solaredge-${SITE}-inverter".toString(), "solaredge-${SITE}-ev-1000001479067354".toString()]
    app.installed(); hub.settle()
    app.tick(); hub.settle(); serve()                       // first (energy) poll reads it
    FakeDevice ev = child("ev-1000001479067354")
    assert ev.current.chargingState == "plugged_out"
    List calls = fastTick()
    assert gets(calls, "v1.0/sites/") == 0 && gets(calls, "powerflow") == 1
    hub.clock += 300000
    app.energyTick(); hub.settle()
    assert gets(serve(), "v1.0/sites/") == 1, "energy tick still reads it"
    // The car starts charging: the power flow shows it first.
    replay[pf] = flow + [evCharger: [isActive: true, connectionStatus: "charging", currentPower: 7.2]]
    calls = fastTick()
    assert gets(calls, "v1.0/sites/") == 0
    assert ev.current.chargingState == "plugged_in_charging" && ev.current.power == 7200
    calls = fastTick()
    assert gets(calls, "v1.0/sites/") == 1, "while charging, every poll"
    assert unknown == []
}

check("info log: state changes only; power and energy readings in debug") {
    fresh()
    install()
    FakeDevice bat = child("battery")
    List info = bat.driver.log.at("info")
    assert info.any { it.contains("chargingState is charging") } && info.any { it.contains("battery is 62 %") }
    assert !info.any { it =~ /power is|energyCharged|residualEnergy|energy is/ }
    assert bat.lastEvent("power").descriptionText == "Synthetic site Battery power is 500 W", "events keep their description"
    bat.driver.settings.logEnable = true
    child("battery").applyEvents([[name: "power", value: 750, unit: "W"]])
    assert bat.driver.log.at("debug").any { it.contains("power 500 -> 750") }
    assert !bat.driver.log.at("info").any { it.contains("power is 750") }
}

check("solar forecast: hourly from Forecast.Solar, on the inverter and its summary") {
    fresh()
    Map answer = new JsonSlurper().parse(new File(root, "tests/fixtures/forecast-solar.json"))
    override = { call -> call[2].uri == "https://api.forecast.solar" ? ok(answer) : null }
    install()
    FakeDevice inv = child("inverter")
    assert inv.current.forecastToday == null, "off by default"
    app.settings.forecastEnable = true
    app.updated(); hub.settle()
    assert app.callsNamed("runEvery1Hour").any { it[1] == "forecastRefresh" }
    assert app.callsNamed("runIn").any { it[2] == "forecastRefresh" }
    app.forecastRefresh(); hub.settle()
    List calls = serve()
    List fc = calls.findAll { it[2].uri == "https://api.forecast.solar" }
    assert fc.size() == 1
    // SolarEdge's peak power (8 kWp), the hub's location rounded, 30 degrees facing south
    assert fc[0][2].path == "/estimate/52.37/4.89/30/0/8" && fc[0][2].query == [time: "iso8601"]
    assert !fc[0][2].headers.containsKey("Cookie"), "no SolarEdge session goes to Forecast.Solar"
    assert inv.current.forecastToday == 34.14 && inv.current.forecastTomorrow == 28.67
    assert inv.current.forecastPower == 4990 && inv.current.actualVsForecast == 45
    assert inv.current.summary.contains("forecast 34.1 kWh, tomorrow 28.7 kWh · 45 % of forecast so far")
    // The polls keep it current without asking Forecast.Solar again.
    hub.clock += 30 * 60000L
    calls = fastTick()
    assert !calls.any { it[2].uri == "https://api.forecast.solar" }
    assert inv.current.forecastPower > 4500 && inv.current.forecastSoFar > 15.74
    // Settings: size and direction.
    app.settings.forecastKwp = 6.5
    app.settings.forecastBearing = 135
    app.settings.forecastTilt = 20
    app.forecastRefresh()
    assert app.callsNamed("asynchttpGet").last()[2].path == "/estimate/52.37/4.89/20/-45/6.5"
    serve()
    // A failure: one warning, and it comes back.
    override = { call -> call[2].uri == "https://api.forecast.solar" ? new FakeResp(status: 429, data: JsonOutput.toJson([message: [text: "Rate limit for API calls reached."]])) : null }
    2.times { app.forecastRefresh(); serve() }
    assert app.log.at("warn").count { it.contains("solar forecast for site ${SITE} failed: Rate limit") } == 1
    override = { call -> call[2].uri == "https://api.forecast.solar" ? ok(answer) : null }
    app.forecastRefresh(); serve()
    assert app.log.at("info").any { it.contains("solar forecast for site ${SITE} works again") }
    // Turned off: no more forecast, and its state goes.
    app.settings.forecastEnable = false
    app.updated(); hub.settle()
    assert !app.state.containsKey("forecast")
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

check("cookies: Hubitat's HeaderEntry values (as seen on a hub)") {
    String id = "JSESSIONID=D8EA5332F48EE29C0CB90D49A560B0E4"
    FakeHeaderEntry entry = new FakeHeaderEntry(name: "Set-Cookie", value: "${id}; Path=/solaredge-apigw; Secure; HttpOnly")
    assert app.cookiesOf(new FakeResp(status: 302, headers: ["Set-Cookie": entry])) == [id]
    assert app.cookiesOf(new FakeResp(status: 302, headers: ["Set-Cookie": [entry, new FakeHeaderEntry(name: "Set-Cookie", value: "B=2")]])) == [id, "B=2"]
    // An entry whose name/value aren't readable: its text still works.
    def opaque = new Object() { String toString() { "Set-Cookie: ${id}; Path=/" } }
    assert app.cookiesOf(new FakeResp(status: 302, headers: ["Set-Cookie": opaque])) == [id]
    // Headers as a list of entries rather than a map.
    FakeResp listed = new FakeResp(status: 302)
    listed.metaClass.getHeaders = { -> [new FakeHeaderEntry(name: "Content-Type", value: "text/html"), entry] }
    assert app.cookiesOf(listed) == [id]
}

check("login with a HeaderEntry cookie logs in and the tick goes out") {
    fresh()
    FakeHeaderEntry entry = new FakeHeaderEntry(name: "Set-Cookie", value: "JSESSIONID=abc; Path=/solaredge-apigw; Secure; HttpOnly")
    override = { call -> call[0] == "asynchttpPost" ? new FakeResp(status: 302, data: "", headers: ["Set-Cookie": entry]) : null }
    app.mainPage()
    app.devicesPage()
    serve()
    assert app.state.status == "Logged in"
    assert app.state.discovered.size() == 5
    assert app.RUNTIME[app.app.id].cookies == ["JSESSIONID=abc"]
    assert app.callsNamed("asynchttpGet").every { it[2].headers.Cookie == "JSESSIONID=abc" }
}

Check.finish()
