package org.oip.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.SchemaRegistry
import org.oip.db.DatabaseService
import org.oip.db.PasswordUpdate
import org.oip.tags.DataType
import org.oip.tags.Dataset
import org.oip.tags.TagCaller
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.TagType
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * Gateway scripting: system.* , gateway events, the sandbox, and reload.
 *
 */
@Timeout(120)
class ScriptingTest {
    @TempDir lateinit var tmp: Path

    @Test fun tagEventScriptsRunOnValueQualityAndAlarm() {
        harness().use { h ->
            h.tag("Level", value = 1)
            h.tags.configure(TagConfig(path = "Raw", type = TagType.OPC, dataType = DataType.INT32, opcItemPath = "ns=1;s=Raw"))
            h.scripts.saveTagEvent("on-value", "[default]Level", "value", "_result = int(newValue.value)\n")
            h.scripts.saveTagEvent("on-quality", "[default]Raw", "quality", "_result = newValue.quality\n")
            h.scripts.saveTagEvent("on-alarm", "[default]Level", "alarm", "_result = event.alarmName + ':' + event.trigger\n", alarmName = "High")
            h.scripts.start()
            h.tags.write("[default]Level", 4)
            await { (h.scripts.lastResult("on-value")?.value as? Number)?.toInt() == 4 }
            h.tags.write("[default]Level", 4)
            Thread.sleep(150)
            assertEquals(4, num(h.scripts.lastResult("on-value")?.value))
            h.tags.updateRaw("[default]Raw", 1, org.oip.tags.Quality.GOOD)
            h.tags.updateRaw("[default]Raw", 1, org.oip.tags.Quality.BAD)
            await { h.scripts.lastResult("on-quality")?.value == "Bad" }
            assertEquals(4, num(h.scripts.lastResult("on-value")?.value))
            val quality = h.scripts.lastResult("on-quality")!!
            assertFalse(quality.heldEngineWriteLock)
            h.scripts.onTagAlarm("[default]Level", "High", "active", 9)
            await { h.scripts.lastResult("on-alarm")?.value == "High:active" }
            h.scripts.onTagAlarm("[default]Level", "Low", "active", 1)
            Thread.sleep(150)
            assertEquals("High:active", h.scripts.lastResult("on-alarm")?.value)
        }
    }

    @Test fun anAlarmEventScriptReadsStateValueTimesAndConfigFromEventAlarm() {
        harness().use { h ->
            h.tag("Level", value = 1)
            h.scripts.saveTagEvent(
                "on-alarm-object", "[default]Level", "alarm",
                "a = event.alarm\n_result = '%s|%d|%s|%s|%s|%s' % (a.name, a.stateCode, a.isActive, a.value, a.config['mode'], a.associatedData['Area'])\n",
            )
            h.scripts.start()
            h.scripts.onTagAlarm(
                "[default]Level", "High", "active", 150,
                mapOf(
                    "name" to "High", "stateCode" to 2, "isActive" to true, "value" to 150, "activeTime" to 1000L,
                    "config" to mapOf("mode" to "ABOVE", "setpointA" to 100),
                    "associatedData" to mapOf("Area" to "Mixing"),
                ),
            )
            await { h.scripts.lastResult("on-alarm-object")?.value == "High|2|True|150|ABOVE|Mixing" }
        }
    }

    @Test fun aTagPathGlobSubscribesToMatchingTagsIncludingOnesAddedLater() {
        harness().use { h ->
            h.tag("Line/Pump")
            h.tag("Line/Valve")
            h.tag("Other/Pump")
            h.scripts.saveTagEvent("on-line", "[default]Line/*", "value", "_result = str(event.getTagPath())\n")
            h.scripts.start()
            h.tags.write("[default]Line/Pump", 2)
            await { h.scripts.lastResult("on-line")?.value == "[default]Line/Pump" }
            h.tags.write("[default]Other/Pump", 3)
            Thread.sleep(150)
            assertEquals("[default]Line/Pump", h.scripts.lastResult("on-line")?.value)
            h.tags.configure(TagConfig(path = "Line/Mixer", type = TagType.MEMORY, dataType = DataType.INT32, value = 0, valueSet = true))
            var sample = 10
            await {
                sample += 1
                h.tags.write("[default]Line/Mixer", sample)
                h.scripts.lastResult("on-line")?.value == "[default]Line/Mixer"
            }
        }
    }

    @Test fun aSessionScriptWalksTheViewAndQueuesAPropertyWrite() {
        harness().use { h ->
            val caller = TagCaller.User("op", setOf("operator"), "default")
            val tree = listOf(
                mapOf("id" to "root", "name" to "Root", "type" to "flex", "parent" to null, "props" to emptyMap<String, Any?>()),
                mapOf("id" to "label", "name" to "Title", "type" to "label", "parent" to "root", "props" to mapOf("text" to "Hi")),
            )
            val out = HmiScripts(h.scripts).event(
                """
                parent = component.parent()
                component.setProp('text', 'Next')
                return {'name': component.get('name'), 'parent': parent.get('name'), 'text': component.get('props').get('text'), 'count': len(session.components)}
                """.trimIndent(),
                caller,
                mapOf("name" to "click"),
                mapOf("nodeId" to "label", "view" to "main", "params" to emptyMap<String, Any?>(), "custom" to emptyMap<String, Any?>(), "tree" to tree),
            )
            assertTrue(out.ok, out.error)
            val body = out.value as Map<*, *>
            assertEquals("Title", body["name"])
            assertEquals("Root", body["parent"])
            assertEquals("Hi", body["text"])
            assertEquals(2, (body["count"] as Number).toInt())
            assertEquals("setProp", out.navigations.single()["method"])
            assertEquals("Next", out.navigations.single()["value"])
        }
    }

    @Test fun aScriptCallsACustomMethodOfAComponentAndOfAnotherNode() {
        harness().use { h ->
            val caller = TagCaller.User("op", setOf("operator"), "default")
            val tree = listOf(
                mapOf("id" to "root", "name" to "Root", "type" to "flex", "parent" to null, "props" to emptyMap<String, Any?>()),
                mapOf("id" to "pump", "name" to "Pump", "type" to "button", "parent" to "root", "props" to emptyMap<String, Any?>()),
            )
            val out = HmiScripts(h.scripts).event(
                """
                component.callMethod('start', {'speed': 40})
                component.parent().callMethod('refreshAll')
                """.trimIndent(),
                caller,
                mapOf("name" to "click"),
                mapOf("nodeId" to "pump", "view" to "main", "params" to emptyMap<String, Any?>(), "custom" to emptyMap<String, Any?>(), "tree" to tree),
            )
            assertTrue(out.ok, out.error)
            assertEquals(2, out.navigations.size)
            assertEquals("call", out.navigations[0]["method"])
            assertEquals("pump", out.navigations[0]["nodeId"])
            assertEquals("start", out.navigations[0]["name"])
            assertEquals(mapOf("speed" to 40), (out.navigations[0]["value"] as Map<*, *>).mapKeys { it.key.toString() }.mapValues { (it.value as Number).toInt() })
            assertEquals("root", out.navigations[1]["nodeId"])
            assertEquals("refreshAll", out.navigations[1]["name"])
        }
    }

    @Test fun aScriptCannotCallAMethodWithABadNameOrArguments() {
        harness().use { h ->
            val caller = TagCaller.User("op", setOf("operator"), "default")
            val context = mapOf("nodeId" to "pump", "view" to "main", "params" to emptyMap<String, Any?>(), "custom" to emptyMap<String, Any?>())
            val bad = HmiScripts(h.scripts).event("actions.callMethod('pump', 'no good', {})", caller, mapOf("name" to "click"), context)
            assertTrue(bad.ok, bad.error)
            assertEquals(0, bad.navigations.size)
        }
    }

