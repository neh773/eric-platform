package org.oip.db.groups

import java.sql.Connection

/** A tag value as a group sees it. [good] is false for bad, error, or missing tags. */
data class TagRead(val value: Any?, val good: Boolean, val diagnostic: String? = null)

/**
 * What a group needs from the tag system. The gateway adapts its tag engine to this so the `db` module
 * does not depend on `tags`.
 *
 * P2-14: each call is expected to hold the tag engine's lock only for that one read or write. The runner
 * calls these outside any database work and never while a database connection is checked out.
 */
interface GroupTags {
    fun read(path: String): TagRead

    /** Write [value] to the tag. Returns null on success, or a short reason. */
    fun write(path: String, value: Any?): String?

    /**
     * Evaluate an expression item. [ref] resolves a `{...}` body: an earlier item's value, otherwise a tag.
     */
    fun evaluate(expression: String, ref: (String) -> TagRead): TagRead
}

/**
 * Hands a group a JDBC connection for one run. The connection is closed (returned) when [block] returns.
 * Error messages thrown from here must already have any password removed; [PooledConnections] relies on
 * the database service for that.
 */
interface GroupDatabase {
    fun <T> withConnection(connection: String, block: (Connection) -> T): T
}

/**
 * Whether this node should run groups right now. In a redundant pair only the active node runs them, so a
 * standby does not write the same rows twice. The default is always active (an independent gateway).
 */
fun interface GroupActivity {
    fun isActive(): Boolean

    companion object {
        val ALWAYS = GroupActivity { true }
    }
}

/** The connection could not be opened or is disabled. */
class GroupDatabaseUnavailable(message: String) : RuntimeException(message)
