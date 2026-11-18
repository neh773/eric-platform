package org.oip.security

import org.oip.config.ChangeSource
import org.oip.config.ChangeType
import org.oip.config.ConfigBatch
import org.oip.config.ConfigBatches
import org.oip.config.ConfigEvent
import org.oip.config.FileConfigStore
import org.oip.config.Resource
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

/**
 * Gateway security library (no HTTP). The REST layer calls the interfaces on this object.
 *
 * Configuration (users, roles, password policy, API-key hashes, zones, gateway security, audit profile)
 * is stored as resources in [FileConfigStore] under `org.oip.security`. Runtime state (the hash-chained
 * audit log and AES-GCM secret ciphertexts) goes through [RuntimeStores], SQLite by default (ADR 0008).
 * The audit head (latest sequence and HMAC) is a separate MAC'd checkpoint, `db/audit.anchor`, not a row
 * in `audit.sqlite`. Record HMACs and that checkpoint use HKDF subkeys of the secrets key.
 * `audit.sqlite` is synchronous=FULL, so the row is durable before the checkpoint moves. A checkpoint
 * one row behind a valid tip is repaired by [AuditLog.verify]; that is a failed checkpoint write, not tampering.
 *
 * Session cookies, CSRF, lockout, and the HTTP bearer check live in gateway/server (P1-14). This library
 * authenticates passwords and API keys and records `login`, `login attempt`, and `token login attempt`.
 * The HTTP layer records `logout` and `Authentication Lockout`, and identity-provider logins (P1-32).
 * [AuditProfileStore.enforceRetention] removes rows older than the profile's retention days and moves a keyed
 * chain base ([AuditBase]) in the same transaction, so the kept rows still verify.
 */
