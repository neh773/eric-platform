package org.oip.tags

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import java.nio.file.Path

/** TAG-024: bulk UDT instances are validated first and written as one batch, or not at all. */
class UdtInstancePlannerTest {
    @TempDir lateinit var dir: Path
    private lateinit var store: FileConfigStore
    private lateinit var engine: TagEngine
    private lateinit var binding: TagConfigBinding
    private lateinit var planner: UdtInstancePlanner

    @BeforeEach fun setUp() {
        val registry = SchemaRegistry()
        store = FileConfigStore(dir, registry)
        engine = TagEngine()
        binding = TagConfigBinding(engine, store, registry).also { it.attach() }
        planner = UdtInstancePlanner(engine, store, registry)
        store.put(Scope.GATEWAY, Resource(UDT_KIND, Metadata("base", TAGS_MODULE), mapOf(
            "path" to "Types/Base",
            "parameters" to listOf(mapOf("name" to "Area", "dataType" to "String", "default" to "North")),
        )))
        store.put(Scope.GATEWAY, Resource(UDT_KIND, Metadata("pump", TAGS_MODULE), mapOf(
            "path" to "Types/Pump",
            "parent" to "Types/Base",
            "parameters" to listOf(
                mapOf("name" to "Gain", "dataType" to "Double", "default" to 1),
                mapOf("name" to "Device", "dataType" to "String"),
                mapOf("name" to "Enabled", "dataType" to "Boolean", "default" to true),
            ),
            "members" to listOf(
                mapOf("name" to "In", "type" to "memory", "dataType" to "Double", "value" to 3),
                mapOf("name" to "Out", "type" to "expression", "dataType" to "Double", "expression" to "{[.]In} * {Gain}"),
                mapOf("name" to "Label", "type" to "expression", "dataType" to "String", "expression" to "{Area} + \"/\" + {Device}"),
            ),
        )))
    }

    @AfterEach fun tearDown() {
        binding.close()
        store.close()
    }

    private fun instances() = store.list(TAGS_MODULE, Scope.GATEWAY, TAG_KIND)
    private fun num(v: Any?) = (v as Number).toDouble()

    private fun request(
        vararg rows: UdtInstanceRow,
        policy: CollisionPolicy = CollisionPolicy.FAIL,
        pattern: NamePattern? = null,
        folder: String = "Line1",
        typeId: String = "Types/Pump",
    ) = UdtInstanceRequest(typeId = typeId, rows = rows.toList(), folder = folder, pattern = pattern, onCollision = policy)

    private fun row(name: String?, vararg params: Pair<String, Any?>) = UdtInstanceRow(name, params.toMap())

    @Test fun patternCreatesManyInstancesInOneBatch() {
        val req = request(
            row(null, "Device" to "PLC1", "Gain" to "2"),
            row(null, "Device" to "PLC2", "Gain" to 3),
            row(null, "Device" to "PLC3", "Area" to "South"),
            pattern = NamePattern("Pump_{n}", start = 1, count = 3, pad = 2),
        )
        val result = planner.execute(req, dryRun = false, actor = "test")
        assertTrue(result.plan.ok, result.plan.messages().toString())
        assertTrue(result.applied)
        assertEquals(3, result.created)
        assertEquals(listOf("Pump_01", "Pump_02", "Pump_03"), result.plan.rows.map { it.name })
        assertEquals(listOf("line1-pump-01", "line1-pump-02", "line1-pump-03"), result.plan.rows.map { it.resourceName })
        assertEquals(3, instances().size)
        assertEquals(6.0, num(engine.read("Line1/Pump_01/Out").value))
        assertEquals(9.0, num(engine.read("Line1/Pump_02/Out").value))
        assertEquals("South/PLC3", engine.read("Line1/Pump_03/Label").value)
        assertEquals("North/PLC1", engine.read("Line1/Pump_01/Label").value)
        // Strings from the wizard table are stored as the parameter's type.
        assertEquals(2.0, num((instances().first { it.id.name == "line1-pump-01" }.resource.spec["parameters"] as Map<*, *>)["Gain"]))
        assertTrue(binding.problems().isEmpty(), binding.problems().toString())
    }

