/**
 * The SolarEdge mapping functions in apps/solaredge-connect.groovy against the
 * captured answers in tests/fixtures (from com.solaredge v2.0.0's test suite):
 * the synthetic site, a real inverter, eight real EV charger states and a KEBA
 * charger with two load controllers. Plus the night rule, counters that never
 * fall, battery units, Fahrenheit optimizers and the discovery offers.
 */

import groovy.json.JsonSlurper
import groovy.transform.Field
import static Check.check

@Field File root = new File(getClass().protectionDomain.codeSource.location.path).parentFile.parentFile
@Field Hub hub = new Hub(root)
@Field def app = hub.app()
@Field Map synth = new JsonSlurper().parse(new File(root, "tests/fixtures/synthetic-site.json"))
@Field List inverterLive = new JsonSlurper().parse(new File(root, "tests/fixtures/inverter-live.json"))
@Field Map evStates = new JsonSlurper().parse(new File(root, "tests/fixtures/ev-charger-devices.json")).responses

/** A fixture answer by method, and by arguments when given. */
def fx(String method, Map args = null, List calls = synth.calls) {
    def c = calls.find { it.method == method && (args == null || args.every { k, v -> it.args?.get(k) == v }) }
    assert c, "no ${method} ${args ?: ''} in the fixture"
    return c.result
}

/** Events as name -> value, no name twice. */
Map evs(List events) {
    Map out = [:]
    events.each { e ->
        assert !out.containsKey(e.name), "duplicate event ${e.name}"
        out[e.name] = e.value
    }
    return out
}

long captured = synth.capturedAt as long
hub.clock = captured

// ---------------------------------------------------------------- inverter

check("inverter: synthetic site") {
    Map v = evs(app.inverterEvents(fx("getSitePowerflow"), [overview: fx("getSiteEnergyOverview"), inverters: fx("getInvertersInformation"),
        byInverter: fx("getSiteEnergyByInverter"), alerts: fx("getSiteAlerts"), communication: fx("getSiteCommunication"), nowMs: captured, scale: "C"]))
    assert v.power == 5600
    assert v.energyLifetime == 29338.528 && v.energy == 29338.528
    assert v.energyToday == 7.131 && v.energyMonth == 176.703 && v.energyYear == 4345.507
    assert v.energyToday <= v.energyMonth && v.energyMonth <= v.energyYear && v.energyYear <= v.energyLifetime
    assert v.inverterStatus == "production"
    assert v.alerts == 1
    assert v.siteConnectivity == "online"
    assert v.optimizerTemperatureMax == 38
}

check("inverter: real capture, phase voltages averaged and rounded") {
    Map info = fx("getInvertersInformation", null, inverterLive)
    List phases = info.serialToPhaseMeasurements.values().flatten()
    Map v = evs(app.inverterEvents(null, [inverters: info, overview: fx("getSiteEnergyOverview", null, inverterLive),
        byInverter: fx("getSiteEnergyByInverter", null, inverterLive), nowMs: Date.parse("yyyy-MM-dd'T'HH:mm:ssX", "2026-10-15T11:52:00Z").time, scale: "F"]))
    [1, 2, 3].each { n ->
        def line = phases.find { it.lineNumber_3ph == n }
        assert v["voltageL${n}"] == (line.v_ac_V as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP)
    }
    assert v.voltage > 230 && v.voltage < 240
    assert !v.containsKey("power"), "no power without a power flow"
    assert v.inverterStatus == "production"
    // 38 °C on a Fahrenheit hub.
    assert v.optimizerTemperatureMax == 100.4
}

check("inverter: at night or idle it reports 0 W, never unavailable") {
    [[isActive: false, currentPower: null], [isActive: false, currentPower: 0], [isActive: true, currentPower: null], [:]].each { sp ->
        Map v = evs(app.inverterEvents([solarProduction: sp], [nowMs: captured]))
        assert v.power == 0
        assert !v.containsKey("healthStatus")
    }
}

