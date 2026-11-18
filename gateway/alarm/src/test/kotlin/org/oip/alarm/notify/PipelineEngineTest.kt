package org.oip.alarm.notify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.oip.alarm.AlarmLiveState
import org.oip.alarm.AlarmNotice
import org.oip.alarm.AlarmPriority
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Each pipeline block, roster and schedule resolution, escalation timing, and dropout.
 *
 */
class PipelineEngineTest {
    @Test fun eachBlockRunsAndAJumpKeepsTheDestinationDropoutRules() {
        val world = World()
        val notified = pipe(
            "dest",
            start("s", "n"),
            note("n", roster = "operators", subject = "calls {numCalls}"),
            dropOnAck = false,
        )
        val source = pipe(
            "src",
            mapOf("id" to "s", "type" to "start", "next" to "split"),
            mapOf("id" to "split", "type" to "splitter", "outputs" to listOf("sw", "expr")),
            mapOf(
                "id" to "sw", "type" to "switch", "expression" to "{priority}",
                "cases" to listOf(mapOf("equals" to 4, "next" to "set")),
                "catchAll" to "end-a",
            ),
            mapOf("id" to "set", "type" to "setProperty", "property" to "numCalls", "expression" to "1", "next" to "jump"),
            mapOf("id" to "jump", "type" to "jump", "pipeline" to "dest"),
            mapOf("id" to "expr", "type" to "expression", "expression" to "{priority}=\"Critical\"", "whenTrue" to "end-b", "whenFalse" to "end-b"),
            mapOf("id" to "end-a", "type" to "end"),
            mapOf("id" to "end-b", "type" to "end"),
        )
        world.use(base(source, notified))
        val mail = world.fire(notice("e1", "src", AlarmPriority.CRITICAL))
        assertEquals(listOf("ada@example.com"), mail.map { it.to })
        assertEquals("calls 1", mail.single().subject)
        assertTrue(mail.single().ackLink)
        assertEquals(0, world.engine.inFlight())
    }

    @Test fun switchAndExpressionChooseByPriorityAndLike() {
        val world = World()
        val pipeline = pipe(
            "route",
            start("s", "sw"),
            mapOf(
                "id" to "sw", "type" to "switch", "expression" to "{priority}",
                "cases" to listOf(
                    mapOf("equals" to 4, "next" to "crit"),
                    mapOf("equals" to 3, "next" to "high"),
                ),
                "catchAll" to "low",
            ),
            note("crit", roster = "managers", subject = "crit"),
            note("high", roster = "operators", subject = "high"),
            note("low", roster = "operators", subject = "low"),
        )
        val branched = pipe(
            "branch",
            start("s", "ex"),
            mapOf(
                "id" to "ex", "type" to "expression",
                "expression" to "{name} like \"Tank*\"",
                "whenTrue" to "yes", "whenFalse" to "no",
            ),
            note("yes", subject = "yes"),
            note("no", subject = "no"),
        )
        world.use(base(pipeline, branched))
        assertEquals("crit", world.fire(notice("c", "route", AlarmPriority.CRITICAL)).single().subject)
        assertEquals("high", world.fire(notice("h", "route", AlarmPriority.HIGH)).single().subject)
        assertEquals("low", world.fire(notice("l", "route", AlarmPriority.LOW)).single().subject)
        assertEquals("yes", world.fire(notice("t", "branch", name = "TankHigh")).single().subject)
        assertEquals("no", world.fire(notice("o", "branch", name = "Other")).single().subject)
    }

