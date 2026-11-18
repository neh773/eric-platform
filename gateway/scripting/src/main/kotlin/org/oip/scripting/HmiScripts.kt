package org.oip.scripting

import com.fasterxml.jackson.databind.ObjectMapper
import org.oip.tags.TagCaller

/**
 * HMI script hooks on the gateway GraalPy runtime, run for one session user.
 *
 * A hook's `source` is either the body of its function or a module that defines it:
 *  - script transform: `transform(value, context)` returns the new value. `context` holds `quality`, `timestamp`,
 *    `nodeId`, `prop`, `view` and `user`.
 *  - event script: `runAction(event, component)`. `event` holds `name` and `payload`; `component` holds `nodeId`,
 *    `view`, `params` and `custom`. The return value is ignored.
 *
 * Dicts are also readable as attributes (`context.quality`). Values cross the boundary as JSON.
 *
 * Both kinds can use `system.*` (tag reads and writes run as the session user, so each tag's permissions apply) and
 * a small action API, `actions`:
 *  - `actions.navigate(page="/path", params={...})` or `actions.navigate(view="Folder/View", params={...})` asks the
 *    session to navigate once the script has finished.
 *  - `actions.writeTag(path, value)` writes one tag as the user and returns the quality text.
 *
 * Scripts run on the caller's thread with the gateway [ScriptLimits], never under the tag-engine lock (P2-14). A
 * [Guard] is asked before each write and polled while the script runs, so a session that ends (logout, revoked key)
 * stops its scripts and their writes; one run writes at most [Guard.maxWrites] values.
 */
/** Tag values one HMI script run may write by default. */
const val HMI_MAX_WRITES_PER_RUN = 64

class HmiScripts(private val scripts: ScriptService) {
    /** What a hook run produced: a value and, for an event script, the navigations it asked for. */
    data class Outcome(
        val ok: Boolean,
        val value: Any?,
        val navigations: List<Map<String, Any?>>,
        val error: String?,
        val failure: ScriptFailure?,
    )

    /** What a run may still do, asked while it runs (not once at the start). */
    data class Guard(
        /** Before each tag write; false refuses that write. */
        val mayWrite: () -> Boolean = { true },
        val maxWrites: Int = HMI_MAX_WRITES_PER_RUN,
        /** Polled by the runtime watchdog every few milliseconds (keep it a flag read); true cancels the run. */
        val cancelled: () -> Boolean = { false },
    )

    fun transform(
        source: String,
        caller: TagCaller.User,
        value: Any?,
        context: Map<String, Any?>,
        name: String = "hmi-transform",
        limits: ScriptLimits = scripts.limits,
        guard: Guard = Guard(),
        /** Project whose library modules the script imports (PRO-001); null sees only the gateway library. */
        project: String? = null,
    ): Outcome = run(source, Call.TRANSFORM, caller, mapOf("value" to value, "context" to context), name, limits, guard, project)

    fun event(
        source: String,
        caller: TagCaller.User,
        event: Map<String, Any?>,
        component: Map<String, Any?>,
        name: String = "hmi-event",
        limits: ScriptLimits = scripts.limits,
        guard: Guard = Guard(),
        /** Project whose library modules the script imports (PRO-001); null sees only the gateway library. */
        project: String? = null,
    ): Outcome = run(source, Call.EVENT, caller, mapOf("event" to event, "component" to component), name, limits, guard, project)

    private fun run(source: String, call: Call, caller: TagCaller.User, args: Map<String, Any?>, name: String, limits: ScriptLimits, guard: Guard, project: String?): Outcome {
        val argsJson = json.writeValueAsString(EventStreamScripts.toJson(args))
        val result = scripts.runForUser(wrap(source, call), argsJson, caller, name, limits, guard.mayWrite, guard.maxWrites, guard.cancelled, project)
        if (!result.ok) return Outcome(false, null, emptyList(), message(result), result.failure)
        val out = result.value as? Map<*, *> ?: return Outcome(true, null, emptyList(), null, null)
        val navigations = (out["actions"] as? List<*>).orEmpty().mapNotNull { action(it) }
        return Outcome(true, out["value"], navigations, null, null)
    }