    @Test fun gatewayScriptsCanReadTheCallerUseFilesAndCallHttp() {
        harness().use { h ->
            val root = tmp.resolve("files")
            h.scripts.prepareHost = { host -> host.fileRoot = root }
            h.scripts.prepareHost = { host ->
                host.fileRoot = root
                host.alarmQuery = { source -> if (source == "Tank") """[{"eventId":"e1","source":"Tank","active":true}]""" else "[]" }
                host.alarmAcknowledge = { ids, user, _ -> require(ids == listOf("e1") && user == "system") }
                host.alarmShelve = { source, minutes, user -> require(source == "Tank" && minutes == 5L && user == "system") }
                host.alarmJournal = { source -> if (source == "Tank") """[{"eventId":"j1","source":"Tank"}]""" else "[]" }
            }
            val who = h.eval("u = system.security.getUsername()\nr = system.security.getRoles()\n_result = u\n")
            assertEquals("system", who)
            h.eval("system.file.writeFile('notes/a.txt', 'hello')\n_result = system.file.readFile('notes/a.txt')\n")
            assertEquals("hello", java.nio.file.Files.readString(root.resolve("notes/a.txt")))
            val refused = h.scripts.run("system.file.readFile('../secret')\n")
            assertFalse(refused.ok)
            val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/echo") { exchange ->
                exchange.requestBody.readBytes()
                val bytes = "pong".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            try {
                val port = server.address.port
                val reply = h.eval("r = system.http.get('http://127.0.0.1:$port/echo')\n_result = r['body']\n")
                assertEquals("pong", reply)
                val posted = h.eval("r = system.http.post('http://127.0.0.1:$port/echo', 'ping')\n_result = r['status']\n")
                assertEquals(200, (posted as Number).toInt())
            } finally {
                server.stop(0)
            }
            val alarms = h.eval("_result = system.alarm.queryStatus('Tank')[0]['eventId']\n")
            assertEquals("e1", alarms)
            val journal = h.eval("_result = system.alarm.queryJournal('Tank')[0]['eventId']\n")
            assertEquals("j1", journal)
            h.eval("system.alarm.acknowledge(['e1'])\nsystem.alarm.shelve('Tank', 5)\n_result = 1\n")
            val blocked = h.scripts.run("system.http.get('file:///etc/passwd')\n")
            assertFalse(blocked.ok)
        }
    }

    @Test fun sessionScreenQueuesNavigationPopupsAndMessages() {
        harness().use { h ->
            val caller = TagCaller.User("op", setOf("operator"), "default")
            val out = HmiScripts(h.scripts).event(
                """
                system.screen.navigate(page="/pumps", params={"line": 2})
                system.screen.openPopup("hi", "Popup/Hi", {"n": 1}, "Hello")
                system.screen.closePopup("old")
                system.screen.sendMessage("refresh", {"ok": True}, "session")
                """.trimIndent(),
                caller,
                mapOf("name" to "click"),
                mapOf("nodeId" to "b"),
            )
            assertTrue(out.ok, out.error)
            assertEquals("navigation", out.navigations[0]["kind"])
            assertEquals("/pumps", out.navigations[0]["page"])
            assertEquals("popup", out.navigations[1]["kind"])
            assertEquals("open", out.navigations[1]["mode"])
            assertEquals("Hello", out.navigations[1]["title"])
            assertEquals("close", out.navigations[2]["mode"])
            assertEquals("message", out.navigations[3]["kind"])
            assertEquals("session", out.navigations[3]["scope"])
            val gateway = h.scripts.run("system.screen.navigate(page='/x')\n")
            assertFalse(gateway.ok, gateway.error)
            assertTrue(gateway.error!!.contains("screen"), gateway.error)
        }
    }

    @Test fun sessionDocksAreQueuedAndGatewayScriptsHaveNoScreen() {
        harness().use { h ->
            val caller = TagCaller.User("op", setOf("operator"), "default")
            val out = HmiScripts(h.scripts).event(
                """
                system.screen.openDock("side")
                system.screen.closeDock("side")
                system.screen.toggleDock("side")
                """.trimIndent(),
                caller,
                mapOf("name" to "click"),
                mapOf("nodeId" to "b"),
            )
            assertTrue(out.ok, out.error)
            assertEquals(listOf("open", "close", "toggle"), out.navigations.map { it["mode"] })
            assertTrue(out.navigations.all { it["kind"] == "dock" && it["id"] == "side" })
        }
    }

    @Test fun aSessionScriptReadsSessionPropertiesAndWritesLocaleAndTimeZone() {
        harness().use { h ->
            val caller = TagCaller.User("op", setOf("operator"), "default")
            val session = mapOf(
                "id" to "s1", "theme" to "daylight", "locale" to "en-US", "timeZoneId" to "UTC",
                "auth" to mapOf("authenticated" to true, "userName" to "op", "roles" to listOf("operator")),
                "device" to mapOf("type" to "browser", "id" to "dev-1", "userAgent" to "TestAgent/1", "screenWidth" to 1920, "screenHeight" to 1080, "timeZone" to "Europe/Berlin"),
            )
            val out = HmiScripts(h.scripts).event(
                """
                before = session.props.locale
                session.props.locale = 'de-DE'
                system.screen.setSessionProp('timeZoneId', 'Europe/Berlin')
                return {'before': before, 'after': session.props.locale, 'user': session.props.auth.userName,
                        'device': session.props.device.id, 'agent': session.props.device.userAgent, 'zone': session.props.device.timeZone,
                        'width': session.props.device.screenWidth}
                """.trimIndent(),
                caller,
                mapOf("name" to "click"),
                mapOf("nodeId" to "b", "session" to session),
            )
            assertTrue(out.ok, out.error)
            val body = out.value as Map<*, *>
            assertEquals("en-US", body["before"])
            assertEquals("de-DE", body["after"])
            assertEquals("op", body["user"])
            assertEquals("dev-1", body["device"])
            assertEquals("TestAgent/1", body["agent"])
            assertEquals("Europe/Berlin", body["zone"])
            assertEquals(1920, (body["width"] as Number).toInt())
            assertEquals(
                listOf(mapOf("kind" to "session", "prop" to "locale", "value" to "de-DE"), mapOf("kind" to "session", "prop" to "timeZoneId", "value" to "Europe/Berlin")),
                out.navigations,
            )
        }
    }

    @Test fun aSessionScriptCannotWriteReadOnlySessionProperties() {
        harness().use { h ->
            val caller = TagCaller.User("op", setOf("operator"), "default")
            val session = mapOf("id" to "s1", "device" to mapOf("id" to "dev-1"), "auth" to mapOf("userName" to "op"))
            for (statement in listOf("session.props.device.id = 'x'", "session.props.id = 'x'", "session.props.auth.userName = 'root'")) {
                val out = HmiScripts(h.scripts).event(statement, caller, mapOf("name" to "click"), mapOf("nodeId" to "b", "session" to session))
                assertTrue(!out.ok, statement)
                assertTrue(out.navigations.isEmpty(), statement)
            }
        }
    }

