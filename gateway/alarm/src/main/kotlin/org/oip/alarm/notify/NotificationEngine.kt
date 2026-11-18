package org.oip.alarm.notify

import org.oip.alarm.AlarmLiveState
import org.oip.alarm.AlarmNotice
import org.oip.alarm.runtimeState
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Runs alarm notification pipelines and the simple one-way email profile on a caller-supplied clock.
 *
 * Dropout (acknowledged, cleared, shelved) is checked before every block and between roster recipients.
 */
class NotificationEngine(
    private val clock: Clock,
    private val states: (String) -> AlarmLiveState?,
    private val zone: ZoneId = ZoneOffset.UTC,
) {
    private val gate = Any()
    private var catalog = NotifyCatalog()
    private val running = ArrayList<Tracked>()
    private val holds = HashMap<String, Hold>()
    private val remoteOut = ArrayList<RemoteNotice>()

    fun drainRemote(): List<RemoteNotice> = synchronized(gate) {
        val copy = remoteOut.toList()
        remoteOut.clear()
        copy
    }

    fun replace(next: NotifyCatalog) = synchronized(gate) { catalog = next }

    fun accept(notice: AlarmNotice): List<OutboundMail> {
        val mail = ArrayList<OutboundMail>()
        synchronized(gate) {
            if (!notice.pipeline.isNullOrBlank()) begin(notice, mail)
            if (notice.trigger == "active") considerOneWay(notice, mail)
            flush(mail)
        }
        return mail
    }

    fun poll(): List<OutboundMail> {
        val mail = ArrayList<OutboundMail>()
        synchronized(gate) {
            releaseHolds(mail)
            flush(mail)
            running.removeIf { !it.alive }
        }
        return mail
    }

    fun inFlight(name: String? = null): Int = synchronized(gate) {
        running.count { it.alive && (name == null || it.pipelineName == name) }
    }

    fun pipelineCounts(): List<Pair<String, Int>> = synchronized(gate) {
        running.filter { it.alive }.groupingBy { it.pipelineName }.eachCount().entries
            .sortedBy { it.key }
            .map { it.key to it.value }
    }

    /**
     * Events now inside a pipeline: which pipeline and block each is at, and when a waiting one wakes (ALA-029).
     * Doc source: FEATURES ALA-029.
     */
    fun inFlightEvents(): List<InFlightEvent> = synchronized(gate) {
        running.filter { it.alive }.map { inst ->
            InFlightEvent(
                eventId = inst.eventId,
                pipeline = inst.pipelineName,
                block = inst.blockId,
                alarm = inst.props["name"]?.toString().orEmpty(),
                displayPath = inst.props["displayPath"]?.toString().orEmpty(),
                state = inst.props["state"]?.toString().orEmpty(),
                waitingUntil = inst.wakeAt,
            )
        }.sortedWith(compareBy({ it.pipeline }, { it.eventId }))
    }

    fun onCall(roster: String): List<String> = synchronized(gate) { rosterUsers(roster, ignoreSchedules = false).map { it.username } }

    fun scheduleActive(name: String): Boolean = synchronized(gate) {
        Schedules.active(name, catalog, clock.instant(), zone)
    }

    private fun begin(notice: AlarmNotice, mail: MutableList<OutboundMail>) {
        val pipeline = catalog.pipelines[notice.pipeline] ?: return
        if (!pipeline.enabled) return
        val tracked = Tracked(
            eventId = notice.eventId,
            pipelineName = pipeline.name,
            pipeline = pipeline,
            blockId = pipeline.start,
            props = seed(notice),
            customSubject = notice.emailSubject,
            customMessage = notice.emailMessage,
            eventState = eventState(notice.trigger),
        )
        refresh(tracked)
        running += tracked
    }

    private fun flush(mail: MutableList<OutboundMail>) {
        val now = clock.instant()
        for (inst in running) {
            val due = inst.wakeAt ?: continue
            if (!now.isBefore(due)) inst.wakeAt = null
        }
        var guard = 0
        while (guard++ < 2_000) {
            val ready = running.filter { it.alive && it.wakeAt == null }
            if (ready.isEmpty()) break
            var moved = false
            for (inst in ready) {
                if (!inst.alive || inst.wakeAt != null) continue
                val before = inst.steps
                step(inst, mail)
                if (inst.steps != before || !inst.alive) moved = true
            }
            if (!moved) break
        }
        running.removeIf { !it.alive }
    }

    private fun step(inst: Tracked, mail: MutableList<OutboundMail>) {
        inst.steps++
        if (inst.steps > 1_000) {
            inst.alive = false
            return
        }
        refresh(inst)
        if (dropped(inst)) {
            inst.alive = false
            return
        }
        when (val block = inst.pipeline.blocks[inst.blockId]) {
            null -> inst.alive = false
            is Block.Start -> go(inst, block.next)
            is Block.End -> inst.alive = false
            is Block.Delay -> delay(inst, block)
            is Block.Notification -> notify(inst, block, mail)
            is Block.Escalation -> escalate(inst, block, mail)
            is Block.Splitter -> split(inst, block)
            is Block.Switch -> select(inst, block)
            is Block.Expr -> branch(inst, block)
            is Block.SetProperty -> assign(inst, block)
            is Block.Jump -> jump(inst, block)
        }
    }

    private fun delay(inst: Tracked, block: Block.Delay) {
        if (inst.cursor == DelayMark) {
            inst.cursor = null
            go(inst, block.next)
            return
        }
        if (block.duration.isZero || block.duration.isNegative) {
            go(inst, block.next)
            return
        }
        inst.cursor = DelayMark
        inst.wakeAt = clock.instant().plus(block.duration)
    }

    private fun notify(inst: Tracked, block: Block.Notification, mail: MutableList<OutboundMail>) {
        emitRemote(inst, block.profiles)
        val cursor = inst.cursor as? NotifyCursor ?: NotifyCursor(emptySet())
        refresh(inst)
        if (dropped(inst)) {
            inst.alive = false
            return
        }
        // Resolve again after a delay so a user who left their shift is skipped and one who came on is included.
        val recipients = resolve(inst, block.profiles, rosterOf(inst, block), block.ignoreSchedules)
        val recipient = recipients.firstOrNull { it.username !in cursor.sent } ?: run {
            go(inst, block.next)
            return
        }
        mail += message(inst, recipient, block.subject, block.message, block.fromAddress, block.sendAckLink, block.testMode, block.retryCount, block.retryDelay)
        val sent = cursor.sent + recipient.username
        inst.cursor = NotifyCursor(sent)
        if (recipients.none { it.username !in sent }) go(inst, block.next)
        else if (!block.delayBetween.isZero) inst.wakeAt = clock.instant().plus(block.delayBetween)
    }

    private fun escalate(inst: Tracked, block: Block.Escalation, mail: MutableList<OutboundMail>) {
        if (block.steps.isEmpty()) {
            go(inst, block.next)
            return
        }
        var cursor = inst.cursor as? EscCursor ?: EscCursor(0, 0, waiting = false)
        var spins = 0
        while (spins++ < 32 && inst.alive && inst.wakeAt == null) {
            if (cursor.waiting) {
                val step = block.steps[cursor.step]
                cursor = when {
                    cursor.attempt + 1 < step.attempts -> EscCursor(cursor.step, cursor.attempt + 1, false)
                    cursor.step + 1 < block.steps.size -> EscCursor(cursor.step + 1, 0, false)
                    else -> {
                        go(inst, block.next)
                        return
                    }
                }
            }
            val step = block.steps.getOrNull(cursor.step) ?: run {
                go(inst, block.next)
                return
            }
            sendRoster(inst, listOf(step.profile), step.roster, false, mail, null, null, "", true, false, 0, Duration.ZERO)
            if (!inst.alive) return
            val more = cursor.attempt + 1 < step.attempts || cursor.step + 1 < block.steps.size
            if (!more) {
                go(inst, block.next)
                return
            }
            val delay = Duration.ofMillis((step.delaySeconds * 1000.0).toLong())
            inst.cursor = EscCursor(cursor.step, cursor.attempt, waiting = true)
            if (!delay.isZero) {
                inst.wakeAt = clock.instant().plus(delay)
                return
            }
            cursor = inst.cursor as EscCursor
        }
    }

    private fun split(inst: Tracked, block: Block.Splitter) {
        if (block.outputs.isEmpty()) {
            inst.alive = false
            return
        }
        for (output in block.outputs.drop(1)) running += inst.copyAt(output)
        inst.cursor = null
        inst.blockId = block.outputs[0]
    }

    private fun select(inst: Tracked, block: Block.Switch) {
        val result = PipelineExpr.eval(block.expression, inst.props)
        val match = if (result.failed) null else block.cases.firstOrNull { same(it.equals, result.value) }
        go(inst, match?.next ?: block.catchAll)
    }

    private fun branch(inst: Tracked, block: Block.Expr) {
        val result = PipelineExpr.eval(block.expression, inst.props)
        val ok = !result.failed && PipelineExpr.truth(result.value)
        go(inst, if (ok) block.whenTrue else block.whenFalse)
    }

    private fun assign(inst: Tracked, block: Block.SetProperty) {
        val result = PipelineExpr.eval(block.expression, inst.props)
        if (!result.failed) inst.props[block.property] = result.value
        go(inst, block.next)
    }

    private fun jump(inst: Tracked, block: Block.Jump) {
        val dest = catalog.pipelines[block.pipeline]
        if (dest == null || !dest.enabled) {
            inst.alive = false
            return
        }
        inst.pipelineName = dest.name
        inst.pipeline = dest
        inst.blockId = dest.start
        inst.cursor = null
    }

    private fun go(inst: Tracked, next: String?) {
        inst.cursor = null
        if (next.isNullOrBlank()) inst.alive = false else inst.blockId = next
    }

    private fun sendRoster(
        inst: Tracked,
        profiles: List<String>,
        roster: String,
        ignoreSchedules: Boolean,
        mail: MutableList<OutboundMail>,
        subject: String?,
        message: String?,
        from: String,
        sendAck: Boolean,
        testMode: Boolean,
        retries: Int,
        retryDelay: Duration,
    ) {
        emitRemote(inst, profiles)
        for (recipient in resolve(inst, profiles, roster, ignoreSchedules)) {
            refresh(inst)
            if (dropped(inst)) {
                inst.alive = false
                return
            }
            mail += message(inst, recipient, subject, message, from, sendAck, testMode, retries, retryDelay)
        }
    }

    private fun emitRemote(inst: Tracked, profiles: List<String>) {
        for (name in profiles) {
            val remote = catalog.remote[name] ?: continue
            if (!remote.enabled || remote.connection.isBlank() || remote.remoteProfile.isBlank()) continue
            remoteOut += RemoteNotice(
                connection = remote.connection,
                remoteProfile = remote.remoteProfile,
                eventId = inst.eventId,
                source = inst.props["source"]?.toString().orEmpty(),
                displayPath = inst.props["displayPath"]?.toString().orEmpty(),
                priority = inst.props["priorityName"]?.toString().orEmpty(),
                state = inst.props["eventState"]?.toString().orEmpty(),
                active = inst.props["isActive"] == true,
            )
        }
    }

    private fun rosterOf(inst: Tracked, block: Block.Notification): String {
        val expression = block.rosterExpression
        if (!expression.isNullOrBlank()) return PipelineExpr.rosterName(expression, inst.props)
        return block.roster.orEmpty()
    }

    private fun resolve(inst: Tracked, profiles: List<String>, roster: String, ignoreSchedules: Boolean): List<Recipient> {
        if (roster.isBlank() || profiles.isEmpty()) return emptyList()
        return rosterUsers(roster, ignoreSchedules).mapNotNull { person ->
            val chosen = profiles.firstNotNullOfOrNull { name ->
                val profile = catalog.email[name] ?: return@firstNotNullOfOrNull null
                if (!profile.enabled) return@firstNotNullOfOrNull null
                val address = person.address("email") ?: return@firstNotNullOfOrNull null
                profile to address
            } ?: return@mapNotNull null
            Recipient(person.username, person.displayName, chosen.second, chosen.first)
        }
    }

    private fun rosterUsers(roster: String, ignoreSchedules: Boolean): List<Person> {
        val users = catalog.rosters[roster]?.users ?: return emptyList()
        val now = clock.instant()
        return users.mapNotNull { name ->
            val person = catalog.people[name] ?: return@mapNotNull null
            if (!person.enabled) return@mapNotNull null
            if (!ignoreSchedules && !Schedules.onShift(person, catalog, now, zone)) return@mapNotNull null
            person
        }
    }

    private fun message(
        inst: Tracked,
        recipient: Recipient,
        subject: String?,
        body: String?,
        from: String,
        sendAck: Boolean,
        testMode: Boolean,
        retries: Int,
        retryDelay: Duration,
    ): OutboundMail {
        inst.props["ackUserName"] = recipient.displayName
        val props = inst.props
        val renderedSubject = AlarmText.render(inst.customSubject ?: subject ?: "[{priorityName}] {name}", props, zone)
        val renderedBody = AlarmText.render(
            inst.customMessage ?: body ?: "{name} at {displayPath} transitioned to {eventState} at {eventTime|HH:mm:ss} with value {eventValue}.",
            props,
            zone,
        )
        return OutboundMail(
            from = from.ifBlank { recipient.profile.fromAddress },
            to = recipient.address,
            subject = renderedSubject,
            body = renderedBody,
            testMode = testMode,
            eventId = inst.eventId,
            username = recipient.username,
            profile = recipient.profile.name,
            ackLink = sendAck && recipient.profile.twoWayEnabled,
            emailProfile = recipient.profile.name,
            smtpProfile = recipient.profile.smtpProfile,
            retries = retries,
            retryDelayMillis = retryDelay.toMillis(),
        )
    }

    private fun considerOneWay(notice: AlarmNotice, mail: MutableList<OutboundMail>) {
        for (profile in catalog.oneWay.values) {
            if (!profile.enabled) continue
            if (notice.priority < profile.minPriority || notice.priority > profile.maxPriority) continue
            val hold = holds.getOrPut(profile.name) { Hold() }
            val now = clock.instant()
            if (!profile.consolidationEnabled) {
                deliverOneWay(profile, listOf(notice), mail)
                continue
            }
            hold.events += notice
            if (hold.quietUntil != null && now.isBefore(hold.quietUntil)) {
                if (hold.releaseAt == null) hold.releaseAt = hold.quietUntil
                continue
            }
            if (profile.delaySeconds <= 0.0) {
                deliverOneWay(profile, hold.events.toList(), mail)
                hold.events.clear()
                hold.releaseAt = null
                if (profile.frequencySeconds > 0) hold.quietUntil = now.plusMillis((profile.frequencySeconds * 1000.0).toLong())
                continue
            }
            if (hold.releaseAt == null) hold.releaseAt = now.plusMillis((profile.delaySeconds * 1000.0).toLong())
        }
    }

    private fun releaseHolds(mail: MutableList<OutboundMail>) {
        val now = clock.instant()
        for ((name, hold) in holds) {
            val release = hold.releaseAt ?: continue
            if (now.isBefore(release)) continue
            val profile = catalog.oneWay[name] ?: continue
            if (hold.events.isNotEmpty()) deliverOneWay(profile, hold.events.toList(), mail)
            hold.events.clear()
            hold.releaseAt = null
            hold.quietUntil = if (profile.frequencySeconds > 0) now.plusMillis((profile.frequencySeconds * 1000.0).toLong()) else null
        }
    }

    private fun deliverOneWay(profile: OneWayProfile, events: List<AlarmNotice>, mail: MutableList<OutboundMail>) {
        if (events.isEmpty()) return
        val recipients = rosterUsers(profile.roster, ignoreSchedules = false).mapNotNull { person ->
            val address = person.address("email") ?: return@mapNotNull null
            person to address
        }
        if (recipients.isEmpty()) return
        val maps = events.map { noticeMap(it).also { props -> decorateCount(props, events) } }
        val template = when {
            events.size > 1 -> profile.consolidatedMessage
                ?: "{count} alarms: {{ {name} at {displayPath} is {eventState}. }}"
            else -> events.first().emailMessage
                ?: "{name} at {displayPath} transitioned to {eventState} at {eventTime|HH:mm:ss} with value {eventValue}."
        }
        val body = AlarmText.render(template, maps, zone)
        val subject = events.first().emailSubject
            ?: if (events.size > 1) "${events.size} alarms" else "[${events.first().priority.label()}] ${events.first().name}"
        for ((person, address) in recipients) {
            mail += OutboundMail(
                from = profile.fromAddress,
                to = address,
                subject = subject,
                body = body,
                testMode = false,
                eventId = events.first().eventId,
                username = person.username,
                profile = profile.name,
                ackLink = false,
                emailProfile = null,
                smtpProfile = profile.smtpProfile,
                retries = 0,
                retryDelayMillis = 0,
            )
        }
    }

    private fun decorateCount(props: MutableMap<String, Any?>, events: List<AlarmNotice>) {
        val maxP = events.maxOf { it.priority }
        val minP = events.minOf { it.priority }
        props["count"] = events.size
        props["AlarmEvents.Count"] = events.size
        props["AlarmEvents.MaxPriority"] = maxP.label()
        props["AlarmEvents.MinPriority"] = minP.label()
    }

    private fun seed(notice: AlarmNotice): LinkedHashMap<String, Any?> {
        val props = noticeMap(notice)
        notice.associatedData.forEach { (key, value) -> props.putIfAbsent(key, numeric(value)) }
        return props
    }

    /**
     * Associated data entered as text compares as a number in a pipeline expression when it reads as one and prints the
     * same either way ("35" and "1.5", not "1.50" or "007"), so `{Margin} > 20` filters as written. Doc source: FEATURES ALA-008.
     */
    private fun numeric(value: Any?): Any? {
        if (value !is String) return value
        value.toLongOrNull()?.let { if (it.toString() == value) return it }
        value.toDoubleOrNull()?.let { if (it.isFinite() && it.toString() == value) return it }
        return value
    }

    private fun noticeMap(notice: AlarmNotice): LinkedHashMap<String, Any?> {
        val props = linkedMapOf<String, Any?>(
            "name" to notice.name,
            "displayPath" to notice.displayPath,
            "displayPathOrSource" to notice.displayPath.ifBlank { notice.source },
            "source" to notice.source,
            "label" to (notice.label ?: ""),
            "priority" to notice.priority.code,
            "priorityName" to notice.priority.label(),
            "eventState" to eventState(notice.trigger),
            "eventTime" to when (notice.trigger) {
                "clear" -> notice.clearTime ?: notice.activeTime
                "ack" -> notice.ackTime ?: notice.activeTime
                else -> notice.activeTime
            },
            "activeTime" to notice.activeTime,
            "clearTime" to notice.clearTime,
            "ackTime" to notice.ackTime,
            "ackUser" to notice.ackUser,
            "eventValue" to notice.value,
            "Value" to notice.value,
            "notes" to (notice.notes ?: ""),
            "isActive" to notice.active,
            "isClear" to !notice.active,
            "isAcked" to notice.acknowledged,
            "isShelved" to notice.shelved,
            "state" to notice.stateLabel,
        )
        return props
    }

    private fun refresh(inst: Tracked) {
        val live = states(inst.eventId)
        val active = live?.active ?: false
        val acked = live?.acknowledged ?: true
        val shelved = live?.shelved ?: false
        inst.props["isActive"] = active
        inst.props["isClear"] = !active
        inst.props["isAcked"] = acked
        inst.props["isShelved"] = shelved
        inst.props["state"] = runtimeState(active, acked).label
        inst.props["eventState"] = inst.eventState
        if (live != null) {
            inst.props["ackUser"] = live.ackUser
            inst.props["ackTime"] = live.ackTime
            inst.props["clearTime"] = live.clearTime
            inst.props["eventValue"] = live.value
            inst.props["Value"] = live.value
            inst.props["displayPath"] = live.displayPath
            inst.props["priority"] = live.priority.code
            inst.props["priorityName"] = live.priority.label()
        }
    }

    private fun dropped(inst: Tracked): Boolean {
        val active = inst.props["isActive"] == true
        val acked = inst.props["isAcked"] == true
        val shelved = inst.props["isShelved"] == true
        if (inst.pipeline.dropOnAck && acked) return true
        if (inst.pipeline.dropOnClear && !active) return true
        if (inst.pipeline.dropOnShelve && shelved) return true
        return false
    }

    private fun same(expected: Any?, actual: Any?): Boolean {
        if (expected == null || actual == null) return expected == actual
        val left = num(expected)
        val right = num(actual)
        if (left != null && right != null) return left == right
        return expected.toString() == actual.toString()
    }

    private fun eventState(trigger: String) = when (trigger) {
        "clear" -> "Cleared"
        "ack" -> "Acknowledged"
        else -> "Active"
    }

    private class Tracked(
        val eventId: String,
        var pipelineName: String,
        var pipeline: PipelineDef,
        var blockId: String,
        val props: LinkedHashMap<String, Any?>,
        var wakeAt: Instant? = null,
        var cursor: Any? = null,
        var steps: Int = 0,
        var alive: Boolean = true,
        val customSubject: String?,
        val customMessage: String?,
        val eventState: String,
    ) {
        fun copyAt(blockId: String) = Tracked(
            eventId, pipelineName, pipeline, blockId, LinkedHashMap(props),
            customSubject = customSubject, customMessage = customMessage, eventState = eventState,
        )
    }

    private data class NotifyCursor(val sent: Set<String>)
    private data class EscCursor(val step: Int, val attempt: Int, val waiting: Boolean)
    private object DelayMark
    private data class Recipient(val username: String, val displayName: String, val address: String, val profile: EmailProfile)
    private class Hold(val events: ArrayList<AlarmNotice> = ArrayList(), var releaseAt: Instant? = null, var quietUntil: Instant? = null)
}

