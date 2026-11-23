package org.oip.gateway

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.javalin.Javalin
import org.oip.alarm.AlarmEngine
import org.oip.alarm.AlarmJournal
import org.oip.alarm.JournalConnectionSource
import org.oip.alarm.JournalStorageBinding
import org.oip.alarm.scriptEvent
import org.oip.alarm.notify.DirectoryUser
import org.oip.alarm.notify.NotificationService
import org.oip.backup.BackupService
import org.oip.backup.ProjectExchange
import org.oip.backup.RuntimeDatabase
import org.oip.config.FileConfigStore
import org.oip.config.SchemaRegistry
import org.oip.historian.HistoryCollector
import org.oip.historian.HistoryConfigBinding
import org.oip.historian.HistoryProviderBinding
import org.oip.historian.HistoryRouter
import org.oip.historian.HistoryRunner
import org.oip.historian.ForwardBuffer
import org.oip.historian.JdbcConnectionSource
import org.oip.historian.EmbeddedHistorian
import org.oip.historian.StoreAndForwardSettings
import org.oip.db.DatabaseService
import org.oip.db.QueryResult
import org.oip.events.ApacheKafkaClients
import org.oip.events.EventStreamEngine
import org.oip.events.NamedUpdate
import org.oip.sfc.FileChartStateStore
import org.oip.sfc.SfcEngine
import org.oip.report.render.PdfArchiveSink
import org.oip.report.render.ReportDelivery
import org.oip.reporting.DatabaseQueries
import org.oip.reporting.ReportEngine
import org.oip.reporting.ReportResultSink
import org.oip.reporting.ReportScheduler
import org.oip.reporting.ReportService
import org.oip.reporting.RouterHistoryAccess
import org.oip.reporting.RunHistory
import org.oip.reporting.TagReadCheck
import org.oip.host.ModuleHost
import org.oip.opcua.server.OpcUaCertificateTrust
import org.oip.opcua.server.OpcUaServerSettings
import org.oip.opcua.server.OpcUaTagServer
import org.oip.opcua.server.OpcUaUserAuthenticator
import org.oip.security.AuditActions
import org.oip.security.AuditDraft
import org.oip.security.AuditStatus
import org.oip.security.AuthenticationResult
import org.oip.security.ConnectionContext
import org.oip.security.CreateUser
import org.oip.security.DefaultRoles
import org.oip.mqtt.MqttDriverModule
import org.oip.mqtt.SecretSource
import org.oip.security.Permissions
import org.oip.security.RequestScope
import org.oip.security.SecurityZone
import org.oip.security.Subject
import org.oip.security.SecretKeySource
import org.oip.security.SecretsKeyFile
import org.oip.security.SecretReferences
import org.oip.scripting.ScriptLogger
import org.oip.scripting.HmiScripts
import org.oip.scripting.ScriptService
import org.oip.scripting.EventStreamScripts
import org.oip.scripting.SfcChartScripts
import org.oip.security.SecurityService
import org.oip.edgesync.EdgeSyncRuntime
import org.oip.eam.EamRuntime
import org.oip.eam.EamSchemas
import org.oip.edgesync.EdgeSyncSettings
import org.oip.network.GatewayNetwork
import org.oip.network.LinkSettings
import org.oip.redundancy.NodeRole
import org.oip.redundancy.RedundancyRuntime
import org.oip.redundancy.RedundancySettings
import org.oip.tags.MemoryTagPersistence
import org.oip.tags.TagCaller
import org.oip.tags.TagConfigBinding
import org.oip.tags.TagEngine
import org.oip.tags.query.QueryTagRuntime
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Effective gateway settings: profile defaults (classpath `profiles/<profile>.yaml`) overridden by `gateway.yaml`,
 * then by `OIP_PROFILE`, `OIP_HTTP_HOST`, `OIP_HTTP_PORT`, and `OIP_FEATURES`.
 * HTTPS keys and `OIP_HTTP_HTTPS`, `OIP_HTTP_HTTPS_PORT`, `OIP_HTTP_PUBLIC_ADDRESS`,
 * `OIP_HTTP_PUBLIC_HTTPS_PORT`, `OIP_HTTP_FORCE_REDIRECT`, `OIP_HTTPS_CERT`, `OIP_HTTPS_KEY`,
 * and `OIP_HTTPS_PKCS12` are documented in `docs/ops/https.md`.
 * HTTP auth knobs are documented in `docs/ops/http-auth.md`.
 * `opcua` (and `OIP_OPCUA_ENABLED`, `OIP_OPCUA_PORT`, `OIP_OPCUA_BIND`, `OIP_OPCUA_HOSTNAME`) configures the tag server.
 * `gatewayNetwork` configures the gRPC listener (port 8060 by default). See docs/ops/gateway-network.md.
 */