check("inverter: asleep after the last upload, alarm suppressed; not after a cut-off or fault") {
    long last = Date.parse("yyyy-MM-dd'T'HH:mm:ssX", "2026-10-15T16:55:28Z").time
    Closure live = { int w, String status = "production" ->
        [serialToLiveData: [I1: [lastMeasurement: "2026-10-15T16:55:28Z", inverterStatus: status, pAc_W: w]]]
    }
    Map offline = [connectivityStatus: "OFFLINE"]
    Map night = evs(app.inverterEvents([solarProduction: [isActive: false]], [inverters: live(0), communication: offline, nowMs: last + 67 * 60000L]))
    assert night.inverterStatus == "sleeping" && night.siteConnectivity == "online" && night.power == 0
    Map cut = evs(app.inverterEvents(null, [inverters: live(1040), communication: offline, nowMs: last + 67 * 60000L]))
    assert cut.inverterStatus == "production" && cut.siteConnectivity == "offline"
    Map day = evs(app.inverterEvents(null, [inverters: live(0), communication: offline, nowMs: last + 25 * 3600000L]))
    assert day.inverterStatus == "production" && day.siteConnectivity == "offline"
    Map fault = evs(app.inverterEvents(null, [inverters: live(0, "FAULT"), nowMs: last + 67 * 60000L]))
    assert fault.inverterStatus == "fault"
}

check("isAsleep follows the upload interval (com.solaredge timing.js cases)") {
    long last = Date.parse("yyyy-MM-dd'T'HH:mm:ssX", "2026-10-15T18:55:28Z").time
    List night = [[lastMeasurement: "2026-10-15T18:55:28Z", pAc_W: 0]]
    Closure min = { int n -> last + n * 60000L }
    long fifteen = 15 * 60000L
    assert !app.isAsleep(night, min(9))
    assert app.isAsleep(night, min(10))
    assert !app.isAsleep(night, min(14), fifteen)
    assert app.isAsleep(night, min(20), fifteen)
    assert !app.isAsleep([[lastMeasurement: "2026-10-15T18:55:28Z", pAc_W: 1040]], min(60))
    assert !app.isAsleep([[lastMeasurement: "2026-10-15T18:55:28Z", pAc_W: 0, inverterStatus: "FAULT"]], min(60))
    assert app.isAsleep([[lastMeasurement: "2026-10-15T18:55:28Z", pAc_W: 0, inverterStatus: "PRODUCTION"]], min(60))
    assert !app.isAsleep([], min(60))
}

check("Fahrenheit optimizers are converted") {
    Map by = [inverters: [[optimizers: [[temperature: [temperature: 104, temperatureUnit: "FAHRENHEIT"]], [temperature: [temperature: 35, temperatureUnit: "CELSIUS"]]]]]]
    assert evs(app.inverterEvents(null, [byInverter: by, nowMs: captured, scale: "C"])).optimizerTemperatureMax == 40
}

// ---------------------------------------------------------------- meter

check("meter: synthetic site power split") {
    Map v = evs(app.meterEvents(fx("getSitePowerflow"), [communication: fx("getSiteCommunication")]))
    assert v.power == -1200 && v.gridPower == -1200 && v.gridStatus == "export"
    assert v.loadPower == 800 && v.solarPower == 5600 && v.batteryPower == 500
    assert v.siteConnectivity == "online"
    assert !v.containsKey("energyImported"), "no lifetime totals before the years are in"
}

check("meter: grid without a reading, discharging battery, totals") {
    Map pf = [grid: [isActive: true, currentPower: null], consumption: [currentPower: null], solarProduction: [isActive: false, currentPower: null],
        storage: [currentPower: 0.3, status: "Discharging"]]
    Map v = evs(app.meterEvents(pf, [totals: [imported: 4400000d, exported: 8300000d, yearImported: 1400000d, yearExported: 2700000d]]))
    assert v.power == 0 && v.gridStatus == "idle" && v.loadPower == 0 && v.solarPower == 0 && v.batteryPower == -300
    assert v.energyImported == 4400 && v.energyExported == 8300 && v.energy == 4400
    assert v.energyImportedYear == 1400 && v.energyExportedYear == 2700
    assert !evs(app.meterEvents([grid: [isActive: false, currentPower: null]])).containsKey("power")
}

check("meter and battery: site not ONLINE while the inverters sleep is no alarm") {
    Map offline = [connectivityStatus: "OFFLINE"]
    assert evs(app.meterEvents(null, [communication: offline, asleep: true])).siteConnectivity == "online"
    assert evs(app.meterEvents(null, [communication: offline, asleep: false])).siteConnectivity == "offline"
    assert evs(app.batteryEvents("b", null, null, offline, true)).siteConnectivity == "online"
    assert evs(app.batteryEvents("b", null, null, offline, false)).siteConnectivity == "offline"
}

// ---------------------------------------------------------------- battery