    @Test fun oneBadRowCreatesNothing() {
        val req = request(
            row("P1", "Device" to "a"),
            row("P2", "Device" to "b", "Gain" to "fast"),
            row("P3", "Device" to "c"),
        )
        val result = planner.execute(req, dryRun = false, actor = "test")
        assertFalse(result.plan.ok)
        assertFalse(result.applied)
        assertEquals(listOf(RowStatus.CREATE, RowStatus.ERROR, RowStatus.CREATE), result.plan.rows.map { it.status })
        assertTrue(result.plan.rows[1].errors.single().contains("Gain"), result.plan.rows[1].errors.toString())
        assertTrue(instances().isEmpty())
        assertEquals(QualityCode.BAD_NOT_FOUND, engine.read("Line1/P1/Out").quality.code)
    }

    @Test fun rowErrorsCoverNamesAndParameters() {
        val plan = planner.plan(request(
            row("Bad/Name", "Device" to "a"),
            row("Tag.Value", "Device" to "a"),
            row("NoDevice"),
            row("Extra", "Device" to "a", "Nope" to 1),
            row("Flag", "Device" to "a", "Enabled" to "maybe"),
            row("", "Device" to "a"),
        ))
        assertFalse(plan.ok)
        val errs = plan.rows.map { it.errors.joinToString() }
        assertTrue(errs[0].contains("invalid tag name"), errs[0])
        assertTrue(errs[1].contains("invalid tag name"), errs[1])
        assertTrue(errs[2].contains("'Device' is required"), errs[2])
        assertTrue(errs[3].contains("unknown parameter 'Nope'"), errs[3])
        assertTrue(errs[4].contains("Boolean"), errs[4])
        assertTrue(errs[5].contains("name is required"), errs[5])
        assertEquals(6, plan.messages().size)
    }

    @Test fun unknownUdtAndBadFolderAreRequestErrors() {
        val unknown = planner.execute(request(row("A", "Device" to "a"), typeId = "Types/Nope"), dryRun = false, actor = "test")
        assertFalse(unknown.plan.ok)
        assertTrue(unknown.plan.errors.single().contains("not found"), unknown.plan.errors.toString())
        val folder = planner.plan(request(row("A", "Device" to "a"), folder = "[other]X"))
        assertTrue(folder.errors.single().contains("invalid folder"), folder.errors.toString())
        val readOnly = planner.plan(UdtInstanceRequest("Types/Pump", listOf(row("A", "Device" to "a")), provider = "system"))
        assertTrue(readOnly.errors.any { it.contains("read-only") }, readOnly.errors.toString())
        assertTrue(instances().isEmpty())
    }

    @Test fun failPolicyRejectsExistingAndDuplicateNames() {
        planner.execute(request(row("P1", "Device" to "a")), dryRun = false, actor = "test")
        val result = planner.execute(request(
            row("p1", "Device" to "b"),
            row("P2", "Device" to "c"),
            row("P2", "Device" to "d"),
        ), dryRun = false, actor = "test")
        assertFalse(result.plan.ok)
        assertEquals(listOf(RowStatus.ERROR, RowStatus.CREATE, RowStatus.ERROR), result.plan.rows.map { it.status })
        assertTrue(result.plan.rows[0].errors.single().contains("UDT instance already exists"))
        assertTrue(result.plan.rows[2].errors.single().contains("duplicate of row 2"))
        assertEquals(1, instances().size)
        assertEquals("a", engine.parametersOf("Line1/P1")["Device"])
    }

    @Test fun skipPolicyLeavesExistingTagsAlone() {
        planner.execute(request(row("P1", "Device" to "a")), dryRun = false, actor = "test")
        val result = planner.execute(request(
            row("P1", "Device" to "changed"),
            row("P2", "Device" to "b"),
            row("P2", "Device" to "dup"),
            policy = CollisionPolicy.SKIP,
        ), dryRun = false, actor = "test")
        assertTrue(result.plan.ok, result.plan.messages().toString())
        assertEquals(listOf(RowStatus.SKIP, RowStatus.CREATE, RowStatus.SKIP), result.plan.rows.map { it.status })
        assertEquals(1, result.created)
        assertEquals(2, result.plan.skipped)
        assertEquals("a", engine.parametersOf("Line1/P1")["Device"])
        assertEquals("b", engine.parametersOf("Line1/P2")["Device"])
        assertEquals(2, instances().size)
    }