    @Test fun aBranchFiltersOnAlarmAssociatedData() {
        val world = World()
        val byArea = pipe(
            "by-area",
            start("s", "ex"),
            mapOf("id" to "ex", "type" to "expression", "expression" to "{Area} = \"North\"", "whenTrue" to "yes", "whenFalse" to "no"),
            note("yes", subject = "north"),
            note("no", subject = "other"),
        )
        val byMargin = pipe(
            "by-margin",
            start("s", "ex"),
            mapOf("id" to "ex", "type" to "expression", "expression" to "{Margin} > 20", "whenTrue" to "yes", "whenFalse" to "no"),
            note("yes", subject = "wide"),
            note("no", subject = "narrow"),
        )
        val bySwitch = pipe(
            "by-switch",
            start("s", "sw"),
            mapOf(
                "id" to "sw", "type" to "switch", "expression" to "associatedData(\"Area\")",
                "cases" to listOf(mapOf("equals" to "North", "next" to "yes")),
                "catchAll" to "no",
            ),
            note("yes", subject = "north"),
            note("no", subject = "other"),
        )
        val byDefined = pipe(
            "by-defined",
            start("s", "ex"),
            mapOf("id" to "ex", "type" to "expression", "expression" to "isPropertyDefined(\"Area\") && associatedData(\"Area\", \"none\") like \"No*\"", "whenTrue" to "yes", "whenFalse" to "no"),
            note("yes", subject = "tagged"),
            note("no", subject = "untagged"),
        )
        world.use(base(byArea, byMargin, bySwitch, byDefined))
        val north = mapOf("Area" to "North", "Margin" to 50.0)
        val south = mapOf("Area" to "South", "Margin" to 5)
        assertEquals("north", world.fire(notice("a1", "by-area", associated = north)).single().subject)
        assertEquals("other", world.fire(notice("a2", "by-area", associated = south)).single().subject)
        assertEquals("other", world.fire(notice("a3", "by-area")).single().subject)
        assertEquals("wide", world.fire(notice("m1", "by-margin", associated = north)).single().subject)
        assertEquals("narrow", world.fire(notice("m2", "by-margin", associated = south)).single().subject)
        // Associated data kept as text still compares as a number.
        assertEquals("wide", world.fire(notice("m3", "by-margin", associated = mapOf("Margin" to "35"))).single().subject)
        assertEquals("north", world.fire(notice("s1", "by-switch", associated = north)).single().subject)
        assertEquals("other", world.fire(notice("s2", "by-switch", associated = south)).single().subject)
        assertEquals("tagged", world.fire(notice("d1", "by-defined", associated = north)).single().subject)
        assertEquals("untagged", world.fire(notice("d2", "by-defined")).single().subject)
    }

    @Test fun failedSwitchUsesCatchAllAndFailedExpressionIsFalse() {
        val world = World()
        val pipeline = pipe(
            "bad",
            start("s", "sw"),
            mapOf("id" to "sw", "type" to "switch", "expression" to "1+", "cases" to emptyList<Any>(), "catchAll" to "ok"),
            note("ok", subject = "caught"),
        )
        val expr = pipe(
            "flag",
            start("s", "ex"),
            mapOf("id" to "ex", "type" to "expression", "expression" to "1+", "whenTrue" to "yes", "whenFalse" to "no"),
            note("yes", subject = "yes"),
            note("no", subject = "no"),
        )
        world.use(base(pipeline, expr))
        assertEquals("caught", world.fire(notice("a", "bad")).single().subject)
        assertEquals("no", world.fire(notice("b", "flag")).single().subject)
    }

    @Test fun delayWaitsForTheClockAndEndStops() {
        val world = World()
        val pipeline = pipe(
            "wait",
            start("s", "d"),
            mapOf("id" to "d", "type" to "delay", "seconds" to 30, "next" to "n"),
            note("n", subject = "after"),
        )
        val stopped = pipe("done", start("s", "e"), mapOf("id" to "e", "type" to "end"))
        world.use(base(pipeline, stopped))
        assertTrue(world.fire(notice("a", "wait")).isEmpty())
        assertEquals(1, world.engine.inFlight("wait"))
        // The status page lists the event, where it waits and until when (ALA-029).
        val flying = world.engine.inFlightEvents().single()
        assertEquals("a", flying.eventId)
        assertEquals("wait", flying.pipeline)
        assertEquals("d", flying.block)
        assertEquals("High", flying.alarm)
        assertEquals("tank/level/High", flying.displayPath)
        assertEquals(Instant.parse("2026-06-01T09:00:30Z"), flying.waitingUntil)
        world.clock.advance(Duration.ofSeconds(29))
        assertTrue(world.engine.poll().isEmpty())
        world.clock.advance(Duration.ofSeconds(1))
        assertEquals("after", world.engine.poll().single().subject)
        assertEquals(0, world.engine.inFlight())
        assertTrue(world.engine.inFlightEvents().isEmpty())
        assertTrue(world.fire(notice("b", "done")).isEmpty())
        assertEquals(0, world.engine.inFlight())
    }

