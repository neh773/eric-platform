package org.oip.alarm.notify

import org.oip.alarm.AlarmEngine
import org.oip.alarm.AlarmNotice
import org.oip.config.FileConfigStore
import org.oip.config.Scope
import java.security.SecureRandom
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Loads notification config, sends email, and remembers acknowledgement links.
 *
 * Passwords stay in the secret store as `secret://` references. They are copied into a char array for the
 * SMTP conversation and wiped afterwards, and they are removed from any log line that would have contained them.
 */
class NotificationService(
    private val clock: Clock,
    private val store: FileConfigStore,
    private val alarms: AlarmEngine,
    private val users: () -> List<DirectoryUser>,
    private val secrets: (String) -> String,
    private val transport: MailTransport = SmtpClient,
    private val log: (String, Map<String, Any?>) -> Unit = { _, _ -> },
    private val zone: ZoneId = ZoneOffset.UTC,
    /** Acknowledgement links: expiry and, with a file, persistence across restarts (review-3 F9). */
    private val links: AckLinks = AckLinks(clock),
    /** SEC-029: tokens from OAuth2 client registrations, for SMTP profiles that name one. */
    private val clientTokens: ((client: String, scope: String) -> String)? = null,
) : AutoCloseable {
    /**
     * The one lookup of SMTP profiles, shared with scheduled reports and gateway scripts (GAT-030). Notification
     * sends resolve their endpoint through it, so a profile means the same thing everywhere it is used.
     */
    val mailProfiles = MailProfiles(store, secrets, clock, transport, log = log, clientTokens = clientTokens)
    private val engine = NotificationEngine(clock, { alarms.liveState(it) }, zone)
    private val random = SecureRandom()
    @Volatile private var lastLinkPurge = 0L
    private val remembered = ConcurrentHashMap<String, AlarmNotice>()
    private var subscription: AutoCloseable? = null
    @Volatile private var catalog = NotifyCatalog()
    @Volatile private var dirty = true
    private val received = CopyOnWriteArrayList<Map<String, Any?>>()

    /**
     * Told once for each notification this gateway tried to send: sent, failed, skipped or held back by test mode.
     * The gateway records it to the audit profile (ALA-036). Doc source: FEATURES ALA-036.
     */
    var auditSink: ((NotificationAudit) -> Unit)? = null

    /** Sends a remote-profile notice to the peer named on the profile. Set by the gateway. */
    var remoteSink: ((RemoteNotice) -> Unit)? = null

    /**
     * When false, pipelines do not start and do not send. A standby node sets this false.
     * An independent gateway leaves it true.
     */
    var actsWhen: () -> Boolean = { true }

    fun attach() {
        NotifySchemas.register(store.registry)
        subscription = store.subscribe { dirty = true }
        reload()
        alarms.onNotice = { notice ->
            if (actsWhen()) {
                try {
                    if (dirty) reload()
                    remembered[notice.eventId] = notice
                    dispatch(engine.accept(notice))
                    forwardRemote()
                } catch (e: RuntimeException) {
                    log("notification failed", mapOf("error" to "${e.javaClass.simpleName}: ${e.message ?: ""}"))
                }
            }
        }
    }

    fun poll() {
        val now = clock.millis()
        if (now - lastLinkPurge >= LINK_PURGE_MILLIS) {
            lastLinkPurge = now
            runCatching { links.purge { alarms.liveState(it) != null } }
        }
        if (!actsWhen()) return
        if (dirty) reload()
        dispatch(engine.poll())
        forwardRemote()
    }

    /** A peer delivered a notification to a local email or one-way profile. */
    fun acceptRemote(profile: String, eventId: String, source: String, displayPath: String, priority: String, state: String, active: Boolean, sender: String): Boolean {
        if (dirty) reload()
        val email = catalog.email[profile]
        val oneWay = catalog.oneWay[profile]
        val enabled = email?.enabled ?: oneWay?.enabled ?: return false
        if (!enabled) return false
        received.add(0, linkedMapOf(
            "profile" to profile,
            "eventId" to eventId,
            "source" to source,
            "displayPath" to displayPath,
            "priority" to priority,
            "state" to state,
            "active" to active,
            "sender" to sender,
        ))
        while (received.size > 50) received.removeAt(received.lastIndex)
        return true
    }

    fun delivered(): List<Map<String, Any?>> = received.toList()

    private fun forwardRemote() {
        val sink = remoteSink ?: return
        for (notice in engine.drainRemote()) {
            try {
                sink(notice)
            } catch (e: RuntimeException) {
                log("remote notification not sent", mapOf("profile" to notice.remoteProfile, "error" to (e.message ?: e.javaClass.simpleName)))
            }
        }
    }

    fun onCall(roster: String): List<String> {
        if (dirty) reload()
        return engine.onCall(roster)
    }

    fun scheduleActive(name: String): Boolean {
        if (dirty) reload()
        return engine.scheduleActive(name)
    }

    fun pipelineCounts(): List<Pair<String, Int>> {
        if (dirty) reload()
        return engine.pipelineCounts()
    }

    fun inFlight(name: String? = null): Int = engine.inFlight(name)

    /** Events now inside a pipeline, for the status page (ALA-029). */
    fun inFlightEvents(): List<InFlightEvent> {
        if (dirty) reload()
        return engine.inFlightEvents()
    }

    fun emailAck(token: String): EmailAckView {
        val link = links.find(token) ?: throw NoSuchElementException("acknowledgement link was not found")
        return view(token, link)
    }

    fun acknowledgeEmail(token: String, notes: String?) {
        val link = links.find(token) ?: throw NoSuchElementException("acknowledgement link was not found")
        val state = alarms.liveState(link.eventId)
        if (state == null) throw NoSuchElementException("alarm event is no longer live")
        if (!state.acknowledged) alarms.acknowledge(listOf(link.eventId), link.username, notes)
    }

    override fun close() {
        subscription?.close()
        subscription = null
        if (alarms.onNotice != null) alarms.onNotice = null
        links.close()
    }

    private fun reload() {
        dirty = false
        val next = readCatalog()
        warnOnClearTextAuth(next)
        catalog = next
        engine.replace(next)
    }

    /**
     * Review-3 F9: a profile that logs in without SSL or STARTTLS sends its password in clear text. That is allowed
     * (some plant relays need it) but logged as a warning each time the configuration loads.
     */
    private fun warnOnClearTextAuth(next: NotifyCatalog) {
        for (smtp in next.smtp.values) {
            if (smtp.enabled && smtp.username.isNotBlank() && !smtp.useSsl && !smtp.startTls) {
                log("smtp password is sent in clear text; enable SSL or STARTTLS", mapOf("profile" to smtp.name))
            }
        }
        for (email in next.email.values) {
            if (email.enabled && !email.useSmtpProfile && email.username.isNotBlank() && !email.useSsl && !email.startTls) {
                log("smtp password is sent in clear text; enable SSL or STARTTLS", mapOf("profile" to email.name))
            }
        }
    }

    private fun readCatalog(): NotifyCatalog {
        fun <T> load(kind: String, parse: (String, Map<String, Any?>) -> T): Map<String, T> {
            val out = linkedMapOf<String, T>()
            for (stored in store.list(NOTIFY_MODULE, Scope.GATEWAY, kind)) {
                try {
                    out[stored.id.name] = parse(stored.id.name, stored.resource.spec)
                } catch (e: RuntimeException) {
                    log("notification config skipped", mapOf("kind" to kind, "name" to stored.id.name, "error" to e.javaClass.simpleName))
                }
            }
            return out
        }
        val contacts = load(NotifyKinds.CONTACT, NotifyCodec::contact)
        val holidays = store.list(NOTIFY_MODULE, Scope.GATEWAY, NotifyKinds.HOLIDAY).mapNotNull { stored ->
            try {
                NotifyCodec.holiday(stored.id.name, stored.resource.spec)
            } catch (e: RuntimeException) {
                log("notification config skipped", mapOf("kind" to NotifyKinds.HOLIDAY, "name" to stored.id.name, "error" to e.javaClass.simpleName))
                null
            }
        }
        return NotifyCatalog(
            smtp = load(NotifyKinds.SMTP, NotifyCodec::smtp),
            email = load(NotifyKinds.EMAIL, NotifyCodec::email),
            oneWay = load(NotifyKinds.ONE_WAY, NotifyCodec::oneWay),
            rosters = load(NotifyKinds.ROSTER, NotifyCodec::roster),
            schedules = load(NotifyKinds.SCHEDULE, NotifyCodec::schedule),
            holidays = holidays,
            pipelines = load(NotifyKinds.PIPELINE, NotifyCodec::pipeline),
            remote = load(NotifyKinds.REMOTE, NotifyCodec::remote),
            contacts = contacts,
            people = mergePeople(users(), contacts),
        )
    }

    private fun dispatch(mail: List<OutboundMail>) {
        for (item in mail) {
            try {
                val prepared = if (item.ackLink) withLink(item) else item
                if (prepared.testMode) {
                    log("notification not sent; test mode", mapOf("profile" to prepared.profile, "to" to prepared.to, "event" to prepared.eventId))
                    audit(prepared, "test mode", "not sent")
                    continue
                }
                val outcome = send(prepared)
                audit(prepared, if (outcome == null) "sent" else "failed", outcome.orEmpty())
            } catch (e: RuntimeException) {
                log("notification not sent", mapOf("profile" to item.profile, "to" to item.to, "error" to (e.message ?: e.javaClass.simpleName)))
                audit(item, "failed", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun audit(mail: OutboundMail, outcome: String, detail: String) {
        val sink = auditSink ?: return
        val notice = remembered[mail.eventId]
        try {
            sink(NotificationAudit(
                profile = mail.profile,
                recipient = mail.username,
                address = mail.to,
                eventId = mail.eventId,
                source = notice?.source.orEmpty(),
                alarm = notice?.name.orEmpty(),
                subject = mail.subject,
                outcome = outcome,
                detail = detail,
            ))
        } catch (e: RuntimeException) {
            log("notification audit failed", mapOf("error" to (e.message ?: e.javaClass.simpleName)))
        }
    }

    private fun withLink(mail: OutboundMail): OutboundMail {
        val profile = catalog.email[mail.emailProfile] ?: return mail.copy(ackLink = false)
        if (!profile.twoWayEnabled || profile.gatewayAddress.isBlank()) return mail.copy(ackLink = false)
        val token = mint()
        val notice = remembered[mail.eventId]
        links.add(token, AckLinks.Link(
            eventId = mail.eventId,
            username = mail.username,
            source = notice?.source ?: "",
            name = notice?.name ?: "",
            displayPath = notice?.displayPath ?: "",
            notesRequired = notice?.notesRequired ?: false,
            createdMillis = clock.millis(),
        ))
        val scheme = if (profile.sendHttpsLink) "https" else "http"
        val url = "$scheme://${profile.gatewayAddress}/api/v1/alarms/email-ack/$token"
        return mail.copy(body = mail.body + "\n\nAcknowledge: $url\n")
    }

    /** Null when the mail was sent, otherwise why it was not. */
    private fun send(mail: OutboundMail): String? {
        val endpoint = endpoint(mail) ?: run {
            log("notification not sent; smtp profile is missing or disabled", mapOf("profile" to mail.profile))
            return "smtp profile is missing or disabled"
        }
        val from = mail.from.ifBlank { endpoint.username }.ifBlank { "alarms@localhost" }
        val outbound = mail.copy(from = from)
        val secret = String(endpoint.password)
        var left = mail.retries
        try {
            while (true) {
                try {
                    val lines = ArrayList<String>()
                    transport.send(endpoint, outbound) { lines += it }
                    for (line in lines) log("smtp", mapOf("detail" to scrubSecret(line, secret)))
                    return null
                } catch (e: RuntimeException) {
                    val detail = scrubSecret(e.message ?: e.javaClass.simpleName, secret)
                    if (left <= 0) {
                        log("smtp send failed", mapOf("profile" to mail.profile, "to" to mail.to, "error" to detail))
                        return detail
                    }
                    left--
                    if (mail.retryDelayMillis > 0) Thread.sleep(mail.retryDelayMillis)
                }
            }
        } finally {
            endpoint.wipe()
        }
    }

    private fun endpoint(mail: OutboundMail): SmtpEndpoint? {
        val email = mail.emailProfile?.let { catalog.email[it] }
        if (email != null && email.enabled && email.useSmtpProfile) {
            val smtp = catalog.smtp[email.smtpProfile] ?: return null
            if (!smtp.enabled) return null
            return mailProfiles.endpoint(smtp, extraDebug = email.debug)
        }
        if (email != null && email.enabled && email.hostname.isNotBlank()) {
            return smtpEndpoint(email.hostname, email.port, email.useSsl, email.startTls, email.username, email.passwordRef, email.timeoutMillis, email.sslProtocols, email.debug)
        }
        val smtp = catalog.smtp[mail.smtpProfile] ?: return null
        if (!smtp.enabled) return null
        return mailProfiles.endpoint(smtp)
    }

    private fun smtpEndpoint(
        host: String,
        port: Int,
        useSsl: Boolean,
        startTls: Boolean,
        username: String,
        passwordRef: String,
        timeoutMillis: Int,
        protocols: String,
        debug: Boolean,
    ): SmtpEndpoint {
        val password = if (username.isBlank() || passwordRef.isBlank()) {
            CharArray(0)
        } else {
            try {
                secrets(passwordRef).toCharArray()
            } catch (_: RuntimeException) {
                throw SmtpException("smtp password could not be read")
            }
        }
        return SmtpEndpoint(
            host = host,
            port = port,
            useSsl = useSsl,
            startTls = startTls && !useSsl,
            username = username,
            password = password,
            timeoutMillis = timeoutMillis,
            protocols = protocols.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            debug = debug,
        )
    }

    private fun view(token: String, link: AckLinks.Link): EmailAckView {
        val state = alarms.liveState(link.eventId)
        val acknowledged = state?.acknowledged ?: true
        return EmailAckView(
            token = token,
            eventId = link.eventId,
            username = link.username,
            source = link.source,
            name = link.name,
            displayPath = state?.displayPath ?: link.displayPath,
            state = when {
                state == null -> "Acknowledged"
                state.active && acknowledged -> "Active and Acknowledged"
                state.active -> "Active and Unacknowledged"
                acknowledged -> "Cleared and Acknowledged"
                else -> "Cleared and Unacknowledged"
            },
            active = state?.active ?: false,
            acknowledged = acknowledged,
            notesRequired = link.notesRequired,
        )
    }

    private fun mint(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

private const val LINK_PURGE_MILLIS = 10_000L

/** One notification attempt, for the audit profile (ALA-036). [outcome] is `sent`, `failed` or `test mode`. */
data class NotificationAudit(
    val profile: String,
    val recipient: String,
    val address: String,
    val eventId: String,
    val source: String,
    val alarm: String,
    val subject: String,
    val outcome: String,
    val detail: String,
)

data class EmailAckView(
    val token: String,
    val eventId: String,
    val username: String,
    val source: String,
    val name: String,
    val displayPath: String,
    val state: String,
    val active: Boolean,
    val acknowledged: Boolean,
    val notesRequired: Boolean,
)
