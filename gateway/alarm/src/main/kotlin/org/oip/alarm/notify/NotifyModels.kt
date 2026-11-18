package org.oip.alarm.notify

import org.oip.alarm.AlarmPriority
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Notification config kinds. Resource names are the profile, roster, schedule, and pipeline names.
 *
 * SMTP settings: -
 * Email notification profile: -
 * One-way email: -
 * Rosters: -
 * Schedules and holidays: -
 * Schedule adjustments: -
 * Contact info: -
 * Pipelines: -
 */
const val NOTIFY_MODULE = "alarm"

object NotifyKinds {
    const val SMTP = "SmtpProfile"
    const val EMAIL = "EmailProfile"
    const val ONE_WAY = "OneWayEmail"
    const val ROSTER = "Roster"
    const val SCHEDULE = "Schedule"
    const val HOLIDAY = "Holiday"
    const val PIPELINE = "Pipeline"
    const val CONTACT = "UserContact"
    const val REMOTE = "RemoteProfile"
}

private const val SECRET = "^$|^secret://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$"
private const val NAME = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$"
/** No control characters (CR and LF above all): an address goes into SMTP commands and headers (review-3 F9). */
const val NO_CONTROL_CHARS = "^[^\\u0000-\\u001F\\u007F]*$"

object NotifySchemas {
    fun register(registry: SchemaRegistry) {
        registry.register(NOTIFY_MODULE, NotifyKinds.SMTP, smtp())
        registry.register(NOTIFY_MODULE, NotifyKinds.EMAIL, email())
        registry.register(NOTIFY_MODULE, NotifyKinds.ONE_WAY, oneWay())
        registry.register(NOTIFY_MODULE, NotifyKinds.ROSTER, roster())
        registry.register(NOTIFY_MODULE, NotifyKinds.SCHEDULE, schedule())
        registry.register(NOTIFY_MODULE, NotifyKinds.HOLIDAY, holiday())
        registry.register(NOTIFY_MODULE, NotifyKinds.PIPELINE, pipeline())
        registry.register(NOTIFY_MODULE, NotifyKinds.CONTACT, contact())
        registry.register(NOTIFY_MODULE, NotifyKinds.REMOTE, remote())
    }

    private fun remote(): Map<String, Any?> = mapOf(
        "type" to "object",
        "required" to listOf("connection", "remoteProfile"),
        "properties" to mapOf(
            "description" to text(500),
            "enabled" to mapOf("type" to "boolean"),
            "connection" to mapOf("type" to "string", "pattern" to NAME),
            "remoteProfile" to mapOf("type" to "string", "pattern" to NAME),
        ),
    )