    @Test fun delayBetweenRosterUsersAndDropoutOnAckSkipsTheRest() {
        val world = World()
        val pipeline = pipe(
            "fan",
            start("s", "n"),
            note("n", delayBetweenSeconds = 30),
        )
        world.use(base(pipeline, people = people("ada", "bea")))
        val first = world.fire(notice("e", "fan"))
        assertEquals(listOf("ada@example.com"), first.map { it.to })
        world.live["e"] = live(acknowledged = true)
        world.clock.advance(Duration.ofSeconds(30))
        assertTrue(world.engine.poll().isEmpty())
        assertEquals(0, world.engine.inFlight())
    }

    @Test fun shelveAndClearDropThePipeline() {
        val world = World()
        val pipeline = pipe(
            "fan",
            start("s", "d"),
            mapOf("id" to "d", "type" to "delay", "seconds" to 10, "next" to "n"),
            note("n", subject = "later"),
        )
        world.use(base(pipeline))
        world.fire(notice("shelved", "fan"))
        world.live["shelved"] = live(shelved = true)
        world.clock.advance(Duration.ofSeconds(10))
        assertTrue(world.engine.poll().isEmpty())
        world.fire(notice("cleared", "fan", active = false))
        world.live["cleared"] = live(active = false)
        world.clock.advance(Duration.ofSeconds(10))
        assertTrue(world.engine.poll().isEmpty())
    }

    @Test fun ackPipelineMustDisableDropOnAck() {
        val world = World()
        val dropped = pipe("ack-drop", start("s", "n"), note("n", subject = "nope"))
        val kept = pipe("ack-keep", start("s", "n"), note("n", subject = "kept"), dropOnAck = false)
        world.use(base(dropped, kept))
        val ack = notice("e", "ack-drop", trigger = "ack", acknowledged = true)
        world.live["e"] = live(acknowledged = true)
        assertTrue(world.fire(ack).isEmpty())
        val keptNotice = notice("k", "ack-keep", trigger = "ack", acknowledged = true)
        world.live["k"] = live(acknowledged = true)
        assertEquals("kept", world.fire(keptNotice).single().subject)
    }

    @Test fun escalationTimingAndAckDropout() {
        val world = World(Instant.parse("2026-06-01T09:00:00Z"))
        val pipeline = pipe(
            "esc",
            start("s", "e"),
            mapOf(
                "id" to "e", "type" to "escalation",
                "steps" to listOf(
                    mapOf("roster" to "operators", "profile" to "mail", "attempts" to 2, "delaySeconds" to 60),
                    mapOf("roster" to "managers", "profile" to "mail", "attempts" to 1, "delaySeconds" to 60),
                ),
            ),
        )
        world.use(base(pipeline))
        assertEquals(listOf("ada@example.com"), world.fire(notice("e", "esc")).map { it.to })
        world.clock.advance(Duration.ofSeconds(59))
        assertTrue(world.engine.poll().isEmpty())
        world.clock.advance(Duration.ofSeconds(1))
        assertEquals(listOf("ada@example.com"), world.engine.poll().map { it.to })
        world.clock.advance(Duration.ofSeconds(60))
        assertEquals(listOf("mia@example.com"), world.engine.poll().map { it.to })
        assertEquals(0, world.engine.inFlight())

        assertEquals(listOf("ada@example.com"), world.fire(notice("drop", "esc")).map { it.to })
        world.live["drop"] = live(acknowledged = true)
        world.clock.advance(Duration.ofSeconds(60))
        assertTrue(world.engine.poll().isEmpty())
        assertEquals(0, world.engine.inFlight())
    }