check("battery: synthetic site; charged/discharged start at 0 and come from power") {
    app.state.clear()
    Map v = evs(app.batteryEvents("b1", fx("getSitePowerflow"), fx("getSiteBatteries"), fx("getSiteCommunication"), false, captured))
    assert v.power == 500 && v.battery == 62 && v.chargingState == "charging"
    // SolarEdge's chargeEnergy/dischargeEnergy (1234567 / 1000000 Wh here) are not used.
    assert v.energyCharged == 0 && v.energyDischarged == 0 && v.energy == 0
    assert v.residualEnergy == 6.2 && v.stateOfHealth == 95
}

check("battery: real two-battery site (stored energy summed, health from the full pack)") {
    app.state.clear()
    Map resp = new JsonSlurper().parse(new File(root, "tests/fixtures/batteries-two.json")).response
    Map v = evs(app.batteryEvents("b2", [storage: [currentPower: 0, status: "Idle", chargeLevel: 95]], resp, null, false, captured))
    assert v.residualEnergy == 18.194 && v.stateOfHealth == 99
    assert v.power == 0 && v.chargingState == "idle" && v.battery == 95
}

check("battery: kWh energies when the nameplate is below 100; discharging is negative") {
    app.state.clear()
    Map bat = [remainingEnergy: 6.2, fullPackEnergy: 9.2, namePlateEnergy: 9.7]
    Map v = evs(app.batteryEvents("b3", [storage: [currentPower: 1.25, status: "DISCHARGING", chargeLevel: 40]], [devicesByType: [BATTERY: [bat]]], null, false, captured))
    assert v.residualEnergy == 6.2 && v.stateOfHealth == 95
    assert v.power == -1250 && v.chargingState == "discharging"
}

// [previous W, current W, charged kWh, discharged kWh] over one hour (the HA port's cases)
[[1000, 1000, 1.0, 0.0], [0, 2000, 1.0, 0.0], [-1000, -3000, 0.0, 2.0], [0, 0, 0.0, 0.0],
 [1000, -1000, 0.25, 0.25], [3000, -1000, 1.125, 0.125], [-1000, 3000, 1.125, 0.125]].each { List c ->
    check("battery flow ${c[0]} W -> ${c[1]} W over an hour: ${c[2]} kWh charged, ${c[3]} discharged") {
        List got = app.batteryFlowKwh(c[0] as double, c[1] as double, 3600d)
        assert Math.abs((got[0] as double) - (c[2] as double)) < 1e-9 && Math.abs((got[1] as double) - (c[3] as double)) < 1e-9
    }
}

check("battery flow: integrated per poll, not across a long gap, kept across restarts") {
    app.state.clear()
    app.settings.pollSeconds = 60
    long t = captured
    Closure poll = { int w, long at -> evs(app.batteryEvents("bf", [storage: [currentPower: Math.abs(w) / 1000d, status: w < 0 ? "discharging" : w > 0 ? "charging" : "idle"]], null, null, false, at)) }
    assert poll(-10000, t).energyDischarged == 0
    // 10 kW out for one minute, then 2 kW in for one minute (crossing zero on the way)
    assert poll(-10000, t + 60000).energyDischarged == 0.17
    Map v = poll(2000, t + 120000)
    // Shown in 0.01 kWh steps; the total behind it is exact (0.236 discharged, 0.003 charged).
    assert v.energyCharged == 0 && v.energyDischarged == 0.24
    assert Math.abs((app.state.flow.bf.discharged as double) - 0.2361) < 0.0001
    // The totals are plain state, so they come through the hub's JSON (a restart).
    hub.settle()
    // A 15-minute gap (an outage) adds nothing; the next minute counts again.
    assert poll(2000, t + 120000 + 15 * 60000).energyCharged == 0
    assert poll(2000, t + 120000 + 16 * 60000).energyCharged == 0.04
    // At a 300 s poll, three intervals (15 min) still count.
    app.settings.pollSeconds = 300
    assert poll(2000, t + 120000 + 31 * 60000).energyCharged == 0.54
    app.settings.remove("pollSeconds")
}

check("counters never fall (com.solaredge devices.js sequence)") {
    app.state.clear()
    List got = [10, 12, 3, 5, 6, 1, 1, 0, 1, 2].collect { app.monotonic("d", "test", it as double) }
    assert got == [10, 12, 12, 12, 13, 13, 13, 13, 13, 14]
}