    private fun smtp(): Map<String, Any?> = mapOf(
        "type" to "object",
        "required" to listOf("hostname"),
        "properties" to mapOf(
            "description" to text(500),
            "enabled" to mapOf("type" to "boolean"),
            "hostname" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 253),
            "port" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 65535),
            "useSsl" to mapOf("type" to "boolean"),
            "startTls" to mapOf("type" to "boolean"),
            "username" to mapOf("type" to "string", "maxLength" to 254),
            "password" to mapOf("type" to "string", "pattern" to SECRET, "description" to "secret:// reference. The password is never stored in the resource."),
            "timeoutMillis" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 600_000),
            "sslProtocols" to mapOf("type" to "string", "maxLength" to 100),
            "debug" to mapOf("type" to "boolean"),
            "fromAddress" to mapOf("type" to "string", "maxLength" to 254, "pattern" to NO_CONTROL_CHARS, "description" to "Sender used when a mail names none."),
            "authType" to mapOf("type" to "string", "enum" to listOf("password", "oauth2"), "default" to "password"),
            "oauthTokenUrl" to mapOf("type" to "string", "maxLength" to 500, "description" to "https token endpoint (http only for a loopback host)."),
            "oauthClientId" to mapOf("type" to "string", "maxLength" to 254),
            "oauthClientSecret" to mapOf("type" to "string", "pattern" to SECRET, "description" to "secret:// reference. The client secret is never stored in the resource."),
            "oauthRefreshToken" to mapOf("type" to "string", "pattern" to SECRET, "description" to "secret:// reference. Empty uses the client-credentials grant."),
            "oauthScope" to mapOf("type" to "string", "maxLength" to 500),
            "oauthClient" to mapOf(
                "type" to "string", "maxLength" to 63,
                "description" to "An OAuth2 client registration (SEC-029) that supplies the token; then the inline oauth* fields other than oauthScope are not used.",
            ),
        ),
    )

    private fun email(): Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "description" to text(500),
            "enabled" to mapOf("type" to "boolean"),
            "useSmtpProfile" to mapOf("type" to "boolean"),
            "smtpProfile" to mapOf("type" to "string", "pattern" to NAME),
            "hostname" to mapOf("type" to "string", "maxLength" to 253),
            "port" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 65535),
            "useSsl" to mapOf("type" to "boolean"),
            "startTls" to mapOf("type" to "boolean"),
            "username" to mapOf("type" to "string", "maxLength" to 254),
            "password" to mapOf("type" to "string", "pattern" to SECRET),
            "timeoutMillis" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 600_000),
            "sslProtocols" to mapOf("type" to "string", "maxLength" to 100),
            "debug" to mapOf("type" to "boolean"),
            "fromAddress" to mapOf("type" to "string", "maxLength" to 254, "pattern" to NO_CONTROL_CHARS),
            "twoWayEnabled" to mapOf("type" to "boolean"),
            "gatewayAddress" to mapOf("type" to "string", "maxLength" to 253),
            "sendHttpsLink" to mapOf("type" to "boolean"),
        ),
    )

    private fun oneWay(): Map<String, Any?> = mapOf(
        "type" to "object",
        "required" to listOf("smtpProfile", "roster"),
        "properties" to mapOf(
            "description" to text(500),
            "enabled" to mapOf("type" to "boolean"),
            "smtpProfile" to mapOf("type" to "string", "pattern" to NAME),
            "roster" to mapOf("type" to "string", "pattern" to NAME),
            "minPriority" to priority(),
            "maxPriority" to priority(),
            "fromAddress" to mapOf("type" to "string", "maxLength" to 254, "pattern" to NO_CONTROL_CHARS),
            "consolidationEnabled" to mapOf("type" to "boolean"),
            "consolidationDelaySeconds" to mapOf("type" to "number", "minimum" to 0),
            "consolidationFrequencySeconds" to mapOf("type" to "number", "minimum" to 0),
            "consolidatedMessage" to text(8000),
        ),
    )

    private fun roster(): Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "description" to text(500),
            "users" to mapOf("type" to "array", "items" to mapOf("type" to "string", "pattern" to NAME), "maxItems" to 200),
        ),
    )

    private fun schedule(): Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "description" to text(500),
            "type" to mapOf("type" to "string", "enum" to listOf("standard", "composite")),
            "observeHolidays" to mapOf("type" to "boolean"),
            "allDays" to mapOf("type" to "string", "maxLength" to 200),
            "weekdays" to mapOf("type" to "string", "maxLength" to 200),
            "monday" to mapOf("type" to "string", "maxLength" to 200),
            "tuesday" to mapOf("type" to "string", "maxLength" to 200),
            "wednesday" to mapOf("type" to "string", "maxLength" to 200),
            "thursday" to mapOf("type" to "string", "maxLength" to 200),
            "friday" to mapOf("type" to "string", "maxLength" to 200),
            "saturday" to mapOf("type" to "string", "maxLength" to 200),
            "sunday" to mapOf("type" to "string", "maxLength" to 200),
            "repeat" to mapOf("type" to "string", "enum" to listOf("off", "daily", "weekly")),
            "repeatOn" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 366),
            "repeatOff" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 366),
            "repeatStart" to mapOf("type" to "string", "pattern" to "^\\d{4}-\\d{2}-\\d{2}$"),
            "scheduleOne" to mapOf("type" to "string", "pattern" to NAME),
            "scheduleTwo" to mapOf("type" to "string", "pattern" to NAME),
        ),
    )

    private fun holiday(): Map<String, Any?> = mapOf(
        "type" to "object",
        "required" to listOf("date"),
        "properties" to mapOf(
            "description" to text(500),
            "date" to mapOf("type" to "string", "pattern" to "^(\\d{4}-\\d{2}-\\d{2}|\\d{2}-\\d{2}-\\d{4})$"),
            "repeatAnnually" to mapOf("type" to "boolean"),
        ),
    )

    private fun pipeline(): Map<String, Any?> = mapOf(
        "type" to "object",
        "required" to listOf("blocks"),
        "properties" to mapOf(
            "description" to text(500),
            "enabled" to mapOf("type" to "boolean"),
            "dropOnAck" to mapOf("type" to "boolean"),
            "dropOnClear" to mapOf("type" to "boolean"),
            "dropOnShelve" to mapOf("type" to "boolean"),
            "blocks" to mapOf("type" to "array", "minItems" to 1, "maxItems" to 64, "items" to block()),
        ),
    )

    private fun block(): Map<String, Any?> = mapOf(
        "type" to "object",
        "required" to listOf("id", "type"),
        "properties" to mapOf(
            "id" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 63),
            "type" to mapOf(
                "type" to "string",
                "enum" to listOf("start", "notification", "delay", "escalation", "splitter", "switch", "expression", "setProperty", "jump", "end"),
            ),
            "next" to mapOf("type" to "string", "maxLength" to 63),
            "seconds" to mapOf("type" to "number", "minimum" to 0),
            "profile" to mapOf("type" to "string", "maxLength" to 63),
            "profiles" to mapOf("type" to "array", "items" to mapOf("type" to "string", "maxLength" to 63), "maxItems" to 8),
            "roster" to mapOf("type" to "string", "maxLength" to 63),
            "rosterExpression" to text(2000),
            "ignoreSchedules" to mapOf("type" to "boolean"),
            "fromAddress" to mapOf("type" to "string", "maxLength" to 254, "pattern" to NO_CONTROL_CHARS),
            "subject" to text(500),
            "message" to text(8000),
            "sendAckLink" to mapOf("type" to "boolean"),
            "delayBetweenSeconds" to mapOf("type" to "number", "minimum" to 0),
            "testMode" to mapOf("type" to "boolean"),
            "retryCount" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 10),
            "retryDelaySeconds" to mapOf("type" to "number", "minimum" to 0),
            "outputs" to mapOf("type" to "array", "items" to mapOf("type" to "string", "maxLength" to 63), "maxItems" to 16),
            "expression" to text(4000),
            "cases" to mapOf(
                "type" to "array",
                "maxItems" to 32,
                "items" to mapOf(
                    "type" to "object",
                    "required" to listOf("equals", "next"),
                    "properties" to mapOf(
                        "equals" to mapOf("type" to listOf("string", "number", "boolean")),
                        "next" to mapOf("type" to "string", "maxLength" to 63),
                    ),
                ),
            ),
            "catchAll" to mapOf("type" to "string", "maxLength" to 63),
            "whenTrue" to mapOf("type" to "string", "maxLength" to 63),
            "whenFalse" to mapOf("type" to "string", "maxLength" to 63),
            "property" to mapOf("type" to "string", "maxLength" to 63),
            "pipeline" to mapOf("type" to "string", "pattern" to NAME),
            "steps" to mapOf("type" to "array", "maxItems" to 16, "items" to escStep()),
        ),
    )

    private fun escStep(): Map<String, Any?> = mapOf(
        "type" to "object",
        "required" to listOf("roster", "profile"),
        "properties" to mapOf(
            "roster" to mapOf("type" to "string", "pattern" to NAME),
            "profile" to mapOf("type" to "string", "pattern" to NAME),
            "attempts" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 20),
            "delaySeconds" to mapOf("type" to "number", "minimum" to 0),
        ),
    )

    private fun contact(): Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "schedule" to mapOf("type" to "string", "pattern" to NAME),
            "contacts" to mapOf(
                "type" to "array",
                "maxItems" to 16,
                "items" to mapOf(
                    "type" to "object",
                    "required" to listOf("type", "value"),
                    "properties" to mapOf(
                        "type" to mapOf("type" to "string", "enum" to listOf("email", "sms", "phone")),
                        "value" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 254, "pattern" to NO_CONTROL_CHARS),
                    ),
                ),
            ),
            "adjustments" to mapOf(
                "type" to "array",
                "maxItems" to 32,
                "items" to mapOf(
                    "type" to "object",
                    "required" to listOf("start", "end", "available"),
                    "properties" to mapOf(
                        "start" to mapOf("type" to "string", "format" to "date-time"),
                        "end" to mapOf("type" to "string", "format" to "date-time"),
                        "available" to mapOf("type" to "boolean"),
                        "note" to text(500),
                    ),
                ),
            ),
        ),
    )

    private fun text(max: Int) = mapOf("type" to "string", "maxLength" to max)
    private fun priority() = mapOf("type" to "string", "enum" to listOf("Diagnostic", "Low", "Medium", "High", "Critical"))
}