    @Test fun renamePolicyTakesTheFirstFreeSuffix() {
        planner.execute(request(row("P1", "Device" to "a"), row("P1_2", "Device" to "a2")), dryRun = false, actor = "test")
        val result = planner.execute(request(
            row("P1", "Device" to "b"),
            row("P3", "Device" to "c"),
            row("P3", "Device" to "d"),
            row("P1_3", "Device" to "e"),
            policy = CollisionPolicy.RENAME,
        ), dryRun = false, actor = "test")
        assertTrue(result.plan.ok, result.plan.messages().toString())
        // P1_3 was asked for by row 4, so row 1 does not take it.
        assertEquals(listOf("P1_4", "P3", "P3_2", "P1_3"), result.plan.rows.map { it.name })
        assertEquals(listOf(RowStatus.RENAME, RowStatus.CREATE, RowStatus.RENAME, RowStatus.CREATE), result.plan.rows.map { it.status })
        assertEquals("renamed from 'P1'", result.plan.rows[0].note)
        assertEquals(4, result.created)
        assertEquals("b", engine.parametersOf("Line1/P1_4")["Device"])
        assertEquals("a", engine.parametersOf("Line1/P1")["Device"])
        assertEquals(6, instances().size)
    }

    @Test fun dryRunWritesNothingAndShowsThePlan() {
        val result = planner.execute(request(
            row(null, "Device" to "x"),
            pattern = NamePattern("Tank {n}", start = 5, count = 2, step = 5),
        ), dryRun = true, actor = "test")
        assertFalse(result.plan.ok)
        assertFalse(result.applied)
        assertEquals(listOf("Tank 5", "Tank 10"), result.plan.rows.map { it.name })
        // The second row has no parameter row, so Device has no value and no default.
        assertEquals(listOf(RowStatus.CREATE, RowStatus.ERROR), result.plan.rows.map { it.status })
        assertEquals(mapOf("Area" to "North", "Gain" to 1, "Device" to "x", "Enabled" to true), result.plan.rows[0].effectiveParameters)
        val clean = planner.execute(request(row(null, "Device" to "x"), pattern = NamePattern("Tank {n}", count = 1)), dryRun = true, actor = "test")
        assertTrue(clean.plan.ok, clean.plan.messages().toString())
        assertEquals(RowStatus.CREATE, clean.plan.rows.single().status)
        assertTrue(instances().isEmpty())
    }

    @Test fun resourceNamesStayUniqueWhenSlugsCollide() {
        val plan = planner.plan(request(row("Pump_1", "Device" to "a"), row("Pump-1", "Device" to "b")))
        assertTrue(plan.ok, plan.messages().toString())
        val names = plan.rows.map { it.resourceName!! }
        assertEquals("line1-pump-1", names[0])
        assertTrue(names[1].startsWith("line1-pump-1-") && names[1].length == "line1-pump-1-".length + 8, names[1])
    }

    @Test fun patternValidation() {
        assertEquals(listOf("A01", "A03", "A05"), NamePattern("A{n}", start = 1, count = 3, step = 2, pad = 2).expand())
        assertTrue(NamePattern("Fixed", count = 2).problems().any { it.contains("{n}") })
        assertTrue(NamePattern("A{n}", count = 0).problems().isNotEmpty())
        assertTrue(NamePattern("A{n}", count = UdtInstancePlanner.MAX_ROWS + 1).problems().isNotEmpty())
        assertEquals(CollisionPolicy.FAIL, CollisionPolicy.parse(null))
        assertEquals(CollisionPolicy.RENAME, CollisionPolicy.parse("Rename"))
    }
}
