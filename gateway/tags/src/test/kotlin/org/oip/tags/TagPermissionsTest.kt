package org.oip.tags

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.config.ValidationException
import org.oip.security.AuditContext
import org.oip.security.AuditQuery
import org.oip.security.AuditRecord
import org.oip.security.AuditStatus
import org.oip.security.ChainedAuditLog
import org.oip.security.MacAuditAnchor
import org.oip.security.MemoryAuditAnchorStore
import org.oip.security.MemoryAuditSink
import org.oip.security.SecurityZone
import org.oip.security.Subject
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files

/** Per-tag permissions and read-only (TAG-010, TAG-013): - */
class TagPermissionsTest {
    private fun num(v: Any?): Double = (v as Number).toDouble()

    private val operatorOnFloor = TagCaller.User("ada", setOf("operator"), zone = "Floor", host = "hmi-1")
    private val operatorRemote = TagCaller.User("ada", setOf("operator"), zone = "Remote", host = "laptop")
    private val viewerOnFloor = TagCaller.User("vic", setOf("viewer"), zone = "Floor")
    private val admin = TagCaller.User("root", setOf("administrator"), zone = "Floor")

    private class Harness : AutoCloseable {
        val registry = SchemaRegistry()
        val store = FileConfigStore(Files.createTempDirectory("oip-tag-perms"), registry)
        val engine = TagEngine()
        val binding = TagConfigBinding(engine, store, registry).also { it.attach() }
        val audit = ChainedAuditLog(MemoryAuditSink(), ByteArray(32) { 1 }, MacAuditAnchor(MemoryAuditAnchorStore(), ByteArray(32) { 2 }))
            .also { engine.audit = it }

        fun put(kind: String, name: String, vararg spec: Pair<String, Any?>) {
            val id = ResourceId(TAGS_MODULE, Scope.GATEWAY, kind, name)
            val version = store.get(id)?.metadata?.version ?: 0L
            store.put(Scope.GATEWAY, Resource(kind, Metadata(name, TAGS_MODULE, version = version), mapOf(*spec)))
        }

        fun writes(): List<AuditRecord> = audit.query(AuditQuery(action = TAG_WRITE_ACTION, limit = 1000)).records

        override fun close() {
            binding.close()
            store.close()
        }
    }

    private fun Harness.seedSetpoint(extra: Map<String, Any?> = emptyMap()) = put(TAG_KIND, "setpoint",
        *(mapOf<String, Any?>(
            "path" to "Line/Setpoint", "type" to "memory", "dataType" to "Double", "value" to 10.0,
            "writePermissions" to mapOf("roles" to listOf("operator"), "zones" to listOf("Floor")),
        ) + extra).toList().toTypedArray())

    @Test
    fun allowedRoleInAllowedZoneWritesAndIsAuditedOnce() {
        Harness().use { h ->
            h.seedSetpoint()
            val result = h.engine.write("Line/Setpoint", 12.5, operatorOnFloor)
            assertEquals(QualityCode.GOOD, result.quality.code)
            assertEquals(12.5, num(h.engine.read("Line/Setpoint").value))

            val records = h.writes()
            assertEquals(1, records.size)
            val r = records.single()
            assertEquals("ada", r.actor)
            assertEquals("hmi-1", r.actorHost)
            assertEquals(AuditStatus.SUCCESS, r.status)
            assertTrue(r.target.endsWith("Line/Setpoint"), r.target)
            assertTrue(r.detail.contains("old=10.0") && r.detail.contains("new=12.5"), r.detail)
            assertTrue(h.audit.verify().ok, "tag writes keep the audit chain valid")
        }
    }

    @Test
    fun disallowedRoleOrZoneIsRejectedValueUnchangedAndAudited() {
        Harness().use { h ->
            h.seedSetpoint()
            for (caller in listOf(viewerOnFloor, operatorRemote)) {
                val result = h.engine.write("Line/Setpoint", 99.0, caller)
                assertEquals(QualityCode.BAD_USER_ACCESS_DENIED, result.quality.code, "$caller")
                assertEquals("Bad_AccessDenied", result.quality.code.label)
                assertEquals(10.0, num(h.engine.read("Line/Setpoint").value), "value unchanged for $caller")
            }
            val records = h.writes()
            assertEquals(2, records.size, "one record per attempt")
            assertTrue(records.all { it.status == AuditStatus.FAILURE })
            assertTrue(records.all { it.detail.contains("rejected Bad_AccessDenied") && it.detail.contains("value=99.0") }, records.toString())
        }
    }

