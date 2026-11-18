package org.oip.alarm.notify

import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories

/**
 * Acknowledgement links sent in two-way notification email (review-3 F9).
 * Doc source: FEATURES ALA-012.
 *
 * - A link expires [ttl] after it was sent, and [grace] after its alarm event left the live set (the grace lets the
 *   page that confirms an acknowledgement still load). An expired link is "not found".
 * - With a [file], links are kept in SQLite, so a restart does not break links already in people's mailboxes. Only
 *   a SHA-256 digest of each token is stored, so the file alone cannot be used to acknowledge anything.
 */
class AckLinks(
    private val clock: Clock,
    file: Path? = null,
    val ttl: Duration = DEFAULT_TTL,
    val grace: Duration = DEFAULT_GRACE,
) : AutoCloseable {
    data class Link(
        val eventId: String,
        val username: String,
        val source: String,
        val name: String,
        val displayPath: String,
        val notesRequired: Boolean,
        val createdMillis: Long,
    )

    private val links = ConcurrentHashMap<String, Link>()
    private val goneSince = ConcurrentHashMap<String, Long>()
    private val lock = Any()
    private val conn: Connection? = file?.let { open(it) }

    init {
        conn?.let { c ->
            synchronized(lock) {
                c.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT digest, event_id, username, source, name, display_path, notes_required, created_ms FROM ack_links",
                    ).use { rs ->
                        while (rs.next()) {
                            links[rs.getString(1)] = Link(
                                eventId = rs.getString(2),
                                username = rs.getString(3),
                                source = rs.getString(4),
                                name = rs.getString(5),
                                displayPath = rs.getString(6),
                                notesRequired = rs.getInt(7) != 0,
                                createdMillis = rs.getLong(8),
                            )
                        }
                    }
                }
            }
        }
    }

    val size: Int get() = links.size

    fun add(token: String, link: Link) {
        val key = digest(token)
        links[key] = link
        conn?.let { c ->
            synchronized(lock) {
                c.prepareStatement(
                    "INSERT OR REPLACE INTO ack_links(digest, event_id, username, source, name, display_path, notes_required, created_ms) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { ps ->
                    ps.setString(1, key)
                    ps.setString(2, link.eventId)
                    ps.setString(3, link.username)
                    ps.setString(4, link.source)
                    ps.setString(5, link.name)
                    ps.setString(6, link.displayPath)
                    ps.setInt(7, if (link.notesRequired) 1 else 0)
                    ps.setLong(8, link.createdMillis)
                    ps.executeUpdate()
                }
            }
        }
    }

    /** The link for [token], or null when it is unknown or has expired. */
    fun find(token: String): Link? {
        val key = digest(token)
        val link = links[key] ?: return null
        val now = clock.millis()
        val gone = goneSince[key]
        if (now >= link.createdMillis + ttl.toMillis() || (gone != null && now >= gone + grace.toMillis())) {
            remove(key)
            return null
        }
        return link
    }

    /** Drop expired links. [isLive] says whether an alarm event is still live. */
    fun purge(isLive: (String) -> Boolean) {
        val now = clock.millis()
        for ((key, link) in links.entries.toList()) {
            if (now >= link.createdMillis + ttl.toMillis()) {
                remove(key)
                continue
            }
            if (isLive(link.eventId)) {
                goneSince.remove(key)
                continue
            }
            val gone = goneSince.getOrPut(key) { now }
            if (now >= gone + grace.toMillis()) remove(key)
        }
    }

    override fun close() {
        synchronized(lock) { conn?.let { runCatching { it.close() } } }
    }

    private fun remove(key: String) {
        links.remove(key)
        goneSince.remove(key)
        conn?.let { c ->
            synchronized(lock) {
                runCatching {
                    c.prepareStatement("DELETE FROM ack_links WHERE digest = ?").use { ps -> ps.setString(1, key); ps.executeUpdate() }
                }
            }
        }
    }

    private fun open(file: Path): Connection {
        file.parent?.createDirectories()
        Class.forName("org.sqlite.JDBC")
        val c = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
        c.createStatement().use { st ->
            st.execute("PRAGMA busy_timeout=5000")
            st.execute(
                "CREATE TABLE IF NOT EXISTS ack_links (digest TEXT PRIMARY KEY, event_id TEXT NOT NULL, username TEXT NOT NULL, " +
                    "source TEXT NOT NULL, name TEXT NOT NULL, display_path TEXT NOT NULL, notes_required INTEGER NOT NULL, " +
                    "created_ms INTEGER NOT NULL)",
            )
        }
        return c
    }

    companion object {
        val DEFAULT_TTL: Duration = Duration.ofHours(24)
        val DEFAULT_GRACE: Duration = Duration.ofMinutes(10)

        private fun digest(token: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
            )
    }
}
