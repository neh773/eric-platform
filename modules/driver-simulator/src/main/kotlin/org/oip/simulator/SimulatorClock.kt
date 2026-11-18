package org.oip.simulator

/**
 * Time source for a programmable simulator. Production devices use [SYSTEM]. Tests pass a
 * [ManualSimulatorClock] and advance it, so each program's samples are exact.
 */
fun interface SimulatorClock {
    fun millis(): Long

    companion object {
        val SYSTEM: SimulatorClock = SimulatorClock { System.currentTimeMillis() }
    }
}

/** A clock the caller moves. Reads see the program at [millis]. */
class ManualSimulatorClock(@Volatile var millis: Long = 0) : SimulatorClock {
    override fun millis(): Long = millis
}
