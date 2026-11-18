package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.alarm.AlarmEventType
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.config.ValidationException
import org.oip.security.AuditQuery
import org.oip.security.DefaultRoles
import org.oip.tags.TAG_KIND
import org.oip.tags.TAGS_MODULE
import java.nio.file.Path

/**
 * Alarm REST uses the existing role matrix: viewers may query, operators may ack and shelve.
 * The journal file is the runtime SQLite database from ADR 0008 and is still readable after a restart.
 * C-14 journal query and shelved list:
 */
class AlarmHttpTest {
    @TempDir lateinit var tmp: Path

    @Test fun rolesQueryAckAndShelve() {
        val gw = Gateway(tmp.resolve("roles"), port = 0).start()
        try {
            gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
            gw.createUser("op", setOf(DefaultRoles.OPERATOR))
            gw.createUser("view", setOf(DefaultRoles.VIEWER))
            gw.store.put(Scope.GATEWAY, tagResource("level", 0), actor = "test")
            gw.tags.write("[default]tank/level", 150)
            val anon = Http(gw.port())
            assertProblem(anon.get("/api/v1/alarms"), 401, "unauthorized")
            val view = client(gw, "view")
            val listed = view.get("/api/v1/alarms")
            assertEquals(200, listed.status, listed.body)
            val event = (listed.json["alarms"] as List<*>).single() as Map<*, *>
            assertEquals("Active and Unacknowledged", event["state"])
            assertEquals("prov:default:/tag:tank/level:/alm:High", event["source"])
            val id = event["eventId"] as String
            val source = event["source"] as String
            assertProblem(view.post("/api/v1/alarms/ack", """{"eventIds":["$id"]}"""), 403, "forbidden")
            assertProblem(view.post("/api/v1/alarms/shelve", """{"sources":["$source"],"durationSeconds":60}"""), 403, "forbidden")
            val op = client(gw, "op")
            val acked = op.post("/api/v1/alarms/ack", """{"eventIds":["$id"],"notes":"seen"}""")
            assertEquals(200, acked.status, acked.body)
            val after = (op.get("/api/v1/alarms").json["alarms"] as List<*>).single() as Map<*, *>
            assertEquals("Active and Acknowledged", after["state"])
            assertEquals("op", after["ackUser"])
            val shelved = op.post("/api/v1/alarms/shelve", """{"sources":["$source"],"durationSeconds":120}""")
            assertEquals(200, shelved.status, shelved.body)
            assertTrue((view.get("/api/v1/alarms").json["alarms"] as List<*>).isEmpty())
            assertProblem(op.post("/api/v1/alarms/ack", """{"eventIds":["missing"]}"""), 404, "not-found")
        } finally {
            gw.close()
        }
    }

    @Test fun unknownAlarmModeIsRejectedAndJournalRestarts() {
        val dir = tmp.resolve("restart")
        val gw = Gateway(dir, port = 0).start()
        val firstEvent: String
        try {
            gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
            assertThrows(ValidationException::class.java) {
                gw.store.put(Scope.GATEWAY, tagResource("bad", 0, mode = "NotAMode"), actor = "test")
            }
            gw.store.put(Scope.GATEWAY, tagResource("level", 0), actor = "test")
            gw.tags.write("[default]tank/level", 150)
            firstEvent = gw.alarms.journal.records().single { it.eventType == AlarmEventType.ACTIVE }.eventId
        } finally {
            gw.close()
        }
        val again = Gateway(dir, port = 0).start()
        try {
            // The journal row written before the restart is still there. The memory tag keeps its value 150 across
            // the restart (TAG-016), and the live alarm state is restored (review-3 F4): the same event is still
            // active, with no second active row.
            assertEquals(150.0, (again.tags.read("[default]tank/level").value as Number).toDouble())
            val rows = again.alarms.journal.records().filter { it.eventType == AlarmEventType.ACTIVE }
            assertEquals(1, rows.size, "$rows")
            assertEquals("prov:default:/tag:tank/level:/alm:High", rows.single().source)
            assertEquals(firstEvent, rows.single().eventId)
            val active = again.alarms.query().single()
            assertEquals(firstEvent, active.eventId)
            // The restarted journal keeps recording: clearing the alarm adds a clear row.
            again.tags.write("[default]tank/level", 0)
            assertEquals(1, again.alarms.journal.records().count { it.eventType == AlarmEventType.CLEAR })
            assertEquals("Cleared and Unacknowledged", again.alarms.query().single().state)
        } finally {
            again.close()
        }
    }

