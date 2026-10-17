# SolarEdge Connect for Hubitat

Bring a SolarEdge site into a Hubitat Elevation hub: solar production, grid
meter and the site's power split, batteries, SolarEdge EV chargers and home
load controllers (smart relays).

> **Status: v0.1, not yet tested on a hub.** It is a port of the
> [com.solaredge](https://github.com/gruijter/com.solaredge) v2.0.0 rewrite
> for Homey, and the mapping is tested locally against that project's captured
> SolarEdge answers. The Hubitat code itself has not run on a hub yet. See
> [What needs testing](#what-needs-testing).

## How it works

SolarEdge Connect logs in to **monitoring.solaredge.com** with your mySolarEdge
e-mail and password, the same way the mySolarEdge phone app does. This is
SolarEdge's private app API, not the public Monitoring API, so you don't need
an API key. It also means SolarEdge can change it without notice.

- **SolarEdge Connect** (app) holds the account, one login session and one
  poll timer. It finds the sites and devices on the account and creates one
  Hubitat device for each one you pick. All SolarEdge mapping lives in the
  app.
- **SolarEdge Inverter**, **Meter**, **Battery**, **EV Charger** and
  **Load Controller** devices are thin drivers. They show what the app hands
  them and send commands back through it.

### Few requests

Every device on a site is fed from the same answers:

| When | Requests per site |
| --- | --- |
| Every poll (default 60 s, 30–300 s) | 1 power flow, for all devices on the site. If you have EV chargers or load controllers, also 1 devices list for all of them. |
| Energy tick, every 5 min, just after the inverters upload | energy overview, inverter details (all inverters in one call), batteries, this year's meter totals |
| Every third energy tick (15 min) | site alerts and connectivity, optimizer temperatures, EV charging history |
| Once | the meter's past years, one per poll, then kept |

- Identical requests in one tick go out once, and ticks never overlap: a tick
  is skipped while the previous one still waits for answers.
- An inverter-only site skips the 60-second polls at night and is read only
  on the 5-minute energy tick until the sun is back.
- A site with an inverter, meter and battery makes **2,880 requests a day**
  at the 60 s default, measured over a simulated day in the tests (4,416 with
  an EV charger and a load controller as well). For comparison, Homey 1.8.1
  sends about 86,000 for the same three devices.
- Hubitat's asynchronous HTTP client manages its own connection pool, so
  reusing connections is up to the hub. This app keeps one login session
  for every device on the account.

### Changes from com.solaredge 2.0.0

These come from a review of the Homey app (1.8.1, and the 2.0.0 rewrite):

1. **Poll interval:** live power every 60 s by default (30–300 s), not 10 s.
   Energy, EV charging history and inverter details are only read on the
   5-minute energy tick or less often.
2. **Requests:** asynchronous HTTP with a 20 s timeout. No overlapping polls.
   Identical requests in a tick are merged, so the power flow is fetched once
   per site.
3. **Outages:** after an outage or an HTTP 429 the app pauses polling for
   twice the poll interval, doubling up to 30 minutes. A rejected login is
   kept apart from a SolarEdge or network error, and a login that SolarEdge
   redirects back to its login page counts as a rejection. After a
   rejection, logins are held back for 1 minute, doubling up to 1 hour. On a
   401 or a redirect to the login page the app logs in again once and
   retries. If the new session is turned away as well, logins are held back
   the same way, so a broken session can't cause a login on every poll.
4. **Night:** the inverter never goes offline at night or while idle. It
   shows 0 W and `inverterStatus` becomes `sleeping`. While the inverters
   sleep, no device reports the site offline. Devices show
   `healthStatus: offline` only after 3 failed polls in a row.
5. **Meter yearly totals:** the walk back through past years stops at a
   hard floor: the install year, or 10 years back if SolarEdge doesn't give
   one. "No data" ends the walk. Any other error aborts the run without
   storing anything, and it retries after 15 minutes. Finished years are
   kept, and only the current year is refreshed, on the energy tick. A
   missing year, such as last year when the hub was off over New Year, is
   fetched later, and the lifetime totals wait for it.
6. **Credentials:** the e-mail and password live only in the app's settings
   and are never written to a log, including the debug log. Devices don't
   get a copy.
7. **Events:** a device only sends an event when the value changes. Debug
   logging turns itself off after 30 minutes. State holds no API answers,
   only small bookkeeping such as the past meter years and the
   energy-tick timing.
8. **EV charger:** no "discharged" counter (it was always 0).

## Install

### Hubitat Package Manager

Not published yet. Once the repository is on GitHub, replace the
`GITHUB_OWNER` placeholders in `packageManifest.json`.

### Manually

1. **Drivers Code → New Driver**: paste and save each file in `drivers/`:
   `solaredge-inverter.groovy`, `solaredge-meter.groovy`,
   `solaredge-battery.groovy`, `solaredge-ev-charger.groovy`,
   `solaredge-load-controller.groovy`.
2. **Apps Code → New App**: paste and save `apps/solaredge-connect.groovy`.
3. **Apps → Add User App → SolarEdge Connect**. Enter your mySolarEdge e-mail
   and password and press **Done** once, so the app saves them.
4. Open the app again, open **Choose devices**, tick the devices you want,
   then press **Done**.

For several SolarEdge accounts, add the app once per account.

## Devices

All devices have `healthStatus` (online/offline) and Refresh. Refresh triggers
one extra poll of the whole account. Power is in W and energy in kWh.

### SolarEdge Inverter

| Hubitat | SolarEdge |
| --- | --- |
| PowerMeter `power` | power flow `solarProduction.currentPower`; 0 when not producing |
| EnergyMeter `energy`, `energyLifetime` | energy overview, lifetime |
| `energyToday`, `energyMonth`, `energyYear` | energy overview, this day, month and year |
| VoltageMeasurement `voltage`; `voltageL1`–`voltageL3` | phase voltages, averaged over the site's inverters |
| `inverterStatus` | production, sleeping, starting, throttled, shutting_down, fault, standby, off |
| `optimizerTemperatureMax` | hottest optimizer today, in the hub's scale |
| `alerts` | number of open site alerts |
| `siteConnectivity` | online/offline. Offline is not reported while the inverter sleeps. |

### SolarEdge Meter

| Hubitat | SolarEdge |
| --- | --- |
| PowerMeter `power`, `gridPower` | grid: import positive, export negative |
| `gridStatus` | import, export, idle |
| `solarPower`, `loadPower`, `batteryPower` | the site's power split: production, consumption, battery (charging positive) |
| EnergyMeter `energy`, `energyImported`; `energyExported` | lifetime grid import and export, the sum of every year since install. They appear once all past years are in. |
| `energyImportedYear`, `energyExportedYear` | this year |
| `siteConnectivity` | online/offline. Offline is not reported while the inverters sleep. |

### SolarEdge Battery

| Hubitat | SolarEdge |
| --- | --- |
| Battery `battery` | charge level % |
| PowerMeter `power` | charging positive, discharging negative |
| `chargingState` | charging, discharging, idle |
| `energyCharged`, `energyDischarged` (EnergyMeter `energy` = discharged) | battery counters, kept rising if SolarEdge resets them |
| `residualEnergy`, `stateOfHealth` | stored kWh; full-pack capacity as % of the nameplate |
| `siteConnectivity` | online/offline. Offline is not reported while the inverters sleep. |

The battery fields come from 2.0.0, which got them from another project
(nerd2/solaredgego). Real battery answers haven't been seen yet.

### SolarEdge EV Charger

| Hubitat | SolarEdge |
| --- | --- |
| Switch | on: start charging now. off: stop. Both put the charger in manual mode. The value shows whether it is charging. |
| `setAutomatic()` | back to the charger's own schedule and solar surplus charging |
| PowerMeter `power` | the charger's own reading, else the site power flow |
| EnergyMeter `energy`, `energyCharged` | total of the charging sessions over the past year, kept rising |
| `chargingState` | plugged_in, plugged_in_charging, plugged_out |
| `sessionEnergy`, `sessionDuration` (min), `chargeMode`, `solarCharging`, `vehicle` | current or last session |

### SolarEdge Load Controller

| Hubitat | SolarEdge |
| --- | --- |
| Switch | on/off in manual mode. The value follows the relay. |
| `controllerState`, `setState(on/off/auto)`, `setAutomatic()` | manual on, manual off, or SolarEdge's automatic control |
| PowerMeter `power`, `powerSource` | the relay's meter, else rated power × level (`estimated`) |

## Limitations

- Needs SolarEdge's cloud. There is no local (Modbus) access.
- Uses the private mySolarEdge app API, which SolarEdge can change at any
  time.
- Not included: battery mode and time-of-use control, per-optimizer data, the
  weather, and environmental benefits.

## What needs testing

### Covered locally (no hub needed)

`tests/run.sh` compiles the app and every driver with Groovy 2.4 (the version
Hubitat runs) against small stand-ins for the hub, then runs:

- **CompileCheck**: every file compiles and its metadata code runs. No method
  shadows a definition word. Every method a driver calls on the app, and that
  the app calls on a device, exists and is public. No log line touches the
  credentials.
- **MappingTest**: the mapping functions against the answers captured by
  com.solaredge's own tests (`tests/fixtures`):
  - the synthetic site and a real inverter (energy, phase voltages,
    optimizer temperature in °C and °F)
  - the night rule and when an inverter counts as asleep
  - meter power split and totals
  - battery in Wh and kWh
  - counters that never fall
  - eight real EV charger states and a KEBA charger
  - two real load controllers
  - commands, discovery offers and ISO times
- **TimingTest**: the 5-minute energy tick follows the inverter uploads, its
  margin adapts, and sleeping inverters and 15-minute sites don't skew it.
  These cases are ported from com.solaredge's `test/timing.js`.
- **FlowTest**: the app and the real drivers end to end, with SolarEdge
  replayed from the synthetic site:
  - discovery (including a restarted one), the shared login, request counts
    per tick, per energy or slow tick and over a simulated day, the meter's
    past years (including a year missed over New Year), and events only on
    change
  - 401 and expired-session retries, a new session turned away at once,
    rejected-login backoff (1 min to 1 h), a login redirected back to the
    login page, and the difference between a login outage and a rejection
  - requests waiting for a login (same URL from two callers, commands kept
    in order), commands answered 204, and the stale-tick limit
  - outage and 429 backoff, and `healthStatus` going offline after 3 failed
    polls
  - an inverter-only site at night, commands, the poll interval range and
    debug auto-off
  - no credentials in any log, state or device

To run them, get a JDK and Groovy 2.4 once, then run the tests. The setup
script downloads them into the git-ignored `.tools/` and installs nothing
system-wide. You can also use `java` and `groovy` from your PATH.

```bash
tests/setup-tools.sh
```

```bash
tests/run.sh
```

The stand-ins only model the hub as far as these files use it, so the hub
itself still needs checking.

### Still needs a hub

1. The app and drivers save in Apps Code and Drivers Code. The local compile
   uses the same Groovy, but not Hubitat's sandbox (`ConcurrentHashMap` in a
   `@Field static`, `SimpleDateFormat`).
2. **Login**: whether `asynchttpPost` honours `followRedirects: false` and
   returns SolarEdge's 302 with all its `Set-Cookie` headers. If it follows
   the redirect, the app accepts a 200 with cookies and no login form. That
   needs checking against the real site, together with the 1-minute backoff
   after a wrong password.
3. Session expiry: that a 401, or a redirect to the login page, leads to one
   new login.
4. **Choose devices** page: the auto-refresh during discovery, and how it
   looks.
5. Real answers from your own site: inverter, meter, and battery if you
   have one. Also `inverterStatus` going to `sleeping` at night with power
   at 0 W and the device staying online.
6. The energy tick moving to just after the inverter upload. Turn on debug
   logging to see "Energy tick moved to …".
7. EV charger and load controller commands. These were never tested live in
   2.0.0 either.

## Credits and licence

GNU General Public License v3.0 or later; see [LICENSE](LICENSE).

A port of [com.solaredge](https://github.com/gruijter/com.solaredge) v2.0.0
for Homey by **Robin de Gruijter**, itself based on the original app by Emile
Nijssen (Athom). The SolarEdge cloud client, the energy-tick timing, the
device mappings and the test fixtures come from that project (GPL-3.0).
Hubitat port and changes © 2026 Digital Homesteaders. Not affiliated with or endorsed
by SolarEdge.
