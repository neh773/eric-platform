package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.eam.EamRole
import org.oip.eam.EamSettings
import org.oip.eam.EamTaskDef
import org.oip.eam.EamTaskKind
import org.oip.eam.EamThresholds
import org.oip.eam.RunState
import org.oip.security.DefaultRoles
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Enterprise administration between in-process gateways on loopback over the mutual-TLS peer network:
 * a controller, one or two agents, and for EAM-012 a second controller.
 * Doc source: FEATURES EAM rows (EAM-001 to EAM-017).
 */
class EamGatewayTest {
    @TempDir lateinit var tmp: Path
    private val HELLO = "org.oip.hello-world"

    /** EAM-014, EAM-008, EAM-005, EAM-001, EAM-002: designate a controller, enroll from first-boot files, approve. */
    @Test fun firstBootAgentEnrollsAndWaitsForApproval() {
        val controller = start(tmp.resolve("controller"), "hq")
        try {
            val http = Http(controller.port())
            controller.loginAdmin(http)
            val designate = http.put("/api/v1/eam/settings", Json.write(mapOf("role" to "controller")))
            assertEquals(200, designate.status, designate.body)
            assertEquals("controller", designate.json["role"])
            val agent = startAgent(controller, "site-a", group = "north")
            try {
                assertFalse(agent.dataDir.resolve("eam-agent.json").exists())
                assertTrue(agent.dataDir.resolve("eam-agent.json.applied").exists())
                assertEquals(EamRole.AGENT, agent.eam.settings().role)
                assertEquals("controller", agent.eam.settings().controller)
                // The pinned controller certificate needs no approval on the agent side; the controller still approves the agent's.
                waitUntil({ http.get("/api/v1/eam/agents").body }) { pendingCertificates(http).isNotEmpty() }
                approveCertificate(http, pendingCertificates(http).first()["fingerprint"] as String)
                waitUntil({ agent.eam.agent.statusDocument().toString() }) { agent.eam.agent.state == "pending" }
                val pending = agentRow(http, "site-a")
                assertEquals("pending", pending["status"])
                assertEquals("north", pending["group"])
                assertEquals(false, pending["approved"])
                // A pending agent gets no runs.
                controller.eam.controller.putTask(EamTaskDef("early", EamTaskKind.BACKUP, agents = listOf("site-a")), "test")
                assertEquals(0, controller.eam.controller.runs(agent = "site-a").size)

                val approved = http.post("/api/v1/eam/agents/site-a/approve", "{}")
                assertEquals(200, approved.status, approved.body)
                waitUntil({ agentRow(http, "site-a").toString() }) { agentRow(http, "site-a")["status"] == "online" }
                assertEquals("approved", agent.eam.agent.checkInOnce())
                val row = agentRow(http, "site-a")
                @Suppress("UNCHECKED_CAST")
                val inventory = row["inventory"] as Map<String, Any?>
                assertEquals("standard", inventory["profile"])
                assertNotNull(inventory["modules"])
                assertEquals(Gateway.VERSION, row["version"])
                assertTrue(controller.eam.controller.events("site-a").any { it["kind"] == "enrolled" })
                assertTrue(controller.eam.controller.events("site-a").any { it["kind"] == "approved" })
                // An operator (gateway.read) can read agents but cannot approve; a viewer cannot read them.
                controller.createUser("ops", setOf(DefaultRoles.OPERATOR))
                val operator = Http(controller.port())
                assertEquals(200, operator.login("ops", TEST_PASSWORD).status)
                assertEquals(200, operator.get("/api/v1/eam/agents").status)
                assertEquals(403, operator.post("/api/v1/eam/agents/site-a/approve", "{}").status)
                controller.createUser("view", setOf(DefaultRoles.VIEWER))
                val viewer = Http(controller.port())
                assertEquals(200, viewer.login("view", TEST_PASSWORD).status)
                assertEquals(403, viewer.get("/api/v1/eam/agents").status)
            } finally {
                agent.close()
            }
        } finally {
            controller.close()
        }
    }