data class SmtpProfile(
    val name: String,
    val enabled: Boolean,
    val hostname: String,
    val port: Int,
    val useSsl: Boolean,
    val startTls: Boolean,
    val username: String,
    /** `secret://` reference, or empty when the server does not authenticate. */
    val passwordRef: String,
    val timeoutMillis: Int,
    val sslProtocols: String,
    val debug: Boolean,
    /** Sender for a mail that names none (reports, scripts). */
    val fromAddress: String = "",
    /** `password` (AUTH LOGIN with [username] and [passwordRef]) or `oauth2` (AUTH XOAUTH2 with a token fetched from [oauthTokenUrl]). */
    val authType: String = AUTH_PASSWORD,
    val oauthTokenUrl: String = "",
    val oauthClientId: String = "",
    val oauthClientSecretRef: String = "",
    /** Empty means the client-credentials grant. */
    val oauthRefreshTokenRef: String = "",
    val oauthScope: String = "",
    /** An OAuth2 client registration (SEC-029) that supplies the token instead of the inline oauth fields. */
    val oauthClient: String = "",
) {
    val oauth2: Boolean get() = authType == AUTH_OAUTH2

    companion object {
        const val AUTH_PASSWORD = "password"
        const val AUTH_OAUTH2 = "oauth2"
    }
}