data class GatewaySettings(
    val profile: String,
    val host: String,
    val port: Int,
    val features: List<String>,
    val httpsEnabled: Boolean = false,
    /** Null means derive from [httpsEnabled] and [trustedProxies]. */
    val secureCookies: Boolean? = null,
    val trustedProxies: List<String> = emptyList(),
    val loginRatePerMinute: Int = 20,
    val lockoutDistinctIps: Int = 5,
    val authFailureRowCap: Int = 10_000,
    /** 0 disables the absolute session lifetime. Default 12 hours. */
    val absoluteSessionMinutes: Int = 12 * 60,
    /** Listen port for HTTPS. 0 asks the kernel for a port. Default 8043. Ignored until [httpsEnabled]. */
    val httpsPort: Int = DEFAULT_HTTPS_PORT,
    /** Hostname or IP clients should use. No scheme, path, or port. */
    val publicAddress: String? = null,
    /** Port written into the HTTPS redirect. Null uses the bound HTTPS port. 443 is omitted from the URL. */
    val publicHttpsPort: Int? = null,
    val forceHttpsRedirect: Boolean = false,
    val certificatePath: String? = null,
    val privateKeyPath: String? = null,
    val pkcs12Path: String? = null,
    val opcua: OpcUaServerSettings = OpcUaServerSettings(),
    val network: LinkSettings = LinkSettings(),
    val redundancy: RedundancySettings = RedundancySettings(),
    val edgeSync: EdgeSyncSettings = EdgeSyncSettings(),
    val limits: EdgeLimits = EdgeLimits(),
    /** Plain-text rolling log files under `logs/` (GAT-025). */
    val logging: LogFileSettings = LogFileSettings(),
    /** PLC scan-engine bridge. Off unless `plc.enabled` is set. */
    val plc: PlcSettings = PlcSettings(),
) {
    init {
        require(httpsPort in 0..65535) { "http.httpsPort out of range: $httpsPort" }
        if (publicHttpsPort != null) require(publicHttpsPort in 1..65535) { "http.publicHttpsPort out of range: $publicHttpsPort" }
        if (forceHttpsRedirect && !httpsEnabled) {
            throw IllegalArgumentException("http.forceHttpsRedirect requires http.https")
        }
        if (httpsEnabled && port != 0 && httpsPort != 0 && port == httpsPort) {
            throw IllegalArgumentException("http.port and http.httpsPort must differ")
        }
        val pem = !certificatePath.isNullOrBlank() || !privateKeyPath.isNullOrBlank()
        if (pem && !pkcs12Path.isNullOrBlank()) {
            throw IllegalArgumentException("set http.certificate and http.privateKey, or http.pkcs12, not both")
        }
        if (certificatePath.isNullOrBlank() != privateKeyPath.isNullOrBlank()) {
            throw IllegalArgumentException("http.certificate and http.privateKey must be set together")
        }
    }
    val secureCookiesEffective: Boolean
        get() = secureCookies ?: (httpsEnabled || trustedProxies.isNotEmpty())

    fun sessionAbsolute(): Duration =
        if (absoluteSessionMinutes <= 0) Duration.ZERO else Duration.ofMinutes(absoluteSessionMinutes.toLong())

    fun authLimits(): AuthLimits = AuthLimits(
        permitsPerMinute = loginRatePerMinute,
        distinctIpCap = lockoutDistinctIps,
        maxFailureRows = authFailureRowCap,
    )

    /** Plain HTTP with no HTTPS and no proxy: the normal state of a new install. Not shown to a browser on this machine. */
    fun plainHttpOnly(): Boolean = !secureCookiesEffective && !(secureCookies == false && (httpsEnabled || trustedProxies.isNotEmpty()))

    fun insecureCookieWarning(): String? = when {
        secureCookiesEffective -> null
        secureCookies == false && (httpsEnabled || trustedProxies.isNotEmpty()) ->
            "Session cookies are not marked Secure (http.secureCookies is false) while HTTPS or a trusted proxy is configured. Browsers on HTTPS will not store them."
        else ->
            "Session cookies are not marked Secure because this listener is plain HTTP and no trusted proxy is configured. " +
                "Set http.https or http.trustedProxies when clients connect with HTTPS, or http.secureCookies: true to force the flag. " +
                "See docs/ops/http-auth.md."
    }

    companion object {
        val PROFILES = listOf("edge", "standard", "hub")
        private val KEYS = setOf("profile", "http", "features", "opcua", "gatewayNetwork", "redundancy", "edgeSync", "limits", "logging", "plc")
        const val DEFAULT_HTTPS_PORT = 8043
        private val HTTP_KEYS = setOf(
            "host", "port", "https", "httpsPort", "publicAddress", "publicHttpsPort", "forceHttpsRedirect",
            "certificate", "privateKey", "pkcs12",
            "secureCookies", "trustedProxies",
            "loginRatePerMinute", "lockoutDistinctIps", "authFailureRowCap", "absoluteSessionMinutes",
        )
        private val yaml = ObjectMapper(YAMLFactory())

        fun load(file: Path, rawEnv: Map<String, String> = System.getenv()): GatewaySettings {
            val env = LegacyEnv.normalize(rawEnv)
            val user = map(yaml.readValue(file.readText(), Any::class.java) ?: emptyMap<String, Any?>(), "gateway.yaml")
            val unknown = user.keys - KEYS
            require(unknown.isEmpty()) { "unknown gateway.yaml keys: $unknown" }
            val profile = env["OIP_PROFILE"] ?: user["profile"] as? String ?: "standard"
            require(profile in PROFILES) { "unknown profile '$profile' (expected one of $PROFILES)" }
            val defaults = map(yaml.readValue(GatewaySettings::class.java.getResource("/profiles/$profile.yaml")!!, Any::class.java), profile)
            val http = map(defaults["http"], "http") + map(user["http"] ?: emptyMap<String, Any?>(), "http")
            val unknownHttp = http.keys - HTTP_KEYS
            require(unknownHttp.isEmpty()) { "unknown gateway.yaml http keys: $unknownHttp" }
            val features = when {
                env.containsKey("OIP_FEATURES") -> env.getValue("OIP_FEATURES").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                user["features"] != null -> (user["features"] as? List<*>)?.map { it.toString() } ?: emptyList()
                else -> (defaults["features"] as? List<*>)?.map { it.toString() } ?: emptyList()
            }
            var host = http["host"] as? String ?: "0.0.0.0"
            var port = (http["port"] as? Number)?.toInt() ?: throw IllegalArgumentException("http.port must be a number")
            env["OIP_HTTP_HOST"]?.let { host = it }
            env["OIP_HTTP_PORT"]?.let { port = it.toIntOrNull() ?: throw IllegalArgumentException("OIP_HTTP_PORT must be a number") }
            require(port in 0..65535) { "http.port out of range: $port" }
            val proxies = stringList(http["trustedProxies"], "http.trustedProxies")
            proxies.forEach { require(ClientAddresses.validRule(it)) { "http.trustedProxies entry is not an IP or IPv4 CIDR: $it" } }
            val opcuaRaw = if (user.containsKey("opcua")) map(user["opcua"], "opcua") else emptyMap()
            var opcua = OpcUaServerSettings.parse(opcuaRaw, env)
            val networkRaw = if (user.containsKey("gatewayNetwork")) map(user["gatewayNetwork"], "gatewayNetwork") else emptyMap()
            var network = if (user.containsKey("gatewayNetwork")) LinkSettings.parse(networkRaw) else LinkSettings()
            // Test JVMs set this so they do not collide with a gateway already bound to the defaults.
            // A port written in gateway.yaml, or OIP_OPCUA_PORT, still wins. The defaults stay 4840 and 8060.
            if (env["ERIC_TEST_EPHEMERAL_LISTENERS"] == "true") {
                if (!opcuaRaw.containsKey("port") && env["OIP_OPCUA_PORT"] == null) opcua = opcua.copy(port = 0)
                if (!networkRaw.containsKey("port")) network = network.copy(port = 0)
            }
            val redundancy = if (user.containsKey("redundancy")) {
                RedundancySettings.parse(map(user["redundancy"], "redundancy"))
            } else {
                RedundancySettings()
            }
            val edgeSync = if (user.containsKey("edgeSync")) {
                EdgeSyncSettings.parse(map(user["edgeSync"], "edgeSync"))
            } else {
                EdgeSyncSettings()
            }
            // Profile defaults first, then gateway.yaml keys override them one by one (GAT-004).
            val limits = EdgeLimits.parse(
                map(defaults["limits"] ?: emptyMap<String, Any?>(), "limits") +
                    map(user["limits"] ?: emptyMap<String, Any?>(), "limits"),
            )
            val logging = LogFileSettings.parse(map(user["logging"] ?: emptyMap<String, Any?>(), "logging"))
            val plc = if (user.containsKey("plc")) PlcSettings.parse(map(user["plc"], "plc")) else PlcSettings()
            val httpsEnabled = envBool(env, "OIP_HTTP_HTTPS") ?: bool(http["https"], "http.https") ?: false
            val httpsPort = envInt(env, "OIP_HTTP_HTTPS_PORT") ?: number(http, "httpsPort", DEFAULT_HTTPS_PORT, 0, 65535)
            val publicHttpsPort = envInt(env, "OIP_HTTP_PUBLIC_HTTPS_PORT") ?: optionalNumber(http, "publicHttpsPort", 1, 65535)
            var certificatePath = filePath(env, "OIP_HTTPS_CERT", http, "certificate")
            var privateKeyPath = filePath(env, "OIP_HTTPS_KEY", http, "privateKey")
            var pkcs12Path = filePath(env, "OIP_HTTPS_PKCS12", http, "pkcs12")
            // An env-selected format replaces the other format from the file so compose can switch without editing yaml.
            if (envText(env, "OIP_HTTPS_PKCS12") != null) {
                certificatePath = null
                privateKeyPath = null
            } else if (envText(env, "OIP_HTTPS_CERT") != null || envText(env, "OIP_HTTPS_KEY") != null) {
                pkcs12Path = null
            }
            return GatewaySettings(
                profile, host, port, features,
                httpsEnabled = httpsEnabled,
                secureCookies = bool(http["secureCookies"], "http.secureCookies"),
                trustedProxies = proxies,
                loginRatePerMinute = number(http, "loginRatePerMinute", 20, 0, 100_000),
                lockoutDistinctIps = number(http, "lockoutDistinctIps", 5, 1, 10_000),
                authFailureRowCap = number(http, "authFailureRowCap", 10_000, 1, 1_000_000),
                absoluteSessionMinutes = number(http, "absoluteSessionMinutes", 12 * 60, 0, 7 * 24 * 60),
                httpsPort = httpsPort,
                publicAddress = publicAddress(envText(env, "OIP_HTTP_PUBLIC_ADDRESS") ?: optionalText(http, "publicAddress")),
                publicHttpsPort = publicHttpsPort,
                forceHttpsRedirect = envBool(env, "OIP_HTTP_FORCE_REDIRECT") ?: bool(http["forceHttpsRedirect"], "http.forceHttpsRedirect") ?: false,
                certificatePath = certificatePath,
                privateKeyPath = privateKeyPath,
                pkcs12Path = pkcs12Path,
                opcua = opcua,
                network = network,
                redundancy = redundancy,
                edgeSync = edgeSync,
                limits = limits,
                logging = logging,
                plc = plc,
            )
        }
        @Suppress("UNCHECKED_CAST")
        private fun map(v: Any?, what: String): Map<String, Any?> =
            v as? Map<String, Any?> ?: throw IllegalArgumentException("$what must be a mapping")

        private fun bool(v: Any?, what: String): Boolean? = when (v) {
            null -> null
            is Boolean -> v
            else -> throw IllegalArgumentException("$what must be true or false")
        }

        private fun number(http: Map<String, Any?>, key: String, default: Int, min: Int, max: Int): Int {
            val raw = http[key] ?: return default
            val n = (raw as? Number)?.toInt() ?: throw IllegalArgumentException("http.$key must be a number")
            require(n in min..max) { "http.$key out of range: $n" }
            return n
        }

        private fun stringList(v: Any?, what: String): List<String> = when (v) {
            null -> emptyList()
            is List<*> -> v.map { it as? String ?: throw IllegalArgumentException("$what entries must be strings") }
            else -> throw IllegalArgumentException("$what must be a list")
        }

        private fun optionalNumber(http: Map<String, Any?>, key: String, min: Int, max: Int): Int? {
            if (http[key] == null) return null
            return number(http, key, 0, min, max)
        }

        private fun envBool(env: Map<String, String>, key: String): Boolean? {
            val raw = env[key]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return when (raw.lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> throw IllegalArgumentException("$key must be true or false")
            }
        }

        private fun envInt(env: Map<String, String>, key: String): Int? {
            val raw = env[key]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return raw.toIntOrNull() ?: throw IllegalArgumentException("$key must be a number")
        }

        private fun envText(env: Map<String, String>, key: String): String? =
            env[key]?.trim()?.takeIf { it.isNotEmpty() }

        private fun optionalText(http: Map<String, Any?>, key: String): String? {
            val raw = http[key] ?: return null
            val text = raw as? String ?: throw IllegalArgumentException("http.$key must be a string")
            return text.trim().ifEmpty { null }
        }

        private fun filePath(env: Map<String, String>, envKey: String, http: Map<String, Any?>, key: String): String? {
            val value = envText(env, envKey) ?: optionalText(http, key) ?: return null
            if (value.contains('\n') || value.contains("PRIVATE KEY") || value.contains("-----")) {
                throw IllegalArgumentException("http.$key must be a file path")
            }
            return value
        }

        private fun publicAddress(raw: String?): String? {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            require(value.length <= 253) { "http.publicAddress is too long" }
            require(!value.contains("://") && !value.contains('/') && !value.contains('\\') && !value.contains('@') && !value.contains(' ')) {
                "http.publicAddress must be a hostname or IP, not a URL"
            }
            if (value.startsWith("[")) {
                require(value.endsWith("]") && value.length > 2) { "http.publicAddress IPv6 must be [address]" }
                return value
            }
            require(!value.contains(':')) { "http.publicAddress must not include a port; set http.publicHttpsPort" }
            require(value.matches(Regex("""[A-Za-z0-9._-]+"""))) { "http.publicAddress has an invalid character" }
            if (value.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) {
                require(value.split('.').all { it.toIntOrNull() in 0..255 }) { "http.publicAddress is not an IPv4 address" }
            }
            return value
        }
    }
}