    /** EAM-004: remote backup is stored on the controller; a restore task brings the agent back to it. */
    @Test fun backupTaskStoresTheAgentBackupAndRestoreReturnsToIt() {
        fleet { controller, agent, http ->
            agent.tag("kept", "path" to "kept", "type" to "memory", "dataType" to "Int32", "value" to 7)
            val created = http.post("/api/v1/eam/tasks", Json.write(mapOf("id" to "nightly-backup", "kind" to "backup", "agents" to listOf("site-a"))))
            assertEquals(201, created.status, created.body)
            val backupRun = finished(controller, "nightly-backup")
            assertEquals(RunState.SUCCEEDED, backupRun["state"], backupRun.toString())
            val stored = controller.eam.controller.backups("site-a")
            assertEquals(1, stored.size)
            assertEquals(stored.first()["name"], (backupRun["result"] as Map<*, *>)["backup"])
            // The stored archive is sealed: the controller keeps no plaintext copy.
            val archive = controller.dataDir.resolve("eam/controller/backups/site-a").resolve(stored.first()["name"] as String).readBytes()
            assertTrue(org.oip.backup.BackupCipher.isEncrypted(archive))

            agent.tag("later", "path" to "later", "type" to "memory", "dataType" to "Int32", "value" to 1)
            assertTrue(agent.tags.isConfigured("[default]later"))
            val restore = http.post("/api/v1/eam/tasks", Json.write(mapOf("id" to "roll-back", "kind" to "restore", "agents" to listOf("site-a"))))
            assertEquals(201, restore.status, restore.body)
            val restoreRun = finished(controller, "roll-back")
            assertEquals(RunState.SUCCEEDED, restoreRun["state"], restoreRun.toString())
            waitUntil({ "later=${agent.tags.isConfigured("[default]later")}" }) { !agent.tags.isConfigured("[default]later") }
            assertTrue(agent.tags.isConfigured("[default]kept"))
            assertTrue(agent.auditCount(org.oip.security.AuditActions.BACKUP_RESTORED) >= 1)
        }
    }

    /** EAM-004: a module package uploaded to the controller is installed on the agent. */
    @Test fun moduleInstallTaskInstallsTheModuleOnTheAgent() {
        fleet { controller, agent, http ->
            assertNull(agent.host.state(HELLO))
            val upload = http.postRaw("/api/v1/eam/payloads?label=hello", helloPackage(), "application/zip")
            assertEquals(201, upload.status, upload.body)
            val payload = upload.json["id"] as String
            val created = http.post("/api/v1/eam/tasks", Json.write(mapOf(
                "id" to "hello", "kind" to "module-install", "agents" to listOf("site-a"), "payload" to payload,
            )))
            assertEquals(201, created.status, created.body)
            val run = finished(controller, "hello")
            assertEquals(RunState.SUCCEEDED, run["state"], run.toString())
            assertEquals("installed", (run["result"] as Map<*, *>)["action"])
            assertNotNull(agent.host.state(HELLO))
            // The next check-in reports the module in the inventory.
            agent.eam.agent.checkInOnce()
            val modules = controller.eam.controller.inventory().first()["modules"] as List<*>
            assertTrue(modules.any { (it as Map<*, *>)["id"] == HELLO }, modules.toString())
        }
    }

    /** EAM-003: a run that fails is retried after its delay, and fails for good when its attempts are spent. */
    @Test fun failedRunIsRetriedThenFailsAndCanBeRetriedByHand() {
        fleet { controller, _, http ->
            val upload = http.postRaw("/api/v1/eam/payloads", "not a module".toByteArray(), "application/zip")
            val payload = upload.json["id"] as String
            controller.eam.controller.putTask(EamTaskDef(
                "broken", EamTaskKind.MODULE_INSTALL, agents = listOf("site-a"), payload = payload, maxAttempts = 2, retryDelayMillis = 100,
            ), "test")
            val run = finished(controller, "broken")
            assertEquals(RunState.FAILED, run["state"])
            assertEquals(2, run["attempts"])
            assertTrue((run["message"] as String).isNotBlank())
            assertTrue(controller.eam.controller.events("site-a").any { it["kind"] == "task-failed" })
            val retried = http.post("/api/v1/eam/runs/${run["id"]}/retry", "{}")
            assertEquals(200, retried.status, retried.body)
            assertEquals(RunState.QUEUED, retried.json["state"])
            val again = finished(controller, "broken")
            assertEquals(RunState.FAILED, again["state"])
            assertEquals(2, again["attempts"])
            // A finished run cannot be cancelled.
            assertEquals(409, http.post("/api/v1/eam/runs/${run["id"]}/cancel", "{}").status)
        }
    }

