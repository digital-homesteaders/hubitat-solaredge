/**
 * SolarEdge Load Controller: a SolarEdge smart relay or switch (home load controller), created by the SolarEdge Connect app.
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
    definition(name: "SolarEdge Load Controller", namespace: "solaredge", author: "Digital Homesteaders", importUrl: "") {
        capability "Actuator"
        capability "Switch"
        capability "PowerMeter"
        capability "Refresh"

        attribute "controllerState", "enum", ["on", "off", "auto"]
        attribute "powerSource", "enum", ["measured", "estimated"]

        command "setAutomatic"
        command "setState", [[name: "State*", type: "ENUM", description: "On, off, or SolarEdge's automatic control", constraints: ["on", "off", "auto"]]]
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

def on() { parent?.childCommand(device.deviceNetworkId, "on") }
def off() { parent?.childCommand(device.deviceNetworkId, "off") }
/** Back to SolarEdge's own control (schedules, solar surplus). */
def setAutomatic() { parent?.childCommand(device.deviceNetworkId, "setAutomatic") }
def setState(String value) { parent?.childCommand(device.deviceNetworkId, "setState", [value]) }

// ---------------------------------------------------------------- events from the app

/** Send the events whose value changed. */
void applyEvents(List events) {
    events.each { Map e ->
        if (e.value == null || same(device.currentValue(e.name as String), e.value)) return
        Map evt = new LinkedHashMap(e)
        evt.descriptionText = "${device.displayName} ${e.name} is ${e.value}${e.unit ? ' ' + e.unit : ''}"
        if (txtEnable) log.info evt.descriptionText
        if (logEnable) log.debug "${device.displayName}: ${e.name} ${device.currentValue(e.name as String)} -> ${e.value}"
        sendEvent(evt)
    }
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