    /**
     * Review-3 F4: raise, acknowledge and shelve, then restart the gateway. The same event ids are live, the
     * acknowledgement and its user are kept, the shelf is still in force with its expiry and actor, and the journal
     * has no second active row.
     */
    @Test fun ackAndShelveSurviveAGatewayRestart() {
        val dir = tmp.resolve("restart-state")
        val level = "prov:default:/tag:tank/level:/alm:High"
        val other = "prov:default:/tag:tank/other:/alm:High"
        val gw = Gateway(dir, port = 0).start()
        val ids: Map<String, String>
        val until: Any?
        try {
            gw.createUser("op", setOf(DefaultRoles.OPERATOR))
            gw.store.put(Scope.GATEWAY, tagResource("level", 0), actor = "test")
            gw.store.put(Scope.GATEWAY, tagResource("other", 0, path = "tank/other"), actor = "test")
            gw.tags.write("[default]tank/level", 150)
            gw.tags.write("[default]tank/other", 150)
            ids = gw.alarms.query().associate { it.source to it.eventId }
            val op = client(gw, "op")
            assertEquals(200, op.post("/api/v1/alarms/ack", """{"eventIds":["${ids.getValue(level)}"],"notes":"seen"}""").status)
            assertEquals(200, op.post("/api/v1/alarms/shelve", """{"sources":["$other"],"durationSeconds":3600}""").status)
            until = (op.get("/api/v1/alarms?state=shelved").json["alarms"] as List<*>).map { it as Map<*, *> }.single()["shelvedUntil"]
        } finally {
            gw.close()
        }
        val again = Gateway(dir, port = 0).start()
        try {
            val op = client(again, "op")
            val live = (op.get("/api/v1/alarms").json["alarms"] as List<*>).map { it as Map<*, *> }.single()
            assertEquals(ids.getValue(level), live["eventId"])
            assertEquals("Active and Acknowledged", live["state"])
            assertEquals("op", live["ackUser"])
            val shelved = (op.get("/api/v1/alarms?state=shelved").json["alarms"] as List<*>).map { it as Map<*, *> }.single()
            assertEquals(other, shelved["source"])
            assertEquals(ids.getValue(other), shelved["eventId"])
            assertEquals(until, shelved["shelvedUntil"])
            assertEquals("op", shelved["shelvedBy"])
            val actives = again.alarms.journal.records().filter { it.eventType == AlarmEventType.ACTIVE }
            assertEquals(ids.values.toSet(), actives.map { it.eventId }.toSet())
            assertEquals(2, actives.size, "$actives")
        } finally {
            again.close()
        }
    }