    @Test fun configResourcesRoundTripAndScopeHintsNameTheLibraries() {
        harness().use { h ->
            val version = h.eval(
                """
                saved = system.config.putResource("scripting", "gateway", "ScriptModule", "from-script", {"source": "x = 1"})
                _result = saved["version"]
                """.trimIndent(),
            )
            assertEquals(1, (version as Number).toInt())
            val listed = h.eval(
                """
                names = [item["name"] for item in system.config.listResources("scripting", "gateway", "ScriptModule")]
                _result = "from-script" in names
                """.trimIndent(),
            )
            assertEquals(true, listed)
            val source = h.eval(
                """
                _result = system.config.getResource("scripting", "gateway", "ScriptModule", "from-script")["spec"]["source"]
                """.trimIndent(),
            )
            assertEquals("x = 1", source)
            h.eval("system.config.deleteResource('scripting', 'gateway', 'ScriptModule', 'from-script')\n_result = 1\n")
            val secret = h.scripts.run("system.config.putResource('scripting', 'gateway', 'ScriptModule', 'bad', {'source': 'x', 'password': 'no'})\n")
            assertFalse(secret.ok, secret.error)
            val security = h.scripts.run("system.config.listResources('org.oip.security', 'gateway', 'User')\n")
            assertFalse(security.ok, security.error)
            assertTrue(security.error!!.contains("security"), security.error)
            val sessionUser = h.scripts.runForUser(
                "system.config.listResources('scripting', 'gateway', 'ScriptModule')\n",
                "{}",
                TagCaller.User("op", setOf("operator"), "default"),
            )
            assertFalse(sessionUser.ok, sessionUser.error)
            assertTrue(sessionUser.error!!.contains("gateway script"), sessionUser.error)
            val designer = h.eval("_result = 'system.opcua.callMethod' in system.util.scopeHints('designer')\n")
            assertEquals(true, designer)
            val session = h.eval("_result = 'system.screen.openDock' in system.util.scopeHints('session') and 'system.opc.write' not in system.util.scopeHints('session')\n")
            assertEquals(true, session)
        }
    }

    @Test fun historianDeviceOpcAndUserLibrariesDecodeHostAnswers() {
        harness().use { h ->
            val stored = mutableListOf<String>()
            h.scripts.prepareHost = { host ->
                host.historyStoreFn = { _, value, _ -> stored += value }
                host.historyQueryFn = { _, _, _, aggregate, _ ->
                    if (aggregate.isEmpty()) """[{"timestamp":1,"value":"4","quality":"GOOD"}]""" else """[{"start":0,"value":4,"rows":1}]"""
                }
                host.historyAnnotateFn = { _, _, _ -> }
                host.historyNotesFn = { """[{"note":"shift"}]""" }
                host.deviceListFn = { """[{"name":"pump","driver":"modbus","enabled":true}]""" }
                host.deviceEnableFn = { name, enabled -> stored += "$name:$enabled" }
                host.opcBrowseFn = { _, _ -> """[{"nodeId":"ns=1;s=A","displayName":"A"}]""" }
                host.opcReadFn = { _, _ -> """{"value":3,"quality":"GOOD"}""" }
                host.opcWriteFn = { _, _, value -> stored += value; """{"quality":"GOOD"}""" }
                host.opcuaListFn = { """[{"name":"line","driver":"opcua"}]""" }
                host.libraryFn = { function, args, _ ->
                    require(function == "opcua.callMethod")
                    mapOf("nodeId" to args[2], "invoked" to true, "outputs" to args[3])
                }
                host.userListFn = { """[{"username":"ada"}]""" }
                host.roleListFn = { """[{"name":"operator"}]""" }
            }
            h.eval("system.historian.store('[default]Plant/Speed', '4', 1000)\n_result = 1\n")
            val raw = h.eval("_result = system.historian.queryRaw('[default]Plant/Speed', 0, 2000)[0]['value']\n")
            assertEquals("4", raw)
            val aggregate = h.eval("_result = system.historian.queryAggregate('[default]Plant/Speed', 0, 999, 'Average', 1000)[0]['rows']\n")
            assertEquals(1, (aggregate as Number).toInt())
            val note = h.eval("system.historian.annotate('[default]Plant/Speed', 1000, 'shift')\n_result = system.historian.notes('[default]Plant/Speed')[0]['note']\n")
            assertEquals("shift", note)
            val device = h.eval("_result = system.device.listDevices()[0]['name']\n")
            assertEquals("pump", device)
            h.eval("system.device.setDeviceEnabled('pump', False)\n_result = 1\n")
            val node = h.eval("_result = system.opc.browse('pump')[0]['displayName']\n")
            assertEquals("A", node)
            val read = h.eval("_result = system.opc.read('pump', 'ns=1;s=A')['value']\n")
            assertEquals(3, (read as Number).toInt())
            h.eval("system.opc.write('pump', 'ns=1;s=A', '9')\n_result = 1\n")
            val connection = h.eval("_result = system.opcua.listConnections()[0]['name']\n")
            assertEquals("line", connection)
            val called = h.eval("_result = system.opcua.callMethod('line', 'Objects', 'Reset')['nodeId']\n")
            assertEquals("Reset", called)
            val outputs = h.eval("_result = system.opcua.callMethod('line', 'Objects', 'Add', [1, 2])['outputs']\n")
            assertEquals(listOf(1, 2), (outputs as List<*>).map { (it as Number).toInt() })
            val user = h.eval("_result = system.user.listUsers()[0]['username']\n")
            assertEquals("ada", user)
            val role = h.eval("_result = system.user.listRoles()[0]['name']\n")
            assertEquals("operator", role)
            assertEquals(listOf("4", "pump:false", "9"), stored)
        }
    }

    @Test fun libraryFunctionsKeepTheirCallerRules() {
        harness().use { h ->
            val seen = mutableListOf<String>()
            h.scripts.prepareHost = { host ->
                host.libraryFn = { function, args, actor ->
                    seen += "$function:${args.firstOrNull()}:$actor"
                    if (function == "security.validateUser") args[1] == "right" else mapOf("name" to args.firstOrNull())
                }
            }
            // A gateway script may validate and read any user, and use the gateway-only functions.
            assertEquals(true, h.eval("_result = system.security.validateUser('ada', 'right')\n"))
            assertEquals(false, h.eval("_result = system.security.validateUser('ada', 'wrong')\n"))
            assertEquals("bob", h.eval("_result = system.user.getUser('bob')['name']\n"))
            val op = TagCaller.User("ada", setOf("operator"), "default")
            // A session user may validate only their own account, and never reaches the gateway-only functions.
            val own = h.scripts.runForUser("_result = system.security.validateUser('ADA', 'right')\n", "{}", op)
            assertTrue(own.ok, own.error)
            assertEquals(true, own.value)
            val other = h.scripts.runForUser("_result = system.security.validateUser('bob', 'right')\n", "{}", op)
            assertFalse(other.ok, other.error)
            assertTrue(other.error!!.contains("signed-in user"), other.error)
            val roles = h.scripts.runForUser("_result = system.security.getUserRoles('bob')\n", "{}", op)
            assertFalse(roles.ok, roles.error)
            for (call in listOf("system.user.getUser('ada')", "system.user.addUser('x', 'pw')", "system.user.removeUser('ada')", "system.opcua.callMethod('a', 'b', 'c')", "system.opcua.removeConnection('a')", "system.user.getHolidays()")) {
                val refused = h.scripts.runForUser("$call\n", "{}", op)
                assertFalse(refused.ok, call)
                assertTrue(refused.error!!.contains("gateway script"), "$call: ${refused.error}")
            }
            // The password is never part of an error or of what the host recorded for the refused call.
            assertFalse(other.error!!.contains("right"), other.error)
            assertEquals(listOf("security.validateUser:ada:system", "security.validateUser:ada:system", "user.getUser:bob:system", "security.validateUser:ADA:ada"), seen)
        }
    }

