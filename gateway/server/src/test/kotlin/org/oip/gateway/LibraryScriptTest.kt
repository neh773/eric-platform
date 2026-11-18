package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** system.historian, system.device, system.opc, system.opcua and system.user run against the live gateway. */
class LibraryScriptTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway

    @BeforeEach fun setUp() {
        tmp.createDirectories()
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        gw = Gateway(tmp, port = 0)
        gw.start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
    }

    @AfterEach fun tearDown() = gw.close()

    @Test fun historyDevicesAndUsersUseTheGatewayServices() {
        val stored = gw.scripts.run(
            """
            system.historian.store("[default]Plant/Speed", "2", 0)
            system.historian.store("[default]Plant/Speed", "6", 500)
            system.historian.annotate("[default]Plant/Speed", 500, "shift")
            rows = system.historian.queryRaw("[default]Plant/Speed", 0, 1000)
            windows = system.historian.queryAggregate("[default]Plant/Speed", 0, 999, "Average", 1000)
            notes = system.historian.notes("[default]Plant/Speed")
            paths = system.historian.browse()
            system.config.putResource("org.oip.driver-modbus", "gateway", "Device", "pump-1", {"host": "127.0.0.1", "enabled": False})
            system.device.setDeviceEnabled("pump-1", True)
            device = [item for item in system.device.listDevices() if item["name"] == "pump-1"][0]
            users = [item["username"] for item in system.user.listUsers()]
            roles = [item["name"] for item in system.user.listRoles()]
            _result = {"rows": len(rows), "windows": windows[0]["rows"], "note": notes[0]["note"], "browsed": "[default]Plant/Speed" in paths, "enabled": device["enabled"], "admin": "admin" in users, "role": "administrator" in roles}
            """.trimIndent(),
        )
        assertTrue(stored.ok, stored.error)
        @Suppress("UNCHECKED_CAST")
        val body = stored.value as Map<String, Any?>
        assertEquals(2, (body["rows"] as Number).toInt())
        assertEquals(2, (body["windows"] as Number).toInt())
        assertEquals("shift", body["note"])
        assertEquals(true, body["browsed"])
        assertEquals(true, body["enabled"])
        assertEquals(true, body["admin"])
        assertEquals(true, body["role"])
        val missing = gw.scripts.run("system.opc.browse('missing-device', '')\n")
        assertFalse(missing.ok, missing.error)
        assertTrue(missing.error!!.contains("not found"), missing.error)
        val method = gw.scripts.run("system.opcua.callMethod('missing-line', 'Objects', 'Reset')\n")
        assertFalse(method.ok, method.error)
        assertTrue(method.error!!.contains("not found"), method.error)
        gw.scripts.run("system.device.setDeviceEnabled('pump-1', False)\n")
    }

    @Test fun securityAndUserFunctionsValidateManageAndNeverReturnSecrets() {
        val made = gw.scripts.run(
            """
            made = system.user.addUser("ada", "Correct-Horse-Battery-9!", ["operator"], "Ada L", True)
            good = system.security.validateUser("ada", "Correct-Horse-Battery-9!")
            bad = system.security.validateUser("ada", "wrong-password-1!")
            missing = system.security.validateUser("nobody", "Correct-Horse-Battery-9!")
            roles = system.security.getUserRoles("ada")
            one = system.user.getUser("ada")
            system.user.editUser("ada", ["viewer"], None, False)
            disabled = system.security.validateUser("ada", "Correct-Horse-Battery-9!")
            system.user.editUser("ada", None, "Ada Lovelace", True)
            edited = system.user.getUser("ada")
            system.user.removeUser("ada")
            gone = system.security.getUserRoles("ada")
            _result = {"made": made, "good": good, "bad": bad, "missing": missing, "roles": roles, "one": one, "disabled": disabled, "edited": edited, "gone": gone}
            """.trimIndent(),
        )
        assertTrue(made.ok, made.error)
        @Suppress("UNCHECKED_CAST")
        val body = made.value as Map<String, Any?>
        assertEquals(true, body["good"])
        assertEquals(false, body["bad"])
        assertEquals(false, body["missing"])
        assertEquals(false, body["disabled"])
        assertEquals(listOf("operator"), body["roles"])
        assertEquals(emptyList<Any?>(), body["gone"])
        @Suppress("UNCHECKED_CAST")
        val edited = body["edited"] as Map<String, Any?>
        assertEquals("Ada Lovelace", edited["displayName"])
        assertEquals(listOf("viewer"), edited["roles"])
        // No view of a user carries a password or its hash.
        for (view in listOf(body["made"], body["one"], body["edited"])) {
            val text = view.toString().lowercase()
            assertFalse(text.contains("hash") || text.contains("password=") || text.contains("correct-horse"), text)
        }
        // The last enabled administrator cannot be removed, disabled or demoted by a script.
        for (call in listOf("system.user.removeUser('admin')", "system.user.editUser('admin', None, None, False)", "system.user.editUser('admin', ['viewer'])")) {
            val refused = gw.scripts.run("$call\n")
            assertFalse(refused.ok, call)
            assertTrue(refused.error!!.contains("last enabled administrator"), refused.error)
        }
        val weak = gw.scripts.run("system.user.addUser('weak', 'a')\n")
        assertFalse(weak.ok, weak.error)
        assertFalse(weak.error!!.contains("'a'"))
    }

    @Test fun schedulesAndHolidaysAreReadFromTheNotificationConfig() {
        val noon = java.time.LocalDate.of(2026, 12, 24).atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        val christmasNoon = java.time.LocalDate.of(2026, 12, 25).atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        val night = java.time.LocalDate.of(2026, 12, 24).atTime(23, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        val result = gw.scripts.run(
            """
            system.config.putResource("alarm", "gateway", "Holiday", "christmas", {"date": "2026-12-25", "repeatAnnually": True})
            system.config.putResource("alarm", "gateway", "Schedule", "day-shift", {"allDays": "08:00-17:00", "observeHolidays": True})
            system.config.putResource("alarm", "gateway", "Schedule", "any-shift", {"type": "composite", "scheduleOne": "day-shift", "scheduleTwo": "day-shift"})
            names = [s["name"] for s in system.user.getSchedules()]
            one = system.user.getSchedule("day-shift")
            holidays = system.user.getHolidays()
            active = system.user.isScheduleActive("day-shift", $noon)
            off = system.user.isScheduleActive("day-shift", $night)
            onHoliday = system.user.isScheduleActive("day-shift", $christmasNoon)
            composite = system.user.isScheduleActive("any-shift", $noon)
            always = system.user.isScheduleActive("always", $night)
            _result = {"names": names, "windows": one["allDays"], "holidays": holidays, "active": active, "off": off, "onHoliday": onHoliday, "composite": composite, "always": always}
            """.trimIndent(),
        )
        assertTrue(result.ok, result.error)
        @Suppress("UNCHECKED_CAST")
        val body = result.value as Map<String, Any?>
        assertEquals(listOf("any-shift", "day-shift"), body["names"])
        assertEquals("08:00-17:00", body["windows"])
        assertEquals(listOf(mapOf("name" to "christmas", "date" to "2026-12-25", "repeatAnnually" to true)), body["holidays"])
        assertEquals(true, body["active"])
        assertEquals(false, body["off"])
        assertEquals(false, body["onHoliday"])
        assertEquals(true, body["composite"])
        assertEquals(true, body["always"])
        val missing = gw.scripts.run("system.user.isScheduleActive('nope')\n")
        assertFalse(missing.ok)
        assertTrue(missing.error!!.contains("schedule not found"), missing.error)
    }

    @Test fun opcuaConnectionsCanBeAddedInspectedDisabledAndRemoved() {
        val result = gw.scripts.run(
            """
            added = system.opcua.addConnection("line-1", "opc.tcp://127.0.0.1:1/none", False)
            info = system.opcua.getConnectionInfo("line-1")
            listed = [c["name"] for c in system.opcua.listConnections()]
            enabled = system.opcua.setConnectionEnabled("line-1", True)["enabled"]
            system.opcua.setConnectionEnabled("line-1", False)
            system.opcua.removeConnection("line-1")
            after = [c["name"] for c in system.opcua.listConnections()]
            _result = {"added": added["endpointUrl"], "info": info, "listed": listed, "enabled": enabled, "after": after}
            """.trimIndent(),
        )
        assertTrue(result.ok, result.error)
        @Suppress("UNCHECKED_CAST")
        val body = result.value as Map<String, Any?>
        assertEquals("opc.tcp://127.0.0.1:1/none", body["added"])
        @Suppress("UNCHECKED_CAST")
        val info = body["info"] as Map<String, Any?>
        assertEquals(false, info["enabled"])
        assertEquals("NONE", info["securityPolicy"])
        assertEquals(listOf("line-1"), body["listed"])
        assertEquals(true, body["enabled"])
        assertEquals(emptyList<Any?>(), body["after"])
        for (call in listOf("system.opcua.getConnectionInfo('gone')", "system.opcua.removeConnection('gone')", "system.opcua.callMethod('gone', 'Objects', 'i=1')")) {
            val refused = gw.scripts.run("$call\n")
            assertFalse(refused.ok, call)
            assertTrue(refused.error!!.contains("not found"), refused.error)
        }
        val duplicate = gw.scripts.run("system.opcua.addConnection('dup', 'opc.tcp://127.0.0.1:1/x', False)\nsystem.opcua.addConnection('dup', 'opc.tcp://127.0.0.1:1/x')\n")
        assertFalse(duplicate.ok)
        assertTrue(duplicate.error!!.contains("already exists"), duplicate.error)
        gw.scripts.run("system.opcua.removeConnection('dup')\n")
    }

    /** doc source: FEATURES SFC-004. */
    @Test fun sfcFunctionsStartSteerAndListCharts() {
        val spec = mapOf(
            "executionMode" to "callable",
            "variables" to listOf(mapOf("name" to "ready", "initial" to false), mapOf("name" to "finished", "initial" to 0)),
            "elements" to listOf(
                mapOf("id" to "begin", "type" to "begin"),
                mapOf("id" to "step", "type" to "action"),
                mapOf("id" to "go", "type" to "transition", "expression" to "{ready}"),
                mapOf("id" to "done", "type" to "action", "onStart" to listOf(mapOf("type" to "expression", "assign" to "finished", "expression" to "1"))),
                mapOf("id" to "end", "type" to "end"),
            ),
            "links" to listOf(
                mapOf("from" to "begin", "to" to "step"), mapOf("from" to "step", "to" to "go"),
                mapOf("from" to "go", "to" to "done"), mapOf("from" to "done", "to" to "end"),
            ),
        )
        gw.store.put(
            org.oip.config.Scope.GATEWAY,
            org.oip.config.Resource("Chart", org.oip.config.Metadata("held", "sfc"), spec),
        )
        val result = gw.scripts.run(
            """
            cid = system.sfc.startChart("held", {})
            running = [c["id"] for c in system.sfc.getRunningCharts()]
            system.sfc.pauseChart(cid)
            paused = system.sfc.getRunningCharts("held")[0]["state"]
            system.sfc.resumeChart(cid)
            system.sfc.setVariable(cid, "ready", True)
            import time
            time.sleep(0.3)
            after = system.sfc.getRunningCharts()
            _result = {"started": cid in running, "paused": paused, "after": len(after)}
            """.trimIndent(),
        )
        assertTrue(result.ok, result.error)
        @Suppress("UNCHECKED_CAST")
        val body = result.value as Map<String, Any?>
        assertEquals(true, body["started"])
        assertEquals("Paused", body["paused"])
        assertEquals(0, (body["after"] as Number).toInt())
        val badVariable = gw.scripts.run("cid = system.sfc.startChart('held')\nsystem.sfc.setVariable(cid, 'nope', 1)\n")
        assertFalse(badVariable.ok)
        assertTrue(badVariable.error!!.contains("has no variable"), badVariable.error)
        val missing = gw.scripts.run("system.sfc.startChart('gone')\n")
        assertFalse(missing.ok)
        assertTrue(missing.error!!.contains("not found"), missing.error)
    }

    /** doc source: FEATURES SFC-004: a session script may list charts but not start or steer them. */
    @Test fun sessionScriptsMayListButNotStartCharts() {
        val caller = org.oip.tags.TagCaller.User("ada", setOf("operator"), "default")
        for (call in listOf("system.sfc.startChart('held')", "system.sfc.cancelChart('x')", "system.sfc.pauseChart('x')", "system.sfc.resumeChart('x')", "system.sfc.setVariable('x', 'a', 1)")) {
            val refused = gw.scripts.runForUser("$call\n", "{}", caller)
            assertFalse(refused.ok, call)
            assertTrue(refused.error!!.contains("gateway script only"), refused.error)
        }
        val listed = gw.scripts.runForUser("_result = len(system.sfc.getRunningCharts())\n", "{}", caller)
        assertTrue(listed.ok, listed.error)
    }

    /** doc source: FEATURES HMI-005: scripts translate through the project's terms and fall back to the key. */
    @Test fun utilTranslateUsesProjectTermsAndFallsBackToTheKey() {
        gw.store.put(
            org.oip.config.Scope.project("plant"),
            org.oip.config.Resource(
                "Translation", org.oip.config.Metadata("messages", "designer"),
                mapOf("locale" to "en-US", "messages" to mapOf("Start" to "Start"), "locales" to mapOf("es" to mapOf("Start" to "Iniciar"), "es-MX" to mapOf("Stop" to "Detener"))),
            ),
        )
        val result = gw.scripts.run(
            """
            _result = {
                "es": system.util.translate("Start", "es", "plant"),
                "mx": system.util.translate("Start", "es-MX", "plant"),
                "mxStop": system.util.translate("Stop", "es-MX"),
                "english": system.util.translate("Start", "en-US", "plant"),
                "missing": system.util.translate("Unknown term", "es", "plant"),
                "noLocale": system.util.translate("Start", "fr", "plant"),
            }
            """.trimIndent(),
        )
        assertTrue(result.ok, result.error)
        @Suppress("UNCHECKED_CAST")
        val body = result.value as Map<String, Any?>
        assertEquals("Iniciar", body["es"])
        assertEquals("Iniciar", body["mx"])
        assertEquals("Detener", body["mxStop"])
        assertEquals("Start", body["english"])
        assertEquals("Unknown term", body["missing"])
        assertEquals("Start", body["noLocale"])
        val session = gw.scripts.runForUser("_result = system.util.translate('Start', 'es', 'plant')\n", "{}", org.oip.tags.TagCaller.User("ada", setOf("operator"), "default"))
        assertTrue(session.ok, session.error)
        assertEquals("Iniciar", session.value)
    }
}