    /** EAM-006: tasks aimed at a group reach its agents; an agent in maintenance is held until maintenance ends. */
    @Test fun groupTasksSkipAnAgentInMaintenanceUntilItEnds() {
        fleet(group = "north") { controller, _, http ->
            assertEquals(listOf("north"), http.get("/api/v1/eam/groups").json["groups"])
            assertEquals(200, http.put("/api/v1/eam/agents/site-a", Json.write(mapOf("maintenance" to true))).status)
            assertEquals("maintenance", agentRow(http, "site-a")["status"])
            val created = http.post("/api/v1/eam/tasks", Json.write(mapOf("id" to "north-tags", "kind" to "send-tags", "groups" to listOf("north"))))
            assertEquals(201, created.status, created.body)
            Thread.sleep(800)
            assertEquals(RunState.QUEUED, controller.eam.controller.runs(task = "north-tags").single()["state"])
            assertEquals(200, http.put("/api/v1/eam/agents/site-a", Json.write(mapOf("maintenance" to false, "group" to "south"))).status)
            assertEquals(RunState.SUCCEEDED, finished(controller, "north-tags")["state"])
            // The group moved: a new run of the same task finds no target.
            assertEquals(0, controller.eam.controller.fire(controller.eam.controller.task("north-tags")!!).size)
            assertTrue(controller.eam.controller.events("site-a").any { it["kind"] == "maintenance-on" })
        }
    }

    /** EAM-007: a replacement gateway under a failed agent's name is held, then recovered from the last backup. */
    @Test fun replacementGatewayIsRecoveredFromTheLastBackup() {
        val controller = controller()
        try {
            val http = Http(controller.port())
            controller.loginAdmin(http)
            val first = enroll(controller, http, "site-a")
            first.tag("line-speed", "path" to "line/speed", "type" to "memory", "dataType" to "Double", "value" to 12.5)
            controller.eam.controller.putTask(EamTaskDef("before-failure", EamTaskKind.BACKUP, agents = listOf("site-a")), "test")
            assertEquals(RunState.SUCCEEDED, finished(controller, "before-failure")["state"])
            first.close()

            val replacement = startAgent(controller, "site-a", dir = tmp.resolve("replacement"))
            try {
                approveCertificate(http, replacement.network.fingerprint())
                waitUntil({ agentRow(http, "site-a").toString() }) { agentRow(http, "site-a")["status"] == "replacement-pending" }
                assertEquals("replaced", replacement.eam.agent.checkInOnce())
                assertFalse(replacement.tags.isConfigured("[default]line/speed"))
                val recovered = http.post("/api/v1/eam/agents/site-a/recover", "{}")
                assertEquals(200, recovered.status, recovered.body)
                assertNotNull(recovered.json["run"])
                assertEquals(RunState.SUCCEEDED, finished(controller, "recover")["state"])
                waitUntil({ "restored=${replacement.tags.isConfigured("[default]line/speed")}" }) {
                    replacement.tags.isConfigured("[default]line/speed")
                }
                assertEquals(replacement.network.fingerprint(), controller.eam.controller.agent("site-a")!!.fingerprint)
                waitUntil({ agentRow(http, "site-a").toString() }) { agentRow(http, "site-a")["status"] == "online" }
            } finally {
                replacement.close()
            }
        } finally {
            controller.close()
        }
    }

    /** EAM-009: a scheduled task fires once per matching period and not again inside it. */
    @Test fun scheduledTaskFiresOncePerMatchingPeriod() {
        fleet { controller, _, http ->
            val created = http.post("/api/v1/eam/tasks", Json.write(mapOf(
                "id" to "every-second", "kind" to "send-tags", "agents" to listOf("site-a"), "schedule" to "* * * * * *",
            )))
            assertEquals(201, created.status, created.body)
            assertEquals(0, (created.json["runs"] as List<*>).size)
            waitUntil({ controller.eam.controller.runs(task = "every-second").toString() }) {
                controller.eam.controller.runs(task = "every-second").count { it["state"] == RunState.SUCCEEDED } >= 2
            }
            val created2 = controller.eam.controller.runs(task = "every-second").map { (it["createdMillis"] as Long) / 1000 }
            assertEquals(created2.size, created2.toSet().size, "one run per second at most: $created2")
            assertTrue(controller.eam.controller.events().any { it["kind"] == "task-scheduled" })
            // A bad schedule is refused.
            val bad = http.post("/api/v1/eam/tasks", Json.write(mapOf("id" to "bad", "kind" to "backup", "agents" to listOf("site-a"), "schedule" to "every day")))
            assertEquals(422, bad.status, bad.body)
            http.delete("/api/v1/eam/tasks/every-second")
        }
    }

