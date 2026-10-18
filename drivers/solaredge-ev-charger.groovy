/**
 * SolarEdge EV Charger: a SolarEdge EV charger, created by the SolarEdge Connect app.
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
    definition(name: "SolarEdge EV Charger", namespace: "solaredge", author: "Digital Homesteaders", importUrl: "") {
        capability "Actuator"
        capability "Switch"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "Refresh"

        attribute "chargingState", "enum", ["plugged_in", "plugged_in_charging", "plugged_out"]
        attribute "sessionEnergy", "number"
        attribute "sessionDuration", "number"
        attribute "chargeMode", "enum", ["auto", "manual"]
        attribute "solarCharging", "enum", ["on", "off"]
        attribute "vehicle", "string"
        attribute "energyCharged", "number"

        command "setAutomatic"
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

// ---------------------------------------------------------------- commands

/** Start charging now (manual mode). */
def on() { parent?.childCommand(device.deviceNetworkId, "on") }
/** Stop charging (manual mode). */
def off() { parent?.childCommand(device.deviceNetworkId, "off") }
/** Back to the charger's own schedule and solar surplus charging. */
def setAutomatic() { parent?.childCommand(device.deviceNetworkId, "setAutomatic") }

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

/** E.g. "Charging 9.5 kW · session 0.1 kWh, 1 min · manual mode · Ioniq · 127 kWh charged in the past year". */
private String summaryText() {
    String st = cv("chargingState")
    String head = st == "plugged_in_charging" ? "Charging ${fmtPower(cv('power')) ?: ''}".trim() :
        st == "plugged_in" ? "Plugged in, not charging" : st == "plugged_out" ? "Unplugged" : null
    BigDecimal minutes = num(cv("sessionDuration"))
    joinParts([
        head,
        cv("sessionEnergy") != null ? "session ${fmtEnergy(cv('sessionEnergy'))}${minutes != null ? ', ' + fmt(minutes, 0) + ' min' : ''}" : null,
        cv("chargeMode") ? "${cv('chargeMode')} mode" : null,
        cv("solarCharging") == "on" ? "solar charging on" : null,
        cv("vehicle"),
        cv("energyCharged") != null ? "${fmtEnergy(cv('energyCharged'))} charged in the past year" : null,
    ])
}