    @Test fun publishedEscalationProcedureNotifiesThreeTimesThenManagers() {
        val world = World()
        val pipeline = pipe(
            "loop",
            start("s", "zero"),
            mapOf("id" to "zero", "type" to "setProperty", "property" to "numCalls", "expression" to "0", "next" to "n"),
            note("n", roster = "operators", subject = "ops {numCalls}", next = "wait"),
            mapOf("id" to "wait", "type" to "delay", "seconds" to 300, "next" to "bump"),
            mapOf("id" to "bump", "type" to "setProperty", "property" to "numCalls", "expression" to "toInt({numCalls})+1", "next" to "check"),
            mapOf(
                "id" to "check", "type" to "expression", "expression" to "toInt({numCalls})<3",
                "whenTrue" to "n", "whenFalse" to "boss",
            ),
            note("boss", roster = "managers", subject = "managers"),
        )
        world.use(base(pipeline))
        assertEquals("ops 0", world.fire(notice("e", "loop")).single().subject)
        world.clock.advance(Duration.ofSeconds(300))
        assertEquals("ops 1", world.engine.poll().single().subject)
        world.clock.advance(Duration.ofSeconds(300))
        assertEquals("ops 2", world.engine.poll().single().subject)
        world.clock.advance(Duration.ofSeconds(300))
        val managers = world.engine.poll().single()
        assertEquals("managers", managers.subject)
        assertEquals("mia@example.com", managers.to)
        assertEquals(0, world.engine.inFlight())
    }

