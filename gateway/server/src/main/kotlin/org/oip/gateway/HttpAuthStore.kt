package org.oip.gateway

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import kotlin.math.min

/**
 * Browser session lifetime. Idle timeout is the gateway security setting `inactivityTimeoutMinutes`
 * (0 disables idle, matching the public gateway setting). Absolute lifetime defaults to 12 hours.
 * The classic gateway page has no separate maximum; the internal identity provider does ("Session Expiration"),
 * and a value <= 0 there means no maximum. Zero here does the same and issues a session cookie (no Max-Age).
 */
data class SessionPolicy(val absolute: Duration = Duration.ofHours(12))

/**
 * Login abuse limits.
 *
 * Account lockout is not a single global counter. One remote address must not be able to keep a
 * real account locked for everyone:
 * - each (username, client IP) pair locks on its own after [pairAttempts] failures inside [window],
 *   then for [lockBase] doubling up to [lockCap];
 * - a username locks for every address only after [distinctIpCap] addresses have each contributed
 *   a full pair threshold (the global cap is `pairAttempts * distinctIpCap`). Further failures from
 *   an address that already reached the pair threshold do not add to the global count.
 *
 * Separately, each IP has a token bucket of [permitsPerMinute] login attempts. Empty buckets back off
 * from one refill interval, doubling up to [throttleBackoffCap]. A throttled attempt does not record
 * an account failure.
 *
 * the reference platform classic authentication locks the account after Lockout Attempts inside Lockout Window
 * (defaults 5 and 15 minutes) and does not document a doubling duration:
 */
data class AuthLimits(
    val permitsPerMinute: Int = 20,
    val throttleBackoffCap: Duration = Duration.ofMinutes(15),
    val pairAttempts: Int = HttpAuth.LOCKOUT_ATTEMPTS,
    val window: Duration = Duration.ofMillis(HttpAuth.LOCKOUT_WINDOW_MILLIS),
    val lockBase: Duration = Duration.ofMillis(HttpAuth.LOCKOUT_BASE_MILLIS),
    val lockCap: Duration = Duration.ofMillis(HttpAuth.LOCKOUT_CAP_MILLIS),
    val distinctIpCap: Int = 5,
    val maxFailureRows: Int = 10_000,
) {
    val globalThreshold: Int get() = pairAttempts * distinctIpCap.coerceAtLeast(1)
}

/**
 * Cookie and lockout contract for the HTTP layer.
 *
 * Cookie flags follow the OWASP session cheat sheet (HttpOnly, SameSite=Strict, Secure when the
 * gateway is configured for HTTPS). Secure is omitted on plain HTTP so browsers keep the cookie:
 * https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html
 * CSRF for cookie sessions uses a synchronizer token (bearer tokens are not cookies, so they skip it):
 * https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html
 */
object HttpAuth {
    const val COOKIE = "oip_session"
    const val CSRF_HEADER = "X-CSRF-Token"
    /**
     * A request with `X-OIP-Background: 1` is a background poll (a running HMI session re-reading its project), not
     * user activity: it is authenticated and expired like any other request but does not slide the idle timeout.
     */
    const val BACKGROUND_HEADER = "X-OIP-Background"
    const val LOCKOUT_ATTEMPTS = 5
    const val LOCKOUT_WINDOW_MILLIS = 15L * 60L * 1000L
    const val LOCKOUT_BASE_MILLIS = 15L * 60L * 1000L
    const val LOCKOUT_CAP_MILLIS = 24L * 60L * 60L * 1000L

    /** [maxAgeSeconds] null is a session cookie (browser drops it on close). */
    fun sessionCookie(token: String, maxAgeSeconds: Long?, secure: Boolean): String =
        "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict" + secureFlag(secure) + maxAge(maxAgeSeconds)

    fun clearCookie(secure: Boolean): String =
        "$COOKIE=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict" + secureFlag(secure)