data class EmailProfile(
    val name: String,
    val enabled: Boolean,
    val useSmtpProfile: Boolean,
    val smtpProfile: String?,
    val hostname: String,
    val port: Int,
    val useSsl: Boolean,
    val startTls: Boolean,
    val username: String,
    val passwordRef: String,
    val timeoutMillis: Int,
    val sslProtocols: String,
    val debug: Boolean,
    val fromAddress: String,
    val twoWayEnabled: Boolean,
    val gatewayAddress: String,
    val sendHttpsLink: Boolean,
)

data class OneWayProfile(
    val name: String,
    val enabled: Boolean,
    val smtpProfile: String,
    val roster: String,
    val minPriority: AlarmPriority,
    val maxPriority: AlarmPriority,
    val fromAddress: String,
    val consolidationEnabled: Boolean,
    val delaySeconds: Double,
    val frequencySeconds: Double,
    val consolidatedMessage: String?,
)

data class Roster(val name: String, val users: List<String>)

data class Holiday(val name: String, val date: LocalDate, val repeatAnnually: Boolean)

data class ContactPoint(val type: String, val value: String)

data class Adjustment(val start: Instant, val end: Instant, val available: Boolean, val note: String)

data class ContactRecord(
    val username: String,
    val schedule: String,
    val contacts: List<ContactPoint>,
    val adjustments: List<Adjustment>,
)

data class Person(
    val username: String,
    val displayName: String,
    val enabled: Boolean,
    val schedule: String,
    val contacts: List<ContactPoint>,
    val adjustments: List<Adjustment>,
) {
    fun address(type: String): String? = contacts.firstOrNull { it.type.equals(type, true) }?.value?.takeIf { it.isNotBlank() }
}

sealed class ScheduleDef {
    abstract val name: String

    data class Standard(
        override val name: String,
        val observeHolidays: Boolean,
        val windows: DayWindows,
        val repeat: String,
        val repeatOn: Int,
        val repeatOff: Int,
        val repeatStart: LocalDate?,
    ) : ScheduleDef()

    data class Composite(override val name: String, val one: String, val two: String) : ScheduleDef()
}

/** Minutes from midnight, end exclusive. A window with end <= start wraps past midnight. */
data class TimeWindow(val startMin: Int, val endMin: Int) {
    fun contains(minute: Int): Boolean = when {
        startMin == endMin -> false
        endMin > startMin -> minute in startMin until endMin
        else -> minute >= startMin || minute < endMin
    }
}

data class DayWindows(
    val allDays: List<TimeWindow>?,
    val weekdays: List<TimeWindow>?,
    val byDay: Map<Int, List<TimeWindow>>,
)

data class SwitchCase(val equals: Any?, val next: String)

data class EscStep(val roster: String, val profile: String, val attempts: Int, val delaySeconds: Double)

sealed class Block {
    abstract val id: String

    data class Start(override val id: String, val next: String?) : Block()
    data class Notification(
        override val id: String,
        val profiles: List<String>,
        val roster: String?,
        val rosterExpression: String?,
        val ignoreSchedules: Boolean,
        val fromAddress: String,
        val subject: String?,
        val message: String?,
        val sendAckLink: Boolean,
        val delayBetween: Duration,
        val testMode: Boolean,
        val retryCount: Int,
        val retryDelay: Duration,
        val next: String?,
    ) : Block()
    data class Delay(override val id: String, val duration: Duration, val next: String?) : Block()
    data class Escalation(override val id: String, val steps: List<EscStep>, val next: String?) : Block()
    data class Splitter(override val id: String, val outputs: List<String>) : Block()
    data class Switch(override val id: String, val expression: String, val cases: List<SwitchCase>, val catchAll: String?) : Block()
    data class Expr(override val id: String, val expression: String, val whenTrue: String?, val whenFalse: String?) : Block()
    data class SetProperty(override val id: String, val property: String, val expression: String, val next: String?) : Block()
    data class Jump(override val id: String, val pipeline: String) : Block()
    data class End(override val id: String) : Block()
}