    @Test fun timerAndScheduleStayIdleUntilTheNodeIsActive() {
        harness().use { h ->
            h.scripts.actsWhen = { false }
            h.scripts.defineEvent(EventScript("boot", EventType.STARTUP, "_result = 7\n"))
            h.scripts.defineEvent(EventScript("tick", EventType.TIMER, "_result = 1\n", delayMillis = 40))
            h.scripts.defineEvent(EventScript("hourly", EventType.SCHEDULED, "_result = 3\n", cron = "0 0 * * *"))
            h.scripts.start()
            await { h.scripts.lastResult("boot")?.ok == true }
            h.scripts.poll(Instant.parse("2020-06-01T00:00:00Z"))
            Thread.sleep(200)
            assertEquals(null, h.scripts.lastResult("tick"))
            assertEquals(null, h.scripts.lastResult("hourly"))
            h.scripts.actsWhen = { true }
            h.scripts.poll(Instant.parse("2020-06-01T00:00:00Z"))
            await { h.scripts.lastResult("tick")?.ok == true }
            await { (h.scripts.lastResult("hourly")?.value as? Number)?.toInt() == 3 }
        }
    }

    @Test fun warmUpPreparesTheScriptEngineBeforeTheFirstScript() {
        // review-2 F2: the first HMI event script on a fresh gateway paid GraalPy's whole cold start
        // (6 to over 10 s on a CI runner), so the first click looked lost. warmUp() (called at sign-in)
        // prepares the process-wide engine in the background, without running user code or touching tags.
        harness().use { h ->
            h.tag("Warm/Untouched", value = 5)
            val first = h.scripts.warmUp()
            assertTrue(first === h.scripts.warmUp(), "one warm-up per process")
            assertTrue(first.get(90, java.util.concurrent.TimeUnit.SECONDS), "the engine warmed up")
            assertEquals(5, num(h.tags.read("[default]Warm/Untouched").value))
            assertEquals(2, num(h.eval("_result = 1 + 1\n")))
        }
    }

    @Test fun python3ArithmeticAndAShortScriptStayInsideASmallStatementBudget() {
        harness().use { h ->
            val half = h.scripts.run("_result = 1 / 2\n", limits = limits(statements = 100))
            assertTrue(half.ok, half.error)
            assertEquals(0.5, (half.value as Number).toDouble(), 0.0)
            val legacy = h.scripts.run("print 'hi'\n")
            assertFalse(legacy.ok)
            assertTrue(legacy.error!!.contains("SyntaxError"), legacy.error)
            val handler = h.scripts.run("try:\n    raise Exception('x')\nexcept Exception, e:\n    pass\n")
            assertFalse(handler.ok)
            val xrange = h.scripts.run("xrange(3)\n")
            assertFalse(xrange.ok)
            assertTrue(xrange.error!!.contains("NameError"), xrange.error)
            assertFalse(half.heldEngineWriteLock)
        }
    }

    @Test fun datesUseZeroBasedMonthsAndSundayIsOne() {
        harness().use { h ->
            val result = h.eval(
                """
                d = system.date.getDate(2018, 3, 1)
                nxt = system.date.addDays(d, 1)
                _result = {
                    'text': system.date.format(d, 'yyyy-MM-dd'),
                    'dow': system.date.getDayOfWeek(d),
                    'month': system.date.getMonth(d),
                    'next': system.date.format(nxt, 'yyyy-MM-dd'),
                    'days': system.date.daysBetween(d, system.date.getDate(2018, 3, 3)),
                    'parsed': system.date.format(system.date.parse('2018-04-01 00:00:00', 'yyyy-MM-dd HH:mm:ss'), 'yyyy-MM-dd'),
                    'zone': system.date.getTimezone(),
                    'offset': system.date.getTimezoneOffset(d),
                }
                """.trimIndent(),
            )
            val row = result as Map<*, *>
            assertEquals("2018-04-01", row["text"])
            assertEquals(1, num(row["dow"]))
            assertEquals(3, num(row["month"]))
            assertEquals("2018-04-02", row["next"])
            assertEquals(2, num(row["days"]))
            assertEquals("2018-04-01", row["parsed"])
            assertEquals("UTC", row["zone"])
            assertEquals(0, num(row["offset"]))
        }
    }

    @Test fun datasetsFilterAndExposeRows() {
        harness().use { h ->
            val result = h.eval(
                """
                ds = system.dataset.toDataset(['a', 'b'], [[1, 'x'], [2, 'y']])
                filtered = system.dataset.filterColumns(ds, ['b'])
                py = system.dataset.toPyDataSet(ds)
                _result = {'cell': ds.getValueAt(1, 'a'), 'name': ds.getColumnName(1), 'filtered': filtered.getValueAt(1, 0), 'rows': len(py)}
                """.trimIndent(),
            ) as Map<*, *>
            assertEquals(2, num(result["cell"]))
            assertEquals("b", result["name"])
            assertEquals("y", result["filtered"])
            assertEquals(2, num(result["rows"]))
        }
    }

    @Test fun datasetTagRoundTripKeepsColumnTypes() {
        harness().use { h ->
            h.tag("Table", DataType.DATASET, mapOf(
                "columns" to listOf(mapOf("name" to "id", "type" to "Int32")),
                "rows" to listOf(listOf(1)),
            ))
            val result = h.eval(
                """
                ds = system.dataset.toDataset(
                    [{'name': 'id', 'type': 'Int32'}, {'name': 'name', 'type': 'String'}, {'name': 'on', 'type': 'Boolean'}, {'name': 'when', 'type': 'DateTime'}],
                    [[4, 'pump', True, '2026-10-27T12:34:56.789Z']],
                )
                wrote = system.tag.writeBlocking('[default]Table', ds)[0]
                back = system.tag.readBlocking('[default]Table')[0].value
                bad = system.tag.writeBlocking('[default]Table', {'columns': [{'name': 'id', 'type': 'Int32'}], 'rows': [['x']]})[0]
                kept = system.tag.readBlocking('[default]Table')[0].value
                _result = {
                    'wrote': wrote,
                    'idType': back.getColumnType('id'),
                    'nameType': back.getColumnType(1),
                    'onType': back.getColumnType('on'),
                    'whenType': back.getColumnType('when'),
                    'id': back.getValueAt(0, 'id'),
                    'name': back.getValueAt(0, 'name'),
                    'on': back.getValueAt(0, 'on'),
                    'when': back.getValueAt(0, 'when'),
                    'bad': bad,
                    'kept': kept.getValueAt(0, 'id'),
                }
                """.trimIndent(),
            ) as Map<*, *>
            assertEquals("Good", result["wrote"])
            assertEquals("Int32", result["idType"])
            assertEquals("String", result["nameType"])
            assertEquals("Boolean", result["onType"])
            assertEquals("DateTime", result["whenType"])
            assertEquals(4, num(result["id"]))
            assertEquals("pump", result["name"])
            assertEquals(true, result["on"])
            assertEquals("2026-10-27T12:34:56.789Z", result["when"])
            assertEquals("Error_TypeConversion", result["bad"])
            assertEquals(4, num(result["kept"]))
            assertEquals(4, num((h.tags.read("[default]Table").value as Dataset).rows[0][0]))
        }
    }

