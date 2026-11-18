package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.websocket.WsContext
import org.oip.security.ConnectionContext
import org.oip.security.Permissions
import org.oip.security.Subject
import org.oip.tags.Level
import org.oip.tags.QualifiedValue
import org.oip.tags.QualityCode
import org.oip.tags.TagCaller
import org.oip.tags.TagEngine
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Gateway-hosted HMI sessions (M4.1): the `/api/v1/ws` WebSocket, backed by the gateway [TagEngine].
 *
 * It speaks the JSON frames of the browser runtime (`web/hmi-runtime/src/tags.ts`), SPEC §6:
 * - client `hello {nonce, resumeToken?}` → server `welcome {nonce, sessionId, resumeToken, serverTime}`. A resume
 *   token from an earlier socket of the same principal, presented within [resumeGrace], reattaches that session with
 *   its subscriptions and sends their current values; any other token starts a new session.
 * - `subscribe {paths}` / `unsubscribe {paths}`: each new path gets its current value at once, then one entry per
 *   change. Changes are coalesced per path while a batch is being sent, so a slow socket gets the latest value, not
 *   a backlog. `update {sent, updates: [{path, value, quality, code, label, timestamp}]}`; `sent` is the gateway time
 *   the frame was built.
 * - `write {requestId, path, value}` → `writeResult {requestId, ok, error?}`.
 * - `heartbeat {time}` → `heartbeat {time}` (server time).
 * - `script {requestId, call, project, view?, hook, ...}` → `scriptResult {requestId, ok, value?, actions?, error?, code?}`
 *   (D-5a): runs one of the project's HMI script hooks as the session user through [SessionScripts]. `call` is
 *   `transform` (with `value`, `quality`, `timestamp`, `nodeId`, `prop`) or `event` (with `event`, `payload`, `nodeId`,
 *   `params`, `custom`). `actions` lists the navigations an event script asked for. Every failure (unknown hook, script
 *   error, timeout, limits, busy) is an `ok: false` result with a message; the socket stays open.
 *
 * Security. The upgrade request is authenticated like any `/api/v1` request (session cookie or bearer API key,
 * [GatewayAuth]) and needs `tags.read`; a browser upgrade from another origin is refused. Each write needs
 * `tags.write`, checked against the login or API key as it is at the write, not at the upgrade. On top of that every
 * read, subscription and write runs as the caller ([TagCaller.User]: its roles and security zone), so a tag's own
 * read and write permissions apply (TAG-013): a tag the caller may not read is sent with a null value and
 * `Bad_AccessDenied`, and a refused write is `ok: false` and is audited by the engine. Every [revalidateEvery] each
 * socket is checked again (a login: logout, timeouts, a disabled user, changed roles; an API key: deleted, revoked,
 * rotated or re-scoped); when it no longer holds, the socket is closed with 1008 and the session is dropped.
 *
 * Scripts: a session runs at most [MAX_SCRIPT_RUNS_PER_SESSION] scripts at once and a principal at most
 * [MAX_SCRIPT_RUNS_PER_PRINCIPAL] across its sockets (past either a call is answered `ok: false`, code `busy`). Script
 * tag reads and writes run as the caller, like the frames above; each script write also needs `tags.write`, checked at
 * that write (the login must still hold), and one run writes at most [org.oip.scripting.HMI_MAX_WRITES_PER_RUN]
 * values. When a session ends (dropped after its resume window, or its login no longer holds) its running scripts are
 * cancelled.
 *
 * Limits: writes of a session run one at a time in arrival order, at most [MAX_QUEUED_WRITES] waiting (past that a
 * write is answered `ok: false`, busy). One principal holds at most [MAX_SOCKETS_PER_PRINCIPAL] sockets (more are
 * closed with 1008) and [MAX_DETACHED_PER_PRINCIPAL] sessions waiting for a resume (past that the oldest is dropped).
 *
 * P2-14: the engine listener only stores the new value in this session's pending map and schedules a send; JSON
 * encoding and socket I/O run on a virtual thread, never under the tag-engine lock.
 *
 * Batching (PRF-011): a send waits [batchWindow] after the first pending change before it takes the batch, so a burst
 * of changes (one write request, one driver poll) reaches the browser as one `update` frame and one render, instead of
 * a frame for whichever changes happened to be pending when the sender woke. Changes still pending after a send go
 * out at once.
 *
 * Session model: -
 * Tag bindings: -
 */