    @Test
    fun readOnlyRejectsEveryWriteIncludingAdministratorAndSystem() {
        Harness().use { h ->
            h.put(TAG_KIND, "locked", "path" to "Line/Locked", "type" to "memory", "dataType" to "Double", "value" to 1.0, "readOnly" to true)
            for (caller in listOf(admin, operatorOnFloor)) {
                assertEquals(QualityCode.BAD_NOT_WRITABLE, h.engine.write("Line/Locked", 2.0, caller).quality.code)
            }
            assertEquals(QualityCode.BAD_NOT_WRITABLE, h.engine.write("Line/Locked", 2.0).quality.code, "system caller too")
            assertEquals(QualityCode.BAD_NOT_WRITABLE, h.engine.openSample("Line/Locked").set(2.0).quality.code, "fast path too")
            assertEquals(1.0, num(h.engine.read("Line/Locked").value))
            assertEquals(2, h.writes().size, "user attempts audited; system write not audited")
        }
    }

    @Test
    fun readPermissionHidesTheValueAndPropertiesFromUnauthorisedCallers() {
        Harness().use { h ->
            h.put(TAG_KIND, "recipe", "path" to "Line/Recipe", "type" to "memory", "dataType" to "String", "value" to "secret-mix",
                "readPermissions" to mapOf("roles" to listOf("operator")))
            assertEquals("secret-mix", h.engine.read("Line/Recipe", operatorOnFloor).value)
            assertEquals("secret-mix", h.engine.read("Line/Recipe").value, "system reads are unchecked")

            val hidden = h.engine.read("Line/Recipe", viewerOnFloor)
            assertNull(hidden.value)
            assertEquals(QualityCode.BAD_USER_ACCESS_DENIED, hidden.quality.code)
            assertNull(h.engine.read("Line/Recipe.Documentation", viewerOnFloor).value)
            assertEquals(listOf<Any?>(null), h.engine.readAll(listOf("Line/Recipe"), viewerOnFloor).map { it.value })
            assertEquals(listOf<Any?>("secret-mix"), h.engine.readAll(listOf("Line/Recipe"), operatorOnFloor).map { it.value })
        }
    }

    @Test
    fun browseHidesTheValueOfTagsTheCallerMayNotRead() {
        Harness().use { h ->
            h.put(TAG_KIND, "secret", "path" to "Line/Secret", "type" to "memory", "dataType" to "Double", "value" to 42.0,
                "readPermissions" to mapOf("roles" to listOf("operator"), "zones" to listOf("Floor")))
            h.put(TAG_KIND, "open", "path" to "Line/Open", "type" to "memory", "dataType" to "Double", "value" to 1.0)

            fun browse(caller: TagCaller) = h.engine.browse("[default]Line", 0, 100, caller).associateBy { it.name }

            val denied = browse(viewerOnFloor)
            assertEquals(setOf("Secret", "Open"), denied.keys, "structure is still listed")
            assertNull(denied.getValue("Secret").value!!.value)
            assertEquals(QualityCode.BAD_USER_ACCESS_DENIED, denied.getValue("Secret").value!!.quality.code)
            assertNull(denied.getValue("Secret").dataType, "the data type of an unreadable tag is not listed (S-1)")
            assertNull(denied.getValue("Secret").resource)
            assertFalse(denied.getValue("Secret").readable)
            assertEquals(NodeKind.TAG, denied.getValue("Secret").kind)
            assertEquals(1.0, num(denied.getValue("Open").value!!.value), "an unrestricted tag is unaffected")
            assertTrue(denied.getValue("Open").readable)
            assertEquals(DataType.DOUBLE, browse(admin).getValue("Open").dataType)
            assertEquals(QualityCode.BAD_USER_ACCESS_DENIED, browse(operatorRemote).getValue("Secret").value!!.quality.code, "wrong zone")

            assertEquals(42.0, num(browse(operatorOnFloor).getValue("Secret").value!!.value))
            assertEquals(DataType.DOUBLE, browse(operatorOnFloor).getValue("Secret").dataType)
            assertTrue(browse(operatorOnFloor).getValue("Secret").readable)
            assertEquals(42.0, num(browse(TagCaller.System).getValue("Secret").value!!.value))
            assertEquals(42.0, num(h.engine.browse("[default]Line").single { it.name == "Secret" }.value!!.value),
                "the caller-less overload is System")
        }
    }