    @Test fun loggerKeepsInfoAndPrintAndDropsDebugUntilTheLevelChanges() {
        harness().use { h ->
            val enabled = h.eval(
                """
                log = system.util.getLogger('script.test')
                log.info('kept')
                log.debug('dropped')
                log.infof('n=%d', 3)
                print('from-print')
                _result = log.isInfoEnabled()
                """.trimIndent(),
            )
            assertEquals(true, enabled)
            assertTrue(h.logs.any { it.level == "INFO" && it.message == "kept" })
            assertTrue(h.logs.any { it.level == "INFO" && it.message == "n=3" })
            assertTrue(h.logs.any { it.level == "INFO" && it.message == "from-print" })
            assertFalse(h.logs.any { it.message == "dropped" })
            h.scripts.setLevel("script.test", "DEBUG")
            h.eval("system.util.getLogger('script.test').debug('now-visible')\n_result = 1\n")
            assertTrue(h.logs.any { it.level == "DEBUG" && it.message == "now-visible" })
        }
    }

    @Test fun tagReadWriteAndBrowse() {
        harness().use { h ->
            h.tag("A", DataType.INT32, 1)
            h.tag("B", DataType.INT32, 2)
            h.tag("Folder/C", DataType.STRING, "c")
            val result = h.eval(
                """
                wrote = system.tag.writeBlocking('[default]A', 7)[0]
                read = system.tag.readBlocking('[default]A')[0]
                missing = system.tag.readBlocking('[default]Missing')[0]
                page = system.tag.browse('[default]', {'name': 'A*'})
                names = sorted(item.name for item in page.getResults())
                kind = [item.tagType for item in system.tag.browse('[default]').getResults() if item.name == 'A'][0]
                _result = {'wrote': wrote, 'value': read.getValue(), 'quality': read.quality, 'missing': missing.quality, 'names': names, 'kind': kind}
                """.trimIndent(),
            ) as Map<*, *>
            assertEquals("Good", result["wrote"])
            assertEquals(7, num(result["value"]))
            assertEquals("Good", result["quality"])
            assertEquals("Bad_NotFound", result["missing"])
            assertEquals(listOf("A"), result["names"])
            assertEquals("AtomicTag", result["kind"])
            assertEquals(7, num(h.tags.read("[default]A").value))
        }
    }

    @Test fun namedQueriesRunAsTheGatewayRoleAndRejectSqlOutsideThem() {
        harness(db = true).use { h ->
            h.db.saveConnection("mem", mapOf("driver" to "sqlite", "url" to "jdbc:sqlite::memory:"), 0, PasswordUpdate.Unchanged)
            h.db.saveQuery("setup", mapOf("connection" to "mem", "queryType" to "update", "sql" to "CREATE TABLE item (id INTEGER, name TEXT)"), 0)
            h.db.saveQuery(
                "add",
                mapOf(
                    "connection" to "mem",
                    "queryType" to "update",
                    "sql" to "INSERT INTO item(id, name) VALUES (:id, :name)",
                    "parameters" to listOf(mapOf("name" to "id", "dataType" to "integer"), mapOf("name" to "name", "dataType" to "string")),
                ),
                0,
            )
            h.db.saveQuery("count", mapOf("connection" to "mem", "queryType" to "scalar", "sql" to "SELECT COUNT(*) FROM item"), 0)
            h.db.saveQuery("rows", mapOf("connection" to "mem", "queryType" to "query", "sql" to "SELECT name FROM item ORDER BY id"), 0)
            h.db.saveQuery(
                "secret",
                mapOf("connection" to "mem", "queryType" to "scalar", "sql" to "SELECT 1", "roles" to listOf("operator")),
                0,
            )
            val result = h.eval(
                """
                system.db.execQuery('setup')
                system.db.execQuery('add', {'id': 1, 'name': 'ada'})
                count = system.db.execQuery('count')
                rows = system.db.execQuery('rows')
                again = system.db.runNamedQuery('count')
                via = system.db.execQuery('count', 'gateway')
                named = system.db.runNamedQuery('gateway', 'rows', {})
                _result = {'count': int(count), 'again': int(again), 'via': int(via), 'name': rows.getValueAt(0, 'name'), 'named': named.getRowCount()}
                """.trimIndent(),
            ) as Map<*, *>
            assertEquals(1, num(result["count"]))
            assertEquals(1, num(result["again"]))
            assertEquals(1, num(result["via"]))
            assertEquals("ada", result["name"])
            assertEquals(1, num(result["named"]))
            val forbidden = h.scripts.run("system.db.execQuery('secret')\n")
            assertFalse(forbidden.ok)
            assertTrue(forbidden.error!!.contains("not permitted"), forbidden.error)
            val other = h.scripts.run("system.db.execQuery('count', 'other')\n")
            assertFalse(other.ok)
            assertTrue(other.error!!.contains("unknown project"), other.error)
            val tx = h.scripts.run("system.db.execQuery('count', {}, 'tx')\n")
            assertFalse(tx.ok)
            assertTrue(tx.error!!.contains("transactions"), tx.error)
            val adHoc = h.scripts.run("system.db.runPrepQuery('SELECT 1')\n")
            assertFalse(adHoc.ok)
        }
    }

    @Test fun adHocQueriesTransactionsAndQueryStringParameters() {
        harness(db = true).use { h ->
            h.db.saveConnection("mem", mapOf("driver" to "sqlite", "url" to "jdbc:sqlite::memory:"), 0, PasswordUpdate.Unchanged)
            val result = h.eval(
                """
                system.db.runUpdateQuery("CREATE TABLE item (id INTEGER PRIMARY KEY, name TEXT)", 'mem')
                tx = system.db.beginTransaction('mem')
                system.db.runPrepUpdate('INSERT INTO item(id, name) VALUES (:id, :name)', {'id': 1, 'name': 'ada'}, 'mem', tx)
                system.db.rollbackTransaction(tx)
                gone = system.db.runScalarPrepQuery('SELECT COUNT(*) FROM item', {}, 'mem')
                tx = system.db.beginTransaction('mem')
                system.db.runPrepUpdate('INSERT INTO item(id, name) VALUES (:id, :name)', {'id': 1, 'name': 'ada'}, 'mem', tx)
                system.db.runPrepUpdate('INSERT INTO item(id, name) VALUES (:id, :name)', {'id': 2, 'name': 'bea'}, 'mem', tx)
                system.db.commitTransaction(tx)
                kept = system.db.runScalarPrepQuery('SELECT COUNT(*) FROM item', {}, 'mem')
                rows = system.db.runPrepQuery('SELECT name FROM item WHERE id = :id', {'id': 2}, 'mem')
                plain = system.db.runQuery("SELECT name FROM item WHERE id = 1", 'mem')
                _result = {'gone': int(gone), 'kept': int(kept), 'name': rows.getValueAt(0, 'name'), 'plain': plain.getValueAt(0, 0)}
                """.trimIndent(),
            ) as Map<*, *>
            assertEquals(0, num(result["gone"]))
            assertEquals(2, num(result["kept"]))
            assertEquals("bea", result["name"])
            assertEquals("ada", result["plain"])
            h.db.saveQuery(
                "bycol",
                mapOf(
                    "connection" to "mem",
                    "queryType" to "scalar",
                    "sql" to "SELECT {col} FROM item WHERE id = :id",
                    "parameters" to listOf(
                        mapOf("name" to "col", "dataType" to "string", "queryString" to true),
                        mapOf("name" to "id", "dataType" to "integer"),
                    ),
                ),
                0,
            )
            val named = h.eval(
                """
                _result = system.db.runNamedQuery('bycol', {'col': 'name', 'id': 1})
                """.trimIndent(),
            )
            assertEquals("ada", named)
            val injected = h.scripts.run("system.db.runNamedQuery('bycol', {'col': 'name; drop table item', 'id': 1})\n")
            assertFalse(injected.ok)
            assertTrue(injected.error!!.contains("identifier"), injected.error)
            val session = h.scripts.runForUser(
                "system.db.runPrepQuery('SELECT 1', {}, 'mem')\n",
                "{}",
                TagCaller.User("op", setOf("operator"), "default"),
            )
            assertFalse(session.ok)
            assertTrue(session.error!!.contains("gateway scripts"), session.error)
        }
    }

