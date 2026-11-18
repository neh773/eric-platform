package org.oip.reporting

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import kotlin.io.path.createDirectories

/**
 * Report run history in its own SQLite file under the gateway `db/` directory (ADR 0008: one file per concern),
 * `db/report-runs.sqlite`. At most [keepPerReport] runs are kept per report name; older ones are deleted on insert.
 * Records survive a restart, so status (last error, consecutive failures) does too.
 */
class RunHistory(val file: Path, private val keepPerReport: Int = DEFAULT_KEEP) : AutoCloseable {
    private val lock = Any()
    private val conn: Connection

    init {
        require(keepPerReport >= 1) { "keepPerReport must be >= 1" }
        file.parent?.createDirectories()
        conn = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute(
                "CREATE TABLE IF NOT EXISTS report_runs(" +
                    "seq INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL UNIQUE, report TEXT NOT NULL, " +
                    "trigger TEXT NOT NULL, user TEXT NOT NULL, started_at INTEGER NOT NULL, duration_ms INTEGER NOT NULL, " +
                    "status TEXT NOT NULL, error TEXT, datasets INTEGER NOT NULL, rows INTEGER NOT NULL)"
            )
            st.execute("CREATE INDEX IF NOT EXISTS report_runs_by_report ON report_runs(report, seq)")
        }
    }

    fun record(r: RunRecord) = synchronized(lock) {
        conn.prepareStatement(
            "INSERT INTO report_runs(run_id, report, trigger, user, started_at, duration_ms, status, error, datasets, rows) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, r.runId)
            ps.setString(2, r.report)
            ps.setString(3, r.trigger.wire)
            ps.setString(4, r.user)
            ps.setLong(5, r.startedAt)
            ps.setLong(6, r.durationMillis)
            ps.setString(7, r.status.wire)
            ps.setString(8, r.error)
            ps.setInt(9, r.datasets)
            ps.setInt(10, r.rows)
            ps.executeUpdate()
        }
        conn.prepareStatement(
            "DELETE FROM report_runs WHERE report = ? AND seq <= " +
                "(SELECT seq FROM report_runs WHERE report = ? ORDER BY seq DESC LIMIT 1 OFFSET ?)"
        ).use { ps ->
            ps.setString(1, r.report)
            ps.setString(2, r.report)
            ps.setInt(3, keepPerReport)
            ps.executeUpdate()
        }
    }

    /** Newest first. */
    fun runs(report: String, limit: Int = DEFAULT_KEEP): List<RunRecord> = synchronized(lock) {
        conn.prepareStatement("SELECT * FROM report_runs WHERE report = ? ORDER BY seq DESC LIMIT ?").use { ps ->
            ps.setString(1, report)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs -> generateSequence { if (rs.next()) read(rs) else null }.toList() }
        }
    }

    private fun read(rs: ResultSet) = RunRecord(
        runId = rs.getString("run_id"),
        report = rs.getString("report"),
        trigger = if (rs.getString("trigger") == RunTrigger.SCHEDULE.wire) RunTrigger.SCHEDULE else RunTrigger.MANUAL,
        user = rs.getString("user"),
        startedAt = rs.getLong("started_at"),
        durationMillis = rs.getLong("duration_ms"),
        status = RunStatus.fromWire(rs.getString("status")),
        error = rs.getString("error"),
        datasets = rs.getInt("datasets"),
        rows = rs.getInt("rows"),
    )

    override fun close() = synchronized(lock) { conn.close() }

    companion object {
        const val DEFAULT_KEEP = 200
    }
}