    @Test
    fun mayReadIsTheSameCheckAsReadAndBrowse() {
        Harness().use { h ->
            h.put(TAG_KIND, "secret", "path" to "Line/Secret", "type" to "memory", "dataType" to "Double", "value" to 42.0,
                "readPermissions" to mapOf("roles" to listOf("operator"), "zones" to listOf("Floor")))
            h.put(TAG_KIND, "open", "path" to "Line/Open", "type" to "memory", "dataType" to "Double", "value" to 1.0)
            val callers = listOf(viewerOnFloor, operatorRemote, operatorOnFloor, admin, TagCaller.System)
            for (caller in callers) {
                val readable = h.engine.read("[default]Line/Secret", caller).quality.code != QualityCode.BAD_USER_ACCESS_DENIED
                assertEquals(readable, h.engine.mayRead("[default]Line/Secret", caller), "$caller")
                assertEquals(readable, h.engine.browse("[default]Line", 0, 10, caller).single { it.name == "Secret" }.readable)
                assertEquals(readable, h.engine.openSample("[default]Line/Secret").mayRead(caller))
                assertTrue(h.engine.mayRead("Line/Open", caller), "a tag without read permissions is open")
            }
            assertFalse(h.engine.mayRead("Line/Secret", admin), "administrators get no implicit pass")
            // No tag configured there (history of a deleted tag), or no such provider: fail closed, except for a
            // caller that may edit tag configuration, and System.
            val configAdmin = viewerOnFloor.copy(configAdmin = true)
            assertFalse(h.engine.mayRead("[default]Line/Gone", viewerOnFloor), "no tag configured there")
            assertFalse(h.engine.mayRead("[nowhere]Line/Secret", viewerOnFloor), "unknown provider")
            assertTrue(h.engine.mayRead("[default]Line/Gone", configAdmin))
            assertTrue(h.engine.mayRead("[nowhere]Line/Secret", configAdmin))
            assertTrue(h.engine.mayRead("[default]Line/Gone", TagCaller.System))
            assertFalse(configAdmin.copy(configAdmin = true).let { h.engine.mayRead("Line/Secret", it) },
                "configAdmin does not bypass a configured tag's permissions")
            assertFalse(h.engine.mayRead("[default", viewerOnFloor), "a path that does not parse is refused to a user")
            assertTrue(h.engine.mayRead("[default", TagCaller.System))

            // A permission edit applies to the next check.
            h.put(TAG_KIND, "secret", "path" to "Line/Secret", "type" to "memory", "dataType" to "Double", "value" to 42.0,
                "readPermissions" to mapOf("roles" to listOf("viewer")))
            assertTrue(h.engine.mayRead("Line/Secret", viewerOnFloor))
            assertFalse(h.engine.mayRead("Line/Secret", operatorOnFloor))

            // Removing the tag does not open it: its path now has no tag, so only a config admin passes.
            h.engine.remove("[default]Line/Secret")
            assertFalse(h.engine.mayRead("Line/Secret", viewerOnFloor))
            assertTrue(h.engine.mayRead("Line/Secret", viewerOnFloor.copy(configAdmin = true)))
        }
    }

    @Test
    fun roleListShorthandForReadPermissionsIsRoleOnlyAccess() {
        // The peer network's remote reads (#53) use the role-list form; it is a role-only TagAccess.
        // Main's documented rule: an empty set names nobody. GatewayNetwork denies on an empty role set.
        val none = TagConfig(path = "N", type = TagType.MEMORY, readPermissions = emptySet())
        assertSame(TagAccess.NOBODY, none.readAccess)
        assertEquals(emptySet<String>(), none.readPermissions)
        assertFalse(none.readAccess!!.allows(operatorOnFloor))
        val short = TagConfig(path = "S", type = TagType.MEMORY, readPermissions = setOf("Driver"))
        assertEquals(TagAccess(listOf("driver"), emptyList()), short.readAccess)
        assertEquals(setOf("driver"), short.readPermissions)
        assertEquals(short.readAccess, TagConfig.fromSpec(mapOf("path" to "S", "type" to "memory", "readPermissions" to listOf("Driver"))).readAccess)
        assertNull(TagConfig(path = "P", type = TagType.MEMORY).readPermissions, "unrestricted")
        assertEquals(emptySet<String>(), TagConfig(path = "Z", type = TagType.MEMORY,
            readAccess = TagAccess(emptyList(), listOf("floor"))).readPermissions, "zone-only: no role passes a remote read")
        Harness().use { h ->
            h.put(TAG_KIND, "legacy", "path" to "Legacy", "type" to "memory", "readPermissions" to listOf("driver"))
            assertEquals(setOf("driver"), h.engine.readPermissionRoles("Legacy"))
        }
    }