    @Test fun tagConfigureCopyMoveDeleteAndUtilHelpers() {
        harness().use { h ->
            h.tag("Line/Speed", DataType.INT32, 4)
            h.tag("Line/Later", DataType.INT32, 0)
            val configured = h.eval(
                """
                system.tag.configure('[default]Line/Setpoint', {'type': 'memory', 'dataType': 'Int32', 'value': 9})
                system.tag.copy('[default]Line/Speed', '[default]Line/SpeedCopy')
                system.tag.move('[default]Line/Setpoint', '[default]Line/Moved')
                before = system.tag.readBlocking('[default]Line/Moved')[0].value
                system.tag.delete('[default]Line/SpeedCopy')
                missing = system.tag.readBlocking('[default]Line/SpeedCopy')[0].quality
                encoded = system.util.jsonEncode({'n': 2, 'ok': True})
                decoded = system.util.jsonDecode(encoded)
                status = system.util.getGatewayStatus()
                system.util.invokeLater("system.tag.writeBlocking('[default]Line/Later', 5)", 30)
                _result = {'before': int(before), 'missing': missing, 'n': decoded['n'], 'state': status['state']}
                """.trimIndent(),
            ) as Map<*, *>
            assertEquals(9, num(configured["before"]))
            assertEquals("Bad_NotFound", configured["missing"])
            assertEquals(2, num(configured["n"]))
            assertEquals("running", configured["state"])
            assertEquals(4, num(h.tags.read("[default]Line/Speed").value))
            await { num(h.tags.read("[default]Line/Later").value) == 5 }
            val refused = h.scripts.runForUser(
                "system.tag.configure('[default]Nope', {'type': 'memory', 'dataType': 'Int32', 'value': 1})\n",
                "{}",
                TagCaller.User("op", setOf("operator"), "default"),
            )
            assertFalse(refused.ok)
            assertTrue(refused.error!!.contains("not permitted"), refused.error)
        }
    }

    @Test fun startupShutdownTimerTagChangeScheduleAndMessages() {
        harness().use { h ->
            h.tag("Boot")
            h.tag("Stop")
            h.tag("Ticks")
            h.tag("Source")
            h.tag("Seen", DataType.STRING, "")
            h.scripts.defineEvent(EventScript("boot", EventType.STARTUP, "system.tag.writeBlocking('[default]Boot', 1)\n_result = 1\n"))
            h.scripts.defineEvent(EventScript("stop", EventType.SHUTDOWN, "system.tag.writeBlocking('[default]Stop', 1)\n_result = 1\n"))
            h.scripts.defineEvent(EventScript("tick", EventType.TIMER, "_result = 1\n", delayMillis = 40, threading = "dedicated"))
            h.scripts.defineEvent(
                EventScript(
                    "on-a",
                    EventType.TAG_CHANGE,
                    """
                    seen = system.tag.readBlocking('[default]Seen')[0].value or ''
                    system.tag.writeBlocking('[default]Seen', seen + str(executionCount))
                    _result = {'count': executionCount, 'initial': initialChange, 'value': None if newValue is None else newValue.value, 'name': event.getTagPath().getItemName()}
                    """.trimIndent(),
                    tagPaths = listOf("[default]Source"),
                    triggers = setOf("value"),
                ),
            )
            h.scripts.defineEvent(EventScript("adder", EventType.MESSAGE, "_result = int(payload['n']) + 1\n"))
            h.clock.instant = Instant.parse("2020-01-01T00:00:00Z")
            h.tag("N")
            h.scripts.defineEvent(
                EventScript(
                    "cron",
                    EventType.SCHEDULED,
                    """
                    n = system.tag.readBlocking('[default]N')[0].value or 0
                    system.tag.writeBlocking('[default]N', int(n) + 1)
                    _result = int(n) + 1
                    """.trimIndent(),
                    cron = "0 0 0 1 1 *",
                ),
            )
            h.scripts.start()
            assertEquals(1, num(h.tags.read("[default]Boot").value))
            assertEquals(0, num(h.tags.read("[default]Stop").value))
            await { h.scripts.lastResult("tick")?.ok == true }
            val tick = h.scripts.lastResult("tick")!!
            assertTrue(tick.thread.startsWith("oip-script-timer-tick"), tick.thread)
            assertFalse(tick.heldEngineWriteLock)
            await { h.tags.read("[default]Seen").value == "0" }
            h.tags.write("[default]Source", 1)
            h.tags.write("[default]Source", 1)
            await { h.tags.read("[default]Seen").value == "01" }
            h.tags.write("[default]Source", 2)
            await { h.tags.read("[default]Seen").value == "012" }
            val change = awaitResult(h, "on-a") { num((it.value as Map<*, *>)["count"]) == 2 }
            val body = change.value as Map<*, *>
            assertEquals(false, body["initial"])
            assertEquals(2, num(body["value"]))
            assertEquals("Source", body["name"])
            assertTrue(change.thread.startsWith("oip-script-tag-on-a"), change.thread)
            assertFalse(change.heldEngineWriteLock)
            h.scripts.poll(h.clock.instant)
            await { num(h.tags.read("[default]N").value) == 1 }
            h.scripts.poll(h.clock.instant)
            h.clock.instant = Instant.parse("2020-01-01T00:00:01Z")
            h.scripts.poll(h.clock.instant)
            Thread.sleep(250)
            assertEquals(1, num(h.tags.read("[default]N").value))
            h.clock.instant = Instant.parse("2021-01-01T00:00:00Z")
            h.scripts.poll(h.clock.instant)
            await { num(h.tags.read("[default]N").value) == 2 }
            val reply = h.eval("system.util.sendRequest('gateway', 'adder', {'n': 2})\n_result = system.util.sendRequest('gateway', 'adder', {'n': 2})\n")
            assertEquals(3, num(reply))
            h.eval("system.util.sendMessage('gateway', 'adder', {'n': 4})\n_result = 1\n")
            await { num(h.scripts.lastResult("adder")?.value) == 5 }
            val unknown = h.scripts.run("system.util.sendRequest('other', 'adder', {})\n")
            assertFalse(unknown.ok)
            val loop = h.scripts.run("system.util.sendRequest('gateway', 'loop', {})\n")
            h.scripts.defineEvent(EventScript("loop", EventType.MESSAGE, "system.util.sendRequest('gateway', 'loop', {})\n"))
            val nested = h.scripts.run("system.util.sendRequest('gateway', 'loop', {})\n")
            assertFalse(nested.ok, nested.error)
            assertTrue(nested.error!!.contains("itself"), nested.error)
            assertFalse(loop.ok)
            h.scripts.shutdown()
            assertEquals(1, num(h.tags.read("[default]Stop").value))
            assertEquals(1, num(h.tags.read("[default]Boot").value))
        }
    }

