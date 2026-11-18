package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.security.DefaultRoles
import org.oip.security.SecurityZone
import org.oip.security.UpdateUser
import org.oip.tags.DataType
import org.oip.tags.Quality
import org.oip.tags.QualityCode
import org.oip.tags.TagCaller
import org.oip.tags.TagConfig
import org.oip.tags.TagType
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * C-4 (M4.1): `/api/v1/ws` serves the browser runtime's tag frames from the gateway tag engine, as the signed-in
 * user, with the per-tag permissions (TAG-013), resume, and logout handling. [WebUi] serves the HMI bundle.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class SessionHostTest {
    @TempDir lateinit var tmp: Path

    private fun gateway(name: String, clock: java.time.Clock = java.time.Clock.systemUTC(), webDist: Path? = null): Gateway {
        val dir = tmp.resolve(name).createDirectories()
        dir.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        return Gateway(dir, port = 0, clock = clock, adminDist = null, webDist = webDist)
    }

    private fun Gateway.tag(name: String, vararg spec: Pair<String, Any?>) {
        val version = store.get(ResourceId("tags", Scope.GATEWAY, "Tag", name))?.metadata?.version ?: 0L
        store.put(Scope.GATEWAY, Resource("Tag", Metadata(name, "tags", version = version), mapOf(*spec)))
    }

    private fun login(gw: Gateway, user: String): Http = Http(gw.port()).also {
        val r = it.login(user, TEST_PASSWORD)
        check(r.status == 200) { "login $user: ${r.status} ${r.body}" }
    }

    /**
     * JDK WebSocket client. Each complete text frame is queued as parsed JSON for [next]; every update entry is also
     * kept in arrival order for [update], so entries are not lost when [next] skips the frame that carried them.
     */
    private class Ws(val socket: WebSocket, val frames: LinkedBlockingQueue<Map<String, Any?>>, val closed: CompletableFuture<Int>) {
        val entries = java.util.concurrent.CopyOnWriteArrayList<Map<String, Any?>>()
        private val cursors = HashMap<String, Int>()

        fun send(vararg entries: Pair<String, Any?>) {
            socket.sendText(Json.write(mapOf(*entries)), true).get(5, TimeUnit.SECONDS)
        }

        fun next(timeoutMillis: Long = 5_000, match: (Map<String, Any?>) -> Boolean): Map<String, Any?> {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            while (true) {
                val left = deadline - System.nanoTime()
                val frame = frames.poll(left.coerceAtLeast(0), TimeUnit.NANOSECONDS)
                    ?: throw AssertionError("no matching frame within $timeoutMillis ms")
                if (match(frame)) return frame
            }
        }

        /** The next update entry for [path] (after the one this returned last) that satisfies [match]. */
        fun update(path: String, timeoutMillis: Long = 5_000, match: (Map<String, Any?>) -> Boolean = { true }): Map<String, Any?> {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (true) {
                val start = cursors[path] ?: 0
                val snapshot = entries.toList()
                for (i in start until snapshot.size) {
                    val entry = snapshot[i]
                    if (entry["path"] == path && match(entry)) {
                        cursors[path] = i + 1
                        return entry
                    }
                }
                if (System.currentTimeMillis() > deadline) throw AssertionError("no update for $path within $timeoutMillis ms: $snapshot")
                Thread.sleep(10)
            }
        }

        fun hello(resumeToken: String? = null): Map<String, Any?> {
            val nonce = java.util.UUID.randomUUID().toString()
            send("type" to "hello", "nonce" to nonce, "resumeToken" to resumeToken)
            return next { it["type"] == "welcome" && it["nonce"] == nonce }
        }

        fun close() {
            runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS) }
        }
    }

    private fun connect(gw: Gateway, http: Http?, headers: Map<String, String> = emptyMap()): Ws {
        val frames = LinkedBlockingQueue<Map<String, Any?>>()
        val closed = CompletableFuture<Int>()
        val builder = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
        http?.sessionCookie()?.let { builder.header("Cookie", "${HttpAuth.COOKIE}=$it") }
        headers.forEach { (k, v) -> builder.header(k, v) }
        var ws: Ws? = null
        val listener = object : WebSocket.Listener {
            private val buffer = StringBuilder()
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                buffer.append(data)
                if (last) {
                    val frame = Json.parse(buffer.toString())
                    buffer.setLength(0)
                    @Suppress("UNCHECKED_CAST")
                    if (frame["type"] == "update") ws?.entries?.addAll(frame["updates"] as List<Map<String, Any?>>)
                    frames.add(frame)
                }
                webSocket.request(1)
                return null
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                closed.complete(statusCode)
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                closed.complete(-1)
            }
        }
        val socket = builder.buildAsync(URI.create("ws://127.0.0.1:${gw.port()}${SessionHost.PATH}"), listener).get(10, TimeUnit.SECONDS)
        return Ws(socket, frames, closed).also { ws = it }
    }

    private fun handshakeStatus(gw: Gateway, http: Http?, headers: Map<String, String> = emptyMap()): Int {
        val failure = runCatching { connect(gw, http, headers) }.exceptionOrNull() ?: return 101
        var cause: Throwable? = failure
        while (cause != null && cause !is WebSocketHandshakeException) cause = cause.cause
        return (cause as? WebSocketHandshakeException)?.response?.statusCode() ?: throw AssertionError("handshake failed", failure)
    }

    private fun num(v: Any?): Double = (v as Number).toDouble()

    @Test fun upgradeIsAuthenticatedNeedsTagsReadAndTheGatewayOrigin() {
        gateway("auth").use { gw ->
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            gw.createUser("nobody", emptySet())
            gw.start()
            assertEquals(401, handshakeStatus(gw, null))
            assertEquals(401, handshakeStatus(gw, null, mapOf("Cookie" to "${HttpAuth.COOKIE}=forged")))
            assertEquals(403, handshakeStatus(gw, login(gw, "nobody")))
            val ada = login(gw, "ada")
            assertEquals(403, handshakeStatus(gw, ada, mapOf("Origin" to "http://evil.example")))
            assertEquals(101, handshakeStatus(gw, ada, mapOf("Origin" to "http://127.0.0.1:${gw.port()}")))
            // The upgrade rule is in the OpenAPI document with the other routes.
            @Suppress("UNCHECKED_CAST") val paths = ada.get("/api/docs").json["paths"] as Map<String, Any?>
            assertTrue(SessionHost.PATH in paths)
        }
    }

    @Test fun subscribeSendsTheCurrentValueThenEachChange() {
        gateway("values").use { gw ->
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            gw.tag("level", "path" to "Line/Level", "type" to "memory", "dataType" to "Double", "value" to 12.5)
            gw.start()
            val ws = connect(gw, login(gw, "ada"))
            ws.send("type" to "subscribe", "paths" to listOf("[default]Line/Level"))
            assertEquals("hello required", ws.next { it["type"] == "error" }["message"])
            val welcome = ws.hello()
            assertTrue((welcome["sessionId"] as String).isNotEmpty())
            ws.send("type" to "subscribe", "paths" to listOf("[default]Line/Level", "[default]Line/Missing", "[nope]x"))
            val first = ws.update("[default]Line/Level")
            assertEquals(12.5, num(first["value"]))
            assertEquals("good", first["quality"])
            assertEquals("good", first["code"])
            assertEquals("Good", first["label"])
            assertTrue(first["timestamp"] is Number)
            assertEquals("bad", ws.update("[default]Line/Missing")["quality"])
            assertEquals("bad", ws.update("[nope]x")["quality"])

            assertTrue(gw.tags.write("[default]Line/Level", 40.0).ok)
            assertEquals(40.0, num(ws.update("[default]Line/Level") { num(it["value"]) == 40.0 }["value"]))
            // Many changes in a burst: the socket ends on the last value, without one frame per change being required.
            for (i in 1..500) gw.tags.write("[default]Line/Level", i.toDouble())
            ws.update("[default]Line/Level", 10_000) { num(it["value"]) == 500.0 }

            ws.send("type" to "heartbeat", "time" to 1)
            assertTrue(ws.next { it["type"] == "heartbeat" }["time"] is Number)
            ws.send("type" to "nonsense")
            assertEquals("unknown frame", ws.next { it["type"] == "error" }["message"])

            ws.send("type" to "unsubscribe", "paths" to listOf("[default]Line/Level"))
            ws.send("type" to "heartbeat", "time" to 2)
            ws.next { it["type"] == "heartbeat" }
            gw.tags.write("[default]Line/Level", -1.0)
            ws.send("type" to "heartbeat", "time" to 3)
            ws.next { it["type"] == "heartbeat" }
            Thread.sleep(200)
            assertTrue(ws.entries.none { it["path"] == "[default]Line/Level" && num(it["value"]) == -1.0 }, ws.entries.toString())
            ws.close()
        }
    }

    /**
     * PRF-011: a burst of changes (here one tight loop of writes, as one write request or driver poll makes) reaches
     * the socket as one update frame, so each browser renders it once.
     */
    @Test fun aBurstOfChangesLeavesAsOneUpdateFrame() {
        gateway("burst").use { gw ->
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            val paths = (0 until 30).map { "[default]Burst/T$it" }
            for (i in paths.indices) gw.tag("burst-$i", "path" to "Burst/T$i", "type" to "memory", "dataType" to "Double", "value" to 0.0)
            gw.start()
            // Without the audit profile the writes below are microseconds apart, a single burst.
            gw.security.auditProfile.update(gw.security.auditProfile.get().copy(enabled = false), "test")
            val ws = connect(gw, login(gw, "ada")).also { it.hello() }
            ws.send("type" to "subscribe", "paths" to paths)
            for (path in paths) ws.update(path)
            Thread.sleep(200)
            ws.frames.clear()
            val bursts = 5
            for (round in 1..bursts) {
                for (path in paths) assertTrue(gw.tags.write(path, round.toDouble()).ok)
                for (path in paths) ws.update(path) { num(it["value"]) == round.toDouble() }
            }
            val frames = ws.frames.filter { it["type"] == "update" }
            assertEquals(bursts * paths.size, frames.sumOf { (it["updates"] as List<*>).size }, frames.toString())
            // One frame per burst; one extra allowed in case the test thread is descheduled inside the batch window.
            assertTrue(frames.size <= bursts + 1, "${frames.size} update frames for $bursts bursts: ${frames.map { (it["updates"] as List<*>).size }}")
            ws.close()
        }
    }

    /**
     * Review-4 F1: one change fans out to every session of its tag, but it is encoded once, not once per session. Each
     * frame carries the gateway time it was built (`sent`), at or after the change it carries.
     */
    @Test fun aChangeSeenByManySessionsIsEncodedOnce() {
        gateway("fanout").use { gw ->
            val users = (0 until 8).map { "op$it" }
            for (user in users) gw.createUser(user, setOf(DefaultRoles.OPERATOR))
            val paths = (0 until 30).map { "[default]Fan/T$it" }
            for (i in paths.indices) gw.tag("fan-$i", "path" to "Fan/T$i", "type" to "memory", "dataType" to "Double", "value" to 0.0)
            gw.start()
            gw.security.auditProfile.update(gw.security.auditProfile.get().copy(enabled = false), "test")
            val sockets = users.map { connect(gw, login(gw, it)).also { ws -> ws.hello() } }
            for (ws in sockets) {
                ws.send("type" to "subscribe", "paths" to paths)
                for (path in paths) ws.update(path)
            }
            Thread.sleep(200)
            for (ws in sockets) ws.frames.clear()
            val before = gw.sessions.encodeCount()
            val bursts = 5
            for (round in 1..bursts) {
                for (path in paths) assertTrue(gw.tags.write(path, round.toDouble()).ok)
                for (ws in sockets) for (path in paths) ws.update(path) { num(it["value"]) == round.toDouble() }
            }
            assertEquals((bursts * paths.size).toLong(), gw.sessions.encodeCount() - before, "one encoding per change, not per session")
            for (ws in sockets) {
                for (frame in ws.frames.filter { it["type"] == "update" }) {
                    val sent = num(frame["sent"])
                    val newest = (frame["updates"] as List<*>).maxOf { num((it as Map<*, *>)["timestamp"]) }
                    assertTrue(sent >= newest, "sent $sent before change $newest")
                }
                ws.close()
            }
        }
    }

    @Test fun readsSubscriptionsAndWritesRunAsTheUserWithPerTagPermissions() {
        gateway("perms").use { gw ->
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            gw.createUser("vic", setOf(DefaultRoles.VIEWER))
            gw.security.zones.create(SecurityZone("floor", ipAddresses = listOf("127.0.0.1"), priority = 10), actor = "test")
            gw.tag("recipe", "path" to "Line/Recipe", "type" to "memory", "dataType" to "String", "value" to "mix",
                "readPermissions" to listOf("operator"))
            gw.tag("setpoint", "path" to "Line/Setpoint", "type" to "memory", "dataType" to "Double", "value" to 10.0,
                "writePermissions" to mapOf("roles" to listOf("operator"), "zones" to listOf("floor")))
            gw.tag("remote", "path" to "Line/RemoteOnly", "type" to "memory", "dataType" to "Double", "value" to 1.0,
                "writePermissions" to mapOf("roles" to listOf("operator"), "zones" to listOf("remote")))
            gw.start()
            val vic = connect(gw, login(gw, "vic")).also { it.hello() }
            val ada = connect(gw, login(gw, "ada")).also { it.hello() }
            val paths = listOf("[default]Line/Recipe", "[default]Line/Setpoint")
            vic.send("type" to "subscribe", "paths" to paths)
            ada.send("type" to "subscribe", "paths" to paths)

            val hidden = vic.update("[default]Line/Recipe")
            assertNull(hidden["value"])
            assertEquals("Bad_AccessDenied", hidden["label"])
            assertEquals("bad", hidden["quality"])
            assertEquals("mix", ada.update("[default]Line/Recipe")["value"])
            gw.tags.write("[default]Line/Recipe", "batch-2")
            assertEquals("batch-2", ada.update("[default]Line/Recipe") { it["value"] == "batch-2" }["value"])

            // The viewer role has no tags.write.
            vic.send("type" to "write", "requestId" to "v1", "path" to "[default]Line/Setpoint", "value" to 99.0)
            val refused = vic.next { it["type"] == "writeResult" && it["requestId"] == "v1" }
            assertEquals(false, refused["ok"])
            assertTrue((refused["error"] as String).contains("tags.write"), refused.toString())

            // Operator from the floor zone may write the setpoint; the remote-only tag refuses it.
            ada.send("type" to "write", "requestId" to "a1", "path" to "[default]Line/Setpoint", "value" to 55.5)
            assertEquals(true, ada.next { it["type"] == "writeResult" && it["requestId"] == "a1" }["ok"])
            assertEquals(55.5, num(ada.update("[default]Line/Setpoint") { num(it["value"]) == 55.5 }["value"]))
            assertEquals(55.5, num(vic.update("[default]Line/Setpoint") { num(it["value"]) == 55.5 }["value"]))
            ada.send("type" to "write", "requestId" to "a2", "path" to "[default]Line/RemoteOnly", "value" to 2.0)
            val denied = ada.next { it["type"] == "writeResult" && it["requestId"] == "a2" }
            assertEquals(false, denied["ok"])
            assertTrue((denied["error"] as String).contains("Bad_AccessDenied"), denied.toString())
            assertEquals(1.0, num(gw.tags.read("[default]Line/RemoteOnly").value))
            // Tag properties are configuration: a user cannot write them over the socket either.
            ada.send("type" to "write", "requestId" to "a4", "path" to "[default]Line/Setpoint.documentation", "value" to "x")
            val property = ada.next { it["type"] == "writeResult" && it["requestId"] == "a4" }
            assertEquals(false, property["ok"])
            assertTrue((property["error"] as String).contains("Bad_NotWritable"), property.toString())
            ada.send("type" to "write", "requestId" to "a3", "value" to 2.0)
            assertEquals(false, ada.next { it["type"] == "writeResult" && it["requestId"] == "a3" }["ok"])

            // The engine sees an HMI user, never the system caller.
            val caller = TagCaller.User("ada", setOf("operator"), "floor", "127.0.0.1")
            assertEquals(55.5, num(gw.tags.read("[default]Line/Setpoint", caller).value))
            vic.close()
            ada.close()
        }
    }

    @Test fun aResumeTokenRestoresTheSessionForTheSameUserOnly() {
        val clock = MutableClock(java.time.Instant.now())
        gateway("resume", clock).use { gw ->
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            gw.createUser("bob", setOf(DefaultRoles.OPERATOR))
            gw.tag("level", "path" to "Line/Level", "type" to "memory", "dataType" to "Double", "value" to 1.0)
            gw.start()
            val adaHttp = login(gw, "ada")
            val first = connect(gw, adaHttp)
            val welcome = first.hello()
            first.send("type" to "subscribe", "paths" to listOf("[default]Line/Level"))
            first.update("[default]Line/Level")
            first.close()
            first.closed.get(5, TimeUnit.SECONDS)
            waitUntil { gw.sessions.socketCount() == 0 }
            assertEquals(1, gw.sessions.sessionCount())
            gw.tags.write("[default]Line/Level", 7.0)

            val token = welcome["resumeToken"] as String
            // Someone else's socket cannot take the session over.
            val bob = connect(gw, login(gw, "bob"))
            assertNotEquals(welcome["sessionId"], bob.hello(token)["sessionId"])
            bob.close()

            val second = connect(gw, adaHttp)
            val again = second.hello(token)
            assertEquals(welcome["sessionId"], again["sessionId"])
            // No new subscribe: the session's subscription answers with the value written while it was away.
            assertEquals(7.0, num(second.update("[default]Line/Level")["value"]))
            gw.tags.write("[default]Line/Level", 8.0)
            assertEquals(8.0, num(second.update("[default]Line/Level") { num(it["value"]) == 8.0 }["value"]))
            second.close()
            waitUntil { gw.sessions.socketCount() == 0 }

            // After the resume window the session and its engine subscriptions are gone.
            clock.advance(Duration.ofSeconds(61))
            gw.sessions.sweepNow()
            assertEquals(0, gw.sessions.sessionCount())
            assertEquals(0, gw.sessions.subscriptionCount())
            val late = connect(gw, adaHttp)
            assertNotEquals(welcome["sessionId"], late.hello(token)["sessionId"])
            late.close()
        }
    }

    @Test fun logoutDisableAndRoleChangesCloseTheSocket() {
        gateway("logout").use { gw ->
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            gw.createUser("cy", setOf(DefaultRoles.OPERATOR))
            gw.createUser("di", setOf(DefaultRoles.OPERATOR))
            gw.start()
            val adaHttp = login(gw, "ada")
            val ada = connect(gw, adaHttp).also { it.hello() }
            val cy = connect(gw, login(gw, "cy")).also { it.hello() }
            val di = connect(gw, login(gw, "di")).also { it.hello() }
            gw.sessions.sweepNow()
            assertFalse(ada.closed.isDone)
            assertFalse(cy.closed.isDone)
            assertFalse(di.closed.isDone)

            assertEquals(204, adaHttp.post("/api/v1/auth/logout", "{}").status)
            gw.security.users.update(UpdateUser("cy", enabled = false), actor = "test")
            gw.security.users.update(UpdateUser("di", roles = setOf(DefaultRoles.VIEWER)), actor = "test")
            gw.sessions.sweepNow()
            assertEquals(1008, ada.closed.get(5, TimeUnit.SECONDS))
            assertEquals(1008, cy.closed.get(5, TimeUnit.SECONDS))
            assertEquals(1008, di.closed.get(5, TimeUnit.SECONDS))
            waitUntil { gw.sessions.sessionCount() == 0 }
        }
    }

    @Test fun closingTheGatewayClosesSockets() {
        val gw = gateway("close")
        gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
        gw.start()
        val ws = connect(gw, login(gw, "ada")).also { it.hello() }
        gw.close()
        val code = ws.closed.get(10, TimeUnit.SECONDS)
        assertTrue(code == 1001 || code == 1006 || code == -1, "close code $code")
    }

    @Test fun theHmiBundleIsServedAtHmiAndStudio() {
        val dist = tmp.resolve("dist").createDirectories()
        dist.resolve("assets").createDirectories().resolve("index-abc.js").writeText("console.log('oip-hmi')")
        dist.resolve("index.html").writeText("<!doctype html><div id=\"root\" data-oip-web=\"1\"></div>")
        gateway("web", webDist = dist).use { gw ->
            gw.start()
            val http = Http(gw.port())
            for (path in listOf("/hmi", "/hmi/", "/hmi/pumps", "/hmi/area/north?theme=nightwatch", "/studio", "/studio/")) {
                val page = http.get(path)
                assertEquals(200, page.status, "$path ${page.body}")
                assertTrue(page.body.contains("data-oip-web"), path)
                assertTrue(page.header("Content-Type")!!.contains("text/html"))
            }
            val script = http.get("/assets/index-abc.js")
            assertEquals(200, script.status)
            assertTrue(script.header("Content-Type")!!.contains("javascript"))
            assertEquals(404, http.get("/assets/missing.js").status)
            assertEquals(404, http.get("/hmi/missing.png").status)
            assertEquals(404, http.get("/assets/..%2Fgateway.yaml").status)
            assertEquals(401, http.get("/api/v1/config").status)
        }
        gateway("noweb").use { gw ->
            gw.start()
            val page = Http(gw.port()).get("/hmi")
            assertEquals(404, page.status)
            assertTrue(page.body.contains("not installed"), page.body)
        }
    }

    @Test fun originCheck() {
        assertTrue(SessionHost.sameOrigin(null, "gw:8088"))
        assertTrue(SessionHost.sameOrigin("http://gw:8088", "gw:8088"))
        assertTrue(SessionHost.sameOrigin("https://GW:8043", "gw:8043"))
        assertFalse(SessionHost.sameOrigin("http://gw:8088", "gw:9999"))
        assertFalse(SessionHost.sameOrigin("null", "gw:8088"))
        assertFalse(SessionHost.sameOrigin("http://gw:8088", null))
        // Default ports may be spelled out on either side.
        assertTrue(SessionHost.sameOrigin("http://gw", "gw:80"))
        assertTrue(SessionHost.sameOrigin("http://gw:80", "gw"))
        assertTrue(SessionHost.sameOrigin("https://gw", "GW:443"))
        assertTrue(SessionHost.sameOrigin("https://gw:443", "gw"))
        assertTrue(SessionHost.sameOrigin("http://[::1]:8088", "[::1]:8088"))
        assertFalse(SessionHost.sameOrigin("https://gw", "gw:80"))
        assertFalse(SessionHost.sameOrigin("http://gw:8088", "gw"))
        assertFalse(SessionHost.sameOrigin("http://gw", "gw.evil"))
        assertFalse(SessionHost.sameOrigin("ftp://gw", "gw"))
    }

    @Test fun anApiKeyThatIsRevokedDisabledDeletedRotatedOrRescopedLosesItsSocket() {
        gateway("keys").use { gw ->
            gw.tag("level", "path" to "Line/Level", "type" to "memory", "dataType" to "Double", "value" to 1.0)
            gw.start()
            val scopes = setOf("tags.read", "tags.write")
            val names = listOf("revoked", "disabled", "deleted", "rotated", "rescoped", "kept")
            val sockets = names.associateWith { name ->
                val key = gw.security.apiKeys.create(name, scopes, secureConnectionRequired = false, actor = "test")
                connect(gw, null, mapOf("Authorization" to "Bearer ${key.token}")).also { it.hello() }
            }
            val revoked = sockets.getValue("revoked")
            revoked.send("type" to "write", "requestId" to "w1", "path" to "[default]Line/Level", "value" to 2.0)
            assertEquals(true, revoked.next { it["type"] == "writeResult" && it["requestId"] == "w1" }["ok"])

            gw.security.apiKeys.revoke("revoked", actor = "test")
            // Writes are authorized against the key as it is now, even before the next re-check closes the socket.
            revoked.send("type" to "write", "requestId" to "w2", "path" to "[default]Line/Level", "value" to 3.0)
            val refused = revoked.next { it["type"] == "writeResult" && it["requestId"] == "w2" }
            assertEquals(false, refused["ok"])
            assertTrue((refused["error"] as String).contains("tags.write"), refused.toString())
            assertEquals(2.0, num(gw.tags.read("[default]Line/Level").value))

            gw.security.apiKeys.update("disabled", enabled = false, actor = "test")
            gw.security.apiKeys.delete("deleted", actor = "test")
            gw.security.apiKeys.rotate("rotated", actor = "test")
            gw.security.apiKeys.update("rescoped", scopes = setOf("tags.read"), actor = "test")
            // A scope reduction also stops writes at once.
            val rescoped = sockets.getValue("rescoped")
            rescoped.send("type" to "write", "requestId" to "r1", "path" to "[default]Line/Level", "value" to 4.0)
            assertEquals(false, rescoped.next { it["type"] == "writeResult" && it["requestId"] == "r1" }["ok"])

            gw.sessions.sweepNow()
            for (name in names - "kept") assertEquals(1008, sockets.getValue(name).closed.get(5, TimeUnit.SECONDS), name)
            val kept = sockets.getValue("kept")
            assertFalse(kept.closed.isDone)
            kept.send("type" to "write", "requestId" to "k1", "path" to "[default]Line/Level", "value" to 5.0)
            assertEquals(true, kept.next { it["type"] == "writeResult" && it["requestId"] == "k1" }["ok"])
            kept.close()
        }
    }

    @Test fun aGoodValueWithAnOlderSourceTimeAfterABadQualityIsDelivered() {
        gateway("sourcetime").use { gw ->
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            gw.start()
            gw.tags.configure(TagConfig(path = "Dev/Raw", type = TagType.OPC, dataType = DataType.DOUBLE, opcServer = "sim", opcItemPath = "ns=1;s=a"))
            gw.tags.updateRaw("Dev/Raw", 1.0)
            val ws = connect(gw, login(gw, "ada")).also { it.hello() }
            ws.send("type" to "subscribe", "paths" to listOf("[default]Dev/Raw"))
            assertEquals(1.0, num(ws.update("[default]Dev/Raw") { it["quality"] == "good" }["value"]))
            // The device drops out (stamped now), then comes back with a sample whose source time is older.
            gw.tags.updateRaw("Dev/Raw", 1.0, Quality(QualityCode.BAD_NOT_CONNECTED))
            ws.update("[default]Dev/Raw") { it["quality"] == "bad" }
            gw.tags.updateRaw("Dev/Raw", 3.0, Quality.GOOD, java.time.Instant.now().minusSeconds(3_600))
            val back = ws.update("[default]Dev/Raw") { it["quality"] == "good" }
            assertEquals(3.0, num(back["value"]))
            ws.close()
        }
    }

    @Test fun writesOfOneSessionRunInOrder() {
        gateway("ordered").use { gw ->
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            gw.tag("level", "path" to "Line/Level", "type" to "memory", "dataType" to "Double", "value" to 0.0)
            gw.start()
            val ws = connect(gw, login(gw, "ada")).also { it.hello() }
            val count = SessionHost.MAX_QUEUED_WRITES
            for (i in 1..count) ws.send("type" to "write", "requestId" to "w$i", "path" to "[default]Line/Level", "value" to i.toDouble())
            val order = ArrayList<String>()
            while (order.size < count) {
                val result = ws.next(10_000) { it["type"] == "writeResult" }
                assertEquals(true, result["ok"], result.toString())
                order += result["requestId"] as String
            }
            assertEquals((1..count).map { "w$it" }, order)
            assertEquals(count.toDouble(), num(gw.tags.read("[default]Line/Level").value))
            ws.close()
        }
    }

    @Test fun onePrincipalHoldsAtMostEightSocketsAndEightDetachedSessions() {
        val clock = MutableClock(java.time.Instant.now())
        gateway("caps", clock).use { gw ->
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            gw.createUser("bob", setOf(DefaultRoles.OPERATOR))
            gw.start()
            val ada = login(gw, "ada")
            val max = SessionHost.MAX_SOCKETS_PER_PRINCIPAL
            val tokens = ArrayList<String>()
            val open = (1..max).map { connect(gw, ada).also { ws -> tokens += ws.hello()["resumeToken"] as String } }
            val refused = connect(gw, ada)
            assertEquals(1008, refused.closed.get(5, TimeUnit.SECONDS))
            // Another user is not affected by ada's sockets.
            val bob = connect(gw, login(gw, "bob")).also { it.hello() }
            assertFalse(bob.closed.isDone)
            assertTrue(open.none { it.closed.isDone })

            // Ten sessions detach one after another: only the most recent eight stay resumable.
            for (ws in open) {
                ws.close()
                ws.closed.get(5, TimeUnit.SECONDS)
                waitUntil { gw.sessions.socketCount() == open.count { !it.closed.isDone } + 1 }
                clock.advance(Duration.ofMillis(10))
            }
            for (i in 1..2) {
                val ws = connect(gw, ada)
                tokens += ws.hello()["resumeToken"] as String
                ws.close()
                ws.closed.get(5, TimeUnit.SECONDS)
                waitUntil { gw.sessions.socketCount() == 1 }
                clock.advance(Duration.ofMillis(10))
            }
            waitUntil { gw.sessions.sessionCount() == SessionHost.MAX_DETACHED_PER_PRINCIPAL + 1 } // + bob's live session
            val oldest = connect(gw, ada)
            assertNotEquals(tokens[0], oldest.hello(tokens[0])["resumeToken"])
            oldest.close()
            oldest.closed.get(5, TimeUnit.SECONDS)
            val newest = connect(gw, ada)
            assertEquals(tokens.last(), newest.hello(tokens.last())["resumeToken"])
            newest.close()
            bob.close()
        }
    }

    private fun waitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met in $timeoutMillis ms")
            Thread.sleep(20)
        }
    }
}
