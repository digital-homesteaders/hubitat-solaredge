/**
 * SolarEdge Inverter: a site's solar production, created by the SolarEdge Connect app.
 *
 * The app polls SolarEdge for every device of the account at once and hands
 * this device its events; only values that changed are sent.
 *
 * Ported from com.solaredge v2.0.0 for Homey by Robin de Gruijter
 * (https://github.com/gruijter/com.solaredge).
 * Copyright (C) 2026 Robin de Gruijter (original), 2026 Digital Homesteaders (Hubitat port)
 * GNU General Public License v3.0 or later; see LICENSE.
 */

metadata {
    definition(name: "SolarEdge Inverter", namespace: "solaredge", author: "Digital Homesteaders", importUrl: "") {
        capability "Sensor"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "VoltageMeasurement"
        capability "Refresh"

        attribute "energyToday", "number"
        attribute "energyMonth", "number"
        attribute "energyYear", "number"
        attribute "energyLifetime", "number"
        attribute "voltageL1", "number"
        attribute "voltageL2", "number"
        attribute "voltageL3", "number"
        attribute "inverterStatus", "enum", ["production", "sleeping", "starting", "throttled", "shutting_down", "fault", "standby", "off", "unknown"]
        attribute "optimizerTemperatureMax", "number"
        attribute "alerts", "number"
        attribute "siteConnectivity", "enum", ["online", "offline"]
        attribute "summary", "string"
        attribute "healthStatus", "enum", ["online", "offline"]
    }
    preferences {
        input "txtEnable", "bool", title: "Description text logging", defaultValue: true
        input "logEnable", "bool", title: "Debug logging (turns itself off after 30 minutes)", defaultValue: false
    }
}

// ---------------------------------------------------------------- lifecycle

def installed() {
    refresh()
}

def updated() {
    if (logEnable) runIn(1800, "logsOff")
}

def logsOff() {
    log.warn "${device.displayName}: debug logging turned off"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

def refresh() {
    parent?.refreshNow()
}

// ---------------------------------------------------------------- events from the app

/** Send the events whose value changed, then the summary line with units. */
void applyEvents(List events) {
    events.each { Map e ->
        if (e.value == null || same(device.currentValue(e.name as String), e.value)) return
        Map evt = new LinkedHashMap(e)
        evt.descriptionText = "${device.displayName} ${e.name} is ${e.value}${e.unit ? ' ' + e.unit : ''}"
        if (txtEnable) log.info evt.descriptionText
        if (logEnable) log.debug "${device.displayName}: ${e.name} ${device.currentValue(e.name as String)} -> ${e.value}"
        sendEvent(evt)
    }
    sendSummary()
}

private boolean same(def current, def value) {
    if (current == null) return false
    if (current instanceof Number && value instanceof Number) return (current as BigDecimal).compareTo(value as BigDecimal) == 0
    try {
        if (value instanceof Number) return (current.toString() as BigDecimal).compareTo(value as BigDecimal) == 0
    } catch (e) {
        return false
    }
    return current.toString() == value.toString()
}

// ---------------------------------------------------------------- summary

private def cv(String attr) { device.currentValue(attr) }

private BigDecimal num(def v) {
    if (v == null) return null
    try { return v as BigDecimal } catch (e) { return null }
}

/** "850 W" or "10.0 kW", without a sign. */
private String fmtPower(def w) {
    BigDecimal v = num(w)?.abs()
    if (v == null) return null
    return v >= 1000 ? "${fmt(v / 1000, 1)} kW" : "${fmt(v, 0)} W"
}

/** "9.8 kWh", or "4,346 kWh" from 100 up. */
private String fmtEnergy(def kwh) {
    BigDecimal v = num(kwh)
    if (v == null) return null
    return "${fmt(v, v.abs() >= 100 ? 0 : 1)} kWh"
}

private String fmt(BigDecimal v, int decimals) { String.format("%,." + decimals + "f", v) }

private String joinParts(List parts) { parts.findAll { it }.join(" · ") }

private void sendSummary() {
    String s = summaryText()
    if (s && !same(cv("summary"), s)) sendEvent(name: "summary", value: s)
}

/** E.g. "Producing 5.6 kW · today 7.1 kWh · month 177 kWh · year 4,346 kWh · lifetime 29,339 kWh · 234.8 V". */
private String summaryText() {
    BigDecimal p = num(cv("power"))
    String status = cv("inverterStatus")
    String head = status == "sleeping" ? "Sleeping" : (p ? "Producing ${fmtPower(p)}" : (p == 0 ? "Not producing" : null))
    BigDecimal alerts = num(cv("alerts"))
    BigDecimal temp = num(cv("optimizerTemperatureMax"))
    joinParts([
        head,
        cv("energyToday") != null ? "today ${fmtEnergy(cv('energyToday'))}" : null,
        cv("energyMonth") != null ? "month ${fmtEnergy(cv('energyMonth'))}" : null,
        cv("energyYear") != null ? "year ${fmtEnergy(cv('energyYear'))}" : null,
        cv("energyLifetime") != null ? "lifetime ${fmtEnergy(cv('energyLifetime'))}" : null,
        num(cv("voltage")) != null ? "${fmt(num(cv('voltage')), 1)} V" : null,
        temp != null ? "optimizers up to ${fmt(temp, 0)} °${location?.temperatureScale ?: 'C'}" : null,
        status && !(status in ["production", "sleeping"]) ? "status ${status.replace('_', ' ')}" : null,
        alerts ? "${alerts} alert${alerts == 1 ? '' : 's'}" : null,
        cv("siteConnectivity") == "offline" ? "site offline" : null,
    ])
}