    /**
     * A request handler runs on the caller's thread with its own context and limits. Its context
     * setup alone is several MiB (tens while the JIT is cold), so charging it to the caller made a
     * script with two requests trip its own 64 MiB cap at random. The caller's cap here is far
     * below one handler context and still has to hold.
     */
    @Test fun aRequestHandlerSpendsItsOwnMemoryBudgetNotTheCallers() {
        harness().use { h ->
            h.scripts.defineEvent(EventScript("echo", EventType.MESSAGE, "_result = int(payload['n'])\n"))
            val calls = h.scripts.run(
                """
                total = 0
                for n in range(4):
                    total += system.util.sendRequest('gateway', 'echo', {'n': n})
                _result = total
                """.trimIndent(),
                limits = limits(bytes = 4L * 1024 * 1024),
            )
            assertTrue(calls.ok, "${calls.failure}: ${calls.error}")
            assertEquals(6, num(calls.value))
            // The caller's own allocation still counts after the requests return.
            val after = h.scripts.run(
                """
                system.util.sendRequest('gateway', 'echo', {'n': 1})
                chunks = []
                i = 0
                while i < 80:
                    chunks.append('x' * 200000)
                    i += 1
                _result = len(chunks)
                """.trimIndent(),
                limits = limits(timeoutMs = 20_000, statements = 100_000_000, bytes = 2_000_000),
            )
            assertEquals(ScriptFailure.MEMORY, after.failure, after.error)
        }
    }

    @Test fun tagWritesReturnWhileATagScriptIsBusy() {
        harness().use { h ->
            h.tag("A")
            h.scripts.defineEvent(
                EventScript(
                    "slow",
                    EventType.TAG_CHANGE,
                    """
                    import time
                    if executionCount == 0:
                        time.sleep(1.2)
                    _result = executionCount
                    """.trimIndent(),
                    tagPaths = listOf("[default]A"),
                    limits = limits(timeoutMs = 10_000),
                ),
            )
            h.scripts.start()
            await { Thread.getAllStackTraces().keys.any { it.name == "oip-script-tag-slow" } }
            val started = System.nanoTime()
            repeat(200) { h.tags.write("[default]A", it + 1) }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsed < 2_000, "tag writes took ${elapsed}ms while a script was running")
            assertTrue(h.scripts.droppedEvents("slow") > 0)
            val result = awaitResult(h, "slow") { it.ok }
            assertTrue(result.thread.startsWith("oip-script-tag-"), result.thread)
            assertFalse(result.heldEngineWriteLock)
        }
    }

    @Test fun timeoutStatementLimitMemoryAndOutputLeaveTheTagEngineUsable() {
        harness().use { h ->
            h.tag("A")
            val budget = h.scripts.run("_result = 41\n", limits = limits(statements = 100))
            assertTrue(budget.ok, budget.error)
            assertEquals(41, num(budget.value))
            val statements = h.scripts.run("while True:\n    pass\n", limits = limits(timeoutMs = 30_000, statements = 5_000))
            assertEquals(ScriptFailure.STATEMENTS, statements.failure, statements.error)
            val timed = h.scripts.run("while True:\n    pass\n", limits = limits(timeoutMs = 200, statements = 100_000_000))
            assertEquals(ScriptFailure.TIMEOUT, timed.failure, timed.error)
            assertEquals("Good", h.tags.write("[default]A", 9).quality.code.label)
            val after = h.scripts.run("_result = 3\n")
            assertTrue(after.ok, after.error)
            assertEquals(3, num(after.value))
            val memory = h.scripts.run(
                """
                chunks = []
                i = 0
                while i < 80:
                    chunks.append('x' * 200000)
                    i += 1
                _result = len(chunks)
                """.trimIndent(),
                limits = limits(timeoutMs = 20_000, statements = 100_000_000, bytes = 2_000_000),
            )
            assertEquals(ScriptFailure.MEMORY, memory.failure, memory.error)
            assertEquals("Good", h.tags.write("[default]A", 10).quality.code.label)
            val output = h.scripts.run("print('x' * 500)\n_result = 1\n", limits = limits(output = 50))
            assertEquals(ScriptFailure.OUTPUT, output.failure, output.error)
        }
    }

    @Test fun sandboxBlocksHostEscape() {
        val escape = Path.of("/tmp/oip-script-escape-406e")
        Files.deleteIfExists(escape)
        harness().use { h ->
            val attempts = listOf(
                "open('/etc/passwd').read()\n",
                "import os\nos.system('touch /tmp/oip-script-escape-406e')\n",
                "import subprocess\nsubprocess.check_call(['touch', '/tmp/oip-script-escape-406e'])\n",
                "import java\njava.lang.System.getProperty('os.name')\n",
                "from java.lang import Runtime\nRuntime.getRuntime()\n",
                "import socket\nsocket.socket()\n",
                "cell = system.tag.readBlocking.__closure__[0].cell_contents\ncell.getClass()\n",
            )
            for (source in attempts) {
                val result = h.scripts.run(source)
                val text = (result.value?.toString() ?: "") + result.output + (result.error ?: "")
                assertFalse(result.ok, source + " -> " + text)
                assertFalse(text.contains("root:"), text)
                assertFalse(text.contains("ScriptHost"), text)
                assertFalse(Files.exists(escape), source)
            }
        }
        Files.deleteIfExists(escape)
    }

    /**
     * SCR-042: one library module is importable and callable in the gateway, session (HMI hook) and designer
     * scopes, and a save is seen by the next run in each of them.
     */
    @Test fun aLibraryModuleIsCallableInTheGatewaySessionAndDesignerScopes() {
        harness().use { h ->
            val saved = h.scripts.saveModule("shared", "def mark(where):\n    return 'v1:' + where\n")
            val caller = TagCaller.User("op", setOf("operator"), "default")
            fun everyScope(): List<Any?> {
                val gateway = h.scripts.run("import shared\n_result = shared.mark('gateway')\n")
                val designer = h.scripts.run("import shared\n_result = shared.mark('designer')\n", scope = ScriptScope.DESIGNER)
                val session = HmiScripts(h.scripts).transform("import shared\nreturn shared.mark(value)", caller, "session", emptyMap())
                assertTrue(gateway.ok, gateway.error)
                assertTrue(designer.ok, designer.error)
                assertTrue(session.ok, session.error)
                return listOf(gateway.value, designer.value, session.value)
            }
            assertEquals(listOf("v1:gateway", "v1:designer", "v1:session"), everyScope())
            h.scripts.saveModule("shared", "def mark(where):\n    return 'v2:' + where\n", version = saved.metadata.version)
            assertEquals(listOf("v2:gateway", "v2:designer", "v2:session"), everyScope())
        }
    }

    @Test fun libraryAndEventReloadApplyOnTheNextRun() {
        harness().use { h ->
            val saved = h.scripts.saveModule("funcs", "def hello():\n    return 'old'\n")
            h.tag("Started")
            h.tag("Go")
            // The run reports that it is executing (its library set is fixed by then) and holds
            // until the test has saved the new library, so the save always lands mid-run.
            val flying = h.scripts.runAsync(
                """
                import time
                system.tag.writeBlocking('[default]Started', 1)
                while not system.tag.readBlocking('[default]Go')[0].value:
                    time.sleep(0.01)
                _result = funcs.hello()
                """.trimIndent(),
            )
            await { num(h.tags.read("[default]Started").value) == 1 }
            h.scripts.saveModule("funcs", "def hello():\n    return 'new'\n", version = saved.metadata.version)
            h.tags.write("[default]Go", 1)
            val first = flying.get(15, TimeUnit.SECONDS)
            assertTrue(first.ok, first.error)
            assertEquals("old", first.value)
            assertEquals("new", h.eval("_result = funcs.hello()\n"))
            h.tag("Source")
            h.tag("Seen", DataType.STRING, "")
            h.scripts.defineEvent(
                EventScript(
                    "on-a",
                    EventType.TAG_CHANGE,
                    """
                    seen = system.tag.readBlocking('[default]Seen')[0].value or ''
                    system.tag.writeBlocking('[default]Seen', seen + str(executionCount))
                    _result = executionCount
                    """.trimIndent(),
                    tagPaths = listOf("[default]Source"),
                ),
            )
            h.scripts.start()
            await { h.tags.read("[default]Seen").value == "0" }
            h.tags.write("[default]Source", 1)
            await { h.tags.read("[default]Seen").value == "01" }
            h.scripts.defineModule("extra", "x = 1\n")
            await { (h.tags.read("[default]Seen").value as String).startsWith("010") }
            val timer = h.scripts.saveEvent("pulse", "timer", "_result = 1\n", delayMillis = 40)
            await { (h.scripts.lastResult("pulse")?.value as? Number)?.toInt() == 1 }
            h.scripts.saveEvent("pulse", "timer", "_result = 2\n", delayMillis = 40, version = timer.metadata.version)
            await { (h.scripts.lastResult("pulse")?.value as? Number)?.toInt() == 2 }
        }
    }

    /** SCR-014: system.math answers the same in every scope, with the usual sample and population definitions. */
    @Test fun mathStatisticsMatchKnownValues() {
        harness().use { h ->
            @Suppress("UNCHECKED_CAST")
            val r = h.eval(
                """
                d = [2, 4, 4, 4, 5, 5, 7, 9]
                m = system.math
                _result = {
                    "sum": m.sum(d), "mean": m.mean(d), "median": m.median(d), "mode": m.mode(d),
                    "min": m.min(d), "max": m.max(d), "popVar": m.populationVariance(d), "popSd": m.populationStandardDeviation(d),
                    "var": m.variance(d), "sd": m.standardDeviation(d),
                    "p25": m.percentile(d, 25), "p50": m.percentile(d, 50), "p90": m.percentile(d, 90), "p100": m.percentile(d, 100),
                    "geo": m.geometricMean([1, 2, 4, 8]), "geoBad": m.geometricMean([1, 0, 4]) != m.geometricMean([1, 0, 4]),
                    "skew": m.skewness([1, 2, 3, 4, 10]), "kurt": m.kurtosis([1, 2, 3, 4, 10]), "flat": m.skewness([3, 3, 3]),
                    "odd": m.median([9, 1, 5]), "sumDiff": m.sumDifference([5, 6], [1, 2]), "meanDiff": m.meanDifference([5, 6], [1, 2]),
                }
                """.trimIndent(),
            ) as Map<String, Any?>
            fun num(key: String) = (r[key] as Number).toDouble()
            assertEquals(40.0, num("sum")); assertEquals(5.0, num("mean")); assertEquals(4.5, num("median")); assertEquals(4.0, num("mode"))
            assertEquals(2.0, num("min")); assertEquals(9.0, num("max")); assertEquals(4.0, num("popVar")); assertEquals(2.0, num("popSd"))
            assertEquals(32.0 / 7.0, num("var"), 1e-12); assertEquals(Math.sqrt(32.0 / 7.0), num("sd"), 1e-12)
            assertEquals(4.0, num("p25")); assertEquals(4.5, num("p50")); assertEquals(9.0, num("p90")); assertEquals(9.0, num("p100"))
            assertEquals(Math.pow(2.0, 1.5), num("geo"), 1e-12); assertEquals(true, r["geoBad"])
            assertEquals(1.6970562748477143, num("skew"), 1e-9); assertEquals(3.152, num("kurt"), 1e-9); assertEquals(0.0, num("flat"))
            assertEquals(5.0, num("odd")); assertEquals(8.0, num("sumDiff")); assertEquals(4.0, num("meanDiff"))
            for (bad in listOf("system.math.mean([])", "system.math.mean('abc')", "system.math.mean([1, 'a'])", "system.math.percentile([1, 2], 0)", "system.math.sumDifference([1], [1, 2])")) {
                val refused = h.scripts.run("$bad\n")
                assertFalse(refused.ok, bad)
            }
            val session = h.scripts.runForUser("_result = system.math.median([3, 1, 2])\n", "{}", TagCaller.User("ada", setOf("operator"), "default"))
            assertTrue(session.ok, session.error)
            assertEquals(2.0, (session.value as Number).toDouble())
        }
    }

    private fun harness(db: Boolean = false): ScriptHarness = ScriptHarness(tmp.resolve("h-${System.nanoTime()}"), db)
}

