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

// ---------------------------------------------------------------- battery

check("battery: synthetic site (Wh energies)") {
    app.state.clear()
    Map v = evs(app.batteryEvents("b1", fx("getSitePowerflow"), fx("getSiteBatteries"), fx("getSiteCommunication")))
    assert v.power == 500 && v.battery == 62 && v.chargingState == "charging"
    assert v.energyCharged == 1234.567 && v.energyDischarged == 1000 && v.energy == 1000
    assert v.residualEnergy == 6.2 && v.stateOfHealth == 95
}

check("battery: kWh energies when the nameplate is below 100; discharging is negative") {
    app.state.clear()
    Map bat = [chargeEnergy: 1234.5, dischargeEnergy: 1000, remainingEnergy: 6.2, fullPackEnergy: 9.2, namePlateEnergy: 9.7]
    Map v = evs(app.batteryEvents("b2", [storage: [currentPower: 1.25, status: "DISCHARGING", chargeLevel: 40]], [devicesByType: [BATTERY: [bat]]]))
    assert v.energyCharged == 1234.5 && v.residualEnergy == 6.2 && v.stateOfHealth == 95
    assert v.power == -1250 && v.chargingState == "discharging"
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

check("ISO times") {
    assert app.parseIso("2026-10-15T11:50:28Z") == Date.parse("yyyy-MM-dd'T'HH:mm:ssX", "2026-10-15T11:50:28Z").time
    assert app.parseIso("2026-10-15T11:50:28.123Z") == app.parseIso("2026-10-15T11:50:28Z")
    assert app.parseIso("2026-10-15T13:50:28+02:00") == app.parseIso("2026-10-15T11:50:28Z")
    assert app.parseIso("2024-03-01") != null
    assert app.parseIso("junk") == null && app.parseIso(null) == null
}

Check.finish()