    /** Level 0 is the first lock (15 min). Each later lock doubles, capped at 24 h. */
    fun backoffMillis(level: Int, base: Long = LOCKOUT_BASE_MILLIS, cap: Long = LOCKOUT_CAP_MILLIS): Long {
        val shift = level.coerceIn(0, 10)
        val raw = base shl shift
        return min(raw, cap)
    }

    private fun secureFlag(secure: Boolean) = if (secure) "; Secure" else ""
    private fun maxAge(seconds: Long?) = if (seconds == null) "" else "; Max-Age=$seconds"
}

data class IssuedSession(val token: String, val csrf: String, val absoluteExpiresMillis: Long)

sealed interface SessionState {
    /**
     * [provider] is the identity provider that signed this session in (P1-32), null for the internal user source.
     * [roles] are the roles mapped at that login; for an internal session they are read from the user source instead.
     */
    data class Active(
        val username: String,
        val csrf: String,
        val absoluteExpiresMillis: Long,
        val provider: String? = null,
        val roles: Set<String> = emptySet(),
        /**
         * The security levels granted at login (SEC-016), with their ancestors; null for a session issued before levels
         * were recorded, which the caller evaluates again.
         */
        val levels: Set<String>? = null,
        /** The roles the login was granted with, null when not recorded; a change re-evaluates the levels. */
        val loginRoles: Set<String>? = null,
    ) : SessionState
    data object Unknown : SessionState
    data object Expired : SessionState
}

data class LockoutView(
    val locked: Boolean,
    val retryAfterSeconds: Long,
    val justLocked: Boolean,
    val failures: Int,
)

/**
 * Sessions, lockout counters, and per-IP login buckets in `<dataDir>/db/http-auth.sqlite` (ADR 0008).
 * The session cookie is stored as a SHA-256 hash. The CSRF token is stored beside it: it is useless
 * without the cookie, and the cookie cannot be recovered from the hash.
 *
 * Failure rows are deleted once their window and any lock have expired, and pair rows are capped at
 * [AuthLimits.maxFailureRows]. See [AuthLimits] for why the key is (username, IP) plus a global cap.
 */
class HttpAuthStore(path: Path, private val clock: Clock, private val limits: AuthLimits = AuthLimits()) : AutoCloseable {
    private val connection: Connection
    private val random = SecureRandom()
    private val lock = Any()