/**
 * The gateway process. Data dir layout: `gateway.yaml`, `config/` (ConfigStore root), `modules/` (installed
 * module packages, one dir per module), `db/` (embedded runtime DB, ADR 0008), `logs/`,
 * `installed-modules.tsv` (module registry), `backups/` (archives created through the API),
 * `opcua/` (server certificate and client-certificate trust lists, when the OPC UA server has started),
 * `https/` (web-server certificate and private key, mode 0600, when HTTPS has been enabled or a certificate stored),
 * `gateway-network/` (gateway-network identity key and peer certificates, when the listener has started),
 * `secrets/gateway.key` (secrets key, directory mode 0700, file mode 0600; backup entry `secrets.key`).
 * [port] overrides the configured HTTP port (0 = ephemeral) and wins over `OIP_HTTP_PORT`.
 * [env] supplies `OIP_SECRETS_KEY` and `OIP_SECRETS_KEY_FILE`. When both are absent the key file above is used.
 */
class Gateway(
    val dataDir: Path,
    port: Int? = null,
    val clock: Clock = Clock.systemUTC(),
    sessionPolicyOverride: SessionPolicy? = null,
    adminDist: Path? = AdminUi.locate(),
    env: Map<String, String> = System.getenv(),
    /** Built `web/designer` bundle served at `/hmi` and `/studio` ([WebUi]); `OIP_WEB_DIST` by default. */
    webDist: Path? = WebUi.locate(),
) : AutoCloseable {
    val settings: GatewaySettings
    val sessionPolicy: SessionPolicy
    val insecureCookieWarning: String?
    val store: FileConfigStore
    val security: SecurityService
    val httpAuth: HttpAuthStore
    /** Identity-provider login and provider bearer tokens (SEC-013, P1-32). */
    val idp: IdpLogin by lazy { IdpLogin(this) }
    /** Security level rules evaluated at every login (SEC-016). */
    val loginLevels: LoginLevels by lazy { LoginLevels({ security }, clock) }
    /** LDAP, database and hybrid user source logins (SEC-007, SEC-010, SEC-021, SEC-025), over the named connections. */
    val userSourceLogin: org.oip.security.UserSourceAuthenticator by lazy {
        org.oip.security.UserSourceAuthenticator(
            security.userSources,
            security.secrets,
            { security.roles.list().mapTo(HashSet()) { it.name } },
            { UserSourceDatabases(databases) },
            security.audit,
            { security.auditProfile.get().enabled },
            clock,
        )
    }
    val host: ModuleHost
    val runtime: RuntimeDatabase
    val backups: BackupService
    val projects: ProjectExchange
    /** Project settings, inheritance, and project CRUD (PRO-002, PRO-004). */
    val projectService: org.oip.config.ProjectService
    val logs: StructuredLog
    val https: HttpsEndpoint
    val diagnostics: Diagnostics
    val tags: TagEngine
    /**
     * Memory-tag values kept across restarts (TAG-016) in `db/memory-tags.sqlite`. Opened before the tag
     * configuration loads so configured memory tags restore their last value, flushed and closed by [close].
     */
    val memoryTags: MemoryTagPersistence
    val network: GatewayNetwork
    val databases: DatabaseService
    /** Where the alarm journal stores its rows (DAT-015). */
    val journalStorage: JournalStorageBinding
    val scripts: ScriptService
    private var tagAlarmWatch: AutoCloseable? = null
    val charts: SfcEngine
    val events: EventStreamEngine
    val webdev: WebDevService
    /** Gateway-wide settings resource (GAT-035): system name, script message encoding, client launch defaults. */
    val platformSettings: PlatformSettingsService
    val alarms: AlarmEngine
    /** The built-in `local` provider: the embedded, partitioned historian (FEATURES HIS-007). */
    val historyStore: EmbeddedHistorian
    /** Gateway-wide store-and-forward engine settings, from `historian/StoreAndForward/engine` (FEATURES HIS-024). */
    @Volatile var storeAndForwardSettings: StoreAndForwardSettings = StoreAndForwardSettings()
        private set
    val historyCollector: HistoryCollector
    /** Routes samples per tag to the local store or, through store-and-forward, to SQL providers. */
    val historyRouter: HistoryRouter
    val diskCacheArchives: org.oip.historian.DiskCacheArchives
    private val historyProviderBinding: HistoryProviderBinding
    private val historyBinding: HistoryConfigBinding
    private val udtMemberHistory: org.oip.historian.UdtMemberHistory
    private var historyRunner: HistoryRunner? = null
    val redundancy: RedundancyRuntime
    val edgeSync: EdgeSyncRuntime
    /** Enterprise administration: controller service and agent loop (EAM-001..EAM-017), state under `eam/`. */
    val eam: EamRuntime
    /** Designers connected now (GAT-028): in-memory, kept alive by heartbeat. */
    val designerSessions: DesignerSessions = DesignerSessions(clock)
    /** Resources Designers are editing now (SEC-020): in-memory, each lock lives as long as its Designer session. */
    val designerLocks: DesignerLocks = DesignerLocks(clock, designerSessions)
    /** Project permissions, protected resources and locks applied to every project write (SEC-020). */
    val designerGuard: DesignerProjectGuard by lazy { DesignerProjectGuard(projectService, security.authorization, designerLocks) }
    private val processEnv: Map<String, String> = env
    val limitGuard: LimitGuard by lazy { LimitGuard(settings.limits, store, dataDir) }
    val notifications: NotificationService
    /**
     * Reports (M5.3): `reporting/Report` config resources, run history in db/report-runs.sqlite, and the cron
     * scheduler (started by [start], stopped by [close]).
     */
    val reports: ReportService
    private var reportScheduler: ReportScheduler? = null
    /**
     * Per-tag read check for report history sources, applied to each tag before its history is read. A user caller
     * needs `tags.read` and must pass the tag's read permissions ([TagEngine.mayRead] with the caller's roles and
     * zone, S-1): no permission = any reader, one naming nobody = nobody, names compare ignoring case,
     * administrators get no implicit pass, API keys carry no roles. A tag the engine no longer has (history only)
     * is refused unless the caller also holds `gateway.write`, the permission that edits tag configuration.
     * Replaceable for tests.
     */
    @Volatile var reportTagCheck: TagReadCheck = TagReadCheck { caller, path ->
        caller.system || (Permissions.TAGS_READ in caller.permissions &&
            tags.mayRead(path, TagCaller.User(caller.name, caller.roles.mapTo(HashSet()) { it.lowercase() }, caller.zone ?: SecurityZone.DEFAULT_NAME,
                configAdmin = Permissions.GATEWAY_WRITE in caller.permissions)))
    }
    /** Scheduled report PDFs under reports/<name>/ (C-9b), served by `GET /api/v1/reports/{name}/files`. */
    val reportArchive = PdfArchiveSink(dataDir.resolve("reports"))
    /** Where scheduled report results go; default renders each to PDF into [reportArchive]. Replaceable for tests. */
    @Volatile var reportSink: ReportResultSink = reportArchive
    val app: Javalin

    /** The transaction group engine (also reached by `system.groups`). */
    val transactionGroups: org.oip.db.groups.TransactionGroups
    /** HMI sessions on `/api/v1/ws` (M4.1), backed by [tags]. */
    val sessions: SessionHost
    /** What a session reads to run a project, with change notification (review-4 F5, F12). */
    val hmiProjects: HmiProjectApi
    /** Project HMI script hooks run for session users over [sessions] (D-5a). */
    val hmiScripts: HmiScriptRunner
    /** Non-null after [start] when `opcua.enabled` is true. */
    var opcUa: OpcUaTagServer? = null
        private set
    /**
     * Client-certificate trust for the OPC UA server under `opcua/pki`. Opened on first use (the trust API,
     * or server start), so a gateway with the server off and no trust calls has no pki directory.
     */
    /** Trusted and rejected server certificates for the gateway's own outbound TLS clients (SEC-022). */
    val outboundTrust: OutboundTrust by lazy { OutboundTrust(dataDir, clock) }
    /** OAuth2 client registrations and their token cache (SEC-029), shared by the outbound services. */
    val oauth2Clients: org.oip.security.OAuth2TokenService by lazy { org.oip.security.OAuth2TokenService(store, security.secrets, clock) }
    private var outboundTlsHandle: AutoCloseable? = null
    val opcUaTrust: OpcUaCertificateTrust by lazy { OpcUaCertificateTrust.open(dataDir.resolve("opcua")).also { trustOpened = true } }
    @Volatile private var trustOpened = false
    private var systemTags: SystemTags? = null
    private val tagBinding: TagConfigBinding
    @Volatile private var drivers: TagDriverBridge? = null
    private var plcBridge: PlcBridge? = null
    val logic: LogicService = LogicService(dataDir.resolve("logic"), engine = PlcControl.fromEnvironment())
    val plcResources: PlcResourceService = PlcResourceService(dataDir.resolve("plc-resources"))
    private val builtIns = BuiltInModules(dataDir.resolve("modules"), dataDir.resolve(BuiltInModules.REMOVED_FILE))

    /**
     * Built-in driver install state for `/api/v1/modules` (review-1 F5). An uninstall is recorded so the next
     * start does not install the driver again; an install writes its manifest back and starts it. Either one
     * rebinds the tag drivers, so tags follow the driver instance that is installed now.
     */
    val builtInModules: BuiltInModuleControl = object : BuiltInModuleControl {
        override fun isBuiltIn(id: String) = builtIns.isBuiltIn(id)
        override fun available() = builtIns.removed().filter { host.state(it.id) == null }
        override fun removed(id: String) {
            builtIns.markRemoved(id)
            rebindDrivers()
        }
        @OptIn(ExperimentalPathApi::class)
        override fun install(id: String): org.oip.sdk.ModuleManifest {
            builtIns.restore(id)
            try {
                host.discover()
                host.install(id)
            } catch (t: Throwable) {
                builtIns.markRemoved(id)
                dataDir.resolve("modules").resolve(id).takeIf { host.state(id) == null }?.deleteRecursively()
                throw t
            }
            prepareDrivers()
            host.start(id)
            rebindDrivers()
            return host.manifest(id)!!
        }
    }
    private var scheduler: java.util.concurrent.ScheduledExecutorService? = null
    private var alarmScheduler: java.util.concurrent.ScheduledExecutorService? = null
    private var expressionTicker: java.util.concurrent.ScheduledExecutorService? = null
    /** Query tags (TAG-030). Attached in init so config loaded with the tags is picked up; the ticker starts in [start]. */
    private var queryTags: QueryTagRuntime? = null
    private val control = GatewayControl(dataDir)
    private var started = false
    private var closed = false

    /** When [start] was called, from [clock]. Reported as `startedAt` on `GET /api/v1/health`. */
    @Volatile
    var startedAt: java.time.Instant? = null
        private set

    init {
        for (d in LAYOUT) dataDir.resolve(d).createDirectories()
        val gy = dataDir.resolve("gateway.yaml")
        if (!gy.exists()) gy.writeText(
            "# Gateway settings; profile is one of edge, standard, hub.\n" +
                "# Optional http keys: https, httpsPort, publicAddress, publicHttpsPort, forceHttpsRedirect,\n" +
                "# certificate, privateKey, pkcs12, secureCookies, trustedProxies, loginRatePerMinute,\n" +
                "# lockoutDistinctIps, authFailureRowCap, absoluteSessionMinutes.\n" +
                "# See docs/ops/http-auth.md and docs/ops/https.md.\n" +
                "# opcua keys: enabled (default true), bindAddress (default 127.0.0.1), port (default 4840), hostname.\n" +
                "# gatewayNetwork keys: enabled (default true), port (default 8060), name, pingRateMillis,\n" +
                "# pingTimeoutMillis, missedPings. See docs/ops/gateway-network.md.\n" +
                "# redundancy keys: mode (independent, master, backup), standbyActivityLevel, recoveryMode,\n" +
                "# masterHost, masterPort, pingRateMillis, pingTimeoutMillis, pingMaxMissed, historyMode.\n" +
                "# See docs/ops/redundancy.md.\n" +
                "# edgeSync keys: remoteGateway, history, audit, alarmJournal. See docs/ops/edge-sync.md.\n" +
                "# limits keys: maxDevices, maxStorageMb (0 = no cap; the edge profile sets defaults). See docs/ops/edge-profile.md.\n" +
                "# Admin UI: set OIP_ADMIN_DIST to the built web/admin/dist directory. It is served at /admin.\n" +
                "# HMI sessions and Studio: set OIP_WEB_DIST to the built web/designer/dist directory (/hmi, /studio).\n" +
                "profile: standard\n"
        )
        settings = GatewaySettings.load(gy).let { if (port != null) it.copy(port = port) else it }
        sessionPolicy = sessionPolicyOverride ?: SessionPolicy(settings.sessionAbsolute())
        insecureCookieWarning = settings.insecureCookieWarning()
        insecureCookieWarning?.let { msg ->
            val log = LoggerFactory.getLogger(Gateway::class.java)
            // Plain HTTP on a new install is expected; Main prints a calm note. Other cases stay a warning.
            if (settings.plainHttpOnly()) log.info(msg) else log.warn(msg)
        }
        val schemas = SchemaRegistry()
        store = FileConfigStore(dataDir.resolve("config"), schemas, clock)
        tags = TagEngine(clock)
        network = GatewayNetwork(dataDir.resolve("gateway-network"), tags, settings.network, VERSION, settings.host)
        memoryTags = MemoryTagPersistence.open(tags, dataDir.resolve("db").resolve(MemoryTagPersistence.FILE_NAME))
        tagBinding = TagConfigBinding(tags, store, schemas).also { it.attach() }
        // Rows no configured tag claimed belong to tags deleted while the gateway was down.
        memoryTags.pruneUnclaimed()
        registerDesignerSchemas(schemas)
        store.revalidate(DESIGNER_MODULE)
        PlatformSettings.register(schemas)
        MetricsDashboard.register(schemas)
        org.oip.config.ProjectService.register(schemas)
        HomeSetup.register(schemas)
        EamSchemas.register(schemas)
        store.revalidate(org.oip.config.GATEWAY_MODULE)
        projectService = org.oip.config.ProjectService(store).also { it.attach() }
        platformSettings = PlatformSettingsService(store)
        security = SecurityService.open(store, dataDir, SecretKeySource { SecretsKeyFile.load(dataDir, env) }, clock)
        // User tag writes (REST, OPC UA) append one audit record each, after the engine lock is released (TAG-013),
        // while the audit profile is enabled, like the gateway's other audit records.
        tags.audit = security.audit
        tags.auditEnabled = { security.auditProfile.get().enabled }
        httpAuth = HttpAuthStore(dataDir.resolve("db").resolve("http-auth.sqlite"), clock, settings.authLimits())
        bootstrapAdmin()
        host = ModuleHost(dataDir.resolve("modules"), dataDir.resolve("config"),
            registryFile = dataDir.resolve("installed-modules.tsv"), store = store)
        runtime = RuntimeDatabase(dataDir.resolve("db/runtime.db"))
        alarms = AlarmEngine(clock, AlarmJournal(dataDir.resolve("db/alarm-journal.sqlite"), clock = clock))
        alarms.attach(tags)
        network.attachAlarms(alarms) { event ->
            if (!security.auditProfile.get().enabled) return@attachAlarms
            security.audit.append(
                AuditDraft(
                    timestampMillis = clock.millis(),
                    actor = event.peerName.ifBlank { event.fingerprint },
                    action = AuditActions.ALARM_ACKNOWLEDGED,
                    status = if (event.success) AuditStatus.SUCCESS else AuditStatus.FAILURE,
                    actorHost = event.fingerprint,
                    target = event.eventIds.joinToString(",").take(500),
                    detail = "user=${event.user};${event.detail}".take(500),
                    origin = "gateway-network",
                    context = "alarms",
                ),
            )
        }
        redundancy = RedundancyRuntime(settings.redundancy, network, store, tags, alarms)
        edgeSync = EdgeSyncRuntime(
            settings.edgeSync,
            network,
            tags,
            store,
            security.audit,
            alarms.journal,
            dataDir.resolve("db/edge-sync.sqlite"),
            clock,
        )
        network.extraServices += redundancy.service
        network.extraServices += edgeSync.service
        eam = EamRuntime(this, dataDir.resolve("eam"))
        network.extraServices += eam.controller.service
        network.linkListener = { name, up ->
            redundancy.onLink(name, up)
            edgeSync.onLink(name, up)
            eam.onLink(name, up)
        }
        // The same key the secret store uses (secrets/gateway.key, or OIP_SECRETS_KEY / OIP_SECRETS_KEY_FILE).
        // Certificate keys in a backup are sealed under it; a missing key is not created here.
        backups = BackupService(
            dataDir, store, runtime, VERSION,
            SecretKeySource { SecretsKeyFile.load(dataDir, env, createFileIfMissing = false) },
        )
        backups.releaseSecurityFiles = { security.releaseLiveFiles() }
        backups.reopenSecurityFiles = { security.reopenLiveFiles() }
        backups.releaseReplacedDirs = {
            runCatching { opcUa?.close() }
            opcUa = null
            if (trustOpened) runCatching { opcUaTrust.close() }
            runCatching { https.releaseFiles() }
        }
        projects = ProjectExchange(store)
        logs = StructuredLog(dataDir.resolve("logs"), text = RollingTextLog.of(dataDir.resolve("logs"), settings.logging))
        notifications = NotificationService(
            clock = clock,
            store = store,
            alarms = alarms,
            users = {
                security.users.list().map { user ->
                    DirectoryUser(user.username, user.displayName, user.email, user.phone, user.enabled)
                }
            },
            secrets = { ref -> security.secrets.getText(SecretReferences.parse(ref)) },
            log = { message, fields -> logs.log(LogLevel.INFO, "org.oip.notify", message, fields) },
            // Review-3 F9: two-way email links expire, and are kept across restarts (digests only).
            links = org.oip.alarm.notify.AckLinks(clock, dataDir.resolve("db/alarm-ack-links.sqlite")),
            clientTokens = { client, scope -> oauth2Clients.accessToken(client, scope.ifBlank { null }) },
        ).also { it.attach() }
        // Each notification attempt is one audit record while the audit profile is enabled (ALA-036).
        notifications.auditSink = { sent ->
            if (security.auditProfile.get().enabled) {
                security.audit.append(
                    AuditDraft(
                        timestampMillis = clock.millis(),
                        actor = sent.profile,
                        action = AuditActions.ALARM_NOTIFICATION,
                        status = if (sent.outcome == "failed") AuditStatus.FAILURE else AuditStatus.SUCCESS,
                        target = sent.source.take(500),
                        detail = "recipient=${sent.recipient};event=${sent.eventId};alarm=${sent.alarm};outcome=${sent.outcome}" +
                            (if (sent.detail.isBlank()) "" else ";${sent.detail}"),
                        origin = "alarm-notification",
                        context = "alarms",
                    ),
                )
            }
        }
        notifications.remoteSink = { notice ->
            network.deliverNotification(
                notice.connection,
                org.oip.network.RemoteNotification(
                    profile = notice.remoteProfile,
                    eventId = notice.eventId,
                    source = notice.source,
                    displayPath = notice.displayPath,
                    priority = notice.priority,
                    state = notice.state,
                    active = notice.active,
                    sender = "",
                ),
            )
            Unit
        }
        network.notificationSink = { notice ->
            notifications.acceptRemote(
                notice.profile,
                notice.eventId,
                notice.source,
                notice.displayPath,
                notice.priority,
                notice.state,
                notice.active,
                notice.sender,
            )
        }
        databases = DatabaseService(
            store,
            dataDir,
            clock,
            readSecret = { reference -> security.secrets.getText(SecretReferences.parse(reference)) },
            writeSecret = { name, plaintext, description -> security.secrets.putText(name, plaintext, description) },
        ).also {
            it.projectChain = { project -> projectService.chain(project).ifEmpty { listOf(project) } }
            it.attach()
        }
        databases.slowQuery = { query, millis ->
            logs.log(LogLevel.WARN, "org.oip.db", "slow query", mapOf("query" to query, "millis" to millis))
        }
        // DAT-015: the alarm journal rows go to the named connection `alarm/AlarmJournal/journal` names, through the
        // same pool as history providers, named queries and reports. No resource keeps them in db/alarm-journal.sqlite.
        journalStorage = JournalStorageBinding(alarms.journal, store) { connection ->
            object : JournalConnectionSource {
                override fun borrow() = databases.borrowConnection(connection)
                override fun release(connection: java.sql.Connection, broken: Boolean) = databases.releaseConnection(connection, broken)
            }
        }.also { it.attach() }
        databases.usage = { connection -> connectionUsage(connection) }
        queryTags = QueryTagRuntime(tags, databases, clock).also { it.attach() }
        scripts = ScriptService(
            tags,
            databases,
            clock,
            logger = ScriptLogger { level, name, message ->
                val mapped = if (level == "FATAL") LogLevel.ERROR else LogLevel.parse(level)
                val fields = if (level == "FATAL") mapOf("fatal" to true) else emptyMap()
                logs.log(mapped, "oip.script.$name", message, fields)
            },
        ).also {
            it.projectChain = { project -> projectService.chain(project).ifEmpty { listOf(project) } }
            it.attach(store)
        }
        tagAlarmWatch = alarms.listen { notice -> scripts.onTagAlarm(notice.source, notice.name, notice.trigger, notice.value, notice.scriptEvent()) }
        val scriptFiles = dataDir.resolve("files")
        scripts.prepareHost = { host ->
            host.fileRoot = scriptFiles
            host.alarmQuery = { source ->
                val events = alarms.query().filter { source.isEmpty() || it.source.equals(source, ignoreCase = true) }
                com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(events.take(200).map { event ->
                    mapOf(
                        "eventId" to event.eventId,
                        "source" to event.source,
                        "name" to event.name,
                        "active" to event.active,
                        "acknowledged" to event.acknowledged,
                        "state" to event.state,
                        "priority" to event.priority,
                    )
                })
            }
            host.emailSend = { profile, to, from, subject, body, html ->
                notifications.mailProfiles.send(
                    profile,
                    org.oip.alarm.notify.OutboundMail(
                        from = from, to = to, subject = subject, body = body, testMode = false, eventId = "", username = "",
                        profile = profile, ackLink = false, emailProfile = null, smtpProfile = profile, html = html,
                    ),
                )
            }
            host.alarmAcknowledge = { ids, user, notes -> alarms.acknowledge(ids, user, notes) }
            host.alarmShelve = { source, minutes, user ->
                alarms.shelve(listOf(source), java.time.Duration.ofMinutes(minutes), user)
            }
            host.alarmJournal = { source ->
                val page = alarms.journal.query(org.oip.alarm.JournalQuery(source = source.ifBlank { null }, limit = 100))
                com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(page.records.map { row ->
                    mapOf("eventId" to row.eventId, "source" to row.source, "displayPath" to row.displayPath, "eventTime" to row.eventTimeMillis)
                })
            }
            ScriptLibraries.attach(host, this)
        }
        reportSink = ReportDelivery(reportArchive, dataDir.resolve("print"), readSecret = { reference ->
            security.secrets.getText(SecretReferences.parse(reference))
        }, mailer = { profile, from, to, subject, filename, pdf ->
            // One shared lookup of SMTP profiles for alarms, reports and scripts (GAT-030).
            notifications.mailProfiles.send(
                profile,
                org.oip.alarm.notify.OutboundMail(
                    from = from, to = to, subject = subject, body = "Report attached: $filename", testMode = false,
                    eventId = "", username = "", profile = profile, ackLink = false, emailProfile = null, smtpProfile = profile,
                    attachment = org.oip.alarm.notify.MailAttachment(filename, "application/pdf", pdf),
                ),
            )
        })
        scripts.eamCall = { function, args -> eam.script(function, args) }
        scripts.gatewayStatus = {
            val started = startedAt
            mapOf(
                "state" to if (started == null) "starting" else "running",
                "name" to platformSettings.get().systemName,
                "uptimeMillis" to if (started == null) 0L else clock.millis() - started.toEpochMilli(),
            )
        }
        val offTagThread = { tags.holdingWriteLock() || Thread.currentThread().name.startsWith("tag-") }
        charts = SfcEngine(clock, tags, store, FileChartStateStore(dataDir.resolve("sfc"))).also {
            it.deferWhen = offTagThread
            it.scripts = SfcChartScripts(scripts)
            it.attach()
        }
        events = EventStreamEngine(clock, tags, store).also {
            it.deferWhen = offTagThread
            it.scripts = EventStreamScripts(scripts)
            it.kafka = ApacheKafkaClients { reference -> security.secrets.getText(SecretReferences.parse(reference)) }
            it.query = NamedUpdate { name, params, roles ->
                when (val result = databases.execute(name, params, roles)) {
                    is QueryResult.Update -> result.rowsAffected
                    else -> throw IllegalStateException("event stream database handler requires an update query")
                }
            }
            it.log = { level, logger, message ->
                logs.log(logLevel(level), logger, message, emptyMap())
            }
            it.gatewayEvents = org.oip.events.GatewayEvents { handler, payload -> scripts.postMessage(handler, payload) }
            it.attach()
        }
        webdev = WebDevService(store, dataDir.resolve("webdev"), WebDevScripts(scripts)).also { it.attach() }
        https = HttpsEndpoint(settings, dataDir.resolve("https")) { port() }
        https.replaced = { actor, fingerprint, certSource -> recordHttpsAudit(actor, fingerprint, certSource) }
        backups.afterRestore = {
            runCatching { https.reloadStored() }.onFailure {
                logs.log(LogLevel.WARN, "org.oip.https", "restored https certificate not loaded", mapOf("error" to it.javaClass.simpleName))
            }
            // The restore renamed the archived audit database and anchor over the live ones (review-1 F4).
            runCatching { security.reloadAfterRestore(SecretKeySource { SecretsKeyFile.load(dataDir, env, createFileIfMissing = false) }) }.onFailure {
                logs.log(LogLevel.WARN, "org.oip.security", "restored audit log not reopened", mapOf("error" to it.javaClass.simpleName))
            }
        }
        diagnostics = Diagnostics(dataDir, store, host)
        // Tag history (M3.1): settings are `historian/HistorySettings` resources; samples go to the embedded historian
        // db/history.sqlite (ADR 0008; partitioned, HIS-007). Tags with another storageProvider go through the
        // store-and-forward buffer db/history-forward.sqlite to a `historian/HistoryProvider` (SQL on a `db/Connection`,
        // CSV, simulator or splitter, HIS-006/HIS-009).
        historyStore = EmbeddedHistorian(dataDir.resolve("db/history.sqlite"), clock = clock)
        historyRouter = HistoryRouter(historyStore, ForwardBuffer(dataDir.resolve("db/history-forward.sqlite")), clock)
        diskCacheArchives = org.oip.historian.DiskCacheArchives(dataDir.resolve("store-forward/archives"), historyRouter.buffer, clock)
        historyProviderBinding = HistoryProviderBinding(
            historyRouter, store, schemas,
            connectionSource = { connection ->
                object : JdbcConnectionSource {
                    override fun borrow() = databases.borrowConnection(connection)
                    override fun release(connection: java.sql.Connection, broken: Boolean) = databases.releaseConnection(connection, broken)
                    override fun supportsConcurrentBorrow() = true
                }
            },
            local = historyStore,
            csvRoot = dataDir.resolve("history-csv"),
            archiveRoot = dataDir.resolve("history-archive"),
            clock = clock,
            onEngine = { storeAndForwardSettings = it },
        ).also { it.attach() }
        historyCollector = HistoryCollector(tags, historyRouter, clock, queueCapacity = storeAndForwardSettings.memoryBufferSize, divert = historyRouter::divert)
        historyBinding = HistoryConfigBinding(historyCollector, store, schemas, historyRouter).also { it.attach() }
        udtMemberHistory = org.oip.historian.UdtMemberHistory(tags, historyCollector, historyRouter, historyBinding).also { it.attach() }
        reports = ReportService(
            store,
            ReportEngine(DatabaseQueries(databases), RouterHistoryAccess(historyCollector, historyRouter), { c, p -> reportTagCheck.canRead(c, p) }, clock, AlarmJournalReports(alarms.journal)),
            RunHistory(dataDir.resolve("db/report-runs.sqlite")),
            clock,
        ).attach()
        followActiveNode()
        hmiScripts = HmiScriptRunner(store, HmiScripts(scripts), { scripts.limits }, projects = projectService)
        sessions = SessionHost(tags, clock, hmiAccess(), scripts = hmiScripts, trustedProxies = { settings.trustedProxies })
        hmiProjects = HmiProjectApi(store, projectService) { ref -> security.secrets.getText(SecretReferences.parse(ref)) }
        app = Javalin.create { cfg ->
            cfg.showJavalinBanner = false
            cfg.http.maxRequestSize = 256L * 1024 * 1024
            if (settings.httpsEnabled) {
                cfg.jetty.addConnector { server, httpConfig -> https.httpConnector(server, httpConfig) }
                cfg.jetty.addConnector { server, httpConfig -> https.tlsConnector(server, httpConfig) }
            }
        }
        SecurityHeaders.register(app)
        Api(this).register(app)
        transactionGroups = TransactionGroupApi.install(this, app).groups
        AdminUi(adminDist).register(app)
        DocsUi(DocsUi.locate()).register(app)
        sessions.register(app)
        hmiProjects.register(app)
        StudioToolsApi(this).register(app)
        HmiUploadApi(this).register(app)
        WebUi(
            webDist,
            { platformSettings.get().defaultProject.ifBlank { null } },
            // A disabled or non-runnable project is not served at /hmi (PRO-004).
            { name -> projectService.info(name)?.settings?.launchable != false },
            { settings.trustedProxies },
            { ctx, project -> if (projectService.info(project)?.settings?.publicHmi == true) GatewayAuth(this).ensurePublicHmi(ctx) },
        ).register(app)
    }

    /**
     * Caller checks for [SessionHost]: the upgrade's subject as a tag caller (roles and security zone), `tags.write`
     * per write, and a periodic check that a cookie login is still live with the same roles. An API-key socket is
     * checked once, at the upgrade.
     */
    /**
     * What uses database connection [name] (DAT-015): the alarm journal, history providers, report SQL sources and
     * named queries. Shown beside the connection's status, so a faulted connection says what it holds up.
     */
    private fun connectionUsage(name: String): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        if (journalStorage.connection() == name) out += mapOf("kind" to "alarmJournal", "name" to org.oip.alarm.JOURNAL_STORAGE_NAME)
        for (stored in store.list(org.oip.historian.HISTORIAN_MODULE, kind = org.oip.historian.HISTORY_PROVIDER_KIND)) {
            if (stored.resource.spec["connection"] == name) out += mapOf("kind" to "historyProvider", "name" to stored.id.name)
        }
        for (stored in store.list(org.oip.reporting.REPORTING_MODULE, kind = org.oip.reporting.REPORT_KIND)) {
            val sources = (stored.resource.spec["dataSources"] as? List<*>).orEmpty()
            if (sources.any { (it as? Map<*, *>)?.let { s -> s["type"] == "sql" && s["connection"] == name } == true }) {
                out += mapOf("kind" to "report", "name" to stored.id.name)
            }
        }
        for (stored in store.list(org.oip.db.DB_MODULE, kind = org.oip.db.QUERY_KIND)) {
            if (stored.resource.spec["connection"] == name) out += mapOf("kind" to "namedQuery", "name" to stored.id.name)
        }
        return out
    }

    private fun hmiAccess() = object : SessionHost.SessionAccess {
        override fun identify(ctx: io.javalin.http.Context): SessionHost.Identity {
            val (subject, connection) = GatewayAuth.subject(ctx)
            // The same tag caller the REST tag routes use (C-1): roles, security zone and host.
            val caller = GatewayAuth.tagCaller(ctx, security.zones)
            val token = if (ctx.header("Authorization") != null) null else ctx.cookie(HttpAuth.COOKIE)
            val keyId = (subject as? Subject.ApiKey)?.let { security.apiKeys.get(it.keyName)?.keyId }
            return SessionHost.Identity(subject, connection, caller, token, keyId, GatewayAuth.provider(ctx), GatewayAuth.levels(ctx))
        }

        override fun mayWrite(identity: SessionHost.Identity): Boolean {
            // Authorized against the key or account as it is now, not as it was at the upgrade.
            val subject = current(identity, touch = true) ?: return false
            return security.authorization.authorize(subject, Permissions.TAGS_WRITE, identity.connection).allowed
        }

        override fun stillValid(identity: SessionHost.Identity): Boolean {
            val subject = current(identity, touch = false) ?: return false
            return security.authorization.authorize(subject, Permissions.TAGS_READ, identity.connection).allowed
        }

        /**
         * The upgrade's subject as it stands now, or null when it no longer holds: a logged-out or timed-out login, a
         * disabled or changed account; a deleted, revoked, rotated or re-scoped API key. [touch] counts as activity on
         * the login (writes do; watching values does not).
         */
        private fun current(identity: SessionHost.Identity, touch: Boolean): Subject? = when (val subject = identity.subject) {
            is Subject.ApiKey -> {
                val info = security.apiKeys.get(subject.keyName)
                if (info == null || !info.enabled || info.keyId != identity.apiKeyId || info.scopes != subject.scopes) null
                else Subject.ApiKey(info.name, info.scopes, info.secureConnectionRequired, info.enabled)
            }
            is Subject.User -> run {
                val token = identity.sessionToken ?: return@run null
                val minutes = security.gatewaySecurity.get().inactivityTimeoutMinutes
                val idle = if (minutes <= 0) null else minutes.toLong() * 60_000L
                val state = (if (touch) httpAuth.resume(token, idle) as? SessionState.Active else httpAuth.peekState(token, idle)) ?: return@run null
                val username = state.username
                if (username != subject.username) return@run null
                // An identity-provider login (P1-32) keeps the roles mapped at login while its provider stays enabled.
                if (state.provider != null) {
                    val provider = security.identityProviders.get(state.provider)
                    return@run if (provider == null || !provider.enabled || state.roles != subject.roles) null
                    else Subject.User(username, state.roles, true)
                }
                val account = security.users.get(username) ?: return@run null
                if (!account.enabled || account.passwordExpired || account.roles != subject.roles) null
                else Subject.User(username, account.roles, true)
            }
        }
    }

    /**
     * Drivers resolve `secret://` references at connect time. The resolver is installed after
     * modules exist and before they open sockets, and it never logs the plaintext.
     * Sparkplug edge nodes get their tag feed here as well.
     */
    private fun wireDriverSecrets() {
        val mqtt = host.instance(MqttDriverModule.MODULE_ID) as? MqttDriverModule ?: return
        mqtt.secretSource = SecretSource { reference ->
            security.secrets.getText(SecretReferences.parse(reference))
        }
        mqtt.tagFeed = SparkplugTagFeed(tags)
    }

    /** Seed `admin` when [OIP_ADMIN_PASSWORD] is set and the user source is empty. The password is not logged. */
    private fun bootstrapAdmin() {
        val pw = (System.getenv("ERIC_ADMIN_PASSWORD") ?: System.getenv("OIP_ADMIN_PASSWORD"))?.takeIf { it.isNotEmpty() } ?: return
        if (security.users.list().isNotEmpty()) return
        security.users.create(CreateUser("admin", pw.toCharArray(), setOf(DefaultRoles.ADMINISTRATOR)), actor = "system")
    }

    fun start(): Gateway {
        if (!control.tryAcquire()) {
            throw IllegalStateException("gateway is already running for ${dataDir.toAbsolutePath()}")
        }
        started = true
        startedAt = clock.instant()
        WindowsFirewall.allowGatewayPortLater(settings.port)
        try {
            // SEC-022: outbound TLS clients (drivers, scripts, mail, identity providers) trust this gateway's store.
            outboundTlsHandle = org.oip.config.OutboundTls.install { client -> outboundTrust.sslContext(client) }
            val registry = dataDir.resolve("installed-modules.tsv")
            builtIns.seed(if (registry.exists()) registry.readText().lines().map { it.substringBefore('\t') }.filter { it.isNotEmpty() }.toSet() else emptySet())
            host.installAll()
            // Container recipes: restore a backup into a fresh data directory after module packages are installed and before any module starts (GAT-009).
            FirstBootRestore(dataDir, store, backups, processEnv, { security.users.list().map { it.username } }) { message, fields -> logs.log(LogLevel.INFO, "org.oip.backup", message, fields) }.run()
            prepareDrivers()
            host.startAll()
            followActiveEdgeNodes()
            val bridge = TagDriverBridge.attach(tags, store, host)
            redundancy.attachDrivers(bridge)
            bridge.start()
            drivers = bridge
            // Fixed-rate and tag-group expression tags (TAG-029): one ticker for the engine, no thread per tag.
            expressionTicker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tag-expression-ticker").apply { isDaemon = true } }
                .also { it.scheduleWithFixedDelay({ runCatching { tags.tickExpressionGroups() } }, 100, 100, TimeUnit.MILLISECONDS) }
            historyRunner = HistoryRunner(
                historyCollector, historyRouter,
                settings = { storeAndForwardSettings },
                maintenance = { historyStore.maintain() },
            ).start()
            if (settings.opcua.enabled && opcUa == null) {
                val server = OpcUaTagServer(
                    settings.opcua,
                    dataDir.resolve("opcua"),
                    tags,
                    // Each session acts as its user: roles from the directory, zone from the client address (TAG-013).
                    OpcUaUserAuthenticator { user, password, client, secure ->
                        when (val result = security.users.authenticate(user, password, host = "opcua")) {
                            is AuthenticationResult.Success -> {
                                val connection = ConnectionContext(client, hostName = null, gatewayName = null,
                                    secure = secure, direct = true, scope = RequestScope.GATEWAY)
                                // tags.write is taken at activation, like the roles: a session of a user without it writes nothing.
                                val tagWrite = security.authorization.authorize(result.subject, Permissions.TAGS_WRITE, connection).allowed
                                TagCaller.of(result.subject, security.zones.match(connection), client?.hostAddress ?: "", tagWrite = tagWrite)
                            }
                            else -> null
                        }
                    },
                    VERSION,
                    opcUaTrust,
                    GatewayDeviceCatalog(this),
                )
                server.start()
                opcUa = server
            }
            databases.start()
            queryTags?.start()
            systemTags = SystemTags(this).also { it.start() }
            scripts.start()
            charts.start()
            followActiveCharts()
            events.start()
            reportScheduler = ReportScheduler(reports, clock, { def, result -> reportSink.deliver(def, result) }).start()
            diagnostics.clockDrift.start()
            registerMetrics()
            val task = diagnostics.tasks.register("diagnostics-sampler", 5_000)
            task.measure { diagnostics.recordSample() }
            scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "diagnostics-sampler").apply { isDaemon = true } }
                .also { it.scheduleWithFixedDelay({ runCatching { task.measure { diagnostics.recordSample() } } }, 5, 5, TimeUnit.SECONDS) }
            // Audit retention (SEC-001): remove rows older than the audit profile's retention, at start and hourly.
            val retention = diagnostics.tasks.register("audit-retention", AUDIT_RETENTION_PERIOD_MS)
            scheduler?.scheduleWithFixedDelay({ enforceAuditRetention(retention) }, 0, AUDIT_RETENTION_PERIOD_MS, TimeUnit.MILLISECONDS)
            network.start()
            redundancy.start()
            edgeSync.start()
            eam.start()
            alarmScheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "alarm-eval").apply { isDaemon = true } }
                .also { it.scheduleWithFixedDelay({ runCatching { alarms.poll(); notifications.poll() } }, 200, 200, TimeUnit.MILLISECONDS) }
            if (settings.plc.enabled) {
                plcBridge = PlcBridge.open(tags, settings.plc) { message ->
                    logs.log(LogLevel.WARN, "org.oip.plc", message, emptyMap())
                }.also { it.start() }
            }
            https.prepare()
            app.start(settings.host, settings.port)
            https.watchIncoming()
            control.publish(clientUrl())
            logs.log(LogLevel.INFO, "org.oip.gateway", "gateway started", mapOf(
                "profile" to settings.profile,
                "port" to port(),
                "https" to (https.listenPort() ?: "disabled"),
                "opcua" to (opcUa?.boundPort ?: "disabled"),
                "gatewayNetwork" to if (settings.network.enabled) network.port() else "disabled",
                "redundancy" to settings.redundancy.mode.name.lowercase(),
                "edgeSync" to settings.edgeSync.remoteGateway,
            ))
            https.fingerprint()?.let { fingerprint ->
                logs.log(LogLevel.INFO, "org.oip.https", "https certificate active", mapOf(
                    "fingerprint" to fingerprint,
                    "source" to https.activeSource(),
                ))
            }
            return this
        } catch (t: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                t.addSuppressed(cleanup)
            }
            throw t
        }
    }

    /**
     * Apply the audit profile's retention now (SEC-001). Returns the rows removed. The scheduler calls this hourly;
     * a failure is logged and the next pass tries again.
     */
    fun enforceAuditRetention(): Long = security.auditProfile.enforceRetention(clock.instant())

    /** Metrics that need the gateway's services (GAT-024); cpu, heap and disk are the diagnostics' own. */
    private fun registerMetrics() {
        val m = diagnostics.metrics
        if ("tags.changesPerSecond" in m.ids()) return
        m.register("tags.changesPerSecond", "Tag changes per second", "/s", read = m.rate({ tags.changesStored() }))
        m.register("sessions.web", "Web sessions", "sessions") {
            val minutes = security.gatewaySecurity.get().inactivityTimeoutMinutes
            httpAuth.activeSessionCount(if (minutes <= 0) null else minutes.toLong() * 60_000L).toDouble()
        }
        m.register("sessions.hmi", "HMI sessions", "sessions") { sessions.sessionCount().toDouble() }
        m.register("history.queueDepth", "History queue depth", "records") { historyRouter.buffer.pendingCounts().values.sum().toDouble() }
    }

    private fun enforceAuditRetention(task: TrackedTask) {
        try {
            task.measure {
                val removed = enforceAuditRetention()
                if (removed > 0) logs.log(LogLevel.INFO, "org.oip.audit", "audit retention removed expired records", mapOf("removed" to removed))
            }
        } catch (e: Exception) {
            logs.log(LogLevel.WARN, "org.oip.audit", "audit retention failed", mapOf("error" to (e.message ?: e.javaClass.simpleName)))
        }
    }

    /** URL a client on this machine can call. A wildcard bind is reached through the loopback address. */
    fun clientUrl(): String {
        val host = when (settings.host) {
            "0.0.0.0", "::", "[::]" -> "127.0.0.1"
            else -> settings.host
        }
        val rendered = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "http://$rendered:${port()}"
    }

    fun port(): Int = app.port()

    /**
     * `/system/eventstream` and `/system/webdev` are public unless that resource asks for a session.
     * The handler still enforces HTTPS and roles.
     */
    fun systemRequiresAuth(path: String): Boolean {
        val normalized = if (path.length > 1 && path.endsWith('/')) path.trimEnd('/') else path
        if (normalized.startsWith("/system/eventstream/")) {
            val name = normalized.removePrefix("/system/eventstream/")
            if (name.isEmpty() || '/' in name) return false
            return runCatching { events.requiresAuthentication(name) }.getOrDefault(false)
        }
        if (normalized.startsWith("/system/webdev/")) return webdev.requiresAuthentication(normalized)
        return false
    }

    /** Side effects that would otherwise run on both nodes follow [RedundancyRuntime.isActive]. */
    private fun followActiveNode() {
        val active = { redundancy.isActive() }
        alarms.recordsJournal = active
        notifications.actsWhen = active
        scripts.actsWhen = active
        charts.actsWhen = active
        events.actsWhen = active
        network.servesRemoteData = active
    }

    /**
     * Park and resume redundancy-synced charts. Registered after charts load, with replay, so the
     * first call is the state at startup. A backup that starts as standby parks charts it restored,
     * so a later failover runs their on-failover actions. A master that has not decided yet only gates
     * its charts: it does not park them, because becoming active is its normal start, not a failover.
     */
    private fun followActiveCharts() {
        redundancy.addListener(replay = true) { previous, current ->
            if (previous == current) {
                if (current.role == NodeRole.BACKUP && !current.active) charts.park()
                return@addListener
            }
            if (previous.active == current.active) return@addListener
            if (current.active) charts.activate() else charts.park()
        }
    }

    /**
     * Sparkplug edge nodes connect and publish only on the active node. The listener only stores the
     * state; each edge node's IO thread publishes NDEATH and disconnects, or connects and births.
     */
    private fun followActiveEdgeNodes() {
        // Looked up on each change: the MQTT driver can be uninstalled and installed again while running.
        redundancy.addListener(replay = true) { _, current ->
            (host.instance(MqttDriverModule.MODULE_ID) as? MqttDriverModule)?.setEdgeActive(current.active)
        }
    }

    /** Secrets, tag feed, publishing, and edge state for the installed drivers, before they start. */
    private fun prepareDrivers() {
        wireDriverSecrets()
        (host.instance(MqttDriverModule.MODULE_ID) as? MqttDriverModule)?.let { mqtt ->
            mqtt.setPublishing(redundancy.gate().deviceWrites)
            mqtt.setEdgeActive(redundancy.isActive())
        }
    }

    /** A new tag-driver bridge over the drivers installed now. No-op before start and after close. */
    private fun rebindDrivers() {
        val previous = drivers ?: return
        previous.close()
        val bridge = TagDriverBridge.attach(tags, store, host)
        redundancy.attachDrivers(bridge)
        bridge.start()
        drivers = bridge
    }

    private fun logLevel(level: String): LogLevel = when (level.lowercase()) {
        "error" -> LogLevel.ERROR
        "warn" -> LogLevel.WARN
        "debug", "trace" -> LogLevel.DEBUG
        else -> LogLevel.INFO
    }

    /** Bound HTTPS port, or null when the TLS listener is not running. */
    fun httpsListenPort(): Int? = https.listenPort()

    /** URL for the TLS listener on this machine, or null when HTTPS is not listening. */
    fun httpsClientUrl(): String? {
        val httpsPort = httpsListenPort() ?: return null
        val host = when (settings.host) {
            "0.0.0.0", "::", "[::]" -> "127.0.0.1"
            else -> settings.host
        }
        val rendered = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "https://$rendered:$httpsPort"
    }

    private fun recordHttpsAudit(actor: String, fingerprint: String, certSource: String) {
        logs.log(LogLevel.INFO, "org.oip.https", "https certificate replaced", mapOf(
            "fingerprint" to fingerprint,
            "source" to certSource,
        ))
        if (!security.auditProfile.get().enabled) return
        security.audit.append(
            AuditDraft(
                timestampMillis = clock.millis(),
                actor = actor,
                action = HTTPS_CERTIFICATE_ACTION,
                status = AuditStatus.SUCCESS,
                actorHost = org.oip.security.AuditContext.current()?.host ?: "",
                target = "https/server.crt",
                detail = "fingerprint=$fingerprint;source=$certSource",
                context = "gateway-https",
            )
        )
    }

    /**
     * Save the redundancy pair into `gateway.yaml` and point the running node at the master.
     * The file is parsed again with [RedundancySettings.parse] before it replaces the previous text.
     */
    fun saveRedundancy(body: Map<String, Any?>): Map<String, Any?> {
        val next = RedundancySettings.parse(body)
        val yaml = ObjectMapper(YAMLFactory())
        val file = dataDir.resolve("gateway.yaml")
        @Suppress("UNCHECKED_CAST")
        val root = (if (file.exists()) yaml.readValue(file.readText(), Any::class.java) else null) as? Map<String, Any?> ?: emptyMap()
        val doc = LinkedHashMap(root)
        doc["redundancy"] = linkedMapOf(
            "mode" to next.mode.wire().lowercase(),
            "standbyActivityLevel" to next.standbyActivityLevel.name.lowercase(),
            "startupAllowanceMillis" to next.startupAllowanceMillis,
            "syncTimeoutSeconds" to next.syncTimeoutSeconds,
            "recoveryMode" to next.recoveryMode.wire(),
            "masterHost" to next.masterHost,
            "masterPort" to next.masterPort,
            "pingRateMillis" to next.pingRateMillis,
            "pingTimeoutMillis" to next.pingTimeoutMillis,
            "pingMaxMissed" to next.pingMaxMissed,
            "historyMode" to next.historyMode.name.lowercase(),
            "useActiveUptime" to next.useActiveUptime,
            "connectionName" to next.connectionName,
        )
        val tmp = file.resolveSibling("gateway.yaml.tmp")
        tmp.writeText(yaml.writeValueAsString(doc))
        try {
            GatewaySettings.load(tmp, emptyMap())
        } catch (e: IllegalArgumentException) {
            tmp.deleteIfExists()
            throw e
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        redundancy.replaceSettings(next)
        return redundancy.statusDocument()
    }

    override fun close() {
        if (started) {
            runCatching { plcBridge?.close() }
            plcBridge = null
            runCatching { outboundTlsHandle?.close() }
            outboundTlsHandle = null
            runCatching { scripts.shutdown() }
            runCatching { eam.close() }
            runCatching { redundancy.close() }
            runCatching { network.close() }
            runCatching { https.close() }
            runCatching { sessions.close() }
            runCatching { app.stop() }
            runCatching { systemTags?.close() }
            systemTags = null
            runCatching { opcUa?.close() }
            opcUa = null
            runCatching { diagnostics.clockDrift.stop() }
            scheduler?.shutdownNow()
            scheduler = null
            alarmScheduler?.shutdownNow()
            alarmScheduler = null
            expressionTicker?.shutdownNow()
            expressionTicker = null
            drivers?.close()
            drivers = null
            runCatching { reportScheduler?.stop() }
            reportScheduler = null
            runCatching { historyRunner?.close() }
            historyRunner = null
            runCatching { host.close() }
            started = false
        }
        control.release()
        // The edge-sync database is opened when the gateway is constructed, even if start() never ran.
        runCatching { edgeSync.close() }
        if (!closed) {
            closed = true
            runCatching { sessions.close() }
            runCatching { hmiProjects.close() }
            runCatching { hmiScripts.close() }
            runCatching { reports.close() }
            runCatching { udtMemberHistory.close() }
            runCatching { historyBinding.close() }
            runCatching { historyProviderBinding.close() }
            runCatching { historyCollector.close() }
            runCatching { historyRouter.close() }
            runCatching { historyStore.close() }
            runCatching { tagAlarmWatch?.close() }
            runCatching { scripts.close() }
            runCatching { charts.close() }
            runCatching { events.close() }
            runCatching { webdev.close() }
            runCatching { queryTags?.close() }
            queryTags = null
            runCatching { databases.close() }
            if (trustOpened) runCatching { opcUaTrust.close() }
            // After every writer (drivers, scripts, charts, OPC UA, HTTP) has stopped: the last flush keeps them all.
            runCatching { memoryTags.close() }
            runCatching { tagBinding.close() }
            runCatching { httpAuth.close() }
            runCatching { security.close() }
            runCatching { logs.close() }
            runCatching { notifications.close() }
            runCatching { alarms.close() }
            runCatching { runtime.close() }
            store.close()
        }
        // After the listener is down. The service stop waits long enough for this delete.
        runCatching { WindowsFirewall.removeOpenedRule() }
    }

    companion object {
        val LAYOUT = listOf("config", "modules", "db", "logs")
        val VERSION: String = Gateway::class.java.`package`?.implementationVersion ?: "0.1.0-SNAPSHOT"
        /** How often the scheduler applies audit retention (SEC-001). */
        const val AUDIT_RETENTION_PERIOD_MS = 3_600_000L
    }
}