private class MutableClock(var instant: Instant = Instant.parse("2020-06-01T00:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = instant
    override fun millis(): Long = instant.toEpochMilli()
}

private class ScriptHarness(dir: Path, withDb: Boolean) : AutoCloseable {
    val clock = MutableClock()
    val tags = TagEngine(clock)
    val logs = java.util.Collections.synchronizedList(mutableListOf<LogLine>())
    val store = FileConfigStore(dir.resolve("config"), SchemaRegistry(), clock)
    val db: DatabaseService = DatabaseService(
        store,
        dir.resolve("data"),
        clock,
        readSecret = { reference -> error("no secret $reference") },
        writeSecret = { _, _, _ -> error("secrets are not used") },
    ).also { if (withDb) it.attach() }
    val scripts = ScriptService(
        tags,
        if (withDb) db else null,
        clock,
        logger = ScriptLogger { level, name, message -> logs.add(LogLine(level, name, message)) },
    ).also { it.attach(store) }

    fun tag(path: String, type: DataType = DataType.INT32, value: Any? = 0) {
        tags.configure(TagConfig(path = path, type = TagType.MEMORY, dataType = type, value = value, valueSet = true))
    }

    fun eval(source: String, limits: ScriptLimits = limits()): Any? {
        val result = scripts.run(source, limits = limits)
        assertTrue(result.ok) { "${result.failure}: ${result.error}\n${result.output}" }
        return result.value
    }

    override fun close() {
        runCatching { scripts.close() }
        runCatching { db.close() }
        runCatching { store.close() }
    }
}

private data class LogLine(val level: String, val logger: String, val message: String)

private fun limits(
    timeoutMs: Long = 10_000,
    statements: Long = 1_000_000,
    bytes: Long = 64L * 1024 * 1024,
    output: Int = 64 * 1024,
) = ScriptLimits(Duration.ofMillis(timeoutMs), statements, bytes, output)

private fun num(value: Any?): Int = (value as Number).toInt()

private fun await(timeoutMs: Long = 8_000, probe: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (System.nanoTime() < deadline) {
        if (probe()) return
        Thread.sleep(20)
    }
    throw AssertionError("condition not met")
}

private fun awaitResult(h: ScriptHarness, name: String, probe: (ScriptResult) -> Boolean): ScriptResult {
    val deadline = System.nanoTime() + 8_000_000_000L
    var last: ScriptResult? = null
    while (System.nanoTime() < deadline) {
        last = h.scripts.lastResult(name)
        if (last != null && probe(last)) return last
        Thread.sleep(20)
    }
    throw AssertionError("no matching result for $name; last=$last")
}