    init {
        Files.createDirectories(path.parent)
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        connection.autoCommit = true
        connection.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA busy_timeout=5000")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS http_session (
                  token_hash TEXT PRIMARY KEY,
                  username TEXT NOT NULL,
                  csrf TEXT NOT NULL,
                  created_millis INTEGER NOT NULL,
                  last_seen_millis INTEGER NOT NULL,
                  absolute_expires_millis INTEGER NOT NULL
                )
                """.trimIndent()
            )
            // Identity-provider sessions (P1-32) carry the provider and the roles mapped at login.
            val sessionCols = st.executeQuery("PRAGMA table_info(http_session)").use { rs ->
                val names = mutableListOf<String>()
                while (rs.next()) names += rs.getString("name")
                names
            }
            if ("provider" !in sessionCols) st.execute("ALTER TABLE http_session ADD COLUMN provider TEXT")
            if ("roles" !in sessionCols) st.execute("ALTER TABLE http_session ADD COLUMN roles TEXT")
            // Security levels granted at login (SEC-016), one path per line.
            if ("levels" !in sessionCols) st.execute("ALTER TABLE http_session ADD COLUMN levels TEXT")
            // Profile fields mapped from an identity provider at its last login (SEC-015), per principal.
            st.execute("CREATE TABLE IF NOT EXISTS idp_profile(principal TEXT PRIMARY KEY, display_name TEXT NOT NULL, email TEXT NOT NULL)")
            val cols = st.executeQuery("PRAGMA table_info(auth_failure)").use { rs ->
                val names = mutableListOf<String>()
                while (rs.next()) names += rs.getString("name")
                names
            }
            if (cols.isNotEmpty() && "bucket" !in cols) st.execute("DROP TABLE auth_failure")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS auth_failure (
                  bucket TEXT PRIMARY KEY,
                  failures INTEGER NOT NULL,
                  last_failure_millis INTEGER NOT NULL,
                  locked_until_millis INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS auth_throttle (
                  ip TEXT PRIMARY KEY,
                  tokens_milli INTEGER NOT NULL,
                  updated_millis INTEGER NOT NULL,
                  penalty INTEGER NOT NULL,
                  blocked_until_millis INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    /** [provider] and [roles] are set for an identity-provider login (P1-32) and left null for the internal user source. */
    fun issue(
        username: String,
        absolute: Duration,
        provider: String? = null,
        roles: Set<String>? = null,
        levels: Set<String>? = null,
    ): IssuedSession = synchronized(lock) {
        val now = clock.millis()
        connection.prepareStatement(
            "DELETE FROM http_session WHERE absolute_expires_millis > 0 AND absolute_expires_millis <= ?"
        ).use { ps ->
            ps.setLong(1, now)
            ps.executeUpdate()
        }
        val token = randomToken()
        val csrf = randomToken()
        val expires = absoluteExpiry(now, absolute)
        connection.prepareStatement(
            """
            INSERT INTO http_session(token_hash, username, csrf, created_millis, last_seen_millis, absolute_expires_millis, provider, roles, levels)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, sha256(token))
            ps.setString(2, username)
            ps.setString(3, csrf)
            ps.setLong(4, now)
            ps.setLong(5, now)
            ps.setLong(6, expires)
            ps.setString(7, provider)
            ps.setString(8, roles?.sorted()?.joinToString(","))
            ps.setString(9, levels?.sorted()?.joinToString("\n"))
            ps.executeUpdate()
        }
        IssuedSession(token, csrf, expires)
    }

    /**
     * Browser sessions still inside both timeouts, as one COUNT over a small table. The system-tag updater
     * calls it every few seconds. [idleMillis] null means the idle timeout is disabled.
     */
    fun activeSessionCount(idleMillis: Long?): Int = synchronized(lock) {
        val now = clock.millis()
        connection.prepareStatement(
            "SELECT COUNT(*) FROM http_session WHERE (absolute_expires_millis <= 0 OR absolute_expires_millis > ?) AND last_seen_millis > ?"
        ).use { ps ->
            ps.setLong(1, now)
            ps.setLong(2, if (idleMillis == null) Long.MIN_VALUE else now - idleMillis)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    /** Drop a presented cookie. Unknown tokens are a no-op. */
    fun delete(token: String) = synchronized(lock) {
        connection.prepareStatement("DELETE FROM http_session WHERE token_hash = ?").use { ps ->
            ps.setString(1, sha256(token))
            ps.executeUpdate()
        }
    }

    /**
     * The username of a session that is still inside both timeouts, without sliding [last_seen] or deleting
     * anything: an open HMI socket checks its login with this, and only user activity keeps a login alive.
     */
    fun peek(token: String, idleMillis: Long?): String? = peekState(token, idleMillis)?.username

    /** [peek], with the provider and mapped roles of an identity-provider session. */
    fun peekState(token: String, idleMillis: Long?): SessionState.Active? = synchronized(lock) {
        connection.prepareStatement(
            "SELECT username, last_seen_millis, absolute_expires_millis, csrf, provider, roles, levels FROM http_session WHERE token_hash = ?"
        ).use { ps ->
            ps.setString(1, sha256(token))
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                val now = clock.millis()
                val lastSeen = rs.getLong(2)
                val absolute = rs.getLong(3)
                if (idleMillis != null && now - lastSeen >= idleMillis) return null
                if (absolute > 0L && now >= absolute) return null
                val roles = rs.getString(6)
                SessionState.Active(rs.getString(1), rs.getString(4), absolute, rs.getString(5), roleSet(roles), levelSet(rs.getString(7)), roles?.let { roleSet(it) })
            }
        }
    }

    /**
     * Load a session and slide [last_seen] when it is still inside both timeouts.
     * [idleMillis] null means idle timeout is disabled (gateway setting <= 0).
     * [touch] false (a background read) checks and expires exactly the same way but leaves [last_seen] alone.
     */
    fun resume(token: String, idleMillis: Long?, touch: Boolean = true): SessionState = synchronized(lock) {
        val hash = sha256(token)
        val row = connection.prepareStatement(
            "SELECT username, csrf, last_seen_millis, absolute_expires_millis, provider, roles, levels FROM http_session WHERE token_hash = ?"
        ).use { ps ->
            ps.setString(1, hash)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return SessionState.Unknown
                val roles = rs.getString(6)
                Row(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getString(5), roleSet(roles), levelSet(rs.getString(7)), roles?.let { roleSet(it) })
            }
        }
        val now = clock.millis()
        val idleExpired = idleMillis != null && now - row.lastSeen >= idleMillis
        val absoluteExpired = row.absoluteExpires > 0L && now >= row.absoluteExpires
        if (idleExpired || absoluteExpired) {
            connection.prepareStatement("DELETE FROM http_session WHERE token_hash = ?").use { ps ->
                ps.setString(1, hash)
                ps.executeUpdate()
            }
            return SessionState.Expired
        }
        if (touch) {
            connection.prepareStatement("UPDATE http_session SET last_seen_millis = ? WHERE token_hash = ?").use { ps ->
                ps.setLong(1, now)
                ps.setString(2, hash)
                ps.executeUpdate()
            }
        }
        SessionState.Active(row.username, row.csrf, row.absoluteExpires, row.provider, row.roles, row.levels, row.loginRoles)
    }

    /**
     * Consume one login permit for [ip]. Returns retry-after seconds when the bucket is empty,
     * or null when the attempt may proceed. A null [AuthLimits.permitsPerMinute] disable (0) always allows.
     */
    fun takeLoginPermit(ip: String): Long? = synchronized(lock) {
        if (limits.permitsPerMinute <= 0) return null
        val now = clock.millis()
        pruneThrottle(now)
        val capacity = limits.permitsPerMinute.toLong() * 1000L
        val row = throttle(ip)
        val elapsed = if (row == null) 0L else (now - row.updated).coerceAtLeast(0L)
        val refilled = if (row == null) capacity else min(capacity, row.tokens + refillMilli(elapsed))
        if (row != null && row.blockedUntil > now) {
            val penalty = row.penalty + 1
            val until = now + throttleBackoff(penalty)
            saveThrottle(ip, refilled, now, penalty, until)
            return retrySeconds(until, now)
        }
        if (refilled >= 1000L) {
            saveThrottle(ip, refilled - 1000L, now, penalty = 0, blockedUntil = 0L)
            return null
        }
        val penalty = (row?.penalty ?: 0) + 1
        val until = now + throttleBackoff(penalty)
        saveThrottle(ip, refilled, now, penalty, until)
        retrySeconds(until, now)
    }

    /** Seconds remaining when [username] from [ip] is locked, otherwise null. Does not record an attempt. */
    fun lockRemaining(username: String, ip: String): Long? = synchronized(lock) {
        val now = clock.millis()
        pruneFailures(now)
        val waits = listOfNotNull(activeRetry(globalKey(username), now), activeRetry(pairKey(username, ip), now))
        waits.maxOrNull()
    }

    /**
     * Count a bad password for the (username, ip) pair, and at most [AuthLimits.pairAttempts] of those
     * toward the global username cap. Failures older than the window are dropped when the row is not locked.
     * A lock that has expired keeps its count so the next failure uses the next backoff step.
     * A call while that pair or the username is already locked does not add another failure.
     */
    fun recordFailure(username: String, ip: String): LockoutView = synchronized(lock) {
        val now = clock.millis()
        pruneFailures(now)
        val global = failure(globalKey(username))
        if (global != null && global.lockedUntil > now) {
            return LockoutView(true, retrySeconds(global.lockedUntil, now), justLocked = false, failures = global.failures)
        }
        val pair = failure(pairKey(username, ip))
        if (pair != null && pair.lockedUntil > now) {
            return LockoutView(true, retrySeconds(pair.lockedUntil, now), justLocked = false, failures = pair.failures)
        }
        val pairNext = nextCount(pair, now)
        val pairLocks = pairNext >= limits.pairAttempts
        val pairUntil = if (pairLocks) now + lockBackoff(pairNext - limits.pairAttempts) else 0L
        upsert(pairKey(username, ip), pairNext, now, pairUntil)
        var globalUntil = 0L
        var globalJust = false
        if (pairNext <= limits.pairAttempts) {
            val globalNext = nextCount(global, now)
            val globalLocks = globalNext >= limits.globalThreshold
            globalUntil = if (globalLocks) now + lockBackoff(globalNext - limits.globalThreshold) else 0L
            globalJust = globalLocks
            upsert(globalKey(username), globalNext, now, globalUntil)
        }
        enforceCap(now)
        val pairJust = pairLocks && (pair == null || pair.lockedUntil <= now)
        val until = maxOf(pairUntil, globalUntil)
        val locked = until > now
        LockoutView(locked, if (locked) retrySeconds(until, now) else 0L, justLocked = pairJust || globalJust, failures = pairNext)
    }

    /** Clears this IP's pair counter and the global username counter. Other addresses stay locked. */
    fun clearFailures(username: String, ip: String) = synchronized(lock) {
        deleteBucket(pairKey(username, ip))
        deleteBucket(globalKey(username))
    }

    /** Drop expired counters. Called from the login path; tests use it to observe the cap. */
    fun pruneExpired() = synchronized(lock) {
        val now = clock.millis()
        pruneFailures(now)
        pruneThrottle(now)
    }

    fun pairRowCount(): Int = synchronized(lock) { loadFailures().count { it.bucket.startsWith(PAIR) } }
    fun globalRowCount(): Int = synchronized(lock) { loadFailures().count { it.bucket.startsWith(GLOBAL) } }
    fun hasPair(username: String, ip: String): Boolean = synchronized(lock) { failure(pairKey(username, ip)) != null }
    fun hasGlobal(username: String): Boolean = synchronized(lock) { failure(globalKey(username)) != null }
    fun globalFailures(username: String): Int = synchronized(lock) { failure(globalKey(username))?.failures ?: 0 }

    override fun close() {
        connection.close()
    }

    private fun activeRetry(bucket: String, now: Long): Long? {
        val row = failure(bucket) ?: return null
        if (row.lockedUntil <= now) return null
        return retrySeconds(row.lockedUntil, now)
    }

    private fun nextCount(row: Failure?, now: Long): Int {
        if (row == null) return 1
        if (row.lockedUntil == 0L && now - row.lastFailure >= limits.window.toMillis()) return 1
        return row.failures + 1
    }

    private fun lockBackoff(level: Int): Long =
        HttpAuth.backoffMillis(level, limits.lockBase.toMillis(), limits.lockCap.toMillis())

    private fun throttleBackoff(level: Int): Long {
        val base = (60_000L / limits.permitsPerMinute).coerceAtLeast(1L)
        val shift = (level - 1).coerceIn(0, 10)
        return min(base shl shift, limits.throttleBackoffCap.toMillis())
    }

    private fun refillMilli(elapsedMillis: Long): Long {
        if (elapsedMillis >= 86_400_000L) return limits.permitsPerMinute.toLong() * 1000L
        return elapsedMillis * limits.permitsPerMinute.toLong() * 1000L / 60_000L
    }

    private fun pruneFailures(now: Long) {
        val window = limits.window.toMillis()
        // Only pair rows age out on the window. The global counter stays while any pair for that
        // username remains, so one address cannot add a fresh set of failures every window and
        // eventually fill the global cap by itself. Orphan globals are removed in enforceCap.
        for (row in loadFailures()) {
            if (!row.bucket.startsWith(PAIR)) continue
            val expired = if (row.lockedUntil == 0L) now - row.lastFailure >= window
            else row.lockedUntil <= now && now - row.lockedUntil >= window
            if (row.lockedUntil <= now && expired) deleteBucket(row.bucket)
        }
        enforceCap(now)
    }

    private fun enforceCap(now: Long) {
        var pairs = loadFailures().filter { it.bucket.startsWith(PAIR) }
        var extra = pairs.size - limits.maxFailureRows
        if (extra > 0) {
            val unlocked = pairs.filter { it.lockedUntil <= now }.sortedWith(compareBy({ it.lastFailure }, { it.rowId }))
            unlocked.take(extra).forEach { deleteBucket(it.bucket) }
            pairs = loadFailures().filter { it.bucket.startsWith(PAIR) }
            extra = pairs.size - limits.maxFailureRows
            if (extra > 0) {
                pairs.sortedWith(compareBy({ it.lockedUntil }, { it.lastFailure }, { it.rowId })).take(extra).forEach { deleteBucket(it.bucket) }
                pairs = loadFailures().filter { it.bucket.startsWith(PAIR) }
            }
        }
        val live = pairs.map { userOfPair(it.bucket) }.toSet()
        for (row in loadFailures()) {
            if (!row.bucket.startsWith(GLOBAL)) continue
            val user = row.bucket.removePrefix(GLOBAL)
            if (row.lockedUntil <= now && user !in live) deleteBucket(row.bucket)
        }
    }

    private fun pruneThrottle(now: Long) {
        val window = limits.window.toMillis()
        val capacity = limits.permitsPerMinute.toLong() * 1000L
        val rows = loadThrottle()
        for (row in rows) {
            val idle = row.blockedUntil <= now && row.tokens >= capacity && now - row.updated >= window
            if (idle) deleteThrottle(row.ip)
        }
        val left = loadThrottle().sortedBy { it.updated }
        val extra = left.size - limits.maxFailureRows
        if (extra > 0) left.take(extra).forEach { deleteThrottle(it.ip) }
    }

    private fun upsert(bucket: String, failures: Int, now: Long, until: Long) {
        connection.prepareStatement(
            """
            INSERT INTO auth_failure(bucket, failures, last_failure_millis, locked_until_millis)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(bucket) DO UPDATE SET
              failures = excluded.failures,
              last_failure_millis = excluded.last_failure_millis,
              locked_until_millis = excluded.locked_until_millis
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, bucket)
            ps.setInt(2, failures)
            ps.setLong(3, now)
            ps.setLong(4, until)
            ps.executeUpdate()
        }
    }

    private fun failure(bucket: String): Failure? =
        connection.prepareStatement(
            "SELECT failures, last_failure_millis, locked_until_millis FROM auth_failure WHERE bucket = ?"
        ).use { ps ->
            ps.setString(1, bucket)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null else Failure(rs.getInt(1), rs.getLong(2), rs.getLong(3))
            }
        }

    private fun loadFailures(): List<FailureRow> =
        connection.createStatement().executeQuery(
            "SELECT rowid, bucket, failures, last_failure_millis, locked_until_millis FROM auth_failure"
        ).use { rs ->
            val rows = mutableListOf<FailureRow>()
            while (rs.next()) rows += FailureRow(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4), rs.getLong(5))
            rows
        }

    private fun deleteBucket(bucket: String) {
        connection.prepareStatement("DELETE FROM auth_failure WHERE bucket = ?").use { ps ->
            ps.setString(1, bucket)
            ps.executeUpdate()
        }
    }

    private fun throttle(ip: String): Throttle? =
        connection.prepareStatement(
            "SELECT tokens_milli, updated_millis, penalty, blocked_until_millis FROM auth_throttle WHERE ip = ?"
        ).use { ps ->
            ps.setString(1, ip)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null else Throttle(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getLong(4))
            }
        }

    private fun saveThrottle(ip: String, tokens: Long, now: Long, penalty: Int, blockedUntil: Long) {
        connection.prepareStatement(
            """
            INSERT INTO auth_throttle(ip, tokens_milli, updated_millis, penalty, blocked_until_millis)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(ip) DO UPDATE SET
              tokens_milli = excluded.tokens_milli,
              updated_millis = excluded.updated_millis,
              penalty = excluded.penalty,
              blocked_until_millis = excluded.blocked_until_millis
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, ip)
            ps.setLong(2, tokens)
            ps.setLong(3, now)
            ps.setInt(4, penalty)
            ps.setLong(5, blockedUntil)
            ps.executeUpdate()
        }
    }

    private fun loadThrottle(): List<ThrottleRow> =
        connection.createStatement().executeQuery(
            "SELECT ip, tokens_milli, updated_millis, penalty, blocked_until_millis FROM auth_throttle"
        ).use { rs ->
            val rows = mutableListOf<ThrottleRow>()
            while (rs.next()) rows += ThrottleRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getInt(4), rs.getLong(5))
            rows
        }

    private fun deleteThrottle(ip: String) {
        connection.prepareStatement("DELETE FROM auth_throttle WHERE ip = ?").use { ps ->
            ps.setString(1, ip)
            ps.executeUpdate()
        }
    }

    private fun randomToken(): String {
        val raw = ByteArray(32)
        random.nextBytes(raw)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    private fun absoluteExpiry(now: Long, absolute: Duration): Long {
        if (absolute.isZero || absolute.isNegative) return 0L
        val delta = absolute.toMillis()
        if (delta <= 0L || now > Long.MAX_VALUE - delta) return INSTANT_CAP
        return min(now + delta, INSTANT_CAP)
    }

    private fun retrySeconds(until: Long, now: Long): Long {
        if (until <= now) return 0
        return ((until - now) + 999) / 1000
    }

    private fun sha256(token: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))

    private fun pairKey(username: String, ip: String) = "$PAIR$username\u001f$ip"
    private fun globalKey(username: String) = "$GLOBAL$username"
    private fun userOfPair(bucket: String): String = bucket.removePrefix(PAIR).substringBefore('\u001f')

    /** End every session [provider] signed in (the provider was deleted or disabled). */
    /** Remember the display name and email an identity provider login mapped for [principal] (SEC-015). */
    fun saveProfile(principal: String, displayName: String, email: String) = synchronized(lock) {
        connection.prepareStatement(
            "INSERT INTO idp_profile(principal, display_name, email) VALUES (?, ?, ?) " +
                "ON CONFLICT(principal) DO UPDATE SET display_name = excluded.display_name, email = excluded.email"
        ).use { ps ->
            ps.setString(1, principal)
            ps.setString(2, displayName)
            ps.setString(3, email)
            ps.executeUpdate()
        }
    }

    /** The mapped display name and email last saved for [principal], or null. */
    fun profile(principal: String): Pair<String, String>? = synchronized(lock) {
        connection.prepareStatement("SELECT display_name, email FROM idp_profile WHERE principal = ?").use { ps ->
            ps.setString(1, principal)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getString(2) else null }
        }
    }

    fun deleteProviderSessions(provider: String): Int = synchronized(lock) {
        connection.prepareStatement("DELETE FROM http_session WHERE provider = ?").use { ps ->
            ps.setString(1, provider)
            ps.executeUpdate()
        }
    }

    private fun roleSet(raw: String?): Set<String> = raw?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    private fun levelSet(raw: String?): Set<String>? = raw?.split('\n')?.filter { it.isNotEmpty() }?.toSet()

    private data class Row(
        val username: String,
        val csrf: String,
        val lastSeen: Long,
        val absoluteExpires: Long,
        val provider: String?,
        val roles: Set<String>,
        val levels: Set<String>?,
        val loginRoles: Set<String>?,
    )
    private data class Failure(val failures: Int, val lastFailure: Long, val lockedUntil: Long)
    private data class FailureRow(val rowId: Long, val bucket: String, val failures: Int, val lastFailure: Long, val lockedUntil: Long)
    private data class Throttle(val tokens: Long, val updated: Long, val penalty: Int, val blockedUntil: Long)
    private data class ThrottleRow(val ip: String, val tokens: Long, val updated: Long, val penalty: Int, val blockedUntil: Long)

    companion object {
        private val INSTANT_CAP = Instant.parse("9999-12-31T00:00:00Z").toEpochMilli()
        private const val PAIR = "p\u001f"
        private const val GLOBAL = "g\u001f"
    }
}