    @Test
    fun subscriptionsRespectReadPermissionIncludingLiveChanges() {
        Harness().use { h ->
            h.put(TAG_KIND, "recipe", "path" to "Line/Recipe", "type" to "memory", "dataType" to "Int32", "value" to 1,
                "readPermissions" to mapOf("roles" to listOf("operator")))
            val seenByViewer = ArrayList<QualifiedValue>()
            val seenByOperator = ArrayList<QualifiedValue>()
            h.engine.subscribe("Line/Recipe", viewerOnFloor) { seenByViewer += it.value }
            h.engine.subscribe("Line/Recipe", operatorOnFloor) { seenByOperator += it.value }

            h.engine.write("Line/Recipe", 2)
            h.engine.write("Line/Recipe", 3)
            assertEquals(listOf(2, 3), seenByOperator.map { (it.value as Number).toInt() })
            assertEquals(1, seenByViewer.size, "one denial, not one per change")
            assertNull(seenByViewer.single().value)
            assertEquals(QualityCode.BAD_USER_ACCESS_DENIED, seenByViewer.single().quality.code)

            // Granting read access in the config store applies to the subscription already open.
            h.put(TAG_KIND, "recipe", "path" to "Line/Recipe", "type" to "memory", "dataType" to "Int32", "value" to 1,
                "readPermissions" to mapOf("roles" to listOf("operator", "viewer")))
            h.engine.write("Line/Recipe", 4)
            assertEquals(4, (seenByViewer.last().value as Number).toInt())
        }
    }

    @Test
    fun referenceAndDerivedNeedPermissionOnTheTagAndItsSource() {
        Harness().use { h ->
            h.seedSetpoint() // operators on the Floor may write
            h.put(TAG_KIND, "ref", "path" to "Hmi/SetpointRef", "type" to "reference", "dataType" to "Double", "sourcePath" to "[default]Line/Setpoint")
            h.put(TAG_KIND, "pct", "path" to "Hmi/SetpointPct", "type" to "derived", "dataType" to "Double",
                "sourcePath" to "[default]Line/Setpoint", "readExpression" to "{source} * 10", "writeExpression" to "{value} / 10",
                "writePermissions" to mapOf("roles" to listOf("viewer", "operator")))

            // The reference itself is open, but the source still rejects a viewer.
            assertEquals(QualityCode.BAD_USER_ACCESS_DENIED, h.engine.write("Hmi/SetpointRef", 50.0, viewerOnFloor).quality.code)
            assertEquals(QualityCode.BAD_USER_ACCESS_DENIED, h.engine.write("Hmi/SetpointPct", 500.0, viewerOnFloor).quality.code,
                "viewer may write the derived tag but not its source")
            assertEquals(10.0, num(h.engine.read("Line/Setpoint").value))

            assertEquals(QualityCode.GOOD, h.engine.write("Hmi/SetpointPct", 150.0, operatorOnFloor).quality.code)
            assertEquals(15.0, num(h.engine.read("Line/Setpoint").value))
            assertEquals(3, h.writes().size, "a write-through is one audit record, not two")
            assertEquals(listOf(AuditStatus.FAILURE, AuditStatus.FAILURE, AuditStatus.SUCCESS), h.writes().sortedBy { it.seq }.map { it.status })

            // A read-only source rejects a write through a reference, for anyone.
            h.seedSetpoint(mapOf("readOnly" to true))
            assertEquals(QualityCode.BAD_NOT_WRITABLE, h.engine.write("Hmi/SetpointRef", 20.0, admin).quality.code)
            assertEquals(15.0, num(h.engine.read("Line/Setpoint").value))
        }
    }