data class PipelineDef(
    val name: String,
    val enabled: Boolean,
    val dropOnAck: Boolean,
    val dropOnClear: Boolean,
    val dropOnShelve: Boolean,
    val blocks: Map<String, Block>,
    val start: String,
)

data class RemoteProfile(
    val name: String,
    val enabled: Boolean,
    val connection: String,
    val remoteProfile: String,
)

/** A pipeline asked a peer gateway to deliver this event through one of its profiles. */
data class RemoteNotice(
    val connection: String,
    val remoteProfile: String,
    val eventId: String,
    val source: String,
    val displayPath: String,
    val priority: String,
    val state: String,
    val active: Boolean,
)

data class NotifyCatalog(
    val smtp: Map<String, SmtpProfile> = emptyMap(),
    val email: Map<String, EmailProfile> = emptyMap(),
    val remote: Map<String, RemoteProfile> = emptyMap(),
    val oneWay: Map<String, OneWayProfile> = emptyMap(),
    val rosters: Map<String, Roster> = emptyMap(),
    val schedules: Map<String, ScheduleDef> = emptyMap(),
    val holidays: List<Holiday> = emptyList(),
    val pipelines: Map<String, PipelineDef> = emptyMap(),
    val contacts: Map<String, ContactRecord> = emptyMap(),
    val people: Map<String, Person> = emptyMap(),
)

object NotifyCodec {
    fun smtp(name: String, spec: Map<String, Any?>) = SmtpProfile(
        name = name,
        enabled = bool(spec, "enabled", true),
        hostname = str(spec, "hostname") ?: throw IllegalArgumentException("hostname is required"),
        port = int(spec, "port", 25),
        useSsl = bool(spec, "useSsl", false),
        startTls = bool(spec, "startTls", false),
        username = str(spec, "username") ?: "",
        passwordRef = str(spec, "password") ?: "",
        timeoutMillis = int(spec, "timeoutMillis", 10_000),
        sslProtocols = str(spec, "sslProtocols") ?: "TLSv1.2",
        debug = bool(spec, "debug", false),
        fromAddress = str(spec, "fromAddress") ?: "",
        authType = (str(spec, "authType") ?: SmtpProfile.AUTH_PASSWORD).also {
            require(it == SmtpProfile.AUTH_PASSWORD || it == SmtpProfile.AUTH_OAUTH2) { "authType must be password or oauth2" }
        },
        oauthTokenUrl = str(spec, "oauthTokenUrl") ?: "",
        oauthClientId = str(spec, "oauthClientId") ?: "",
        oauthClientSecretRef = str(spec, "oauthClientSecret") ?: "",
        oauthRefreshTokenRef = str(spec, "oauthRefreshToken") ?: "",
        oauthScope = str(spec, "oauthScope") ?: "",
        oauthClient = str(spec, "oauthClient") ?: "",
    )

    fun remote(name: String, spec: Map<String, Any?>) = RemoteProfile(
        name = name,
        enabled = bool(spec, "enabled", true),
        connection = str(spec, "connection") ?: throw IllegalArgumentException("connection is required"),
        remoteProfile = str(spec, "remoteProfile") ?: throw IllegalArgumentException("remoteProfile is required"),
    )

    fun email(name: String, spec: Map<String, Any?>) = EmailProfile(
        name = name,
        enabled = bool(spec, "enabled", true),
        useSmtpProfile = bool(spec, "useSmtpProfile", false),
        smtpProfile = str(spec, "smtpProfile"),
        hostname = str(spec, "hostname") ?: "",
        port = int(spec, "port", 25),
        useSsl = bool(spec, "useSsl", false),
        startTls = bool(spec, "startTls", false),
        username = str(spec, "username") ?: "",
        passwordRef = str(spec, "password") ?: "",
        timeoutMillis = int(spec, "timeoutMillis", 10_000),
        sslProtocols = str(spec, "sslProtocols") ?: "TLSv1.2",
        debug = bool(spec, "debug", false),
        fromAddress = str(spec, "fromAddress") ?: "",
        twoWayEnabled = bool(spec, "twoWayEnabled", false),
        gatewayAddress = str(spec, "gatewayAddress") ?: "",
        sendHttpsLink = bool(spec, "sendHttpsLink", false),
    )

