package org.oip.security

import java.util.Locale

/**
 * Byte-wise comparison that always examines every index of the longer input.
 * [java.security.MessageDigest.isEqual] returns immediately when the lengths differ; this does not.
 * [visit] is optional and exists so tests can prove the loop does not stop at the first mismatch.
 */
object ConstantTime {
    fun equal(a: ByteArray, b: ByteArray): Boolean = compare(a, b, null) == 0

    fun equal(a: String, b: String): Boolean =
        equal(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    internal fun equalVisiting(a: ByteArray, b: ByteArray, visit: (Int) -> Unit): Boolean =
        compare(a, b, visit) == 0

    private fun compare(a: ByteArray, b: ByteArray, visit: ((Int) -> Unit)?): Int {
        val n = maxOf(a.size, b.size)
        var diff = a.size xor b.size
        for (i in 0 until n) {
            visit?.invoke(i)
            val x = if (i < a.size) a[i].toInt() and 0xFF else 0
            val y = if (i < b.size) b[i].toInt() and 0xFF else 0
            diff = diff or (x xor y)
        }
        return diff
    }
}

internal object Hex {
    fun encode(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun decode(text: String): ByteArray {
        require(text.length % 2 == 0) { "odd hex length" }
        return ByteArray(text.length / 2) { i ->
            text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}

/** SHA-256 of an API-key secret. Keys are 256-bit random, so a fast hash keeps request latency low. */
internal fun sha256(data: ByteArray): ByteArray =
    java.security.MessageDigest.getInstance("SHA-256").digest(data)

/**
 * Closed permission vocabulary. Gateway area permissions follow the implication rules on
 * [Gateway General Security Settings](-):
 * Write implies Read, and Read implies Access.
 */
object Permissions {
    const val GATEWAY_WRITE = "gateway.write"
    const val GATEWAY_READ = "gateway.read"
    const val GATEWAY_ACCESS = "gateway.access"
    const val DESIGNER_LOGIN = "designer.login"
    const val USERS_ADMIN = "users.admin"
    const val ROLES_ADMIN = "roles.admin"
    const val APIKEYS_ADMIN = "apikeys.admin"
    const val AUDIT_READ = "audit.read"
    const val SECRETS_ADMIN = "secrets.admin"
    const val TAGS_READ = "tags.read"
    const val TAGS_WRITE = "tags.write"
    const val MODULES_ADMIN = "modules.admin"
    /**
     * Create, download, and restore gateway backups. An archive holds the secrets key, the secret store, and every
     * user and role, and a restore replaces them, so this is administrator-level. Only the administrator role holds it
     * by default. Listing backup names and sizes stays a gateway read.
     */
    const val BACKUPS_ADMIN = "backups.admin"

    val ALL: Set<String> = linkedSetOf(
        GATEWAY_WRITE, GATEWAY_READ, GATEWAY_ACCESS, DESIGNER_LOGIN,
        USERS_ADMIN, ROLES_ADMIN, APIKEYS_ADMIN, AUDIT_READ, SECRETS_ADMIN,
        TAGS_READ, TAGS_WRITE, MODULES_ADMIN, BACKUPS_ADMIN,
    )

    fun known(permission: String) = permission in ALL

    /** Permissions granted by holding [permission], including itself. */
    fun impliedBy(permission: String): Set<String> = when (permission) {
        GATEWAY_WRITE -> setOf(GATEWAY_WRITE, GATEWAY_READ, GATEWAY_ACCESS)
        GATEWAY_READ -> setOf(GATEWAY_READ, GATEWAY_ACCESS)
        else -> setOf(permission)
    }

    fun expand(held: Collection<String>): Set<String> = held.flatMapTo(linkedSetOf()) { impliedBy(it) }
}

/** Default role matrix seeded into an empty user source. Role names are resource names (lowercase). */
object DefaultRoles {
    const val ADMINISTRATOR = "administrator"
    const val OPERATOR = "operator"
    const val VIEWER = "viewer"

    val operator: Set<String> = setOf(
        Permissions.GATEWAY_READ, Permissions.GATEWAY_ACCESS,
        Permissions.TAGS_READ, Permissions.TAGS_WRITE, Permissions.AUDIT_READ,
    )
    val viewer: Set<String> = setOf(
        Permissions.GATEWAY_ACCESS, Permissions.TAGS_READ, Permissions.AUDIT_READ,
    )

    fun permissions(name: String): Set<String>? = when (name) {
        ADMINISTRATOR -> Permissions.ALL
        OPERATOR -> operator
        VIEWER -> viewer
        else -> null
    }
}

sealed class Subject {
    abstract val enabled: Boolean
    abstract val principal: String

    data class User(val username: String, val roles: Set<String>, override val enabled: Boolean) : Subject() {
        override val principal: String get() = username
    }

    data class ApiKey(
        val keyName: String,
        val scopes: Set<String>,
        val secureConnectionRequired: Boolean,
        override val enabled: Boolean,
    ) : Subject() {
        override val principal: String get() = keyName
    }
}

enum class AuthFailure { BAD_CREDENTIALS, DISABLED, INSECURE_CHANNEL }

/**
 * Password verified ([Success]), password verified but past its maximum age ([PasswordExpired]),
 * or rejected ([Failure]). [PasswordExpired] is not a session: the REST layer may offer a change-password flow.
 * Unknown users and wrong passwords share [AuthFailure.BAD_CREDENTIALS].
 */
sealed class AuthenticationResult {
    data class Success(val subject: Subject) : AuthenticationResult()
    data class PasswordExpired(val subject: Subject.User) : AuthenticationResult()
    data class Failure(val reason: AuthFailure) : AuthenticationResult()
}

enum class DecisionReason {
    ALLOW, UNAUTHENTICATED, DISABLED, ZONE_DENY, PERMISSION_DENIED, INSECURE_CHANNEL, UNKNOWN_PERMISSION,
}

data class AuthorizationDecision(
    val allowed: Boolean,
    val permission: String,
    val zone: String,
    val reason: DecisionReason,
)

enum class CallerScope { GATEWAY, DESIGNER, CLIENT }

enum class MatchMode { ANY, ALL }

enum class DesignerAuthStrategy { CLASSIC, IDENTITY_PROVIDER }

enum class RequestScope { CLIENT, DESIGNER, GATEWAY }

/** Where a request comes from. Used to pick a security zone and to apply connection qualifiers. */
data class ConnectionContext(
    val address: java.net.InetAddress?,
    val hostName: String?,
    val gatewayName: String?,
    val secure: Boolean,
    val direct: Boolean,
    val scope: RequestScope,
) {
    companion object {
        fun local(scope: RequestScope = RequestScope.GATEWAY, secure: Boolean = true, direct: Boolean = true) =
            ConnectionContext(
                java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
                "localhost", "local", secure, direct, scope,
            )
    }
}

open class SecurityException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class AlreadyExistsException(kind: String, name: String) : SecurityException("$kind '$name' already exists")
class NotFoundException(kind: String, name: String) : SecurityException("$kind '$name' not found")
class AccessDeniedException(message: String) : SecurityException(message)
class SecretKeyException(message: String) : SecurityException(message)
class SecretException(message: String, cause: Throwable? = null) : SecurityException(message, cause)

enum class PasswordRule {
    MIN_LENGTH, MAX_LENGTH, COMPLEXITY, HISTORY, PROHIBIT_PASSWORD, PROHIBIT_USERNAME, MAX_CONSECUTIVE,
}

data class PasswordViolation(val rule: PasswordRule, val message: String)

class PasswordRejectedException(val violations: List<PasswordViolation>) :
    SecurityException(violations.joinToString("; ") { "${it.rule}: ${it.message}" })

/**
 * Actor and request host for audit records written by the config listener.
 * The listener runs on the thread that writes the resource, so call [call] around the write.
 * The REST layer should do the same when it writes config outside this library.
 */
object AuditContext {
    data class Origin(val actor: String, val host: String, val context: String)

    private val local = ThreadLocal<Origin?>()

    fun current(): Origin? = local.get()

    fun <T> call(actor: String, host: String = "", context: String = "", block: () -> T): T {
        val scope = open(actor, host, context)
        try {
            return block()
        } finally {
            scope.close()
        }
    }

    /**
     * Publish [actor] on this thread until [AutoCloseable.close]. The HTTP filter uses this so config
     * writes that happen inside the request, including deletes, record the caller. Close it on the same thread.
     */
    fun open(actor: String, host: String = "", context: String = ""): AutoCloseable {
        val prev = local.get()
        local.set(Origin(actor, host.ifEmpty { prev?.host ?: "" }, context.ifEmpty { prev?.context ?: "" }))
        return AutoCloseable { if (prev == null) local.remove() else local.set(prev) }
    }
}

internal fun String.lower() = lowercase(Locale.ROOT)