    /** EAM-010: an inventory row per agent, as JSON and CSV. */
    @Test fun inventoryReportListsEveryAgentAsJsonAndCsv() {
        fleet { controller, _, http ->
            val second = enroll(controller, http, "site-b")
            try {
                val inventory = http.get("/api/v1/eam/inventory")
                assertEquals(200, inventory.status)
                val rows = inventory.json["agents"] as List<*>
                assertEquals(listOf("site-a", "site-b"), rows.map { (it as Map<*, *>)["agent"] })
                val row = rows.first() as Map<*, *>
                assertEquals(Gateway.VERSION, row["version"])
                assertNotNull(row["java"])
                assertNotNull(row["uptimeMillis"])
                assertFalse(row.keys.any { it.toString().contains("licen", ignoreCase = true) })
                val csv = http.get("/api/v1/eam/inventory?format=csv")
                assertTrue(csv.header("Content-Type")!!.startsWith("text/csv"))
                val lines = csv.body.trim().lines()
                assertTrue(lines.first().startsWith("agent,group,status,version"))
                assertEquals(3, lines.size, csv.body)
                assertTrue(lines[2].startsWith("site-b,"))
            } finally {
                second.close()
            }
        }
    }

    /** EAM-011: an upgrade package is checked and staged on the agent for its next start. */
    @Test fun upgradeTaskStagesAVerifiedPackage() {
        fleet { controller, agent, http ->
            val bytes = ByteArray(700_000) { (it % 251).toByte() }
            val upload = http.postRaw("/api/v1/eam/payloads?label=gateway-0.2.0", bytes, "application/octet-stream")
            assertEquals(201, upload.status, upload.body)
            val created = http.post("/api/v1/eam/tasks", Json.write(mapOf(
                "id" to "to-020", "kind" to "upgrade", "agents" to listOf("site-a"), "payload" to upload.json["id"], "params" to mapOf("version" to "0.2.0"),
            )))
            assertEquals(201, created.status, created.body)
            val run = finished(controller, "to-020")
            assertEquals(RunState.SUCCEEDED, run["state"], run.toString())
            assertTrue(Files.readAllBytes(agent.dataDir.resolve("upgrade/staged.bin")).contentEquals(bytes))
            assertTrue(agent.dataDir.resolve("upgrade/staged.json").readText().contains("0.2.0"))
            agent.eam.agent.checkInOnce()
            val staged = (controller.eam.controller.agentViews().first()["inventory"] as Map<*, *>)["upgrade"] as Map<*, *>
            assertEquals("0.2.0", staged["version"])
            // A module package task without an uploaded payload is refused.
            val missing = http.post("/api/v1/eam/tasks", Json.write(mapOf("id" to "nothing", "kind" to "upgrade", "agents" to listOf("site-a"))))
            assertEquals(422, missing.status)
        }
    }