    @Test fun journalQueryShelvedListRetentionAndPurge() {
        val gw = Gateway(tmp.resolve("journal"), port = 0).start()
        try {
            gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
            gw.createUser("op", setOf(DefaultRoles.OPERATOR))
            gw.createUser("view", setOf(DefaultRoles.VIEWER))
            gw.store.put(Scope.GATEWAY, tagResource("level", 0), actor = "test")
            gw.tags.write("[default]tank/level", 150)
            val view = client(gw, "view")
            val op = client(gw, "op")
            val admin = client(gw, "admin")
            val event = (view.get("/api/v1/alarms").json["alarms"] as List<*>).single() as Map<*, *>
            val id = event["eventId"] as String
            val source = event["source"] as String
            assertEquals(200, op.post("/api/v1/alarms/ack", """{"eventIds":["$id"],"notes":"seen"}""").status)
            assertEquals(200, op.post("/api/v1/alarms/shelve", """{"sources":["$source"],"durationSeconds":120}""").status)

            // Shelved list: expiry and actor. The default list is unchanged and still hides it.
            assertTrue((view.get("/api/v1/alarms").json["alarms"] as List<*>).isEmpty())
            val shelvedRes = view.get("/api/v1/alarms?state=shelved")
            assertEquals(200, shelvedRes.status, shelvedRes.body)
            val shelved = (shelvedRes.json["alarms"] as List<*>).single() as Map<*, *>
            assertEquals(source, shelved["source"])
            assertEquals(id, shelved["eventId"])
            assertEquals("Active and Acknowledged", shelved["state"])
            assertEquals(true, shelved["shelved"])
            assertEquals("op", shelved["shelvedBy"])
            val until = java.time.Instant.parse(shelved["shelvedUntil"] as String)
            val at = java.time.Instant.parse(shelved["shelvedAt"] as String)
            assertEquals(java.time.Duration.ofSeconds(120), java.time.Duration.between(at, until))
            assertProblem(view.get("/api/v1/alarms?state=bogus"), 400, "bad-request")

            // Journal: one row per event, with actors; filters; validation; roles.
            assertProblem(Http(gw.port()).get("/api/v1/alarms/journal"), 401, "unauthorized")
            val page = view.get("/api/v1/alarms/journal?order=asc")
            assertEquals(200, page.status, page.body)
            val rows = page.json["events"] as List<*>
            assertEquals(listOf("active", "ack", "shelve"), rows.map { (it as Map<*, *>)["eventType"] })
            assertEquals(listOf(null, "op", "op"), rows.map { (it as Map<*, *>)["actor"] })
            assertEquals(3, (page.json["total"] as Number).toInt())
            val first = rows.first() as Map<*, *>
            assertEquals("High", first["priority"])
            assertEquals(id, first["eventId"])
            assertEquals(150.0, ((first["data"] as Map<*, *>)["eventValue"] as Number).toDouble())
            val acks = view.get("/api/v1/alarms/journal?eventType=ack").json["events"] as List<*>
            assertEquals("seen", ((acks.single() as Map<*, *>)["data"] as Map<*, *>)["ackNotes"])
            assertEquals(2, (view.get("/api/v1/alarms/journal?eventType=active,shelve").json["total"] as Number).toInt())
            assertEquals(0, (view.get("/api/v1/alarms/journal?source=*other*").json["total"] as Number).toInt())
            assertEquals(0, (view.get("/api/v1/alarms/journal?maxPriority=Medium").json["total"] as Number).toInt())
            assertEquals(3, (view.get("/api/v1/alarms/journal?minPriority=High&displayPath=tank/*").json["total"] as Number).toInt())
            assertEquals(0, (view.get("/api/v1/alarms/journal?end=2000-01-01T00:00:00Z").json["total"] as Number).toInt())
            val paged = view.get("/api/v1/alarms/journal?limit=1&offset=1")
            assertEquals(1, (paged.json["events"] as List<*>).size)
            assertEquals("ack", ((paged.json["events"] as List<*>).single() as Map<*, *>)["eventType"])
            assertProblem(view.get("/api/v1/alarms/journal?eventType=bogus"), 400, "bad-request")
            assertProblem(view.get("/api/v1/alarms/journal?limit=0"), 400, "bad-request")
            assertProblem(view.get("/api/v1/alarms/journal?start=yesterday"), 400, "bad-request")

            // Retention and purge: gateway.write, audited.
            assertEquals(mapOf("maxAgeDays" to null, "connection" to null), view.get("/api/v1/alarms/journal/settings").json)
            assertProblem(op.put("/api/v1/alarms/journal/settings", """{"maxAgeDays":30}"""), 403, "forbidden")
            assertProblem(op.post("/api/v1/alarms/journal/purge", """{}"""), 403, "forbidden")
            assertProblem(admin.post("/api/v1/alarms/journal/purge", """{}"""), 400, "bad-request")
            assertProblem(admin.put("/api/v1/alarms/journal/settings", """{"maxAgeDays":-1}"""), 400, "bad-request")
            val set = admin.put("/api/v1/alarms/journal/settings", """{"maxAgeDays":30}""")
            assertEquals(200, set.status, set.body)
            assertEquals(30.0, (view.get("/api/v1/alarms/journal/settings").json["maxAgeDays"] as Number).toDouble())
            assertEquals(java.time.Duration.ofDays(30), gw.alarms.journal.maxAge)
            assertEquals(0, (admin.post("/api/v1/alarms/journal/purge", "").json["deleted"] as Number).toInt(), "nothing is 30 days old")
            val purged = admin.post("/api/v1/alarms/journal/purge", """{"olderThan":"2999-01-01T00:00:00Z"}""")
            assertEquals(200, purged.status, purged.body)
            assertEquals(3, (purged.json["deleted"] as Number).toInt())
            assertEquals(0, (view.get("/api/v1/alarms/journal").json["total"] as Number).toInt())
            val audit = gw.security.audit.query(AuditQuery(actor = "admin")).records.map { it.action to it.detail }
            assertTrue(audit.any { it.first == "alarm journal retention updated" && it.second == "maxAgeDays=null->30.0" }, audit.toString())
            assertTrue(audit.count { it.first == "alarm journal purged" } == 2, audit.toString())
            assertTrue(audit.any { it.first == "alarm journal purged" && it.second.endsWith("deleted=3") }, audit.toString())

            // Unshelve removes it from the shelved list and writes a row with the actor.
            assertEquals(200, op.post("/api/v1/alarms/unshelve", """{"sources":["$source"]}""").status)
            assertTrue((view.get("/api/v1/alarms?state=shelved").json["alarms"] as List<*>).isEmpty())
            assertEquals(1, (view.get("/api/v1/alarms").json["alarms"] as List<*>).size)
            val un = (view.get("/api/v1/alarms/journal?eventType=unshelve").json["events"] as List<*>).single() as Map<*, *>
            assertEquals("op", un["actor"])
        } finally {
            gw.close()
        }
    }