    @Test
    fun aUserCannotRepointAReferenceAtATagTheyMayNotRead() {
        Harness().use { h ->
            h.put(TAG_KIND, "secret", "path" to "Line/Secret", "type" to "memory", "dataType" to "Double", "value" to 99.0,
                "readPermissions" to mapOf("roles" to listOf("operator")))
            h.put(TAG_KIND, "open", "path" to "Line/Open", "type" to "memory", "dataType" to "Double", "value" to 1.0)
            h.put(TAG_KIND, "view", "path" to "Hmi/View", "type" to "reference", "dataType" to "Double",
                "sourcePath" to "[default]Line/Open", "readOnly" to true)

            for (caller in listOf(viewerOnFloor, admin)) {
                val result = h.engine.write("Hmi/View.sourceTagPath", "[default]Line/Secret", caller)
                assertEquals(QualityCode.BAD_NOT_WRITABLE, result.quality.code)
            }
            assertEquals("[default]Line/Open", h.engine.read("Hmi/View.sourceTagPath").value)
            val seen = h.engine.read("Hmi/View", viewerOnFloor)
            assertEquals(1.0, num(seen.value))
            assertEquals(QualityCode.BAD_USER_ACCESS_DENIED, h.engine.read("Line/Secret", viewerOnFloor).quality.code)
            // The gateway itself still can.
            assertEquals(QualityCode.GOOD, h.engine.write("Hmi/View.sourceTagPath", "[default]Line/Secret").quality.code)
        }
    }

    @Test
    fun udtMembersInheritPermissionsAndInstancesOverridePerMember() {
        Harness().use { h ->
            h.put(UDT_KIND, "valve", "path" to "Types/Valve", "members" to listOf(
                mapOf("name" to "Cmd", "type" to "memory", "dataType" to "Boolean", "value" to false,
                    "writePermissions" to mapOf("roles" to listOf("operator"))),
                mapOf("name" to "Position", "type" to "memory", "dataType" to "Double", "value" to 0.0, "readOnly" to true),
            ))
            h.put(TAG_KIND, "v1", "path" to "Line/V1", "type" to "udt", "typeId" to "Types/Valve")
            h.put(TAG_KIND, "v2", "path" to "Line/V2", "type" to "udt", "typeId" to "Types/Valve",
                "overrides" to mapOf("Cmd" to mapOf("writePermissions" to mapOf("roles" to listOf("viewer")))))

            assertEquals(QualityCode.GOOD, h.engine.write("Line/V1/Cmd", true, operatorOnFloor).quality.code, "inherited from the definition")
            assertEquals(QualityCode.BAD_USER_ACCESS_DENIED, h.engine.write("Line/V1/Cmd", true, viewerOnFloor).quality.code)
            assertEquals(QualityCode.BAD_NOT_WRITABLE, h.engine.write("Line/V1/Position", 5.0, admin).quality.code)

            assertEquals(QualityCode.GOOD, h.engine.write("Line/V2/Cmd", true, viewerOnFloor).quality.code, "instance override")
            assertEquals(QualityCode.BAD_USER_ACCESS_DENIED, h.engine.write("Line/V2/Cmd", false, operatorOnFloor).quality.code)
        }
    }

    @Test
    fun tagsWithoutPermissionsAndSystemCallersBehaveAsBefore() {
        Harness().use { h ->
            h.put(TAG_KIND, "open", "path" to "Open", "type" to "memory", "dataType" to "Double", "value" to 1.0)
            assertEquals(QualityCode.GOOD, h.engine.write("Open", 2.0, viewerOnFloor).quality.code)
            assertEquals(2.0, num(h.engine.read("Open", viewerOnFloor).value))
            h.seedSetpoint()
            assertEquals(QualityCode.GOOD, h.engine.write("Line/Setpoint", 3.0).quality.code, "internal callers skip role/zone checks")
            assertEquals(1, h.writes().size, "only the user write is audited")
        }
    }

    @Test
    fun auditUsesTheRequestHostFromAuditContextWhenTheCallerHasNone() {
        Harness().use { h ->
            h.seedSetpoint()
            AuditContext.call("ada", host = "10.0.0.7", context = "rest") {
                h.engine.write("Line/Setpoint", 11.0, operatorOnFloor.copy(host = ""))
            }
            val r = h.writes().single()
            assertEquals("10.0.0.7", r.actorHost)
            assertEquals("rest", r.context)
        }
    }

