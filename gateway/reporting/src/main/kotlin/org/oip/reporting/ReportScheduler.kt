package org.oip.reporting

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Runs scheduled reports. Every [tickMillis] it reads the stored definitions; a report whose definition (version)
 * changed gets its next fire time recomputed from now. A due report runs as [ReportCaller.SYSTEM] with the
 * schedule's parameters, through [ReportService.run], so it produces the same result (and the same run-history
 * record, trigger `schedule`) as a manual run; the result goes to [sink].
 *
 * Fire times missed while the gateway was stopped are not caught up: after a start the next fire is computed from
 * the start time. A report still running when it comes due again is skipped for that fire.
 * Runs execute on [runner] (two daemon threads by default) so a slow report does not delay the others' ticks.
 */
class ReportScheduler(
    private val service: ReportService,
    private val clock: Clock = Clock.systemUTC(),
    private val sink: ReportResultSink = ReportResultSink.NONE,
    private val tickMillis: Long = 1000,
    runner: Executor? = null,
) : AutoCloseable {
    private val log: System.Logger = System.getLogger(ReportScheduler::class.java.name)
    private data class Entry(val version: Long, val cron: CronExpression, val zone: ZoneId, val next: Long?)

    private val entries = HashMap<String, Entry>()
    private val running: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val ownedRunner: ExecutorService? = if (runner == null) Executors.newFixedThreadPool(2) { r ->
        Thread(r, "report-run").apply { isDaemon = true }
    } else null
    private val runner: Executor = runner ?: ownedRunner!!
    private var ticker: ScheduledExecutorService? = null
    private var stopped = false

    /** Start and stop use their own lock: [tick] holds the instance monitor, and stop waits for a tick to finish. */
    private val lifecycle = Any()

    fun start(): ReportScheduler = synchronized(lifecycle) {
        check(!stopped) { "a stopped ReportScheduler cannot be restarted" }
        if (ticker != null) return this
        ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "report-scheduler").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({
                try {
                    tick()
                } catch (e: Exception) {
                    log.log(System.Logger.Level.WARNING, "report scheduler tick failed: $e")
                }
            }, 0, tickMillis, TimeUnit.MILLISECONDS)
        }
        return this
    }

    /** Stop ticking and wait (up to 10 s) for runs in progress. Idempotent. */
    fun stop(): Unit = synchronized(lifecycle) {
        stopped = true
        ticker?.let {
            it.shutdown()
            if (!it.awaitTermination(5, TimeUnit.SECONDS)) it.shutdownNow()
        }
        ticker = null
        ownedRunner?.let {
            it.shutdown()
            if (!it.awaitTermination(10, TimeUnit.SECONDS)) it.shutdownNow()
        }
    }

    override fun close() = stop()

    /** Next fire time per scheduled report (epoch millis), as the scheduler currently has it. */
    @Synchronized
    fun nextFires(): Map<String, Long?> = entries.mapValues { it.value.next }

    /** One scheduler pass at [now]: refresh definitions, start the due runs. Tests call this directly. */
    @Synchronized
    fun tick(now: Long = clock.millis()) {
        val defs = service.definitions().filter { it.enabled && it.schedule?.enabled == true }
        val live = defs.map { it.name }.toSet()
        entries.keys.retainAll(live)
        for (def in defs) {
            val schedule = def.schedule!!
            var entry = entries[def.name]
            if (entry == null || entry.version != def.version) {
                val cron = CronExpression.parse(schedule.cron)
                val zone = ZoneId.of(schedule.timezone)
                entry = Entry(def.version, cron, zone, cron.next(Instant.ofEpochMilli(now), zone)?.toEpochMilli())
                entries[def.name] = entry
            }
            val due = entry.next ?: continue
            if (now < due) continue
            entries[def.name] = entry.copy(next = entry.cron.next(Instant.ofEpochMilli(now), entry.zone)?.toEpochMilli())
            if (!running.add(def.name)) {
                log.log(System.Logger.Level.WARNING, "report ${def.name} is still running; skipping this fire")
                continue
            }
            try {
                runner.execute { runScheduled(def.name, schedule.parameters) }
            } catch (e: Exception) {
                running.remove(def.name)
                log.log(System.Logger.Level.WARNING, "report ${def.name} could not be started: $e")
            }
        }
    }

    private fun runScheduled(name: String, parameters: Map<String, Any?>) {
        try {
            val result = service.run(name, parameters, ReportCaller.SYSTEM, RunTrigger.SCHEDULE)
            // Delivery hook: rendering (C-9b) and delivery plug in here.
            try {
                sink.deliver(service.definition(name), result)
            } catch (e: Exception) {
                log.log(System.Logger.Level.WARNING, "report $name result delivery failed: $e")
            }
        } catch (e: Exception) {
            // ReportService.run has already recorded a data-source failure in run history.
            log.log(System.Logger.Level.WARNING, "scheduled report $name failed: ${e.message}")
        } finally {
            running.remove(name)
        }
    }
}