    /** S-1: live, shelved and journal queries leave out alarms whose tag the caller may not read (TAG-013). */
    @Test fun alarmQueriesHideAlarmsOfTagsTheCallerMayNotRead() {
        val gw = Gateway(tmp.resolve("perms"), port = 0).start()
        try {
            gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
            gw.createUser("op", setOf(DefaultRoles.OPERATOR))
            gw.store.put(Scope.GATEWAY, tagResource("level", 0), actor = "test")
            gw.store.put(Scope.GATEWAY, tagResource("secret", 0, path = "tank/secret",
                extra = mapOf("readPermissions" to listOf(DefaultRoles.ADMINISTRATOR))), actor = "test")
            gw.tags.write("[default]tank/level", 150)
            gw.tags.write("[default]tank/secret", 150)
            gw.tags.write("[default]tank/secret", 0)
            gw.tags.write("[default]tank/secret", 150)
            val op = client(gw, "op")
            val admin = client(gw, "admin")
            val level = "prov:default:/tag:tank/level:/alm:High"
            val secret = "prov:default:/tag:tank/secret:/alm:High"
            fun sources(http: Http, query: String): List<Any?> {
                val r = http.get("/api/v1/alarms$query")
                assertEquals(200, r.status, r.body)
                return (r.json["alarms"] as List<*>).map { (it as Map<*, *>)["source"] }.distinct()
            }

            // Live status: the administrator role is listed on tank/secret, the operator's is not.
            assertEquals(listOf(level, secret), sources(admin, ""))
            assertEquals(listOf(level), sources(op, ""))
            assertEquals(emptyList<Any?>(), sources(op, "?source=*secret*"), "a filter does not get around it")

            // Journal: hidden before paging, so total and pages count only the rows the operator may see.
            val all = admin.get("/api/v1/alarms/journal?order=asc")
            assertEquals(4, (all.json["total"] as Number).toInt(), all.body)
            val mine = op.get("/api/v1/alarms/journal?order=asc&limit=1")
            assertEquals(200, mine.status, mine.body)
            assertEquals(1, (mine.json["total"] as Number).toInt(), mine.body)
            assertEquals(level, ((mine.json["events"] as List<*>).single() as Map<*, *>)["source"])
            assertEquals(0, (op.get("/api/v1/alarms/journal?source=*secret*").json["total"] as Number).toInt())

            // Shelved list: the shelved tank/secret alarm is listed to the administrator only.
            assertEquals(200, admin.post("/api/v1/alarms/shelve", """{"sources":["$secret"],"durationSeconds":60}""").status)
            assertEquals(listOf(secret), sources(admin, "?state=shelved"))
            assertEquals(emptyList<Any?>(), sources(op, "?state=shelved"))
            val opJournal = op.get("/api/v1/alarms/journal").json["events"] as List<*>
            assertTrue(opJournal.none { (it as Map<*, *>)["source"] == secret }, "$opJournal")

            // A removed alarm source fails closed: with tank/level deleted there is no tag left to check, so its
            // journal rows are hidden from the operator and still listed to the administrator (gateway.write).
            gw.store.delete(org.oip.config.ResourceId(TAGS_MODULE, Scope.GATEWAY, TAG_KIND, "level"))
            assertEquals(0, (op.get("/api/v1/alarms/journal").json["total"] as Number).toInt())
            val adminRows = admin.get("/api/v1/alarms/journal").json["events"] as List<*>
            assertTrue(adminRows.any { (it as Map<*, *>)["source"] == level }, "$adminRows")
        } finally {
            gw.close()
        }
    }