    @Test
    fun permissionsAreValidatedByTheTagSchemaAndRoundTripThroughSpec() {
        Harness().use { h ->
            assertThrows<ValidationException> {
                h.put(TAG_KIND, "bad", "path" to "Bad", "type" to "memory", "writePermissions" to mapOf("roles" to "operator"))
            }
            assertThrows<ValidationException> {
                h.put(TAG_KIND, "bad", "path" to "Bad", "type" to "memory", "readPermissions" to mapOf("groups" to listOf("x")))
            }
        }
        val cfg = TagConfig.fromSpec(mapOf("path" to "A", "type" to "memory", "readOnly" to true,
            "readPermissions" to mapOf("roles" to listOf("Operator"), "zones" to listOf("Floor"))))
        assertFalse(cfg.writable)
        assertEquals(setOf("operator"), cfg.readAccess!!.roles)
        assertEquals(cfg.readAccess, TagConfig.fromSpec(cfg.toSpec()).readAccess)
        val empty = TagConfig.fromSpec(mapOf("path" to "B", "type" to "memory", "writePermissions" to mapOf("roles" to emptyList<String>())))
        assertSame(TagAccess.NOBODY, empty.writeAccess, "a present but empty block names nobody")
        assertEquals(TagAccess.NOBODY, TagConfig.fromSpec(empty.toSpec()).writeAccess, "and round-trips as such")
        assertNull(TagConfig.fromSpec(mapOf("path" to "C", "type" to "memory")).writeAccess, "only an absent key is unrestricted")
    }

    @Test
    fun presentButEmptyPermissionsDenyEveryUserButNotSystem() {
        val empties: List<Any?> = listOf(null, emptyList<String>(), "", "  ", emptyMap<String, Any?>(),
            mapOf("roles" to emptyList<String>(), "zones" to emptyList<String>()))
        for ((i, empty) in empties.withIndex()) {
            Harness().use { h ->
                h.put(TAG_KIND, "t$i", "path" to "T", "type" to "memory", "dataType" to "Double", "value" to 1.0,
                    "readPermissions" to empty, "writePermissions" to empty)
                assertTrue(h.binding.problems().isEmpty(), "form $empty is valid: ${h.binding.problems()}")
                val cfg = TagConfig.fromSpec(h.store.get(ResourceId(TAGS_MODULE, Scope.GATEWAY, TAG_KIND, "t$i"))!!.spec)
                assertEquals(TagAccess.NOBODY, cfg.readAccess, "form $empty")
                assertEquals(TagAccess.NOBODY, TagConfig.fromSpec(cfg.toSpec()).readAccess, "form $empty round-trips")

                for (user in listOf(operatorOnFloor, admin, viewerOnFloor)) {
                    val read = h.engine.read("T", user)
                    assertNull(read.value, "form $empty hides the value from ${user.actor}")
                    assertEquals(QualityCode.BAD_USER_ACCESS_DENIED, read.quality.code)
                    assertEquals(QualityCode.BAD_USER_ACCESS_DENIED, h.engine.write("T", 2.0, user).quality.code)
                }
                assertEquals(1.0, num(h.engine.read("T").value), "users changed nothing")
                assertEquals(QualityCode.GOOD, h.engine.write("T", 3.0).quality.code, "System is unaffected")
                assertEquals(3.0, num(h.engine.read("T", TagCaller.System).value))
                assertEquals(emptySet<String>(), h.engine.readPermissionRoles("T"), "remote readers: empty set, as on main")
            }
        }
    }

    @Test
    fun callerIsBuiltFromSecuritySubjectsAndZones() {
        val floor = SecurityZone("Floor")
        val user = TagCaller.of(Subject.User("ada", setOf("Operator"), enabled = true), floor, "hmi-1")
        assertEquals(TagCaller.User("ada", setOf("operator"), "Floor", "hmi-1"), user)
        assertEquals(emptySet<String>(), TagCaller.of(Subject.User("old", setOf("operator"), enabled = false), floor).roles)
        val key = TagCaller.of(Subject.ApiKey("svc", setOf("tags.write"), secureConnectionRequired = false, enabled = true), floor)
        assertEquals("svc", key.actor)
        assertEquals(emptySet<String>(), key.roles, "API keys carry scopes, not roles")
        val access = TagAccess(listOf("operator"), emptyList())
        assertTrue(access.allows(user))
        assertFalse(access.allows(key))
        assertTrue(TagAccess(emptyList(), listOf("floor")).allows(key), "a zone-only rule admits any principal in the zone")
    }

