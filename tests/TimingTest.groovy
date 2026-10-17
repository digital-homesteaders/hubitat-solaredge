/**
 * The energy tick (ported from com.solaredge lib/solarEdgeTiming.js and its
 * test/timing.js): placed a margin after the inverters' upload moment, the
 * margin following whether a tick found new data, sleeping inverters and
 * 15-minute sites left out.
 */

import groovy.transform.Field
import static Check.check

@Field File root = new File(getClass().protectionDomain.codeSource.location.path).parentFile.parentFile
@Field Hub hub = new Hub(root)
@Field def app = hub.app()

long PERIOD = 5 * 60000L
long MARGIN = 20000L
long at28 = Date.parse("yyyy-MM-dd'T'HH:mm:ssX", "2026-10-15T12:40:28Z").time

check("tickPhase, adaptMargin, delayToNextTick") {
    assert app.tickPhase([at28]) == 28000 + MARGIN
    assert app.tickPhase([]) == 0
    assert app.tickPhase([at28, at28 + 150000L]) == 0, "inverters far apart"
    assert app.tickPhase([at28, at28 + 10000L]) == 38000 + MARGIN
    assert app.adaptMargin(MARGIN, false) == MARGIN + 15000
    assert app.adaptMargin(MARGIN + 15000, true) == MARGIN + 10000
    assert app.adaptMargin(120000L, false) == 120000L
    long now = Date.parse("yyyy-MM-dd'T'HH:mm:ssX", "2026-10-15T12:41:00Z").time
    assert app.delayToNextTick(now, 48000L) == (5 * 60 + 48 - 60) * 1000L
    assert app.delayToNextTick(now, 60500L) == 300000L + 500, "at least a second away"
    assert app.learnCadence(null, 3 * PERIOD) == 3 * PERIOD
    assert app.learnCadence(3 * PERIOD, 60000L) == PERIOD
}

/** Fresh timing state, then the app's noteSnapshot at time `t`. */
void snap(String serial, long at, long t) {
    hub.clock = t
    app.noteSnapshot(serial, at, t)
    hub.settle()
}
Map tm() { app.state.timing }

check("the energy tick follows the inverter and its margin adapts") {
    app.state.timing = [phaseMs: 0, marginMs: MARGIN, snapshots: [:], judgePending: false]
    snap("INV1", at28, at28 + 32000)
    assert tm().phaseMs == 28000 + MARGIN
    assert app.callsNamed("runInMillis").last()[2] == "energyTick"
    assert app.callsNamed("runInMillis").last()[1] == app.delayToNextTick(at28 + 32000, 28000 + MARGIN)

    // Right after the energy tick the same upload says nothing: the next one isn't due.
    app.state.timing.judgePending = true
    snap("INV1", at28, at28 + 50000)
    assert tm().marginMs == MARGIN

    // A period later it found the same upload: too early, the margin grows.
    snap("INV1", at28, at28 + PERIOD + MARGIN)
    assert tm().marginMs == MARGIN + 15000 && tm().phaseMs == 28000 + MARGIN + 15000

    // The next tick finds a new one: the margin eases back.
    app.state.timing.judgePending = true
    snap("INV1", at28 + PERIOD, at28 + PERIOD + 40000)
    assert tm().marginMs == MARGIN + 10000

    // A sleeping inverter (old upload) does not judge the tick.
    app.state.timing.judgePending = true
    snap("INV1", at28 + PERIOD, at28 + 10 * PERIOD)
    assert tm().marginMs == MARGIN + 10000 && tm().judgePending
}

check("a site that uploads every 15 minutes: ticks between uploads don't judge") {
    app.state.timing = [phaseMs: 0, marginMs: MARGIN, snapshots: [:], judgePending: false]
    snap("INV2", at28, at28 + MARGIN)
    app.state.timing.judgePending = true
    snap("INV2", at28 + 3 * PERIOD, at28 + 3 * PERIOD + MARGIN)
    assert tm().cadenceMs == 3 * PERIOD
    [4, 5].each { int n ->
        app.state.timing.judgePending = true
        snap("INV2", at28 + 3 * PERIOD, at28 + n * PERIOD + MARGIN)
    }
    assert tm().marginMs == MARGIN
    // The upload that was due is late: that does judge.
    app.state.timing.judgePending = true
    snap("INV2", at28 + 3 * PERIOD, at28 + 6 * PERIOD + MARGIN)
    assert tm().marginMs == MARGIN + 15000
}

check("the energy tick marks itself for judging and schedules the next") {
    app.state.timing = [phaseMs: 48000, marginMs: MARGIN, snapshots: [:], judgePending: false]
    hub.clock = at28
    int before = app.callsNamed("runInMillis").size()
    app.energyTick()
    assert app.state.timing.judgePending == true
    assert app.callsNamed("runInMillis").size() == before + 1
}

Check.finish()