    private fun message(result: ScriptResult): String = when (result.failure) {
        ScriptFailure.TIMEOUT -> "script timed out"
        ScriptFailure.MEMORY -> "script exceeded its memory limit"
        ScriptFailure.STATEMENTS -> "script exceeded its statement limit"
        ScriptFailure.OUTPUT -> "script output exceeded its limit"
        ScriptFailure.SANDBOX -> "script tried an operation that is not allowed: ${lastLine(result.error)}"
        ScriptFailure.CANCELLED -> "script cancelled"
        else -> lastLine(result.error)
    }

    /** The guest's own message line (`ValueError: bad input`), without the traceback. */
    private fun lastLine(error: String?): String {
        val text = error?.trim().orEmpty()
        if (text.isEmpty()) return "script failed"
        val line = text.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: text
        return if (line.length > 500) line.substring(0, 500) else line
    }

    /** Page and view navigation, popups, and messages leave the gateway. Anything else a script built is dropped. */
    private fun action(raw: Any?): Map<String, Any?>? {
        val entry = raw as? Map<*, *> ?: return null
        val params = (entry["params"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
            ?: (entry["payload"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
            ?: emptyMap()
        return when (entry["type"]) {
            "navigate" -> {
                val page = entry["page"] as? String
                val view = entry["view"] as? String
                when {
                    page != null && view == null -> linkedMapOf("kind" to "navigation", "mode" to "page", "page" to page, "params" to params)
                    view != null && page == null -> linkedMapOf("kind" to "navigation", "mode" to "view", "view" to view, "params" to params)
                    else -> null
                }
            }
            "popup" -> {
                val mode = entry["mode"] as? String ?: return null
                val id = entry["id"] as? String ?: return null
                if (mode != "open" && mode != "close") return null
                val view = entry["view"] as? String
                if (mode == "open" && view.isNullOrBlank()) return null
                linkedMapOf<String, Any?>("kind" to "popup", "mode" to mode, "id" to id, "params" to params).also { out ->
                    if (view != null) out["view"] = view
                    (entry["title"] as? String)?.let { out["title"] = it }
                }
            }
            "message" -> {
                val type = entry["messageType"] as? String ?: return null
                val scope = entry["scope"] as? String ?: return null
                if (scope !in setOf("page", "view", "session")) return null
                linkedMapOf("kind" to "message", "messageType" to type, "scope" to scope, "payload" to params)
            }
            "dock" -> {
                val mode = entry["mode"] as? String ?: return null
                val id = entry["id"] as? String ?: return null
                if (mode != "open" && mode != "close" && mode != "toggle") return null
                if (id.isBlank() || id.length > 200) return null
                linkedMapOf("kind" to "dock", "mode" to mode, "id" to id)
            }
            "session" -> {
                val prop = entry["prop"] as? String ?: return null
                val value = entry["value"] as? String ?: return null
                if (prop != "locale" && prop != "timeZoneId" && prop != "theme") return null
                if (value.isBlank() || value.length > MAX_SESSION_VALUE) return null
                linkedMapOf("kind" to "session", "prop" to prop, "value" to value)
            }
            "method" -> {
                val method = entry["method"] as? String ?: return null
                val nodeId = entry["nodeId"] as? String ?: return null
                if (method != "setProp" && method != "refresh" && method != "call") return null
                // A custom method call (HMV-114) names the method and carries its arguments as an object.
                if (method == "call") {
                    val name = entry["name"] as? String ?: return null
                    if (!Regex("[A-Za-z][A-Za-z0-9_]{0,63}").matches(name)) return null
                    val args = entry["value"]
                    if (args != null && args !is Map<*, *>) return null
                }
                linkedMapOf<String, Any?>("kind" to "method", "method" to method, "nodeId" to nodeId).also { out ->
                    (entry["name"] as? String)?.let { out["name"] = it }
                    if (entry.containsKey("value")) out["value"] = entry["value"]
                }
            }
            else -> null
        }
    }

    internal enum class Call(val function: String, val parameters: String, val invoke: String) {
        TRANSFORM("transform", "value, context", "transform(_oip_args['value'], _oip_args['context'])"),
        EVENT("runAction", "event, component", "runAction(_oip_args['event'], _oip_args['component'])"),
    }

    internal companion object {
        private val json = ObjectMapper()
        private const val MAX_NAVIGATIONS = 8
        private const val MAX_SESSION_VALUE = 100

        /** The action API, the user's function (wrapped when only a body was given), then the call and encode. */
        fun wrap(source: String, call: Call): String {
            val defines = Regex("^def\\s+${call.function}\\s*\\(", RegexOption.MULTILINE).containsMatchIn(source)
            val user = if (defines) source else {
                val body = source.trimEnd().ifBlank { "pass" }.lines().joinToString("\n") { "    $it" }
                "def ${call.function}(${call.parameters}):\n$body"
            }
            val prelude = """
                |import json as _oip_json
                |class _OipDict(dict):
                |    def __getattr__(self, name):
                |        try:
                |            return self[name]
                |        except KeyError:
                |            raise AttributeError(name)
                |def _oip_in(v):
                |    if isinstance(v, dict):
                |        return _OipDict({k: _oip_in(x) for k, x in v.items()})
                |    if isinstance(v, list):
                |        return [_oip_in(x) for x in v]
                |    return v
                |def _oip_out(v):
                |    if isinstance(v, dict):
                |        return {str(k): _oip_out(x) for k, x in v.items()}
                |    if isinstance(v, (list, tuple)):
                |        return [_oip_out(x) for x in v]
                |    if isinstance(v, (bytes, bytearray)):
                |        return list(v)
                |    return v
                |class _OipActions(object):
                |    def __init__(self):
                |        self._queued = []
                |    def navigate(self, page=None, view=None, params=None):
                |        if (page is None) == (view is None):
                |            raise ValueError("navigate needs exactly one of page or view")
                |        if len(self._queued) >= $MAX_NAVIGATIONS:
                |            raise ValueError("at most $MAX_NAVIGATIONS navigations per run")
                |        entry = {"type": "navigate", "params": dict(params or {})}
                |        if page is not None:
                |            entry["page"] = str(page)
                |        else:
                |            entry["view"] = str(view)
                |        self._queued.append(entry)
                |    def openPopup(self, id, view, params=None, title=None):
                |        entry = {"type": "popup", "mode": "open", "id": str(id), "view": str(view), "params": dict(params or {})}
                |        if title is not None:
                |            entry["title"] = str(title)
                |        self._queue(entry)
                |    def closePopup(self, id):
                |        self._queue({"type": "popup", "mode": "close", "id": str(id), "params": {}})
                |    def sendMessage(self, messageType, payload=None, scope="page"):
                |        if scope not in ("page", "view", "session"):
                |            raise ValueError("message scope must be page, view, or session")
                |        self._queue({"type": "message", "messageType": str(messageType), "scope": scope, "payload": dict(payload or {})})
                |    def dock(self, dock_id, mode):
                |        if mode not in ("open", "close", "toggle"):
                |            raise ValueError("dock mode must be open, close, or toggle")
                |        self._queue({"type": "dock", "mode": mode, "id": str(dock_id)})
                |    def setSession(self, name, value):
                |        if name not in ("locale", "timeZoneId", "theme"):
                |            raise ValueError("session property '%s' cannot be written; only locale, timeZoneId and theme" % name)
                |        self._queue({"type": "session", "prop": name, "value": str(value)})
                |    def setProp(self, node, name, value):
                |        self._queue({"type": "method", "method": "setProp", "nodeId": str(node), "name": str(name), "value": value})
                |    def refresh(self, node):
                |        self._queue({"type": "method", "method": "refresh", "nodeId": str(node)})
                |    def callMethod(self, node, name, args=None):
                |        self._queue({"type": "method", "method": "call", "nodeId": str(node), "name": str(name), "value": dict(args or {})})
                |    def _queue(self, entry):
                |        if len(self._queued) >= $MAX_NAVIGATIONS:
                |            raise ValueError("at most $MAX_NAVIGATIONS actions per run")
                |        self._queued.append(entry)
                |    def writeTag(self, path, value):
                |        return system.tag.writeBlocking([str(path)], [value])[0]
                |actions = _OipActions()
                |class _OipScreen(object):
                |    def navigate(self, page=None, view=None, params=None):
                |        actions.navigate(page=page, view=view, params=params)
                |    def openPopup(self, id, view, params=None, title=None):
                |        actions.openPopup(id, view, params=params, title=title)
                |    def closePopup(self, id):
                |        actions.closePopup(id)
                |    def sendMessage(self, messageType, payload=None, scope="page"):
                |        actions.sendMessage(messageType, payload=payload, scope=scope)
                |    def openDock(self, dock_id):
                |        actions.dock(dock_id, "open")
                |    def closeDock(self, dock_id):
                |        actions.dock(dock_id, "close")
                |    def toggleDock(self, dock_id):
                |        actions.dock(dock_id, "toggle")
                |    def setSessionProp(self, name, value):
                |        actions.setSession(name, value)
                |system.screen = _OipScreen()
                |def _oip_component():
                |    raw = _oip_args['component']
                |    tree = raw.get('tree') or []
                |    by_id = {}
                |    class _Fixed(_OipDict):
                |        def __setitem__(self, key, value):
                |            raise AttributeError("session property '%s' is read-only" % key)
                |        def __setattr__(self, key, value):
                |            raise AttributeError("session property '%s' is read-only" % key)
                |    class _Props(_OipDict):
                |        def __setitem__(self, key, value):
                |            actions.setSession(key, value)
                |            dict.__setitem__(self, key, str(value))
                |        def __setattr__(self, key, value):
                |            if key not in ("locale", "timeZoneId", "theme"):
                |                raise AttributeError("session property '%s' is read-only" % key)
                |            self[key] = value
                |    incoming = raw.get('session') or {}
                |    session_props = _Props()
                |    for key, value in incoming.items():
                |        dict.__setitem__(session_props, key, _Fixed(value) if isinstance(value, dict) else value)
                |    class _Session(object):
                |        props = session_props
                |        def getComponent(self, node_id):
                |            return by_id[node_id]
                |        @property
                |        def components(self):
                |            return [by_id[item.get('id')] for item in tree if item.get('id') in by_id]
                |    globals()['session'] = _Session()
                |    if not tree:
                |        return raw
                |    class _Node(_OipDict):
                |        def children(self):
                |            me = self.get('nodeId')
                |            return [by_id[item.get('id')] for item in tree if item.get('parent') == me and item.get('id') in by_id]
                |        def parent(self):
                |            parent = self.get('parent')
                |            return None if not parent else by_id.get(parent)
                |        def getChild(self, name):
                |            for child in self.children():
                |                if child.get('name') == name:
                |                    return child
                |            raise KeyError(name)
                |        def setProp(self, name, value):
                |            actions.setProp(self.get('nodeId'), name, value)
                |        def refresh(self):
                |            actions.refresh(self.get('nodeId'))
                |        def callMethod(self, name, args=None):
                |            actions.callMethod(self.get('nodeId'), name, args)
                |    for item in tree:
                |        node = _Node()
                |        node.update({
                |            'id': item.get('id'), 'nodeId': item.get('id'), 'name': item.get('name'), 'type': item.get('type'),
                |            'parent': item.get('parent'), 'props': _OipDict(item.get('props') or {}),
                |            'view': raw.get('view'), 'params': raw.get('params') or {}, 'custom': raw.get('custom') or {},
                |        })
                |        by_id[item.get('id')] = node
                |    found = by_id.get(raw.get('nodeId'))
                |    return found if found is not None else raw
            """.trimMargin()
            val invoke = if (call == Call.EVENT) "runAction(_oip_args['event'], _oip_component())" else call.invoke
            val epilogue = """
                |_oip_args = _oip_in(_oip_json.loads(_oip_stream_json))
                |_oip_value = $invoke
                |_result = {"value": _oip_out(_oip_value), "actions": _oip_out(actions._queued)}
            """.trimMargin()
            return prelude + "\n" + user + "\n" + epilogue
        }
    }
}