/** Merge directory users with contact records. A contact record's list replaces the directory email and phone. */
fun mergePeople(users: List<DirectoryUser>, contacts: Map<String, ContactRecord>): Map<String, Person> {
    val out = linkedMapOf<String, Person>()
    for (user in users) {
        val record = contacts[user.username]
        val points = record?.contacts?.takeIf { it.isNotEmpty() } ?: buildList {
            if (user.email.isNotBlank()) add(ContactPoint("email", user.email))
            if (user.phone.isNotBlank()) add(ContactPoint("phone", user.phone))
        }
        out[user.username] = Person(
            username = user.username,
            displayName = user.displayName.ifBlank { user.username },
            enabled = user.enabled,
            schedule = record?.schedule ?: Schedules.ALWAYS,
            contacts = points,
            adjustments = record?.adjustments ?: emptyList(),
        )
    }
    return out
}

data class DirectoryUser(
    val username: String,
    val displayName: String,
    val email: String,
    val phone: String,
    val enabled: Boolean,
)

/** An alarm event currently inside a notification pipeline. [waitingUntil] is set while it sits in a delay. */
data class InFlightEvent(
    val eventId: String,
    val pipeline: String,
    val block: String,
    val alarm: String,
    val displayPath: String,
    val state: String,
    val waitingUntil: Instant?,
)