    @Test fun rosterExpressionSchedulesAndAdjustments() {
        val start = Instant.parse("2026-06-01T09:00:00Z")
        val world = World(start)
        val day = NotifyCodec.schedule("day", mapOf("monday" to "8:00-12:00, 12:45-17:00", "observeHolidays" to false))
        val night = NotifyCodec.schedule("night", mapOf("allDays" to "0:00-8:00, 17:00-24:00", "observeHolidays" to false))
        val both = NotifyCodec.schedule("both", mapOf("type" to "composite", "scheduleOne" to "day", "scheduleTwo" to "night"))
        val weekly = NotifyCodec.schedule(
            "weekly",
            mapOf(
                "allDays" to "0:00-24:00", "observeHolidays" to false,
                "repeat" to "weekly", "repeatOn" to 1, "repeatOff" to 1, "repeatStart" to "2026-06-01",
            ),
        )
        val holiday = NotifyCodec.holiday("new", mapOf("date" to "06-01-2026", "repeatAnnually" to true))
        val closed = NotifyCodec.schedule("closed", mapOf("allDays" to "0:00-24:00", "observeHolidays" to true))
        val open = NotifyCodec.schedule("open", mapOf("allDays" to "0:00-24:00", "observeHolidays" to false))
        val adaOff = ContactRecord(
            "ada", "day", listOf(ContactPoint("email", "ada@example.com")),
            listOf(Adjustment(start, start.plus(Duration.ofHours(1)), false, "out")),
        )
        val beaDay = ContactRecord("bea", "day", listOf(ContactPoint("email", "bea@example.com")), emptyList())
        val miaOn = ContactRecord(
            "mia", "day", listOf(ContactPoint("email", "mia@example.com")),
            listOf(
                Adjustment(start, start.plus(Duration.ofMinutes(30)), false, "first"),
                Adjustment(start, start.plus(Duration.ofMinutes(30)), true, "last wins"),
            ),
        )
        val catalog = NotifyCatalog(
            email = mapOf("mail" to mailProfile()),
            rosters = mapOf(
                "operators" to Roster("operators", listOf("ada", "bea", "gone")),
                "managers" to Roster("managers", listOf("mia")),
                "named" to Roster("named", listOf("bea")),
            ),
            schedules = mapOf("day" to day, "night" to night, "both" to both, "weekly" to weekly, "closed" to closed, "open" to open),
            holidays = listOf(holiday),
            people = mergePeople(
                listOf(
                    DirectoryUser("ada", "Ada", "directory@example.com", "", true),
                    DirectoryUser("bea", "Bea", "bea@example.com", "", true),
                    DirectoryUser("mia", "Mia", "mia@example.com", "", true),
                    DirectoryUser("cal", "Cal", "cal@example.com", "", false),
                ),
                mapOf("ada" to adaOff, "bea" to beaDay, "mia" to miaOn),
            ),
            pipelines = mapOf(
                "by-priority" to pipe(
                    "by-priority",
                    start("s", "n"),
                    note("n", roster = null, rosterExpression = "if({priority}>2, \"managers\", \"operators\")"),
                ),
                "bare" to pipe("bare", start("s", "n"), note("n", roster = null, rosterExpression = "named")),
                "ignore" to pipe("ignore", start("s", "n"), note("n", roster = "operators", ignoreSchedules = true, next = null)),
            ),
        )
        world.use(catalog)
        assertEquals(listOf("mia"), world.engine.onCall("managers"))
        assertEquals(listOf("bea"), world.engine.onCall("operators"))
        assertTrue(world.engine.scheduleActive("always"))
        assertTrue(world.engine.scheduleActive("day"))
        assertFalse(world.engine.scheduleActive("night"))
        assertTrue(world.engine.scheduleActive("both"))
        assertTrue(world.engine.scheduleActive("weekly"))
        assertFalse(world.engine.scheduleActive("closed"))
        assertTrue(world.engine.scheduleActive("open"))
        world.clock.set(Instant.parse("2026-06-01T12:15:00Z"))
        assertFalse(world.engine.scheduleActive("day"))
        assertTrue(world.engine.onCall("operators").isEmpty())
        world.clock.set(Instant.parse("2026-06-01T13:00:00Z"))
        assertTrue(world.engine.scheduleActive("day"))
        assertEquals(listOf("ada", "bea"), world.engine.onCall("operators"))
        world.clock.set(Instant.parse("2026-06-01T03:00:00Z"))
        assertTrue(world.engine.scheduleActive("night"))
        assertTrue(world.engine.onCall("operators").isEmpty())
        world.clock.set(Instant.parse("2026-06-08T13:00:00Z"))
        assertFalse(world.engine.scheduleActive("weekly"))
        world.clock.set(start)
        assertEquals(listOf("mia@example.com"), world.fire(notice("p", "by-priority", AlarmPriority.HIGH)).map { it.to })
        assertEquals(listOf("bea@example.com"), world.fire(notice("q", "by-priority", AlarmPriority.MEDIUM)).map { it.to })
        assertEquals(listOf("bea@example.com"), world.fire(notice("r", "bare")).map { it.to })
        val ignored = world.fire(notice("s", "ignore")).map { it.to }
        assertEquals(listOf("ada@example.com", "bea@example.com"), ignored)
        assertFalse(ignored.contains("directory@example.com"))
    }

    @Test fun oneWayRespectsPriorityAndConsolidationAndHasNoAckLink() {
        val world = World()
        val one = OneWayProfile(
            name = "simple", enabled = true, smtpProfile = "plant", roster = "operators",
            minPriority = AlarmPriority.HIGH, maxPriority = AlarmPriority.CRITICAL, fromAddress = "alarms@example.com",
            consolidationEnabled = true, delaySeconds = 60.0, frequencySeconds = 120.0, consolidatedMessage = null,
        )
        world.use(base(oneWay = mapOf("simple" to one)))
        assertTrue(world.fire(notice("low", null, AlarmPriority.LOW)).isEmpty())
        assertTrue(world.fire(notice("a", null, AlarmPriority.HIGH, name = "Alpha")).isEmpty())
        world.fire(notice("b", null, AlarmPriority.CRITICAL, name = "Beta"))
        world.clock.advance(Duration.ofSeconds(60))
        val consolidated = world.engine.poll().single()
        assertFalse(consolidated.ackLink)
        assertEquals("2 alarms", consolidated.subject)
        assertTrue(consolidated.body.contains("Alpha"))
        assertTrue(consolidated.body.contains("Beta"))
        assertTrue(world.fire(notice("c", null, AlarmPriority.HIGH, name = "Gamma")).isEmpty())
        world.clock.advance(Duration.ofSeconds(59))
        assertTrue(world.engine.poll().isEmpty())
        world.clock.advance(Duration.ofSeconds(61))
        assertTrue(world.engine.poll().single().body.contains("Gamma"))
    }