class SecurityService private constructor(
    val users: UserDirectory,
    val roles: RoleDirectory,
    val apiKeys: ApiKeyDirectory,
    val zones: ZoneDirectory,
    val authorization: Authorizer,
    val audit: AuditLog,
    val secrets: SecretStore,
    val passwordPolicy: PasswordPolicyStore,
    val gatewaySecurity: GatewaySecurityStore,
    val auditProfile: AuditProfileStore,
    /** OIDC and SAML provider resources (SEC-014). The protocol code is in gateway/server. */
    val identityProviders: IdentityProviderDirectory,
    /** LDAP, database and hybrid user sources (SEC-007, SEC-010, SEC-021, SEC-025). The login code is [UserSourceAuthenticator]. */
    val userSources: UserSourceDirectory,
    /** The security level tree and the rules that grant levels at login (SEC-016, SEC-017). */
    val levels: SecurityLevelDirectory,
    private val runtime: RuntimeStores,
    private val subscription: AutoCloseable,
    private val clock: Clock,
) : AutoCloseable {
    private val auditErrorCount = AtomicLong()
    @Volatile var lastAuditError: String? = null
        private set
    private var batchFinish: AutoCloseable? = null

    /** Listener failures. Config writes already committed; [FileConfigStore] swallows listener exceptions. */
    fun auditErrors(): Long = auditErrorCount.get()

    fun resolveSecretReferences(spec: Map<String, Any?>): Map<String, Any?> =
        SecretReferences.resolveSpec(spec) { ref -> secrets.getText(SecretReferences.parse(ref)) }

    /**
     * After a backup restore replaced the audit database, its anchor, and possibly the secrets key on disk: continue
     * the restored audit chain with keys from [keys], and give full roles any permission added since the archive was
     * made ([AddedPermissions]).
     */
    /** Close the audit and secret databases so a restore can rename those files. */
    fun releaseLiveFiles() {
        (audit as? ChainedAuditLog)?.release()
        runtime.secrets.release()
    }

    /** Open the audit and secret databases again after the restore renamed them. */
    fun reopenLiveFiles() {
        runtime.secrets.reopen()
        (audit as? ChainedAuditLog)?.reopenSink()
    }

    fun reloadAfterRestore(keys: SecretKeySource) {
        val key = keys.key()
        (audit as? ChainedAuditLog)?.reload(AuditKeys.chainKey(key), MacAuditAnchor(runtime.auditAnchor, AuditKeys.anchorKey(key)))
        AddedPermissions.apply(roles)
    }

    internal fun noteAuditError(message: String?) {
        auditErrorCount.incrementAndGet()
        lastAuditError = message
    }

    override fun close() {
        runCatching { batchFinish?.close() }
        runCatching { subscription.close() }
        runCatching { runtime.close() }
    }

    companion object {
        fun open(
            config: FileConfigStore,
            dataDir: Path,
            keys: SecretKeySource,
            clock: Clock = Clock.systemUTC(),
            hasher: PasswordHasher = Argon2idHasher.production(),
        ): SecurityService = open(config, RuntimeStores.sqlite(dataDir), keys, clock, hasher)

        fun open(
            config: FileConfigStore,
            runtime: RuntimeStores,
            keys: SecretKeySource,
            clock: Clock = Clock.systemUTC(),
            hasher: PasswordHasher = Argon2idHasher.production(),
        ): SecurityService {
            SecuritySchemas.register(config.registry)
            config.revalidate(SECURITY_MODULE)
            val secretKey = keys.key()
            val auditLog = ChainedAuditLog(
                runtime.audit,
                AuditKeys.chainKey(secretKey),
                MacAuditAnchor(runtime.auditAnchor, AuditKeys.anchorKey(secretKey)),
            )
            val encrypted = EncryptedSecretStore(runtime.secrets, secretKey, clock)
            val docs = ConfigDocs(config, config.registry)
            val directory = SecurityDirectory(docs, hasher, clock, auditLog)
            val zones = ZoneService(directory)
            val serviceHolder = arrayOfNulls<SecurityService>(1)
            val subscription = config.subscribe { event -> serviceHolder[0]?.onConfig(event) }
            val auditingSecrets = AuditingSecrets(encrypted, auditLog, clock) { directory.auditEnabled() }
            val service = SecurityService(
                users = UserService(directory),
                roles = RoleService(directory),
                apiKeys = ApiKeyService(directory),
                zones = zones,
                authorization = PermissionAuthorizer(directory, zones),
                audit = auditLog,
                secrets = auditingSecrets,
                passwordPolicy = PasswordPolicyService(directory),
                gatewaySecurity = GatewaySecurityService(directory),
                auditProfile = AuditProfileService(directory),
                identityProviders = IdentityProviderService(docs),
                userSources = UserSourceService(docs),
                levels = SecurityLevelService(docs),
                runtime = runtime,
                subscription = subscription,
                clock = clock,
            )
            serviceHolder[0] = service
            service.batchFinish = ConfigBatches.onFinished { batch -> service.onBatch(batch) }
            directory.seed()
            return service
        }
    }

    private fun onConfig(event: ConfigEvent) {
        val id = event.id
        if (event.type == ChangeType.INVALID || id == null) return
        val batch = ConfigBatches.current()
        if (batch != null) {
            // One chained audit row for the whole POST /api/v1/config/batch, written when the batch closes.
            // Per-tag fsync of the MAC anchor was a large share of import CPU.
            batch.note(event.type)
            if (auditProfile.get().enabled || id.kind == Kinds.AUDIT_PROFILE) batch.record = true
            return
        }
        // The write that disables the profile is still recorded. Later writes are not.
        if (!auditProfile.get().enabled && id.kind != Kinds.AUDIT_PROFILE) return
        val action = when (event.type) {
            ChangeType.CREATED -> AuditActions.CONFIG_ADDED
            ChangeType.UPDATED -> AuditActions.CONFIG_UPDATED
            ChangeType.DELETED -> AuditActions.CONFIG_DELETED
            ChangeType.INVALID -> return
        }
        val origin = AuditContext.current()
        val actor = when {
            event.source == ChangeSource.FILE -> "file"
            origin != null -> origin.actor
            event.type == ChangeType.DELETED -> "system"
            else -> event.resource?.metadata?.modifiedBy ?: "system"
        }
        try {
            audit.append(
                AuditDraft(
                    timestampMillis = clock.millis(),
                    actor = actor,
                    action = action,
                    status = AuditStatus.SUCCESS,
                    actorHost = origin?.host ?: "",
                    target = id.toString(),
                    detail = summarize(event.resource ?: event.previous),
                    context = origin?.context ?: "",
                )
            )
        } catch (e: Exception) {
            noteAuditError(e.message)
        }
    }

    private fun onBatch(batch: ConfigBatch) {
        if (!batch.record || batch.size == 0) return
        val origin = AuditContext.current()
        try {
            audit.append(
                AuditDraft(
                    timestampMillis = clock.millis(),
                    actor = origin?.actor ?: batch.actor,
                    action = batch.action ?: AuditActions.CONFIG_BATCH,
                    status = AuditStatus.SUCCESS,
                    actorHost = origin?.host ?: "",
                    target = batch.target ?: "config/batch",
                    detail = (batch.detail?.let { "$it;" } ?: "") +
                        "created=${batch.created},updated=${batch.updated},deleted=${batch.deleted},scope=${batch.scope}",
                    context = origin?.context ?: "",
                )
            )
        } catch (e: Exception) {
            noteAuditError(e.message)
        }
    }

    private fun summarize(resource: Resource?): String {
        if (resource == null) return ""
        val sensitive = Regex("password|secret|hash|token", RegexOption.IGNORE_CASE)
        val keys = resource.spec.keys.sorted()
        val safe = keys.filter { !sensitive.containsMatchIn(it) }
        val hidden = keys.size - safe.size
        return buildString {
            append("keys=")
            append(safe.joinToString(","))
            if (hidden > 0) append(";redacted=").append(hidden)
        }
    }
}

private class AuditingSecrets(
    private val inner: SecretStore,
    private val audit: AuditLog,
    private val clock: Clock,
    private val enabled: () -> Boolean,
) : SecretStore {
    override fun put(name: String, plaintext: ByteArray, description: String): String {
        val ref = inner.put(name, plaintext, description)
        record(AuditActions.SECRET_WRITTEN, name, AuditStatus.SUCCESS)
        return ref
    }

    override fun get(name: String): ByteArray = inner.get(name)

    override fun delete(name: String) {
        inner.delete(name)
        record(AuditActions.SECRET_DELETED, name, AuditStatus.SUCCESS)
    }

    override fun list() = inner.list()

    private fun record(action: String, name: String, status: AuditStatus) {
        if (!enabled()) return
        val origin = AuditContext.current()
        audit.append(
            AuditDraft(
                timestampMillis = clock.millis(),
                actor = origin?.actor ?: "system",
                action = action,
                status = status,
                actorHost = origin?.host ?: "",
                target = SecretReferences.format(name),
                context = origin?.context ?: "",
            )
        )
    }
}
