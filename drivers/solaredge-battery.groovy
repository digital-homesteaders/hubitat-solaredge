/**
 * SolarEdge Battery: a site's batteries, created by the SolarEdge Connect app.
 *
 * The app polls SolarEdge for every device of the account at once and hands
 * this device its events; only values that changed are sent.
 *
 * Ported from com.solaredge v2.0.0 for Homey by Robin de Gruijter
 * (https://github.com/gruijter/com.solaredge).
 * Copyright (C) 2026 Robin de Gruijter (original), 2026 Digital Homesteaders (Hubitat port)
 * GNU General Public License v3.0 or later; see LICENSE.
 */

import groovy.transform.Field

// This run's new values. On a hub, device.currentValue() right after sendEvent can
// still return the cached previous value, so the summary reads these first. A driver
// script is a new instance per run, so this doesn't outlive it.
@Field Map freshValues = [:]

metadata {
    definition(name: "SolarEdge Battery", namespace: "solaredge", author: "Digital Homesteaders", importUrl: "") {
        capability "Sensor"
        capability "Battery"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "Refresh"

        attribute "chargingState", "enum", ["charging", "discharging", "idle"]
        attribute "energyCharged", "number"
        attribute "energyDischarged", "number"
        attribute "residualEnergy", "number"
        attribute "stateOfHealth", "number"
        attribute "siteConnectivity", "enum", ["online", "offline"]
        attribute "summary", "string"
        attribute "healthStatus", "enum", ["online", "offline"]
    }
    preferences {
        input "txtEnable", "bool", title: "Description text logging (state changes; power and energy readings only in debug logging)", defaultValue: true
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
    freshValues = events.findAll { it.value != null }.collectEntries { [(it.name): it.value] }
    events.each { Map e ->
        if (e.value == null || same(device.currentValue(e.name as String), e.value)) return
        Map evt = new LinkedHashMap(e)
        evt.descriptionText = "${device.displayName} ${e.name} is ${e.value}${e.unit ? ' ' + e.unit : ''}"
        if (txtEnable && !quiet(e.name as String)) log.info evt.descriptionText
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

/**
 * Values that change on almost every poll are not info-logged (debug logging
 * still shows them); state changes are. Their events keep a description.
 */
private boolean quiet(String name) {
    name in ["power", "energy", "energyToday", "energyMonth", "energyYear", "energyLifetime", "energyCharged",
        "energyDischarged", "energyImported", "energyExported", "energyImportedYear", "energyExportedYear",
        "residualEnergy", "sessionEnergy", "sessionDuration", "gridPower", "solarPower", "loadPower", "batteryPower",
        "voltage", "voltageL1", "voltageL2", "voltageL3", "optimizerTemperatureMax"]
}

// ---------------------------------------------------------------- summary

private def cv(String attr) { freshValues.containsKey(attr) ? freshValues[attr] : device.currentValue(attr) }

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

/** E.g. "Discharging 10.0 kW · 35 % · 7.2 kWh left · charged 0.0 kWh · discharged 9.8 kWh · health 100 %". */
private String summaryText() {
    String st = cv("chargingState")
    BigDecimal p = num(cv("power"))
    String head = st == "charging" ? "Charging ${fmtPower(p) ?: ''}".trim() : st == "discharging" ? "Discharging ${fmtPower(p) ?: ''}".trim() : st == "idle" ? "Idle" : null
    joinParts([
        head,
        cv("battery") != null ? "${cv('battery')} %" : null,
        cv("residualEnergy") != null ? "${fmtEnergy(cv('residualEnergy'))} left" : null,
        cv("energyCharged") != null ? "charged ${fmtEnergy(cv('energyCharged'))}" : null,
        cv("energyDischarged") != null ? "discharged ${fmtEnergy(cv('energyDischarged'))}" : null,
        cv("stateOfHealth") != null ? "health ${cv('stateOfHealth')} %" : null,
        cv("siteConnectivity") == "offline" ? "site offline" : null,
    ])
}