class SessionHost(
    private val engine: TagEngine,
    private val clock: Clock,
    private val access: SessionAccess,
    private val resumeGrace: Duration = Duration.ofSeconds(60),
    private val revalidateEvery: Duration = Duration.ofSeconds(15),
    /** HMI script hooks (D-5a); null when this gateway runs none, and every `script` call is refused. */
    private val scripts: SessionScripts? = null,
    /** How long a send waits after the first pending change, to take the rest of its burst in the same frame. */
    private val batchWindow: Duration = DEFAULT_BATCH_WINDOW,
    /** `http.trustedProxies`: only a socket peer in this list may set `X-Forwarded-Host` for the origin check (HMP-088). */
    private val trustedProxies: () -> List<String> = { emptyList() },
) : AutoCloseable {
    /** Runs one HMI script hook for a session user. Blocking; called on a session worker, never under the engine lock. */
    fun interface SessionScripts {
        fun run(caller: TagCaller.User, call: ScriptCall): ScriptReply
    }

    enum class ScriptKind { TRANSFORM, EVENT }

    data class ScriptCall(
        val kind: ScriptKind,
        val project: String,
        val view: String?,
        val hook: String,
        /** Transform input value. */
        val value: Any? = null,
        /** Transform `context`, or the event script's `component`. */
        val context: Map<String, Any?> = emptyMap(),
        /** Event script `event`: `name` and `payload`. */
        val event: Map<String, Any?> = emptyMap(),
        /**
         * Asked before each script write: whether the session is still live and its login or key still holds
         * `tags.write` (evaluated at that write, so a logout or a revoked key stops later writes).
         */
        val mayWrite: () -> Boolean = { false },
        /** True once the session has ended; polled while the script runs, and cancels it. */
        val cancelled: () -> Boolean = { false },
        /** The identity provider the session's caller logged in through, for session security (HMP-092). */
        val provider: String? = null,
        /** The security levels the session's caller holds, with ancestors (SEC-017). */
        val levels: Set<String> = setOf(org.oip.security.SecurityLevels.PUBLIC),
    )

    data class ScriptReply(
        val ok: Boolean,
        val value: Any?,
        val actions: List<Map<String, Any?>>,
        val error: String?,
        /** Machine-readable failure, such as [UNKNOWN_HOOK]: the project has no saved source for the hook. */
        val code: String? = null,
    ) {
        companion object {
            const val UNKNOWN_HOOK = "unknown_hook"
            /** The gateway's script caps are full; the browser asks again later and does not keep it as the answer. */
            const val BUSY = "busy"
            fun failed(message: String, code: String? = null) = ScriptReply(false, null, emptyList(), message, code)
        }
    }

    /** How the host checks callers; implemented by [Gateway] over its security service and HTTP session store. */
    interface SessionAccess {
        /** The caller of an upgrade request that [GatewayAuth] has already authenticated. */
        fun identify(ctx: Context): Identity

        /** Whether [identity], as its login or API key stands now, may write tag values (`tags.write`). */
        fun mayWrite(identity: Identity): Boolean

        /**
         * Whether [identity] still holds: its login session is live and its account and roles are unchanged, or its
         * API key still exists, is enabled and has the same key id and scopes; and it still has `tags.read`.
         */
        fun stillValid(identity: Identity): Boolean
    }

    data class Identity(
        val subject: Subject,
        val connection: ConnectionContext,
        val caller: TagCaller.User,
        /** The login cookie the upgrade presented, for [SessionAccess.stillValid]; null for an API key. */
        val sessionToken: String?,
        /** The API key's id at the upgrade, so a rotated key no longer holds; null for a login. */
        val apiKeyId: String? = null,
        /** The identity provider of a provider login (HMP-092 session security); null otherwise. */
        val provider: String? = null,
        /** The security levels granted at the login (SEC-017), with ancestors. */
        val levels: Set<String> = setOf(org.oip.security.SecurityLevels.PUBLIC),
    )

    private val sessions = ConcurrentHashMap<String, HmiSession>()
    private val sockets = ConcurrentHashMap<String, Socket>()
    private val workers: ExecutorService = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("hmi-session-", 0).factory()
    )
    private val sweeper: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "hmi-session-sweeper").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val random = SecureRandom()
    /** Serializes the per-principal socket and detached-session caps. */
    private val admission = Any()

    init {
        val period = revalidateEvery.toMillis().coerceAtLeast(10)
        sweeper.scheduleWithFixedDelay({ runCatching { sweep() } }, period, period, TimeUnit.MILLISECONDS)
    }

    fun register(app: Javalin) {
        app.get("/api/v1/hmi/sessions") { ctx ->
            ctx.contentType("application/json").result(Json.write(mapOf("sessions" to roster())))
        }
        // GatewayAuth's upgrade guard has already run (it is registered first) and requires tags.read.
        app.wsBeforeUpgrade(PATH) { ctx ->
            if (!sameOrigin(ctx.header("Origin"), ForwardedHeaders.host(ctx.ip(), ctx.header("Host"), ctx.header("X-Forwarded-Host"), trustedProxies()))) throw Problems.forbidden("cross-origin session socket refused")
            ctx.attribute(IDENTITY, access.identify(ctx))
        }
        app.ws(PATH) { ws ->
            ws.onConnect { ctx ->
                ctx.session.policy.maxTextMessageSize = MAX_FRAME_BYTES.toLong()
                ctx.session.idleTimeout = IDLE_TIMEOUT
                val identity = ctx.attribute<Identity>(IDENTITY)
                if (identity == null) {
                    ctx.closeSession(POLICY_VIOLATION, "authentication required")
                    return@onConnect
                }
                val principal = identity.subject.principal
                val admitted = synchronized(admission) {
                    if (sockets.values.count { it.identity.subject.principal == principal } >= MAX_SOCKETS_PER_PRINCIPAL) false
                    else {
                        sockets[ctx.sessionId()] = Socket(ctx, identity)
                        true
                    }
                }
                if (!admitted) ctx.closeSession(POLICY_VIOLATION, "too many session sockets for this user")
            }
            ws.onMessage { ctx -> sockets[ctx.sessionId()]?.let { onFrame(it, ctx.message()) } }
            ws.onClose { ctx -> sockets.remove(ctx.sessionId())?.let { detach(it) } }
            ws.onError { ctx -> sockets.remove(ctx.sessionId())?.let { detach(it) } }
        }
    }

    /** Sessions held by the host, attached or waiting for a resume. */
    fun sessionCount(): Int = sessions.size

    /** Open sockets. */
    fun socketCount(): Int = sockets.size

    /** Engine subscriptions held by all sessions. */
    fun subscriptionCount(): Int = sessions.values.sumOf { it.subs.size }

    /** Live and resumable sessions for the admin list (HMP-133). */
    fun roster(): List<Map<String, Any?>> = sessions.values.map { session ->
        linkedMapOf(
            "id" to session.id,
            "user" to session.principal,
            "connected" to (session.detachedSince() == null),
            "project" to session.projectName,
            "page" to session.pagePath,
            "views" to session.viewPaths,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sweeper.shutdownNow()
        for (socket in sockets.values) runCatching { socket.ctx.closeSession(GOING_AWAY, "gateway stopping") }
        sockets.clear()
        for (session in sessions.values) session.dispose()
        sessions.clear()
        workers.shutdownNow()
    }

    private fun onFrame(socket: Socket, raw: String) {
        val frame = try {
            Json.parse(raw)
        } catch (_: Exception) {
            return socket.error("malformed frame")
        }
        when (frame["type"]) {
            "hello" -> hello(socket, frame)
            "subscribe" -> withSession(socket) { session -> subscribe(session, paths(socket, frame) ?: return@withSession) }
            "unsubscribe" -> withSession(socket) { session -> unsubscribe(session, paths(socket, frame) ?: return@withSession) }
            "write" -> withSession(socket) { session -> write(socket, session, frame) }
            "heartbeat" -> socket.sendNow(linkedMapOf("type" to "heartbeat", "time" to clock.millis()))
            "script" -> withSession(socket) { session -> script(socket, session, frame) }
            "presence" -> withSession(socket) { session -> presence(session, frame) }
            else -> socket.error("unknown frame")
        }
    }

    private inline fun withSession(socket: Socket, block: (HmiSession) -> Unit) {
        val session = socket.session ?: return socket.error("hello required")
        block(session)
    }

    private fun hello(socket: Socket, frame: Map<String, Any?>) {
        val nonce = frame["nonce"] as? String ?: return socket.error("hello needs a nonce")
        if (socket.session != null) return socket.error("hello already received")
        val token = frame["resumeToken"] as? String
        val resumed = token?.let { sessions[it] }?.takeIf { candidate ->
            candidate.principal == socket.identity.subject.principal &&
                candidate.caller == socket.identity.caller &&
                candidate.attach(socket)
        }
        val session = resumed ?: HmiSession(newToken(), newToken(), socket.identity.subject.principal, socket.identity.caller).also {
            it.attach(socket)
            sessions[it.token] = it
        }
        socket.session = session
        socket.sendNow(
            linkedMapOf(
                "type" to "welcome",
                "nonce" to nonce,
                "sessionId" to session.id,
                "resumeToken" to session.token,
                "serverTime" to clock.millis(),
            )
        )
        if (resumed != null) {
            val paths = session.subs.keys.toList()
            if (paths.isNotEmpty()) snapshot(session, paths)
        }
    }

    private fun paths(socket: Socket, frame: Map<String, Any?>): List<String>? {
        val raw = frame["paths"] as? List<*> ?: run {
            socket.error("paths must be an array of strings")
            return null
        }
        if (raw.size > MAX_PATHS_PER_SESSION) {
            socket.error("at most $MAX_PATHS_PER_SESSION paths per frame")
            return null
        }
        val paths = raw.map { it as? String ?: run { socket.error("paths must be an array of strings"); return null } }
        if (paths.any { it.length > TagApi.MAX_PATH_LENGTH }) {
            socket.error("path longer than ${TagApi.MAX_PATH_LENGTH}")
            return null
        }
        return paths
    }

    private fun subscribe(session: HmiSession, paths: List<String>) {
        val added = ArrayList<String>(paths.size)
        for (path in LinkedHashSet(paths)) {
            if (session.subs.containsKey(path)) {
                added += path // already live: a repeated subscribe still answers with the current value
                continue
            }
            if (session.subs.size >= MAX_PATHS_PER_SESSION) {
                session.socket?.error("subscription limit of $MAX_PATHS_PER_SESSION paths reached")
                break
            }
            val handle = try {
                engine.subscribe(path, session.caller) { change -> session.offer(path, change.value) }
            } catch (_: Exception) {
                null // invalid path or unknown provider: the read below reports it, nothing to watch
            }
            if (handle != null) session.subs.putIfAbsent(path, handle)?.let { runCatching { handle.close() } }
            added += path
        }
        if (added.isNotEmpty()) snapshot(session, added)
    }

    /** Current values, read as the caller. A change offered after the read began wins over the read. */
    private fun snapshot(session: HmiSession, paths: List<String>) {
        for (path in paths) {
            val stamp = session.stamp()
            session.offerSnapshot(path, stamp, engine.read(path, session.caller))
        }
        session.schedule()
    }

    private fun unsubscribe(session: HmiSession, paths: List<String>) {
        for (path in paths) {
            session.subs.remove(path)?.let { runCatching { it.close() } }
            session.forget(path)
        }
    }

    private fun write(socket: Socket, session: HmiSession, frame: Map<String, Any?>) {
        val requestId = frame["requestId"] as? String ?: return socket.error("write needs a requestId")
        val path = frame["path"] as? String
        if (path == null || path.length > TagApi.MAX_PATH_LENGTH || !frame.containsKey("value")) {
            return socket.sendNow(writeResult(requestId, false, "write needs a path and a value"))
        }
        if (!session.enqueueWrite(PendingWrite(socket, requestId, path, frame["value"]))) {
            socket.sendNow(writeResult(requestId, false, "busy: more than $MAX_QUEUED_WRITES writes waiting"))
        }
    }

    /** Runs on the session's write drainer, one write at a time; device writes may wait on I/O. */
    private fun runWrite(session: HmiSession, job: PendingWrite) {
        val allowed = runCatching { access.mayWrite(job.socket.identity) }.getOrDefault(false)
        if (!allowed) return job.socket.sendNow(writeResult(job.requestId, false, "${Permissions.TAGS_WRITE} denied"))
        val result = engine.write(job.path, job.value, session.caller)
        val error = if (result.ok) null else result.quality.toString()
        job.socket.sendNow(writeResult(job.requestId, result.ok, error))
    }

    private fun script(socket: Socket, session: HmiSession, frame: Map<String, Any?>) {
        val requestId = frame["requestId"] as? String ?: return socket.error("script needs a requestId")
        val runner = scripts ?: return socket.sendNow(scriptResult(requestId, ScriptReply.failed("scripts are not available on this gateway")))
        val call = try {
            scriptCall(socket, session, frame)
        } catch (e: IllegalArgumentException) {
            return socket.sendNow(scriptResult(requestId, ScriptReply.failed(e.message ?: "invalid script call")))
        }
        if (!session.scriptSlots.tryAcquire()) {
            return socket.sendNow(
                scriptResult(requestId, ScriptReply.failed("busy: more than $MAX_SCRIPT_RUNS_PER_SESSION scripts running", ScriptReply.BUSY)),
            )
        }
        if (!takePrincipalRun(session.principal)) {
            session.scriptSlots.release()
            return socket.sendNow(
                scriptResult(requestId, ScriptReply.failed("busy: more than $MAX_SCRIPT_RUNS_PER_PRINCIPAL scripts running for this user", ScriptReply.BUSY)),
            )
        }
        val started = submit {
            // Slots are free before the answer goes out, so a call sent right after it is not refused as busy.
            val reply = try {
                runner.run(session.caller, call)
            } catch (e: Exception) {
                ScriptReply.failed(e.message ?: "script failed")
            } finally {
                releasePrincipalRun(session.principal)
                session.scriptSlots.release()
            }
            if (!session.gone) socket.sendNow(scriptResult(requestId, reply))
        }
        if (!started) {
            releasePrincipalRun(session.principal)
            session.scriptSlots.release()
        }
    }

    /** Script runs in flight per principal, across its sockets and sessions; at most [MAX_SCRIPT_RUNS_PER_PRINCIPAL]. */
    private val principalRuns = ConcurrentHashMap<String, Int>()

    private fun takePrincipalRun(principal: String): Boolean {
        var taken = false
        principalRuns.compute(principal) { _, running ->
            val now = running ?: 0
            if (now >= MAX_SCRIPT_RUNS_PER_PRINCIPAL) now else now.plus(1).also { taken = true }
        }
        return taken
    }

    private fun releasePrincipalRun(principal: String) {
        principalRuns.computeIfPresent(principal) { _, running -> if (running <= 1) null else running - 1 }
    }

    private fun scriptCall(socket: Socket, session: HmiSession, frame: Map<String, Any?>): ScriptCall {
        val kind = when (frame["call"]) {
            "transform" -> ScriptKind.TRANSFORM
            "event" -> ScriptKind.EVENT
            else -> throw IllegalArgumentException("script call must be 'transform' or 'event'")
        }
        val project = text(frame, "project") ?: throw IllegalArgumentException("script needs a project")
        val hook = text(frame, "hook") ?: throw IllegalArgumentException("script needs a hook")
        val view = text(frame, "view")
        val nodeId = text(frame, "nodeId").orEmpty()
        val user = linkedMapOf<String, Any?>(
            "name" to socket.identity.caller.actor,
            "roles" to socket.identity.caller.roles.sorted(),
            "securityLevels" to socket.identity.levels.sorted(),
        )
        val identity = socket.identity
        // Evaluated at each script write, not once here: a logout or a revoked key stops later writes of a running script.
        val mayWrite = { !session.gone && runCatching { access.mayWrite(identity) }.getOrDefault(false) }
        val cancelled = { session.gone }
        return when (kind) {
            ScriptKind.TRANSFORM -> ScriptCall(
                kind, project, view, hook,
                provider = identity.provider,
                levels = identity.levels,
                value = frame["value"],
                context = linkedMapOf(
                    "quality" to (frame["quality"] as? String ?: "good"),
                    "timestamp" to (frame["timestamp"] as? Number)?.toLong(),
                    "nodeId" to nodeId,
                    "prop" to text(frame, "prop").orEmpty(),
                    "view" to view,
                    "user" to user,
                ),
                mayWrite = mayWrite,
                cancelled = cancelled,
            )
            ScriptKind.EVENT -> ScriptCall(
                kind, project, view, hook,
                provider = identity.provider,
                levels = identity.levels,
                context = linkedMapOf(
                    "nodeId" to nodeId,
                    "view" to view,
                    "params" to (frame["params"] as? Map<*, *> ?: emptyMap<String, Any?>()),
                    "custom" to (frame["custom"] as? Map<*, *> ?: emptyMap<String, Any?>()),
                    "user" to user,
                    "session" to scriptSession(frame["session"], session, user),
                    "tree" to scriptTree(frame["tree"]),
                ),
                event = linkedMapOf("name" to text(frame, "event").orEmpty(), "payload" to frame["payload"]),
                mayWrite = mayWrite,
                cancelled = cancelled,
            )
        }
    }

    private fun presence(session: HmiSession, frame: Map<String, Any?>) {
        session.projectName = text(frame, "project")?.take(200) ?: ""
        session.pagePath = text(frame, "page")?.take(200) ?: ""
        val views = frame["views"]
        session.viewPaths = if (views is List<*>) views.mapNotNull { it as? String }.take(32) else emptyList()
    }

    /**
     * The session properties a script reads as `session.props` (HMP-084). The login comes from the authenticated socket,
     * never from the page; the page reports the rest (theme, locale, time zone, device), so it is capped and typed here.
     */
    private fun scriptSession(raw: Any?, session: HmiSession, user: Map<String, Any?>): Map<String, Any?> {
        val reported = raw as? Map<*, *> ?: emptyMap<String, Any?>()
        val device = reported["device"] as? Map<*, *> ?: emptyMap<String, Any?>()
        fun str(map: Map<*, *>, key: String, max: Int) = (map[key] as? String)?.take(max) ?: ""
        fun size(key: String) = ((device[key] as? Number)?.toInt() ?: 0).coerceIn(0, 100_000)
        return linkedMapOf(
            "id" to str(reported, "id", 200),
            "theme" to str(reported, "theme", 100),
            "locale" to str(reported, "locale", 35),
            "timeZoneId" to str(reported, "timeZoneId", 64),
            "auth" to linkedMapOf("authenticated" to true, "userName" to user["name"], "roles" to user["roles"]),
            "device" to linkedMapOf(
                "type" to str(device, "type", 16),
                "id" to str(device, "id", 100),
                "userAgent" to str(device, "userAgent", 500),
                "screenWidth" to size("screenWidth"),
                "screenHeight" to size("screenHeight"),
                "timeZone" to str(device, "timeZone", 64),
            ),
        )
    }

    /** Saved view nodes the script can walk. Live values are not accepted here. */
    private fun scriptTree(raw: Any?): List<Map<String, Any?>> {
        val list = raw as? List<*> ?: return emptyList()
        val out = ArrayList<Map<String, Any?>>()
        for (item in list) {
            if (out.size == 200) break
            val map = item as? Map<*, *> ?: continue
            val id = map["id"] as? String ?: continue
            if (id.isEmpty() || id.length > 128) continue
            val props = linkedMapOf<String, Any?>()
            val incoming = map["props"] as? Map<*, *> ?: emptyMap<String, Any?>()
            for ((key, value) in incoming) {
                if (props.size == 16) break
                val name = key as? String ?: continue
                if (name.length > 64) continue
                props[name] = when (value) {
                    is String -> value.take(500)
                    is Number, is Boolean, null -> value
                    else -> continue
                }
            }
            out += linkedMapOf(
                "id" to id,
                "name" to (map["name"] as? String)?.take(200),
                "type" to (map["type"] as? String)?.take(64),
                "parent" to (map["parent"] as? String)?.take(128),
                "props" to props,
            )
        }
        return out
    }

    private fun text(frame: Map<String, Any?>, key: String): String? {
        val value = frame[key] ?: return null
        val text = value as? String ?: throw IllegalArgumentException("$key must be a string")
        if (text.length > MAX_SCRIPT_NAME_LENGTH) throw IllegalArgumentException("$key longer than $MAX_SCRIPT_NAME_LENGTH")
        return text.ifEmpty { null }
    }

    private fun scriptResult(requestId: String, reply: ScriptReply): Map<String, Any?> {
        val frame = linkedMapOf<String, Any?>("type" to "scriptResult", "requestId" to requestId, "ok" to reply.ok)
        if (reply.ok) {
            frame["value"] = reply.value
            if (reply.actions.isNotEmpty()) frame["actions"] = reply.actions
        } else {
            frame["error"] = reply.error ?: "script failed"
            if (reply.code != null) frame["code"] = reply.code
        }
        return frame
    }

    private class PendingWrite(val socket: Socket, val requestId: String, val path: String, val value: Any?)

    private fun writeResult(requestId: String, ok: Boolean, error: String?) =
        linkedMapOf<String, Any?>("type" to "writeResult", "requestId" to requestId, "ok" to ok).also { if (error != null) it["error"] = error }

    private fun detach(socket: Socket) {
        val session = socket.session ?: return
        session.detach(socket, clock.millis())
        // Cap the sessions one principal keeps for a resume; the oldest go first.
        val surplus = synchronized(admission) {
            val waiting = sessions.values.filter { it.principal == session.principal && it.detachedSince() != null }
            waiting.sortedBy { it.detachedSince() ?: Long.MAX_VALUE }.take((waiting.size - MAX_DETACHED_PER_PRINCIPAL).coerceAtLeast(0))
        }
        for (old in surplus) if (old.expire(sessions, force = true)) old.release()
    }

    /** One [sweep] now, instead of waiting for the timer (tests). */
    internal fun sweepNow() = sweep()

    /** Drops sessions whose resume window has passed, and closes sockets whose login no longer holds. */
    private fun sweep() {
        val now = clock.millis()
        val grace = resumeGrace.toMillis()
        for (session in sessions.values) {
            if (session.expire(sessions, force = false, now = now, grace = grace)) session.release()
        }
        for ((id, socket) in sockets) {
            val valid = runCatching { access.stillValid(socket.identity) }.getOrDefault(false)
            if (valid) continue
            sockets.remove(id)
            runCatching { socket.ctx.closeSession(POLICY_VIOLATION, "session is no longer authorized") }
            socket.session?.let { session ->
                session.dispose()
                sessions.remove(session.token, session)
            }
        }
    }

    private fun submit(task: () -> Unit): Boolean {
        if (closed.get()) return false
        return try {
            workers.execute { runCatching(task) }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            false // closing
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(18)
        random.nextBytes(bytes)
        return HexFormat.of().formatHex(bytes)
    }

    /** One socket. Sends are serialized per socket (Jetty allows one blocking send at a time). */
    private inner class Socket(val ctx: WsContext, val identity: Identity) {
        @Volatile var session: HmiSession? = null
        private val sendLock = ReentrantLock()

        fun sendNow(frame: Map<String, Any?>) {
            sendText(Json.write(frame))
        }

        fun sendText(text: String): Boolean = sendLock.withLock {
            try {
                if (!ctx.session.isOpen) return false
                ctx.send(text)
                true
            } catch (_: Exception) {
                false
            }
        }

        fun error(message: String) {
            sendNow(linkedMapOf("type" to "error", "message" to message))
        }
    }

    /**
     * Subscriptions and pending changes of one HMI session. It outlives its socket for [resumeGrace]; while detached
     * its engine subscriptions stay open (so a resume is cheap) and a resume sends a fresh snapshot.
     */
    private inner class HmiSession(val id: String, val token: String, val principal: String, val caller: TagCaller.User) {
        val subs = ConcurrentHashMap<String, AutoCloseable>()
        /** Script runs in flight; at most [MAX_SCRIPT_RUNS_PER_SESSION]. */
        val scriptSlots = java.util.concurrent.Semaphore(MAX_SCRIPT_RUNS_PER_SESSION)
        private val pending = ConcurrentHashMap<String, Pending>()
        /**
         * Offer order. Each change takes the next number; a snapshot read takes the current one before it reads, so a
         * change offered after that read began (a higher number) is never replaced or overtaken by the read. Only
         * snapshots are ever skipped; changes are sent whatever their timestamps.
         */
        private val seq = AtomicLong()
        /** Number of the last entry sent per path. */
        private val sent = ConcurrentHashMap<String, Long>()
        private val scheduled = AtomicBoolean(false)
        private val writes = ArrayDeque<PendingWrite>()
        private var writing = false
        private val state = Any()
        @Volatile var projectName: String = ""
        @Volatile var pagePath: String = ""
        @Volatile var viewPaths: List<String> = emptyList()
        @Volatile var socket: Socket? = null
        private var detachedAt = Long.MAX_VALUE
        private var disposed = false
        /** Set when the session ends (disposed or expired): its running scripts are cancelled and their writes refused. */
        @Volatile var gone = false
            private set

        fun attach(next: Socket): Boolean = synchronized(state) {
            if (disposed || socket != null) return false
            socket = next
            detachedAt = Long.MAX_VALUE
            sent.clear()
            true
        }

        fun detach(from: Socket, now: Long) = synchronized(state) {
            if (socket !== from) return
            socket = null
            detachedAt = now
            pending.clear()
        }

        /** When the session lost its socket, or null while attached (or disposed). */
        fun detachedSince(): Long? = synchronized(state) { if (socket == null && !disposed) detachedAt else null }

        /**
         * If the session is detached and its resume window has passed (or [force]), marks it disposed and removes it
         * from [registry] in one step under the session lock, so a concurrent resume either attached first or finds
         * it disposed. True when this call disposed it; the caller then calls [release].
         */
        fun expire(registry: MutableMap<String, HmiSession>, force: Boolean, now: Long = 0, grace: Long = 0): Boolean = synchronized(state) {
            if (disposed || socket != null || (!force && now - detachedAt < grace)) return false
            disposed = true
            gone = true
            registry.remove(token, this)
            true
        }

        fun dispose() {
            synchronized(state) {
                disposed = true
                gone = true
                socket = null
            }
            release()
        }

        /** Closes the engine subscriptions of a disposed session (outside the session lock). */
        fun release() {
            for (handle in subs.values) runCatching { handle.close() }
            subs.clear()
            pending.clear()
            synchronized(writes) { writes.clear() }
        }

        /** Engine listener; runs on the writer's thread. A map merge and, at most, one task submission. */
        fun offer(path: String, value: QualifiedValue) {
            if (socket == null) return
            val entry = Pending(seq.incrementAndGet(), value)
            pending.merge(path, entry) { current, next -> if (next.seq >= current.seq) next else current }
            schedule()
        }

        fun stamp(): Long = seq.get()

        fun offerSnapshot(path: String, stamp: Long, value: QualifiedValue) {
            pending.merge(path, Pending(stamp, value)) { current, snap -> if (current.seq > snap.seq) current else snap }
        }

        /** Queues a write behind this session's earlier ones; false when [MAX_QUEUED_WRITES] are already waiting. */
        fun enqueueWrite(job: PendingWrite): Boolean {
            synchronized(writes) {
                if (writes.size >= MAX_QUEUED_WRITES) return false
                writes.addLast(job)
                if (writing) return true
                writing = true
            }
            submit { drainWrites() }
            return true
        }

        private fun drainWrites() {
            while (true) {
                val job = synchronized(writes) {
                    writes.removeFirstOrNull() ?: run { writing = false; null }
                } ?: return
                runCatching { runWrite(this, job) }
            }
        }

        fun forget(path: String) {
            pending.remove(path)
            sent.remove(path)
        }

        fun schedule() {
            if (pending.isEmpty() || !scheduled.compareAndSet(false, true)) return
            submit { flush() }
        }

        private fun flush() {
            try {
                if (!batchWindow.isZero) Thread.sleep(batchWindow)
                while (true) {
                    val target = socket ?: return
                    if (pending.isEmpty()) return
                    val frame = StringBuilder(64 + 160 * minOf(pending.size, MAX_UPDATES_PER_FRAME))
                        .append(UPDATE_PREFIX).append(clock.millis()).append(UPDATE_ENTRIES)
                    var count = 0
                    val keys = pending.keys.iterator()
                    while (keys.hasNext() && count < MAX_UPDATES_PER_FRAME) {
                        val path = keys.next()
                        val entry = pending.remove(path) ?: continue
                        val last = sent[path]
                        if (last != null && entry.seq < last) continue // a read older than a change already sent
                        sent[path] = entry.seq
                        if (count > 0) frame.append(',')
                        frame.append(encoded(path, entry.value))
                        count += 1
                    }
                    if (count > 0) target.sendText(frame.append("]}").toString())
                }
            } finally {
                scheduled.set(false)
                if (pending.isNotEmpty() && socket != null) schedule()
            }
        }
    }

    private class Pending(val seq: Long, val value: QualifiedValue)

    /** The JSON text of one `update` entry, for the value object it was made from. */
    private class Encoded(val value: QualifiedValue, val text: String)

    /**
     * Review-4 F1: the last encoded entry per path. The engine hands every subscriber of a tag the same
     * [QualifiedValue] object for one change, so 50 sessions of one tag encode it once, not 50 times. An entry is
     * reused only for that very object (identity), so a value masked for one caller (`Bad_AccessDenied`) or a snapshot
     * read is never confused with another.
     */
    private val encodedCache = ConcurrentHashMap<String, Encoded>()

    private fun encoded(path: String, value: QualifiedValue): String {
        val cached = encodedCache[path]
        if (cached != null && cached.value === value) return cached.text
        if (encodedCache.size >= MAX_ENCODED_CACHE) encodedCache.clear()
        // Sessions flush at the same moment after a burst; compute holds the others on this path until one encodes.
        return encodedCache.compute(path) { _, current ->
            if (current != null && current.value === value) current
            else Encoded(value, Json.write(wire(path, value))).also { encodes.incrementAndGet() }
        }!!.text
    }

    private val encodes = AtomicLong()

    /** Update entries encoded so far, across all sessions (tests). */
    internal fun encodeCount(): Long = encodes.get()

    companion object {
        const val PATH = "/api/v1/ws"
        const val MAX_QUEUED_WRITES = 64
        const val MAX_SCRIPT_RUNS_PER_SESSION = 4
        /** Across all of one principal's sockets and sessions. */
        const val MAX_SCRIPT_RUNS_PER_PRINCIPAL = 8
        const val MAX_SCRIPT_NAME_LENGTH = 1024
        const val MAX_SOCKETS_PER_PRINCIPAL = 8
        const val MAX_DETACHED_PER_PRINCIPAL = 8
        const val MAX_PATHS_PER_SESSION = 20_000
        const val MAX_UPDATES_PER_FRAME = 5_000
        const val MAX_FRAME_BYTES = 4 * 1024 * 1024
        /** PRF-011 batching: a few ms more per update buys one frame (and one browser render) per burst. */
        val DEFAULT_BATCH_WINDOW: Duration = Duration.ofMillis(5)
        /**
         * An `update` frame is [UPDATE_PREFIX], the gateway time the frame was built (`sent`, epoch ms), then
         * [UPDATE_ENTRIES], the entries, and `]}`. `sent` splits a session's latency into gateway queueing (change to
         * sent) and client draining (sent to arrival), PRF-011.
         */
        internal const val UPDATE_PREFIX = "{\"type\":\"update\",\"sent\":"
        internal const val UPDATE_ENTRIES = ",\"updates\":["
        private const val MAX_ENCODED_CACHE = 200_000
        private const val POLICY_VIOLATION = 1008
        private const val GOING_AWAY = 1001
        private val IDLE_TIMEOUT: Duration = Duration.ofMinutes(5)
        private const val IDENTITY = "oip.hmiIdentity"

        /** One `update` entry in the browser runtime's shape (`TagUpdate`), plus the gateway quality label. */
        fun wire(path: String, qv: QualifiedValue): Map<String, Any?> {
            val code = qv.quality.code
            val (quality, short) = when {
                code.level == Level.GOOD -> "good" to "good"
                code == QualityCode.BAD_NOT_CONNECTED -> "bad" to "bad_not_connected"
                code == QualityCode.UNCERTAIN_INITIAL_VALUE -> "uncertain" to "uncertain_initial"
                code.level == Level.UNCERTAIN -> "uncertain" to "uncertain"
                else -> "bad" to "bad"
            }
            return linkedMapOf(
                "path" to path,
                "value" to scalar(qv.value),
                "quality" to quality,
                "code" to short,
                "label" to code.label,
                "timestamp" to qv.timestamp.toEpochMilli(),
            )
        }

        /** The browser tag model carries scalars; other values travel as their JSON text. */
        private fun scalar(value: Any?): Any? = when (value) {
            null, is String, is Boolean -> value
            is Number -> if (value is Double && !value.isFinite() || value is Float && !value.isFinite()) value.toString() else value
            is java.time.temporal.TemporalAccessor, is java.util.Date -> value.toString()
            else -> runCatching { Json.write(value) }.getOrElse { value.toString() }
        }

        /**
         * A browser sends `Origin` on every WebSocket upgrade; it must name this host (cross-site socket hijacking).
         * Clients that send no `Origin` (not a browser) pass; they still had to authenticate.
         */
        fun sameOrigin(origin: String?, host: String?): Boolean {
            if (origin == null) return true
            if (host.isNullOrBlank()) return false
            val uri = runCatching { java.net.URI(origin) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase()
            val defaultPort = when (scheme) {
                "http" -> 80
                "https" -> 443
                else -> return false
            }
            val originHost = uri.host ?: return false
            val originPort = if (uri.port == -1) defaultPort else uri.port
            // The Host header carries no scheme; without a port it means the origin scheme's default port.
            val hostUri = runCatching { java.net.URI("$scheme://$host") }.getOrNull() ?: return false
            val hostName = hostUri.host ?: return false
            if (hostUri.rawUserInfo != null || !hostUri.rawPath.isNullOrEmpty()) return false
            val hostPort = if (hostUri.port == -1) defaultPort else hostUri.port
            return originHost.equals(hostName, ignoreCase = true) && originPort == hostPort
        }
    }
}