    @Test fun testModeRendersWithoutAnAckLinkWhenTwoWayIsOffAndCustomTemplateWins() {
        val world = World()
        val pipeline = pipe(
            "t",
            start("s", "n"),
            note("n", subject = "block", message = "block {area}", testMode = true, sendAckLink = true),
        )
        world.use(base(pipeline, email = mailProfile(twoWay = false)))
        val mail = world.fire(
            notice("e", "t", subject = "custom {name}", message = "area {area}", associated = mapOf("area" to "North")),
        ).single()
        assertTrue(mail.testMode)
        assertFalse(mail.ackLink)
        assertEquals("custom High", mail.subject)
        assertEquals("area North", mail.body)
    }

    @Test fun editingAPipelineDoesNotRetargetAnEventAlreadyInsideIt() {
        val world = World()
        val original = pipe(
            "p",
            start("s", "d"),
            mapOf("id" to "d", "type" to "delay", "seconds" to 10, "next" to "n"),
            note("n", roster = "operators", subject = "operators"),
        )
        world.use(base(original))
        assertTrue(world.fire(notice("e", "p")).isEmpty())
        val edited = pipe(
            "p",
            start("s", "d"),
            mapOf("id" to "d", "type" to "delay", "seconds" to 10, "next" to "n"),
            note("n", roster = "managers", subject = "managers"),
        )
        world.use(base(edited))
        world.clock.advance(Duration.ofSeconds(10))
        assertEquals("operators", world.engine.poll().single().subject)
    }

    @Test fun aJumpCycleWithoutADelayStops() {
        val world = World()
        val a = pipe("a", start("s", "j"), mapOf("id" to "j", "type" to "jump", "pipeline" to "b"))
        val b = pipe("b", start("s", "j"), mapOf("id" to "j", "type" to "jump", "pipeline" to "a"))
        world.use(base(a, b))
        assertTrue(world.fire(notice("e", "a")).isEmpty())
        assertEquals(0, world.engine.inFlight())
    }

    @Test fun missingJumpTargetEndsTheInstance() {
        val world = World()
        val pipeline = pipe("a", start("s", "j"), mapOf("id" to "j", "type" to "jump", "pipeline" to "missing"))
        world.use(base(pipeline))
        assertTrue(world.fire(notice("e", "a")).isEmpty())
        assertEquals(0, world.engine.inFlight())
    }

    private fun base(
        vararg pipelines: PipelineDef,
        people: Map<String, Person> = people("ada", "mia"),
        email: EmailProfile = mailProfile(),
        oneWay: Map<String, OneWayProfile> = emptyMap(),
    ) = NotifyCatalog(
        email = mapOf(email.name to email),
        oneWay = oneWay,
        rosters = mapOf(
            "operators" to Roster("operators", listOf("ada")),
            "managers" to Roster("managers", listOf("mia")),
        ),
        pipelines = pipelines.associateBy { it.name },
        people = people,
    )

    private fun people(vararg names: String, roster: Boolean = true): Map<String, Person> =
        names.associateWith { name ->
            Person(name, name, true, Schedules.ALWAYS, listOf(ContactPoint("email", "$name@example.com")), emptyList())
        }.let { if (roster) it else it }

    private fun mailProfile(twoWay: Boolean = true) = EmailProfile(
        name = "mail", enabled = true, useSmtpProfile = false, smtpProfile = null,
        hostname = "127.0.0.1", port = 25, useSsl = false, startTls = false,
        username = "", passwordRef = "", timeoutMillis = 1000, sslProtocols = "TLSv1.2", debug = false,
        fromAddress = "alarms@example.com", twoWayEnabled = twoWay, gatewayAddress = "127.0.0.1:8088", sendHttpsLink = false,
    )