    fun oneWay(name: String, spec: Map<String, Any?>): OneWayProfile {
        val min = AlarmPriority.fromName(str(spec, "minPriority")) ?: AlarmPriority.HIGH
        val max = AlarmPriority.fromName(str(spec, "maxPriority")) ?: AlarmPriority.CRITICAL
        return OneWayProfile(
            name = name,
            enabled = bool(spec, "enabled", true),
            smtpProfile = str(spec, "smtpProfile") ?: throw IllegalArgumentException("smtpProfile is required"),
            roster = str(spec, "roster") ?: throw IllegalArgumentException("roster is required"),
            minPriority = min,
            maxPriority = max,
            fromAddress = str(spec, "fromAddress") ?: "",
            consolidationEnabled = bool(spec, "consolidationEnabled", false),
            delaySeconds = num(spec["consolidationDelaySeconds"]) ?: 0.0,
            frequencySeconds = num(spec["consolidationFrequencySeconds"]) ?: 0.0,
            consolidatedMessage = str(spec, "consolidatedMessage"),
        )
    }

    fun roster(name: String, spec: Map<String, Any?>) = Roster(name, strings(spec["users"]))

    fun holiday(name: String, spec: Map<String, Any?>): Holiday {
        val raw = str(spec, "date") ?: throw IllegalArgumentException("date is required")
        return Holiday(name, parseDate(raw), bool(spec, "repeatAnnually", true))
    }

    fun schedule(name: String, spec: Map<String, Any?>): ScheduleDef {
        val type = str(spec, "type") ?: "standard"
        if (type == "composite") {
            return ScheduleDef.Composite(
                name,
                str(spec, "scheduleOne") ?: throw IllegalArgumentException("scheduleOne is required"),
                str(spec, "scheduleTwo") ?: throw IllegalArgumentException("scheduleTwo is required"),
            )
        }
        val days = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
        val named = days.filter { spec.containsKey(it) }
        if (spec.containsKey("allDays") && (spec.containsKey("weekdays") || named.isNotEmpty())) {
            throw IllegalArgumentException("allDays cannot be combined with other days")
        }
        if (spec.containsKey("weekdays") && named.isNotEmpty()) {
            throw IllegalArgumentException("weekdays cannot be combined with individual days")
        }
        val byDay = linkedMapOf<Int, List<TimeWindow>>()
        days.forEachIndexed { index, day ->
            if (spec.containsKey(day)) byDay[index + 1] = windows(str(spec, day))
        }
        return ScheduleDef.Standard(
            name = name,
            observeHolidays = bool(spec, "observeHolidays", true),
            windows = DayWindows(
                allDays = if (spec.containsKey("allDays")) windows(str(spec, "allDays")) else null,
                weekdays = if (spec.containsKey("weekdays")) windows(str(spec, "weekdays")) else null,
                byDay = byDay,
            ),
            repeat = str(spec, "repeat") ?: "off",
            repeatOn = int(spec, "repeatOn", 1).coerceAtLeast(1),
            repeatOff = int(spec, "repeatOff", 1).coerceAtLeast(1),
            repeatStart = str(spec, "repeatStart")?.let { LocalDate.parse(it) },
        )
    }

    fun contact(name: String, spec: Map<String, Any?>): ContactRecord {
        val points = (spec["contacts"] as? List<*>)?.map { item ->
            val map = item as? Map<*, *> ?: throw IllegalArgumentException("contact must be an object")
            ContactPoint(
                type = map["type"]?.toString() ?: throw IllegalArgumentException("contact type is required"),
                value = map["value"]?.toString() ?: throw IllegalArgumentException("contact value is required"),
            )
        } ?: emptyList()
        val adjustments = (spec["adjustments"] as? List<*>)?.map { item ->
            val map = item as? Map<*, *> ?: throw IllegalArgumentException("adjustment must be an object")
            val start = Instant.parse(map["start"]?.toString())
            val end = Instant.parse(map["end"]?.toString())
            if (!end.isAfter(start)) throw IllegalArgumentException("adjustment end must be after start")
            Adjustment(start, end, map["available"] as? Boolean ?: true, map["note"]?.toString() ?: "")
        } ?: emptyList()
        return ContactRecord(name, str(spec, "schedule") ?: Schedules.ALWAYS, points, adjustments)
    }