// ---------------------------------------------------------------- EV charger

// state -> [chargingState, switch, chargeMode, solarCharging, power]
Map EV_EXPECTED = [
    charging: ["plugged_in_charging", "on", "manual", "off", 9509],
    pluggedin_not_charging: ["plugged_in", "off", "manual", "off", 0],
    pluggedin_pending_schedule: ["plugged_in", "off", "auto", "off", 0],
    pluggedin_pending_solar: ["plugged_in", "off", "auto", "on", 0],
    unplugged_not_charging: ["plugged_out", "off", "manual", "off", 0],
    unplugged_not_charging_excesspv_and_schedule_disabled: ["plugged_out", "off", "manual", "off", 0],
    "unplugged_not_charging_excesspv_disabled_but_schedule_enabled_M-F1000-1200_SASU0900-2000": ["plugged_out", "off", "auto", "off", 0],
    unplugged_not_charging_excesspv_enabled_schedule_disabled: ["plugged_out", "off", "auto", "on", 0],
    // CURRENT_POWER_WH is 0.0 while charging: no reading, so no power event without a power flow.
    keba_charging: ["plugged_in_charging", "on", "auto", "on", null],
]

EV_EXPECTED.each { String name, List want ->
    check("EV charger: ${name}") {
        Map resp = evStates[name]
        Map charger = resp.devicesByType.EV_CHARGER[0]
        Map v = evs(app.evEvents("ev", charger.reporterId as String, null, resp))
        assert [v.chargingState, v.switch, v.chargeMode, v.solarCharging, v.power] == want
        assert v.sessionEnergy == (charger.sessionEnergy as BigDecimal) / 1000
        assert v.sessionDuration == Math.round((charger.sessionDuration as double) / 60)
        assert v.vehicle == charger.applianceData?.alias
        assert !v.keySet().any { it.toLowerCase().contains("discharg") }
    }
}

check("EV charger: synthetic site, charger's own power over the site power flow; history total") {
    app.state.clear()
    Map resp = fx("getSiteDevices")
    String rid = resp.devicesByType.EV_CHARGER[0].reporterId as String
    Map v = evs(app.evEvents("ev", rid, fx("getSitePowerflow"), resp, fx("getSiteDeviceApplianceSessionsHistory")))
    assert v.power == 9509 && v.switch == "on" && v.chargingState == "plugged_in_charging"
    assert v.energyCharged == 127.456 && v.energy == 127.456
    assert v.sessionEnergy == 0.093 && v.sessionDuration == 1 && v.chargeMode == "manual" && v.solarCharging == "off" && v.vehicle == "Ioniq"
}

check("EV charger: power flow only, while the devices list failed") {
    Map v = evs(app.evEvents("ev", "1", [evCharger: [connectionStatus: "disconnected", currentPower: null]], null))
    assert v.power == 0 && v.switch == "off" && v.chargingState == "plugged_out"
}

// ---------------------------------------------------------------- load controller

check("load controllers: KEBA site (rated power x level)") {
    Map resp = evStates.keba_charging
    Map want = ["Wärme-Pumpe": ["on", "on", 1500], Entfeuchter: ["auto", "off", 0]]
    app.allSiteDevices(resp).findAll { it.type == "ON_OFF" }.each { d ->
        Map v = evs(app.hlcEvents(d.reporterId as String, resp))
        assert [v.controllerState, v.switch, v.power] == want[d.name], d.name
        assert v.powerSource == "estimated"
    }
}

check("load controller: synthetic site; missing one gives no events") {
    Map v = evs(app.hlcEvents("102", fx("getSiteDevices")))
    assert v.controllerState == "on" && v.switch == "on" && v.power == 2000
    assert app.hlcEvents("999", fx("getSiteDevices")) == []
    assert app.hlcEvents("102", null) == []
}

check("commands: activationState bodies") {
    assert app.activationBody("ev", "on", []) == [mode: "MANUAL", level: 100, duration: null]
    assert app.activationBody("ev", "off", []) == [mode: "MANUAL", level: 0, duration: null]
    assert app.activationBody("ev", "setAutomatic", []) == [mode: "AUTO"]
    assert app.activationBody("hlc", "setState", ["auto"]) == [mode: "AUTO", level: null, duration: null]
    assert app.activationBody("hlc", "on", []) == [mode: "MANUAL", level: 100]
    assert app.activationBody("hlc", "setState", ["bogus"]) == null
    assert app.activationBody("inverter", "on", []) == null
}