    /**
     * Review-3 F2 (TAG-013): acknowledge, shelve and unshelve need read and write access to the alarm's source tag.
     * An operator who may not read tank/secret, or may not write tank/locked, is refused with 403 and nothing changes:
     * the event stays unacknowledged, nothing is shelved, and the journal has no ack or shelve row. A source on a tag
     * that does not exist is refused the same way, so a refusal does not reveal whether the alarm exists.
     */
    @Test fun ackAndShelveAreRefusedForAlarmsOfTagsTheCallerMayNotUse() {
        val gw = Gateway(tmp.resolve("act-perms"), port = 0).start()
        try {
            gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
            gw.createUser("op", setOf(DefaultRoles.OPERATOR))
            gw.store.put(Scope.GATEWAY, tagResource("secret", 0, path = "tank/secret",
                extra = mapOf("readPermissions" to listOf(DefaultRoles.ADMINISTRATOR))), actor = "test")
            gw.store.put(Scope.GATEWAY, tagResource("locked", 0, path = "tank/locked",
                extra = mapOf("writePermissions" to listOf(DefaultRoles.ADMINISTRATOR))), actor = "test")
            gw.tags.write("[default]tank/secret", 150)
            gw.tags.write("[default]tank/locked", 150)
            val secret = "prov:default:/tag:tank/secret:/alm:High"
            val locked = "prov:default:/tag:tank/locked:/alm:High"
            val ids = gw.alarms.query().associate { it.source to it.eventId }
            val op = client(gw, "op")
            val admin = client(gw, "admin")

            for (source in listOf(secret, locked)) {
                assertProblem(op.post("/api/v1/alarms/ack", """{"eventIds":["${ids.getValue(source)}"],"notes":"x"}"""), 403, "forbidden")
                assertProblem(op.post("/api/v1/alarms/shelve", """{"sources":["$source"],"durationSeconds":600}"""), 403, "forbidden")
            }
            // A request that mixes an allowed and a refused item changes nothing either.
            assertEquals(200, admin.post("/api/v1/alarms/shelve", """{"sources":["$locked"],"durationSeconds":600}""").status)
            assertProblem(op.post("/api/v1/alarms/unshelve", """{"sources":["$locked"]}"""), 403, "forbidden")
            assertTrue(gw.alarms.isShelved(locked), "the operator's unshelve was refused")
            assertEquals(200, admin.post("/api/v1/alarms/unshelve", """{"sources":["$locked"]}""").status)

            val unknown = "prov:default:/tag:tank/nothing:/alm:High"
            assertProblem(op.post("/api/v1/alarms/shelve", """{"sources":["$unknown"],"durationSeconds":60}"""), 403, "forbidden")
            assertProblem(admin.post("/api/v1/alarms/shelve", """{"sources":["$unknown"],"durationSeconds":60}"""), 404, "not-found")

            val live = gw.alarms.query()
            assertEquals(2, live.size)
            assertTrue(live.all { it.active && !it.acknowledged }, "$live")
            assertTrue(!gw.alarms.isShelved(secret) && !gw.alarms.isShelved(locked))
            val rows = gw.alarms.journal.records()
            assertTrue(rows.none { it.eventType == AlarmEventType.ACKNOWLEDGED }, "$rows")
            assertTrue(rows.filter { it.eventType == org.oip.alarm.JournalEventTypes.SHELVED }.all { it.actor == "admin" }, "$rows")

            // The administrator may act on both.
            assertEquals(200, admin.post("/api/v1/alarms/ack", """{"eventIds":["${ids.getValue(secret)}"]}""").status)
        } finally {
            gw.close()
        }
    }

    /** Review-3 F5: journal write health is in diagnostics, with lost rows and retry state. */
    @Test fun journalHealthIsInDiagnostics() {
        val gw = Gateway(tmp.resolve("journal-health"), port = 0).start()
        try {
            gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
            gw.store.put(Scope.GATEWAY, tagResource("level", 0), actor = "test")
            gw.tags.write("[default]tank/level", 150)
            val r = client(gw, "admin").get(JOURNAL_DIAGNOSTICS)
            assertEquals(200, r.status, r.body)
            assertEquals(0, (r.json["lostRows"] as Number).toInt(), r.body)
            assertEquals(false, r.json["retrying"], r.body)
            assertTrue(r.json.containsKey("failedWriteAttempts"), r.body)
            assertEquals(401, Http(gw.port()).get(JOURNAL_DIAGNOSTICS).status)
        } finally {
            gw.close()
        }
    }