    private fun pipe(name: String, vararg blocks: Map<String, Any?>, dropOnAck: Boolean = true, dropOnClear: Boolean = true, dropOnShelve: Boolean = true) =
        NotifyCodec.pipeline(
            name,
            mapOf(
                "dropOnAck" to dropOnAck,
                "dropOnClear" to dropOnClear,
                "dropOnShelve" to dropOnShelve,
                "blocks" to blocks.toList(),
            ),
        )

    private fun start(id: String, next: String) = mapOf("id" to id, "type" to "start", "next" to next)

    private fun note(
        id: String,
        roster: String? = "operators",
        rosterExpression: String? = null,
        subject: String? = null,
        message: String? = null,
        next: String? = null,
        delayBetweenSeconds: Int? = null,
        testMode: Boolean = false,
        sendAckLink: Boolean = true,
        ignoreSchedules: Boolean = false,
    ): Map<String, Any?> {
        val spec = linkedMapOf<String, Any?>(
            "id" to id,
            "type" to "notification",
            "profile" to "mail",
            "sendAckLink" to sendAckLink,
            "testMode" to testMode,
            "ignoreSchedules" to ignoreSchedules,
        )
        if (roster != null) spec["roster"] = roster
        if (rosterExpression != null) spec["rosterExpression"] = rosterExpression
        if (subject != null) spec["subject"] = subject
        if (message != null) spec["message"] = message
        if (next != null) spec["next"] = next
        if (delayBetweenSeconds != null) spec["delayBetweenSeconds"] = delayBetweenSeconds
        return spec
    }

    private fun notice(
        id: String,
        pipeline: String?,
        priority: AlarmPriority = AlarmPriority.HIGH,
        name: String = "High",
        trigger: String = "active",
        active: Boolean = trigger != "clear",
        acknowledged: Boolean = trigger == "ack",
        subject: String? = null,
        message: String? = null,
        associated: Map<String, Any?> = emptyMap(),
    ) = AlarmNotice(
        trigger = trigger,
        eventId = id,
        source = "prov:default:/tag:tank/level:/alm:$name",
        name = name,
        displayPath = "tank/level/$name",
        priority = priority,
        active = active,
        acknowledged = acknowledged,
        shelved = false,
        value = 150,
        activeTime = Instant.parse("2026-06-01T09:00:00Z"),
        clearTime = null,
        ackTime = null,
        ackUser = if (acknowledged) "ada" else null,
        pipeline = pipeline,
        emailSubject = subject,
        emailMessage = message,
        associatedData = associated,
        notes = null,
        notesRequired = false,
        label = null,
        stateLabel = "Active and Unacknowledged",
    )

    private fun live(
        active: Boolean = true,
        acknowledged: Boolean = false,
        shelved: Boolean = false,
        priority: AlarmPriority = AlarmPriority.HIGH,
    ) = AlarmLiveState(active, acknowledged, shelved, null, null, null, 150, "tank/level/High", priority)

    private class World(start: Instant = Instant.parse("2026-06-01T09:00:00Z")) {
        val clock = MutableClock(start)
        val live = HashMap<String, AlarmLiveState>()
        val engine = NotificationEngine(clock, { id -> live[id] })

        fun use(catalog: NotifyCatalog) = engine.replace(catalog)

        fun fire(notice: AlarmNotice): List<OutboundMail> {
            live.putIfAbsent(
                notice.eventId,
                AlarmLiveState(
                    notice.active, notice.acknowledged, notice.shelved, notice.ackUser, notice.ackTime,
                    notice.clearTime, notice.value, notice.displayPath, notice.priority,
                ),
            )
            return engine.accept(notice)
        }
    }

    private class MutableClock(start: Instant) : Clock() {
        private var now = start
        fun set(instant: Instant) { now = instant }
        fun advance(duration: Duration) { now = now.plus(duration) }
        override fun instant(): Instant = now
        override fun millis(): Long = now.toEpochMilli()
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