// ---------------------------------------------------------------- discovery

check("discovery offers: synthetic site") {
    Map offers = app.offersFor("9999999", "Synthetic site", fx("getSitePowerflow"), fx("getSiteDevices"), fx("getSiteEquipment"))
    assert offers.keySet() == ["solaredge-9999999-inverter", "solaredge-9999999-meter", "solaredge-9999999-battery",
        "solaredge-9999999-ev-1000001479067354", "solaredge-9999999-hlc-102"] as Set
    assert offers["solaredge-9999999-inverter"].serials == "7E000000-00"
    assert offers["solaredge-9999999-hlc-102"].label == "Boiler"
}

check("discovery offers: inverter at night, KEBA charger and its load controllers") {
    Map offers = app.offersFor("1", null, [solarProduction: [isActive: false], grid: [isActive: false]], evStates.keba_charging, null)
    assert offers.values()*.type.sort() == ["ev", "hlc", "hlc", "inverter"]
    assert offers["solaredge-1-inverter"].label == "Site 1 Inverter"
}

check("getSites answer as map or list") {
    assert app.sitesOf([sites: ["1": [id: 1]]])*.id == [1]
    assert app.sitesOf([sites: [[id: 2]]])*.id == [2]
    assert app.sitesOf(fx("getSites"))*.id == [9999999]
}

check("stale answers: power flow not real time, devices list not updated lately") {
    long t = captured
    assert app.staleReason("powerflow", [isRealTime: true, isCommunicating: true], t) == null
    assert app.staleReason("powerflow", [isRealTime: false], t) == "isRealTime false"
    assert app.staleReason("powerflow", [isRealTime: false, isCommunicating: false], t) == "isRealTime false, isCommunicating false"
    assert app.staleReason("siteDevices", [isUpdated: true, fieldLastUpdateTS: t - 9 * 60000L], t) == null
    assert app.staleReason("siteDevices", [isUpdated: true, fieldLastUpdateTS: t - 33 * 60000L], t) == "last updated 33 min ago"
    assert app.staleReason("siteDevices", [isUpdated: false], t) == "isUpdated false"
    assert app.staleReason("batteries", [isUpdated: false], t) == null
    assert app.staleReason("powerflow", null, t) == null
}

check("raw data: vehicle and network identifiers hidden, the rest kept") {
    Map resp = fx("getSiteDevices")
    Map r = app.redact(resp)
    Map ev = r.devicesByType.EV_CHARGER[0]
    assert ev.macAddress == "(hidden)" && ev.applianceData.physicalId == "(hidden)" && ev.applianceData.id == "(hidden)"
    assert ev.applianceData.siteApplianceUUID == "(hidden)" && r.rfIdCardsListSupported == "(hidden)"
    assert ev.reporterId == 1000001479067354 && ev.applianceData.alias == "Ioniq" && ev.sessionEnergy == 93
    assert resp.devicesByType.EV_CHARGER[0].applianceData.physicalId == "XXX-0000", "the answer itself is untouched"
}

check("devices list: every poll with a load controller or a charging EV, else on energy ticks") {
    Closure ev = { String st -> [type: "ev", chargingState: st] }
    assert app.devicesDue([[type: "hlc"]], false)
    assert app.devicesDue([ev("plugged_in_charging")], false)
    assert app.devicesDue([ev(null)], false), "state not known yet"
    assert !app.devicesDue([ev("plugged_out")], false)
    assert !app.devicesDue([ev("plugged_in")], false)
    assert app.devicesDue([ev("plugged_out")], true)
    assert !app.devicesDue([[type: "inverter"], [type: "battery"]], true)
    assert app.devicesDue([ev("plugged_out"), ev("plugged_in_charging")], false)
}

check("ISO times") {
    assert app.parseIso("2026-10-15T11:50:28Z") == Date.parse("yyyy-MM-dd'T'HH:mm:ssX", "2026-10-15T11:50:28Z").time
    assert app.parseIso("2026-10-15T11:50:28.123Z") == app.parseIso("2026-10-15T11:50:28Z")
    assert app.parseIso("2026-10-15T13:50:28+02:00") == app.parseIso("2026-10-15T11:50:28Z")
    assert app.parseIso("2024-03-01") != null
    assert app.parseIso("junk") == null && app.parseIso(null) == null
    assert app.parseIso(1709287200000L) == 1709287200000L
}

Check.finish()