    /** Review-3 F7: shelve duration is bounded, and a non-finite or over-long value is a 400, not a 500. */
    @Test fun shelveDurationIsValidated() {
        val gw = Gateway(tmp.resolve("shelve-bounds"), port = 0).start()
        try {
            gw.createUser("op", setOf(DefaultRoles.OPERATOR))
            gw.store.put(Scope.GATEWAY, tagResource("level", 0), actor = "test")
            val op = client(gw, "op")
            val source = "prov:default:/tag:tank/level:/alm:High"
            for (bad in listOf("1e300", "0", "-5", "\"NaN\"", "\"Infinity\"")) {
                assertProblem(op.post("/api/v1/alarms/shelve", """{"sources":["$source"],"durationSeconds":$bad}"""), 400, "bad-request")
            }
            assertTrue(!gw.alarms.isShelved(source))
            assertEquals(200, op.post("/api/v1/alarms/shelve", """{"sources":["$source"],"durationSeconds":60}""").status)
        } finally {
            gw.close()
        }
    }

    /**
     * A-1: saving the tag config again through the config API keeps the live alarm event and writes no
     * second active row. Saving it with a setpoint the value no longer exceeds clears that event, and
     * saving it without the alarm acknowledges the cleared event for the system.
     */
    @Test fun resavingTagConfigThroughTheApiJournalsOneActiveAndAClear() {
        val gw = Gateway(tmp.resolve("resave"), port = 0).start()
        try {
            gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
            val admin = client(gw, "admin")
            val url = "/api/v1/config/$TAGS_MODULE/gateway/$TAG_KIND/level"
            val created = admin.put(url, tagDoc(version = 0, setpoint = 100))
            assertEquals(201, created.status, created.body)
            gw.tags.write("[default]tank/level", 150)
            val id = gw.alarms.query().single().eventId
            fun journal() = (admin.get("/api/v1/alarms/journal?order=asc").json["events"] as List<*>).map { it as Map<*, *> }

            val same = admin.put(url, tagDoc(version = 1, setpoint = 100))
            assertEquals(200, same.status, same.body)
            val other = admin.put(url, tagDoc(version = 2, setpoint = 100, documentation = "level in the day tank"))
            assertEquals(200, other.status, other.body)
            assertEquals(listOf("active"), journal().map { it["eventType"] })
            assertEquals(id, gw.alarms.query().single().eventId)

            val raised = admin.put(url, tagDoc(version = 3, setpoint = 200))
            assertEquals(200, raised.status, raised.body)
            assertEquals(listOf("active", "clear"), journal().map { it["eventType"] })
            assertEquals("Cleared and Unacknowledged", gw.alarms.query().single().state)

            val removed = admin.put(url, tagDoc(version = 4, setpoint = null))
            assertEquals(200, removed.status, removed.body)
            val rows = journal()
            assertEquals(listOf("active", "clear", "ack"), rows.map { it["eventType"] })
            assertTrue(rows.all { it["eventId"] == id }, rows.toString())
            assertEquals(null, rows.last()["actor"])
            assertTrue(gw.alarms.query().isEmpty())
        } finally {
            gw.close()
        }
    }

    private fun tagDoc(version: Long, setpoint: Int?, documentation: String? = null): String {
        val alarms = if (setpoint == null) "" else
            ""","alarms":[{"name":"High","mode":"AboveValue","priority":"High","setpointA":$setpoint}]"""
        val doc = if (documentation == null) "" else ""","documentation":"$documentation""""
        return """{"apiVersion":"oip/v1","kind":"$TAG_KIND","metadata":{"name":"level","module":"$TAGS_MODULE","version":$version},""" +
            """"spec":{"path":"tank/level","type":"memory","dataType":"Double","value":0$doc$alarms}}"""
    }

    private fun client(gw: Gateway, user: String): Http {
        val http = Http(gw.port())
        assertEquals(200, http.login(user, TEST_PASSWORD).status)
        return http
    }

    private fun assertProblem(r: Http.Res, status: Int, slug: String) {
        assertEquals(status, r.status, r.body)
        assertTrue(r.header("Content-Type")!!.startsWith("application/problem+json"), r.header("Content-Type"))
        assertEquals("urn:oip:problem:$slug", r.json["type"])
    }

    private fun tagResource(
        name: String,
        value: Int,
        mode: String = "AboveValue",
        path: String = "tank/level",
        extra: Map<String, Any?> = emptyMap(),
    ) = Resource(
        TAG_KIND,
        Metadata(name, TAGS_MODULE),
        extra + mapOf(
            "path" to path,
            "type" to "memory",
            "dataType" to "Double",
            "value" to value,
            "alarms" to listOf(mapOf(
                "name" to "High",
                "mode" to mode,
                "priority" to "High",
                "setpointA" to 100,
            )),
        ),
    )
}
