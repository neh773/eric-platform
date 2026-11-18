package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Scope
import org.oip.db.PasswordUpdate
import org.oip.tags.DataType
import org.oip.tags.TAG_KIND
import org.oip.tags.TAGS_MODULE
import org.oip.tags.TagConfig
import org.oip.tags.TagType
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Gateway event scripts on a running gateway: startup, timer, tag change, schedule, shutdown.
 *
 */
@Timeout(180)
class GatewayScriptingTest {
    @TempDir lateinit var tmp: Path

    @Test fun gatewayRunsEachEventOffTheTagThreadAndShutsDownWhileTagsAreLive() {
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        Gateway(tmp, port = 0).use { g ->
            fun tag(path: String) {
                g.tags.configure(TagConfig(path = path, type = TagType.MEMORY, dataType = DataType.INT32, value = 0, valueSet = true))
            }
            tag("Boot")
            tag("Ticks")
            tag("Source")
            tag("Changed")
            tag("Cron")
            tag("Stop")
            g.scripts.saveEvent("boot", "startup", "system.tag.writeBlocking('[default]Boot', 1)\n_result = 1\n")
            g.scripts.saveEvent(
                "tick",
                "timer",
                """
                n = system.tag.readBlocking('[default]Ticks')[0].value or 0
                system.tag.writeBlocking('[default]Ticks', int(n) + 1)
                _result = int(n) + 1
                """.trimIndent(),
                delayMillis = 40,
            )
            g.scripts.saveEvent(
                "on-change",
                "tagChange",
                """
                system.tag.writeBlocking('[default]Changed', newValue.value)
                _result = newValue.value
                """.trimIndent(),
                tagPaths = listOf("[default]Source"),
            )
            g.scripts.saveEvent(
                "cron",
                "scheduled",
                """
                n = system.tag.readBlocking('[default]Cron')[0].value or 0
                system.tag.writeBlocking('[default]Cron', int(n) + 1)
                _result = int(n) + 1
                """.trimIndent(),
                cron = "* * * * * *",
            )
            g.scripts.saveEvent("stop", "shutdown", "system.tag.writeBlocking('[default]Stop', 1)\n_result = 1\n")
            g.start()
            assertEquals(1, num(g.tags.read("[default]Boot").value))
            await { num(g.tags.read("[default]Ticks").value) >= 1 }
            g.tags.write("[default]Source", 4)
            await { num(g.tags.read("[default]Changed").value) == 4 }
            await { num(g.tags.read("[default]Cron").value) >= 1 }
            val change = g.scripts.lastResult("on-change")
            assertTrue(change != null && change.ok, change?.error)
            assertTrue(change!!.thread.startsWith("oip-script-tag-"), change.thread)
            assertFalse(change.heldEngineWriteLock)
            g.close()
            assertEquals(1, num(g.tags.read("[default]Stop").value))
        }
    }

    @Test fun aRunningGatewayPersistsScriptedTagsAndRunsSql() {
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        Gateway(tmp, port = 0).use { g ->
            g.databases.saveConnection("mem", mapOf("driver" to "sqlite", "url" to "jdbc:sqlite::memory:"), 0, PasswordUpdate.Unchanged)
            g.start()
            val result = g.scripts.run(
                """
                system.tag.configure('[default]Plant/Level', {'type': 'memory', 'dataType': 'Int32', 'value': 6})
                system.db.runUpdateQuery('CREATE TABLE n (v INTEGER)', 'mem')
                system.db.runPrepUpdate('INSERT INTO n(v) VALUES (:v)', {'v': 6}, 'mem')
                counted = system.db.runScalarPrepQuery('SELECT COUNT(*) FROM n', {}, 'mem')
                status = system.util.getGatewayStatus()
                _result = {'level': int(system.tag.readBlocking('[default]Plant/Level')[0].value), 'counted': int(counted), 'state': status['state']}
                """.trimIndent(),
            )
            assertTrue(result.ok, result.error)
            val body = result.value as Map<*, *>
            assertEquals(6, num(body["level"]))
            assertEquals(1, num(body["counted"]))
            assertEquals("running", body["state"])
            val stored = g.store.list(TAGS_MODULE, Scope.GATEWAY, TAG_KIND)
            assertTrue(stored.any { it.resource.spec["path"] == "Plant/Level" }, stored.map { it.resource.spec["path"] }.toString())
        }
    }
}

private fun num(value: Any?): Int = (value as Number).toInt()

private fun await(timeoutMs: Long = 8_000, probe: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (System.nanoTime() < deadline) {
        if (probe()) return
        Thread.sleep(20)
    }
    throw AssertionError("condition not met")
}