    fun pipeline(name: String, spec: Map<String, Any?>): PipelineDef {
        val raw = spec["blocks"] as? List<*> ?: throw IllegalArgumentException("blocks is required")
        val blocks = linkedMapOf<String, Block>()
        for (item in raw) {
            val map = item as? Map<*, *> ?: throw IllegalArgumentException("block must be an object")
            val specMap = map.entries.associate { it.key.toString() to it.value }
            val block = block(specMap)
            if (blocks.put(block.id, block) != null) throw IllegalArgumentException("duplicate block id '${block.id}'")
        }
        val start = blocks.values.filterIsInstance<Block.Start>().singleOrNull()
            ?: throw IllegalArgumentException("pipeline '$name' needs one start block")
        return PipelineDef(
            name = name,
            enabled = bool(spec, "enabled", true),
            dropOnAck = bool(spec, "dropOnAck", true),
            dropOnClear = bool(spec, "dropOnClear", true),
            dropOnShelve = bool(spec, "dropOnShelve", true),
            blocks = blocks,
            start = start.id,
        )
    }

    private fun block(spec: Map<String, Any?>): Block {
        val id = str(spec, "id") ?: throw IllegalArgumentException("block id is required")
        val next = str(spec, "next")
        return when (str(spec, "type")) {
            "start" -> Block.Start(id, next)
            "notification" -> Block.Notification(
                id = id,
                profiles = strings(spec["profiles"]).ifEmpty { listOfNotNull(str(spec, "profile")) },
                roster = str(spec, "roster"),
                rosterExpression = str(spec, "rosterExpression"),
                ignoreSchedules = bool(spec, "ignoreSchedules", false),
                fromAddress = str(spec, "fromAddress") ?: "",
                subject = str(spec, "subject"),
                message = str(spec, "message"),
                sendAckLink = bool(spec, "sendAckLink", true),
                delayBetween = duration(spec["delayBetweenSeconds"]),
                testMode = bool(spec, "testMode", false),
                retryCount = int(spec, "retryCount", 0),
                retryDelay = duration(spec["retryDelaySeconds"]),
                next = next,
            )
            "delay" -> Block.Delay(id, duration(spec["seconds"]), next)
            "escalation" -> Block.Escalation(id, steps(spec["steps"]), next)
            "splitter" -> Block.Splitter(id, strings(spec["outputs"]))
            "switch" -> Block.Switch(
                id,
                str(spec, "expression") ?: "\"\"",
                cases(spec["cases"]),
                str(spec, "catchAll"),
            )
            "expression" -> Block.Expr(id, str(spec, "expression") ?: "false", str(spec, "whenTrue"), str(spec, "whenFalse"))
            "setProperty" -> Block.SetProperty(
                id,
                str(spec, "property") ?: throw IllegalArgumentException("property is required"),
                str(spec, "expression") ?: "null",
                next,
            )
            "jump" -> Block.Jump(id, str(spec, "pipeline") ?: throw IllegalArgumentException("pipeline is required"))
            "end" -> Block.End(id)
            else -> throw IllegalArgumentException("unknown block type '${spec["type"]}'")
        }
    }

    private fun steps(raw: Any?): List<EscStep> = (raw as? List<*>)?.map { item ->
        val map = item as? Map<*, *> ?: throw IllegalArgumentException("escalation step must be an object")
        EscStep(
            roster = map["roster"]?.toString() ?: throw IllegalArgumentException("roster is required"),
            profile = map["profile"]?.toString() ?: throw IllegalArgumentException("profile is required"),
            attempts = (map["attempts"] as? Number)?.toInt() ?: 1,
            delaySeconds = num(map["delaySeconds"]) ?: 0.0,
        )
    } ?: emptyList()

    private fun cases(raw: Any?): List<SwitchCase> = (raw as? List<*>)?.map { item ->
        val map = item as? Map<*, *> ?: throw IllegalArgumentException("switch case must be an object")
        SwitchCase(map["equals"], map["next"]?.toString() ?: throw IllegalArgumentException("case next is required"))
    } ?: emptyList()

    fun windows(text: String?): List<TimeWindow> {
        if (text.isNullOrBlank()) return listOf(TimeWindow(0, 24 * 60))
        return text.split(',').map { part ->
            val piece = part.trim()
            val bits = piece.split('-')
            if (bits.size != 2) throw IllegalArgumentException("time window '$piece' must be HH:mm-HH:mm")
            TimeWindow(minute(bits[0].trim()), minute(bits[1].trim()))
        }
    }

