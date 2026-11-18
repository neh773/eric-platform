package org.oip.gateway

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Test clock. Advances are visible to gateway threads that read it after the advance returns. */
class MutableClock(start: Instant = Instant.parse("2026-06-01T00:00:00Z")) : Clock() {
    private val lock = Any()
    private var now = start
    fun advance(d: Duration) = synchronized(lock) { now = now.plus(d) }
    override fun millis(): Long = synchronized(lock) { now.toEpochMilli() }
    override fun instant(): Instant = Instant.ofEpochMilli(millis())
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}