    /** EAM-011: an upgrade task with apply switches the install, asks for the restart exit code, and the controller sees the outcome. */
    @Test fun upgradeTaskWithApplyAsksForARestartAndTheControllerSeesTheResult() {
        fleet { controller, agent, http ->
            val install = tmp.resolve("agent-install"); install.resolve("versions/0.1.0").createDirectories()
            Files.writeString(install.resolve("current"), "0.1.0\n")
            val exits = java.util.concurrent.LinkedBlockingQueue<Int>()
            agent.eam.upgrade = org.oip.eam.GatewayUpgrade(install, agent.dataDir)
            agent.eam.restartDelayMillis = 100
            agent.eam.restartHook = { exits.add(it) }
            val bytes = ByteArrayOutputStream().also { bo ->
                ZipOutputStream(bo).use { z -> z.putNextEntry(ZipEntry("bin/oip-gateway")); z.write("#!/bin/sh\n".toByteArray()); z.closeEntry() }
            }.toByteArray()
            val upload = http.postRaw("/api/v1/eam/payloads?label=gateway-0.2.0", bytes, "application/octet-stream")
            http.post("/api/v1/eam/tasks", Json.write(mapOf(
                "id" to "to-021", "kind" to "upgrade", "agents" to listOf("site-a"), "payload" to upload.json["id"],
                "params" to mapOf("version" to "0.2.0", "apply" to true),
            )))
            val run = finished(controller, "to-021")
            assertEquals(RunState.SUCCEEDED, run["state"], run.toString())
            assertEquals(75, exits.poll(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("0.2.0", install.resolve("current").readText().trim())
            // The restarted agent finds the marker, is healthy, and reports the outcome in its inventory.
            agent.eam.upgrade.markHealthy()
            agent.eam.agent.checkInOnce()
            val inv = controller.eam.controller.agentViews().first()["inventory"] as Map<*, *>
            assertEquals("0.2.0", inv["installedVersion"])
            assertEquals("applied", (inv["upgradeResult"] as Map<*, *>)["state"])
        }
    }

    /** EAM-012: an agent whose controller stops checks in with the redundant controller, and back again. */
    @Test fun agentMovesToTheBackupControllerWhenThePrimaryStops() {
        val primary = controller("hq")
        val backup = controller("hq-backup", dir = tmp.resolve("backup-controller"))
        try {
            val http = Http(primary.port())
            primary.loginAdmin(http)
            val backupHttp = Http(backup.port())
            backup.loginAdmin(backupHttp)
            val agent = startAgent(primary, "site-a", backupController = backup)
            try {
                waitUntil({ http.get("/api/v1/eam/agents").body }) { pendingCertificates(http).isNotEmpty() }
                approveCertificate(http, agent.network.fingerprint())
                waitUntil({ backupHttp.get("/api/v1/eam/agents").body }) { pendingCertificates(backupHttp).isNotEmpty() }
                approveCertificate(backupHttp, agent.network.fingerprint())
                // The agent tries the backup when the primary channel is not up yet, and that reply
                // also says pending. Approve only once the primary has the agent record; posting
                // before that check-in is a 404.
                waitUntil({
                    val listed = runCatching { http.get("/api/v1/eam/agents").body }.getOrElse { it.toString() }
                    agent.eam.agent.statusDocument().toString() + "\n" + listed
                }) {
                    agent.eam.agent.state == "pending" && agentRow(http, "site-a")["approved"] == false
                }
                assertEquals(200, http.post("/api/v1/eam/agents/site-a/approve", "{}").status)
                // The backup controller holds the same approval (a redundant pair shares it through config sync).
                backup.eam.controller.putAgent(primary.eam.controller.agent("site-a")!!, "sync")
                waitUntil({ agent.eam.agent.statusDocument().toString() }) { agent.eam.agent.state == "approved" }
                assertEquals("controller", agent.eam.agent.activeConnection)

                primary.close()
                waitUntil({ agent.eam.agent.statusDocument().toString() }, 15_000) { agent.eam.agent.activeConnection == "controller-backup" }
                assertEquals("approved", agent.eam.agent.state)
                assertEquals("hq-backup", agent.eam.agent.controllerName)
                backup.eam.controller.putTask(EamTaskDef("after-failover", EamTaskKind.SEND_TAGS, agents = listOf("site-a")), "test")
                assertEquals(RunState.SUCCEEDED, finished(backup, "after-failover")["state"])
            } finally {
                agent.close()
            }
        } finally {
            runCatching { primary.close() }
            backup.close()
        }
    }

    /** EAM-012: a controller that is a redundancy standby refuses check-ins, so agents stay with the active node. */
    @Test fun standbyControllerRefusesCheckInsUntilItIsActive() {
        val standby = start(tmp.resolve("standby"), "hq-standby", extra = """
            redundancy:
              mode: backup
              standbyActivityLevel: warm
              startupAllowanceMillis: 600000
              masterHost: 127.0.0.1
              masterPort: 1
              connectionName: redundancy
        """.trimIndent())
        try {
            standby.eam.updateSettings(EamSettings(role = EamRole.CONTROLLER), "test")
            assertFalse(standby.redundancy.isActive())
            val http = Http(standby.port())
            standby.loginAdmin(http)
            val agent = startAgent(standby, "site-a")
            try {
                approveCertificate(http, agent.network.fingerprint())
                waitUntil({ agent.eam.agent.statusDocument().toString() }) { agent.eam.agent.lastError.contains("FAILED_PRECONDITION") }
                assertEquals("disconnected", agent.eam.agent.state)
                assertNull(standby.eam.controller.agent("site-a"), "a standby must not enroll agents")
                assertEquals(false, (http.get("/api/v1/eam").json["controller"] as Map<*, *>)["serving"])
                standby.redundancy.requestFailover()
                waitUntil({ "active=${standby.redundancy.isActive()}" }) { standby.redundancy.isActive() }
                waitUntil({ agent.eam.agent.statusDocument().toString() }) { agent.eam.agent.state == "pending" }
                assertNotNull(standby.eam.controller.agent("site-a"))
            } finally {
                agent.close()
            }
        } finally {
            standby.close()
        }
    }

    /** EAM-013: a project exported from the controller is imported on the agent. */
    @Test fun sendProjectTaskImportsTheProjectOnTheAgent() {
        fleet(schemas = true) { controller, agent, http ->
            controller.store.put(Scope.project("plant"), Resource("Thing", Metadata("main", "m"), mapOf("x" to 4)))
            controller.store.put(Scope.project("plant"), Resource("Thing", Metadata("extra", "m"), mapOf("x" to 2)))
            val created = http.post("/api/v1/eam/tasks", Json.write(mapOf(
                "id" to "push-plant", "kind" to "send-project", "agents" to listOf("site-a"), "params" to mapOf("project" to "plant"),
            )))
            assertEquals(201, created.status, created.body)
            val run = finished(controller, "push-plant")
            assertEquals(RunState.SUCCEEDED, run["state"], run.toString())
            assertEquals(2, ((run["result"] as Map<*, *>)["created"] as Number).toInt())
            assertEquals(4, agent.store.get(org.oip.config.ResourceId("m", Scope.project("plant"), "Thing", "main"))!!.spec["x"])
            assertTrue("plant" in agent.projects.listProjects())
        }
    }

    /** EAM-015: tag configuration exported from the controller is imported on the agent. */
    @Test fun sendTagsTaskImportsTheTagsOnTheAgent() {
        fleet { controller, agent, http ->
            controller.tag("pump-speed", "path" to "pump/speed", "type" to "memory", "dataType" to "Double", "value" to 5.0)
            val created = http.post("/api/v1/eam/tasks", Json.write(mapOf("id" to "push-tags", "kind" to "send-tags", "agents" to listOf("site-a"))))
            assertEquals(201, created.status, created.body)
            val run = finished(controller, "push-tags")
            assertEquals(RunState.SUCCEEDED, run["state"], run.toString())
            waitUntil({ "configured=${agent.tags.isConfigured("[default]pump/speed")}" }) { agent.tags.isConfigured("[default]pump/speed") }
            assertEquals(5.0, (agent.tags.read("[default]pump/speed").value as Number).toDouble())
            // The export made for the run is deleted once the run is finished.
            assertEquals(emptyList<Any>(), controller.eam.controller.payloads())
        }
    }

    /** EAM-016 and SCR-012: agent status tags on both sides, and system.eam in a controller script. */
    @Test fun agentStatusTagsAndSystemEamFunctions() {
        fleet(group = "north") { controller, agent, _ ->
            waitUntil({ controller.tags.read("[system]Gateway/EAM/Agents/site-a/Status").toString() }) {
                controller.tags.read("[system]Gateway/EAM/Agents/site-a/Status").value == "online"
            }
            assertEquals(true, controller.tags.read("[system]Gateway/EAM/Agents/site-a/Online").value)
            assertEquals("north", controller.tags.read("[system]Gateway/EAM/Agents/site-a/Group").value)
            assertEquals(1, (controller.tags.read("[system]Gateway/EAM/AgentCount").value as Number).toInt())
            assertEquals("controller", controller.tags.read("[system]Gateway/EAM/Role").value)
            waitUntil({ agent.tags.read("[system]Gateway/EAM/Controller/Connected").toString() }) {
                agent.tags.read("[system]Gateway/EAM/Controller/Connected").value == true
            }
            assertEquals("approved", agent.tags.read("[system]Gateway/EAM/Controller/State").value)

            controller.eam.controller.putTask(EamTaskDef("by-script", EamTaskKind.SEND_TAGS, agents = listOf("site-a")), "test", runNow = false)
            val result = controller.scripts.run(
                """
                groups = system.eam.getGroups()
                status = system.eam.queryAgentStatus(groupIds=["north"])
                history = system.eam.queryAgentHistory(agentIds=["site-a"], limit=10)
                runs = system.eam.runTask("by-script")
                _result = "%s|%d|%s|%s|%d|%d" % (",".join(groups), status.getRowCount(), status.getValueAt(0, "AgentName"),
                    status.getValueAt(0, "IsConnected"), history.getRowCount(), len(runs))
                """.trimIndent(),
            )
            assertTrue(result.ok, result.error ?: result.output)
            val parts = result.value.toString().split('|')
            assertEquals("north", parts[0])
            assertEquals("1", parts[1])
            assertEquals("site-a", parts[2])
            assertEquals("True", parts[3])
            assertTrue(parts[4].toInt() >= 2)
            assertEquals("1", parts[5])
            assertEquals(RunState.SUCCEEDED, finished(controller, "by-script")["state"])
            // On a gateway that is not a controller the functions fail with a clear message.
            val onAgent = agent.scripts.run("_result = system.eam.getGroups()")
            assertFalse(onAgent.ok)
            assertTrue((onAgent.error ?: "").contains("controller"), onAgent.error)
        }
    }

    /** EAM-017: a threshold crossed on an agent raises an alarm on the controller, and clears with it. */
    @Test fun thresholdCrossedOnAnAgentRaisesAnAlarmOnTheController() {
        fleet { controller, agent, http ->
            val limits = http.put("/api/v1/eam/settings", Json.write(mapOf("thresholds" to EamThresholds(offlineMillis = 60_000, cpuPercent = 80.0).toSpec())))
            assertEquals(200, limits.status, limits.body)
            agent.eam.metricsOverride = { mapOf("cpuPercent" to 97.0) }
            agent.eam.agent.checkInOnce()
            waitUntil({ controller.alarms.query().toString() }) {
                controller.alarms.query().any { it.source.contains("EAM/Alarms/site-a/Cpu") }
            }
            assertTrue(controller.eam.controller.events("site-a").any { it["kind"] == "threshold-raised" && it["detail"] == "Cpu" })
            assertEquals(true, controller.tags.read("[system]Gateway/EAM/Agents/site-a/Thresholds/Cpu").value)
            agent.eam.metricsOverride = { mapOf("cpuPercent" to 10.0) }
            agent.eam.agent.checkInOnce()
            waitUntil({ controller.tags.read("[system]Gateway/EAM/Agents/site-a/Thresholds/Cpu").toString() }) {
                controller.tags.read("[system]Gateway/EAM/Agents/site-a/Thresholds/Cpu").value == false
            }
            assertTrue(controller.eam.controller.events("site-a").any { it["kind"] == "threshold-cleared" && it["detail"] == "Cpu" })
            // Maintenance suppresses threshold events.
            agent.eam.metricsOverride = { mapOf("cpuPercent" to 99.0) }
            http.put("/api/v1/eam/agents/site-a", Json.write(mapOf("maintenance" to true)))
            agent.eam.agent.checkInOnce()
            Thread.sleep(1_500)
            assertEquals(false, controller.tags.read("[system]Gateway/EAM/Agents/site-a/Thresholds/Cpu").value)
        }
    }

    // ----- fixtures -----

    private fun fleet(group: String = "", schemas: Boolean = false, body: (Gateway, Gateway, Http) -> Unit) {
        val controller = controller(schemas = schemas)
        try {
            val http = Http(controller.port())
            controller.loginAdmin(http)
            val agent = enroll(controller, http, "site-a", group, schemas)
            try {
                body(controller, agent, http)
            } finally {
                agent.close()
            }
        } finally {
            controller.close()
        }
    }

    private fun controller(name: String = "hq", dir: Path = tmp.resolve("controller-$name"), schemas: Boolean = false): Gateway {
        val gw = start(dir, name, schemas)
        gw.eam.updateSettings(EamSettings(role = EamRole.CONTROLLER), "test")
        return gw
    }

    private fun enroll(controller: Gateway, http: Http, name: String, group: String = "", schemas: Boolean = false): Gateway {
        val agent = startAgent(controller, name, group = group, schemas = schemas)
        try {
            approveCertificate(http, agent.network.fingerprint())
            waitUntil({ agent.eam.agent.statusDocument().toString() }) { agent.eam.agent.state == "pending" }
            assertEquals(200, http.post("/api/v1/eam/agents/$name/approve", "{}").status)
            waitUntil({ agent.eam.agent.statusDocument().toString() }) { agent.eam.agent.state == "approved" }
            return agent
        } catch (t: Throwable) {
            agent.close()
            throw t
        }
    }

    private fun startAgent(
        controller: Gateway,
        name: String,
        group: String = "",
        dir: Path = tmp.resolve("agent-$name"),
        backupController: Gateway? = null,
        schemas: Boolean = false,
    ): Gateway {
        dir.createDirectories()
        val seed = linkedMapOf<String, Any?>(
            "controllerHost" to "127.0.0.1",
            "controllerPort" to controller.network.port(),
            "controllerFingerprint" to controller.network.fingerprint(),
            "group" to group,
            "checkInMillis" to 200,
        )
        if (backupController != null) {
            seed["backupControllerHost"] = "127.0.0.1"
            seed["backupControllerPort"] = backupController.network.port()
            seed["backupControllerFingerprint"] = backupController.network.fingerprint()
        }
        dir.resolve("eam-agent.json").writeText(Json.write(seed))
        return start(dir, name, schemas)
    }

    private fun start(dir: Path, name: String, schemas: Boolean = false, extra: String = ""): Gateway {
        dir.createDirectories()
        dir.resolve("gateway.yaml").writeText(
            """
            profile: standard
            opcua:
              enabled: false
            gatewayNetwork:
              name: $name
              port: 0
              pingRateMillis: 40
              pingTimeoutMillis: 400
              missedPings: 1
              backoffInitialMillis: 20
              backoffCapMillis: 80
            """.trimIndent() + (if (extra.isEmpty()) "" else "\n$extra\n"),
        )
        val gw = Gateway(dir, port = 0, adminDist = null, webDist = null)
        if (schemas) gw.store.registry.register("m", "Thing", mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "integer"))))
        return gw.start()
    }

    private fun Gateway.tag(name: String, vararg spec: Pair<String, Any?>) {
        store.put(Scope.GATEWAY, Resource("Tag", Metadata(name, "tags"), mapOf(*spec)))
    }

    /** Approve [fingerprint] once the controller has seen it in a handshake. */
    private fun approveCertificate(http: Http, fingerprint: String) {
        waitUntil({ pendingCertificates(http).toString() }) { pendingCertificates(http).any { it["fingerprint"] == fingerprint } }
        val r = http.post("/api/v1/gateway-network/incoming/$fingerprint/approve", "{}")
        assertEquals(200, r.status, r.body)
    }

    @Suppress("UNCHECKED_CAST")
    private fun pendingCertificates(http: Http): List<Map<String, Any?>> =
        http.get("/api/v1/eam/agents").json["pendingCertificates"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun agentRow(http: Http, name: String): Map<String, Any?> =
        (http.get("/api/v1/eam/agents").json["agents"] as List<Map<String, Any?>>).first { it["name"] == name }

    /** The newest run of [task] once it has finished. */
    private fun finished(controller: Gateway, task: String): Map<String, Any?> {
        var last: Map<String, Any?>? = null
        waitUntil({ "run of $task: $last" }, 20_000) {
            last = controller.eam.controller.runs(task = task).firstOrNull()
            last?.get("state") in setOf(RunState.SUCCEEDED, RunState.FAILED, RunState.CANCELLED)
        }
        return last!!
    }

    private fun helloPackage(): ByteArray {
        val jar = Path.of(System.getProperty("oip.helloJar"))
        val manifest = ZipFile(jar.toFile()).use { z -> z.getInputStream(z.getEntry("module.yaml")).readBytes().decodeToString() }
        return ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z ->
                z.putNextEntry(ZipEntry("module.yaml")); z.write(manifest.toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("hello-world.jar")); z.write(jar.readBytes()); z.closeEntry()
            }
        }.toByteArray()
    }

    private fun waitUntil(detail: () -> String, timeoutMs: Long = 10_000, body: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var error = ""
        while (System.nanoTime() < deadline) {
            try {
                if (body()) return
            } catch (thrown: Throwable) {
                error = thrown.message ?: thrown.toString()
            }
            Thread.sleep(25)
        }
        throw AssertionError(detail() + (if (error.isEmpty()) "" else " ($error)"))
    }
}
