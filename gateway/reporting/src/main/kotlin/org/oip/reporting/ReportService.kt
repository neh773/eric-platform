package org.oip.reporting

import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class ReportNotFound(message: String) : RuntimeException(message)
class ReportDisabled(message: String) : RuntimeException(message)

/** A draft definition that would not save: schema or semantic errors, one message each. */
class ReportInvalid(val errors: List<String>) : RuntimeException(errors.joinToString("; "))

/** Where a scheduled run's result goes. Rendering and delivery (PDF, email, files) are out of scope for C-9a. */
fun interface ReportResultSink {
    fun deliver(definition: ReportDefinition, result: ReportResult)

    companion object {
        val NONE = ReportResultSink { _, _ -> }
    }
}

/**
 * Report definitions (`reporting/gateway/Report/<name>` config resources), runs, and run history.
 * CRUD goes through the generic config resource API; this class reads the store on each call, so a run always
 * uses the definition as last saved.
 */
class ReportService(
    private val store: FileConfigStore,
    private val engine: ReportEngine,
    val history: RunHistory,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    /** Register the schema and semantic checks, and drop stored resources that no longer validate. */
    fun attach(): ReportService {
        registerReportSchemas(store.registry)
        store.revalidate(REPORTING_MODULE)
        return this
    }

    fun definition(name: String): ReportDefinition {
        val id = try {
            ResourceId(REPORTING_MODULE, Scope.GATEWAY, REPORT_KIND, name)
        } catch (e: IllegalArgumentException) {
            throw ReportNotFound("report '$name' not found")
        }
        val r = store.get(id) ?: throw ReportNotFound("report '$name' not found")
        return ReportDefinitions.parse(name, r.metadata.version, r.spec)
    }

    /**
     * A definition that is not stored yet, checked the way a save checks it. Used by the workspace preview so the
     * draft on screen is what runs. Throws [ReportInvalid].
     */
    fun draft(spec: Map<String, Any?>): ReportDefinition {
        val errors = store.registry.validate(Resource(REPORT_KIND, Metadata("preview", REPORTING_MODULE), spec))
        if (errors.isNotEmpty()) throw ReportInvalid(errors)
        return try {
            ReportDefinitions.parse("preview", 0, spec)
        } catch (e: IllegalArgumentException) {
            throw ReportInvalid(listOf(e.message ?: "invalid report"))
        }
    }

    /** Run [def] (a [draft]) once for a preview: same engine and permission checks as [run], not recorded in history. */
    fun preview(def: ReportDefinition, supplied: Map<String, Any?>, caller: ReportCaller): ReportResult {
        val resolved = Parameters.resolve(def.parameters, supplied, clock.millis()).associate { it.name to it.value }
        for (source in def.dataSources) {
            val range = (source as? DataSourceDef.TagHistory)?.range as? Binding.Param ?: continue
            if (resolved[range.name] == null) throw ReportParameterException("parameter ${range.name} is required by data source ${source.name}")
        }
        return try {
            engine.run(def, supplied, caller, RunTrigger.MANUAL)
        } catch (e: ReportRunException) {
            throw e
        } catch (e: Exception) {
            throw ReportRunException(500, "report run failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /** Every stored definition that parses, sorted by name. */
    fun definitions(): List<ReportDefinition> =
        store.list(REPORTING_MODULE, Scope.GATEWAY, REPORT_KIND)
            .mapNotNull { runCatching { ReportDefinitions.parse(it.id.name, it.resource.metadata.version, it.resource.spec) }.getOrNull() }
            .sortedBy { it.name }

    /**
     * Run [name] and record the run. Parameter errors ([ReportParameterException]), unknown and disabled reports
     * are refused before running and not recorded. A data source failure is recorded as `failure` and rethrown as
     * [ReportRunException].
     */
    fun run(name: String, supplied: Map<String, Any?>, caller: ReportCaller, trigger: RunTrigger): ReportResult {
        val def = definition(name)
        if (!def.enabled) throw ReportDisabled("report '$name' is disabled")
        // Refuse bad parameters before recording a run.
        val resolved = Parameters.resolve(def.parameters, supplied, clock.millis()).associate { it.name to it.value }
        for (source in def.dataSources) {
            val range = (source as? DataSourceDef.TagHistory)?.range as? Binding.Param ?: continue
            if (resolved[range.name] == null) throw ReportParameterException("parameter ${range.name} is required by data source ${source.name}")
        }
        val runId = UUID.randomUUID().toString()
        val started = clock.millis()
        try {
            val result = engine.run(def, supplied, caller, trigger, runId)
            history.record(RunRecord(
                runId, name, trigger, caller.name, result.run.startedAt, result.run.durationMillis, result.run.status,
                error = null, datasets = result.datasets.size, rows = result.datasets.sumOf { it.rows.size },
            ))
            return result
        } catch (e: Exception) {
            val failure = e as? ReportRunException ?: ReportRunException(500, "report run failed: ${e.message ?: e.javaClass.simpleName}", e)
            history.record(RunRecord(
                runId, name, trigger, caller.name, started, clock.millis() - started, RunStatus.FAILURE,
                error = (failure.message ?: "failed").take(MAX_ERROR), datasets = 0, rows = 0,
            ))
            throw failure
        }
    }

    fun runs(name: String, limit: Int): List<RunRecord> = history.runs(name, limit)

    fun status(name: String): ReportStatus = status(definition(name))

    fun statuses(): List<ReportStatus> = definitions().map { status(it) }

    private fun status(def: ReportDefinition): ReportStatus {
        val recent = history.runs(def.name, RunHistory.DEFAULT_KEEP)
        val last = recent.firstOrNull()
        val schedule = def.schedule
        val scheduled = def.enabled && schedule != null && schedule.enabled
        val next = if (scheduled) {
            CronExpression.parse(schedule!!.cron).next(Instant.ofEpochMilli(clock.millis()), ZoneId.of(schedule.timezone))?.toEpochMilli()
        } else null
        return ReportStatus(
            report = def.name,
            scheduled = scheduled,
            cron = schedule?.cron,
            timezone = schedule?.timezone,
            nextFireAt = next,
            lastRunAt = last?.startedAt,
            lastStatus = last?.status,
            lastError = last?.error,
            consecutiveFailures = recent.takeWhile { it.status == RunStatus.FAILURE }.size,
        )
    }

    override fun close() = history.close()

    companion object {
        const val MAX_ERROR = 1000
    }
}