    private fun minute(text: String): Int {
        if (text == "24:00") return 24 * 60
        val bits = text.split(':')
        if (bits.size !in 2..3) throw IllegalArgumentException("time '$text' must be HH:mm")
        val hour = bits[0].toInt()
        val min = bits[1].toInt()
        if (hour !in 0..23 || min !in 0..59) throw IllegalArgumentException("time '$text' is out of range")
        return hour * 60 + min
    }

    private fun parseDate(raw: String): LocalDate {
        val text = raw.trim()
        if (text.length == 10 && text[4] == '-') return LocalDate.parse(text)
        val bits = text.split('-')
        if (bits.size == 3 && bits[0].length == 2) return LocalDate.of(bits[2].toInt(), bits[0].toInt(), bits[1].toInt())
        throw IllegalArgumentException("date '$raw' must be YYYY-MM-DD or MM-DD-YYYY")
    }
}

/**
 * Schedule windows use the gateway clock's zone. The built-in schedule `always` is active at every instant.
 * A schedule adjustment on the user overrides the weekly windows for its whole range.
 */
object Schedules {
    const val ALWAYS = "always"

    fun onShift(person: Person, catalog: NotifyCatalog, now: Instant, zone: ZoneId = ZoneOffset.UTC): Boolean {
        val hit = person.adjustments.lastOrNull { !now.isBefore(it.start) && now.isBefore(it.end) }
        if (hit != null) return hit.available
        return active(person.schedule.ifBlank { ALWAYS }, catalog, now, zone, HashSet())
    }

    fun active(name: String, catalog: NotifyCatalog, now: Instant, zone: ZoneId = ZoneOffset.UTC, stack: MutableSet<String> = HashSet()): Boolean {
        if (name.equals(ALWAYS, true) && name !in catalog.schedules) return true
        if (!stack.add(name)) return false
        val schedule = catalog.schedules[name] ?: return name.equals(ALWAYS, true)
        return try {
            when (schedule) {
                is ScheduleDef.Composite ->
                    active(schedule.one, catalog, now, zone, stack) || active(schedule.two, catalog, now, zone, stack)
                is ScheduleDef.Standard -> standard(schedule, catalog.holidays, now, zone)
            }
        } finally {
            stack.remove(name)
        }
    }

    private fun standard(schedule: ScheduleDef.Standard, holidays: List<Holiday>, now: Instant, zone: ZoneId): Boolean {
        val local = now.atZone(zone)
        val date = local.toLocalDate()
        if (schedule.repeat != "off") {
            val start = schedule.repeatStart ?: return false
            if (date.isBefore(start)) return false
            val on = when (schedule.repeat) {
                "daily" -> {
                    val days = ChronoUnit.DAYS.between(start, date)
                    days % (schedule.repeatOn + schedule.repeatOff) < schedule.repeatOn
                }
                "weekly" -> {
                    val weeks = ChronoUnit.DAYS.between(start, date) / 7
                    weeks % (schedule.repeatOn + schedule.repeatOff) < schedule.repeatOn
                }
                else -> true
            }
            if (!on) return false
        }
        if (schedule.observeHolidays && holidays.any { it.matches(date) }) return false
        val minute = local.hour * 60 + local.minute
        val windows = schedule.windows.forDay(local.dayOfWeek.value) ?: return false
        return windows.any { it.contains(minute) }
    }

    private fun Holiday.matches(date: LocalDate): Boolean =
        if (repeatAnnually) date.month == this.date.month && date.dayOfMonth == this.date.dayOfMonth else date == this.date

    private fun DayWindows.forDay(day: Int): List<TimeWindow>? {
        allDays?.let { return it }
        if (weekdays != null) return if (day in 1..5) weekdays else null
        return byDay[day]
    }
}

internal fun specOf(resource: Resource): Map<String, Any?> = resource.spec

internal fun str(spec: Map<String, Any?>, key: String): String? = spec[key]?.toString()?.takeIf { it.isNotBlank() }

internal fun bool(spec: Map<String, Any?>, key: String, default: Boolean): Boolean = when (val v = spec[key]) {
    null -> default
    is Boolean -> v
    else -> v.toString().equals("true", true)
}

internal fun int(spec: Map<String, Any?>, key: String, default: Int): Int = (spec[key] as? Number)?.toInt() ?: default

internal fun num(v: Any?): Double? = when (v) {
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull()
    else -> null
}

internal fun strings(raw: Any?): List<String> =
    (raw as? List<*>)?.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } } ?: emptyList()

internal fun duration(raw: Any?): Duration {
    val n = num(raw) ?: return Duration.ZERO
    if (n < 0) return Duration.ZERO
    return Duration.ofMillis((n * 1000.0).toLong())
}