    @Test
    fun userWriteNotifiesSubscribersAfterTheEngineLockIsReleased() {
        Harness().use { h ->
            h.seedSetpoint()
            h.put(TAG_KIND, "echo", "path" to "Line/Echo", "type" to "expression", "dataType" to "Double", "expression" to "{[.]Setpoint} * 2")
            val held = java.util.concurrent.atomic.AtomicInteger(-1)
            val seen = java.util.concurrent.atomic.AtomicReference<Any?>()
            h.engine.subscribe("Line/Setpoint") { change ->
                held.set(if (h.engine.holdingWriteLock() || h.engine.gate.readHoldCount > 0) 1 else 0)
                seen.set(change.value.value)
            }
            assertEquals(QualityCode.GOOD, h.engine.write("Line/Setpoint", 21.0, operatorOnFloor).quality.code)
            assertEquals(21.0, num(seen.get()), "the subscriber ran before write() returned")
            assertEquals(0, held.get(), "no engine lock is held while subscribers run")
            assertEquals(42.0, num(h.engine.read("Line/Echo").value), "the dependent expression re-evaluated")
            assertEquals(QualityCode.GOOD, h.engine.write("Line/Setpoint", 22.0).quality.code)
            assertEquals(0, held.get(), "System writes defer their notifications too")
            assertEquals(1, h.writes().size, "only the user write is audited")
        }
    }

    @Test
    fun auditEnabledOffStopsTagWriteRecordsButNotTheCheck() {
        Harness().use { h ->
            h.seedSetpoint()
            h.engine.auditEnabled = { false }
            assertEquals(QualityCode.BAD_USER_ACCESS_DENIED, h.engine.write("Line/Setpoint", 1.0, viewerOnFloor).quality.code)
            assertEquals(QualityCode.GOOD, h.engine.write("Line/Setpoint", 2.0, operatorOnFloor).quality.code)
            assertEquals(0, h.writes().size)
            h.engine.auditEnabled = { true }
            h.engine.write("Line/Setpoint", 3.0, operatorOnFloor)
            assertEquals(1, h.writes().size)
        }
    }

    @Test
    fun aResolvedSampleChecksTheCallerWithoutAPathLookup() {
        Harness().use { h ->
            h.put(TAG_KIND, "recipe", "path" to "Line/Recipe", "type" to "memory", "dataType" to "String", "value" to "mix",
                "readPermissions" to listOf("operator"))
            val sample = h.engine.openSample("Line/Recipe")
            assertTrue(sample.mayRead(operatorOnFloor))
            assertFalse(sample.mayRead(viewerOnFloor))
            assertTrue(sample.mayRead(TagCaller.System))
            assertEquals("mix", sample.read(operatorOnFloor).value)
            val denied = sample.read(viewerOnFloor)
            assertNull(denied.value)
            assertEquals(QualityCode.BAD_USER_ACCESS_DENIED, denied.quality.code)
            // A config edit that opens the tag takes effect on the same resolved sample.
            h.put(TAG_KIND, "recipe", "path" to "Line/Recipe", "type" to "memory", "dataType" to "String", "value" to "mix")
            assertTrue(h.engine.openSample("Line/Recipe").mayRead(viewerOnFloor))
        }
    }

    @Test
    fun readAllAppliesTheCallerToEachPath() {
        Harness().use { h ->
            h.put(TAG_KIND, "recipe", "path" to "Line/Recipe", "type" to "memory", "dataType" to "String", "value" to "mix",
                "readPermissions" to listOf("operator"))
            h.seedSetpoint()
            val values = h.engine.readAll(listOf("Line/Recipe", "Line/Setpoint", "Line/Missing"), viewerOnFloor)
            assertEquals(QualityCode.BAD_USER_ACCESS_DENIED, values[0].quality.code)
            assertEquals(10.0, num(values[1].value))
            assertEquals(QualityCode.BAD_NOT_FOUND, values[2].quality.code)
            assertFalse(h.engine.holdingWriteLock())
        }
    }
}
