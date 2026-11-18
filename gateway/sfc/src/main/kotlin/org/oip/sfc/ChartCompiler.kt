package org.oip.sfc

import org.oip.tags.expr.Expr
import org.oip.tags.expr.Expressions

/**
 * A chart is a directed graph, read top to bottom, with links ordered left to right.
 *
 * Flow, selection, and parallel rules:
 * Elements:
 * Parameters and chart scope:
 */

/** Python dict methods plus the engine's own chart names. */
internal val RESERVED: Set<String> = setOf(
    "clear", "copy", "fromkeys", "get", "has_key", "items", "keys", "setdefault", "update", "values",
    "instanceId", "startTime", "runningTime", "parent", "running", "state", "abortCause", "chartPath",
)

class ChartCompileException(val errors: List<String>) : IllegalArgumentException(errors.joinToString("; "))

data class ChartParam(val name: String, val default: Any?, val key: Boolean)
data class ChartVar(val name: String, val initial: Any?)

data class StepAction(val kind: String, val assign: String?, val expression: Expr?, val source: String?)

data class ChartEvents(
    val onStart: List<StepAction>,
    val onStop: List<StepAction>,
    val onCancel: List<StepAction>,
    val onAbort: List<StepAction>,
    val onRedundantFailover: List<StepAction>,
)

data class AssertCheck(val parameter: String, val operator: String, val value: Any?)
data class EnclosingArg(val name: String, val expression: Expr)
data class EnclosingReturn(val from: String, val to: String)

sealed class Node {
    abstract val id: String
    data class Begin(override val id: String) : Node()
    data class End(override val id: String) : Node()
    data class Action(
        override val id: String,
        val onStart: List<StepAction>,
        val onStop: List<StepAction>,
        val onError: List<StepAction>,
        val timerPeriodMillis: Long?,
        val timer: List<StepAction>,
    ) : Node()
    data class Assertion(override val id: String, val checks: List<AssertCheck>, val onFail: String, val flag: String?) : Node()
    data class Transition(override val id: String, val expression: Expr, val timeoutMillis: Long?, val timeoutFlag: String?) : Node()
    data class Parallel(override val id: String, val branches: List<Graph>) : Node()
    data class Jump(override val id: String, val anchor: String) : Node()
    data class Anchor(override val id: String) : Node()
    data class Enclosing(
        override val id: String,
        val chart: String,
        val execution: String,
        val parameters: List<EnclosingArg>,
        val returns: List<EnclosingReturn>,
    ) : Node()
    data class Note(override val id: String) : Node()
}

data class Graph(val nodes: Map<String, Node>, val outgoing: Map<String, List<String>>, val entry: String)

data class CompiledChart(
    val name: String,
    val mode: String,
    val persistState: Boolean,
    val redundancySync: Boolean,
    val failover: String,
    val failoverStep: String?,
    val parameters: List<ChartParam>,
    val variables: List<ChartVar>,
    val events: ChartEvents,
    val graph: Graph,
)

internal fun compileChart(name: String, spec: Map<String, Any?>): CompiledChart {
    val errors = ArrayList<String>()
    val mode = spec["executionMode"] as? String ?: "callable"
    val failover = spec["failover"] as? String ?: "continue"
    val failoverStep = spec["failoverStep"] as? String
    if (failover == "goto" && failoverStep.isNullOrBlank()) errors += "failover goto requires failoverStep"
    val parameters = readParams(spec["parameters"], errors)
    val variables = readVars(spec["variables"], parameters.map { it.name }.toSet(), errors)
    val events = ChartEvents(
        onStart = readActions(spec["onStart"], "onStart", errors),
        onStop = readActions(spec["onStop"], "onStop", errors),
        onCancel = readActions(spec["onCancel"], "onCancel", errors),
        onAbort = readActions(spec["onAbort"], "onAbort", errors),
        onRedundantFailover = readActions(spec["onRedundantFailover"], "onRedundantFailover", errors),
    )
    val graph = readGraph(asList(spec["elements"]), asList(spec["links"]), top = true, errors = errors)
    if (errors.isNotEmpty()) throw ChartCompileException(errors)
    return CompiledChart(
        name = name,
        mode = mode,
        persistState = spec["persistState"] as? Boolean ?: false,
        redundancySync = spec["redundancySync"] as? Boolean ?: false,
        failover = failover,
        failoverStep = failoverStep,
        parameters = parameters,
        variables = variables,
        events = events,
        graph = graph,
    )
}

private fun readParams(raw: Any?, errors: MutableList<String>): List<ChartParam> {
    val out = ArrayList<ChartParam>()
    val seen = HashSet<String>()
    for (item in asList(raw)) {
        val m = asMap(item)
        if (m == null) { errors += "parameter must be an object"; continue }
        val name = m["name"] as? String ?: continue
        if (!seen.add(name)) errors += "duplicate parameter '$name'"
        if (name in RESERVED) errors += "parameter name '$name' is reserved"
        out += ChartParam(name, m["default"], m["key"] as? Boolean ?: false)
    }
    if (out.count { it.key } > 1) errors += "only one parameter may be the key param"
    return out
}

private fun readVars(raw: Any?, params: Set<String>, errors: MutableList<String>): List<ChartVar> {
    val out = ArrayList<ChartVar>()
    val seen = HashSet<String>()
    for (item in asList(raw)) {
        val m = asMap(item)
        if (m == null) { errors += "variable must be an object"; continue }
        val name = m["name"] as? String ?: continue
        if (!seen.add(name)) errors += "duplicate variable '$name'"
        if (name in RESERVED) errors += "variable name '$name' is reserved"
        if (name in params) errors += "variable '$name' collides with a parameter"
        out += ChartVar(name, m["initial"])
    }
    return out
}

private fun readGraph(elements: List<*>, links: List<*>, top: Boolean, errors: MutableList<String>): Graph {
    val nodes = linkedMapOf<String, Node>()
    for (raw in elements) {
        val m = asMap(raw)
        if (m == null) { errors += "element must be an object"; continue }
        val id = m["id"] as? String
        if (id.isNullOrBlank()) { errors += "element is missing id"; continue }
        if (nodes.containsKey(id)) { errors += "duplicate element '$id'"; continue }
        nodes[id] = readNode(id, m, errors)
    }
    val outgoing = linkedMapOf<String, MutableList<String>>()
    val incoming = HashMap<String, Int>()
    for (raw in links) {
        val m = asMap(raw)
        if (m == null) { errors += "link must be an object"; continue }
        val from = m["from"] as? String
        val to = m["to"] as? String
        if (from == null || to == null) { errors += "link needs from and to"; continue }
        if (from !in nodes) errors += "link leaves unknown element '$from'"
        if (to !in nodes) errors += "link enters unknown element '$to'"
        if (from in nodes && to in nodes) {
            outgoing.getOrPut(from) { ArrayList() }.add(to)
            incoming[to] = (incoming[to] ?: 0) + 1
        }
    }
    val begins = nodes.values.filterIsInstance<Node.Begin>()
    val entry: String? = if (top) {
        if (begins.size != 1) {
            errors += "a chart needs exactly one begin step"
            null
        } else begins.single().id
    } else {
        if (begins.isNotEmpty()) errors += "a parallel branch cannot contain a begin step"
        if (nodes.values.any { it is Node.End }) errors += "end steps are not allowed inside a parallel section"
        val entries = nodes.filter { (id, node) -> node !is Node.Note && (incoming[id] ?: 0) == 0 }.keys.toList()
        when {
            entries.size != 1 -> { errors += "a parallel branch needs exactly one entry element"; null }
            else -> entries.single()
        }
    }
    for ((id, node) in nodes) {
        if (node is Node.Note) continue
        val outs = outgoing[id].orEmpty()
        when (node) {
            is Node.End -> if (outs.isNotEmpty()) errors += "end step '$id' cannot have an outgoing link"
            is Node.Begin -> if (outs.size != 1) errors += "begin step must have one outgoing link"
            is Node.Jump -> {
                if (outs.isNotEmpty()) errors += "jump '$id' cannot have an outgoing link"
                val anchor = nodes[node.anchor]
                if (anchor !is Node.Anchor) errors += "jump '$id' has no anchor '${node.anchor}' in this section"
            }
            is Node.Anchor -> if (outs.size != 1) errors += "anchor '$id' must have one outgoing link"
            is Node.Transition -> if (outs.size != 1) errors += "transition '$id' must have one outgoing link"
            else -> {
                if (!top && outs.isEmpty()) continue
                if (top && outs.isEmpty()) errors += "element '$id' is a dead end"
                val direct = outs.filter { nodes[it] !is Node.Transition }
                if (outs.size > 1 && direct.isNotEmpty()) errors += "flow from '$id' leads into multiple actions; use transitions or a parallel"
            }
        }
        if ((incoming[id] ?: 0) == 0 && node !is Node.Begin && top) {
            val targeted = nodes.values.filterIsInstance<Node.Jump>().any { it.anchor == id }
            if (!targeted && node !is Node.Note) errors += "element '$id' is not connected"
        }
    }
    val reachable = HashSet<String>()
    fun walk(id: String) {
        if (!reachable.add(id)) return
        val node = nodes[id]
        if (node is Node.Jump) nodes[node.anchor]?.let { walk(it.id) }
        outgoing[id]?.forEach { walk(it) }
        if (node is Node.Parallel) node.branches.forEach { branch -> branch.nodes.keys.forEach { reachable += it } }
    }
    if (entry != null) walk(entry)
    for ((id, node) in nodes) {
        if (node is Node.Note) continue
        if (top && id !in reachable) errors += "element '$id' is not reachable from begin"
    }
    return Graph(nodes, outgoing.mapValues { it.value.toList() }, entry ?: "")
}

private fun readNode(id: String, m: Map<String, Any?>, errors: MutableList<String>): Node {
    return when (val type = m["type"] as? String) {
        "begin" -> Node.Begin(id)
        "end" -> Node.End(id)
        "note" -> Node.Note(id)
        "anchor" -> Node.Anchor(id)
        "jump" -> {
            val anchor = m["anchor"] as? String
            if (anchor.isNullOrBlank()) errors += "jump '$id' needs an anchor"
            Node.Jump(id, anchor ?: "")
        }
        "action" -> Node.Action(
            id,
            readActions(m["onStart"], "$id.onStart", errors),
            readActions(m["onStop"], "$id.onStop", errors),
            readActions(m["onError"], "$id.onError", errors),
            (m["timerPeriodMillis"] as? Number)?.toLong(),
            readActions(m["timer"], "$id.timer", errors),
        )
        "transition" -> {
            val text = m["expression"] as? String
            if (text.isNullOrBlank()) errors += "transition '$id' needs an expression"
            val flag = m["timeoutFlag"] as? String
            if (flag != null && flag in RESERVED) errors += "timeout flag '$flag' is reserved"
            Node.Transition(id, parseExpr(text ?: "false", "transition $id", errors), (m["timeoutMillis"] as? Number)?.toLong(), flag)
        }
        "assertion" -> {
            val checks = ArrayList<AssertCheck>()
            for (raw in asList(m["assertions"])) {
                val a = asMap(raw)
                if (a == null) { errors += "assertion in '$id' must be an object"; continue }
                val parameter = a["parameter"] as? String
                val op = a["operator"] as? String
                if (parameter.isNullOrBlank() || op.isNullOrBlank()) errors += "assertion in '$id' needs parameter and operator"
                if (op != null && op !in COMPARE) errors += "assertion operator '$op' is not supported"
                checks += AssertCheck(parameter ?: "", op ?: "=", a["value"])
            }
            if (checks.isEmpty()) errors += "assertion '$id' needs at least one check"
            val onFail = (m["onFail"] as? String) ?: "abort"
            if (onFail != "abort" && onFail != "flag") errors += "assertion '$id' onFail must be abort or flag"
            val flag = m["flag"] as? String
            if (onFail == "flag" && flag.isNullOrBlank()) errors += "assertion '$id' needs a flag"
            Node.Assertion(id, checks, onFail, flag)
        }
        "parallel" -> {
            val branches = ArrayList<Graph>()
            val rawBranches = asList(m["branches"])
            if (rawBranches.size < 2) errors += "parallel '$id' needs at least two branches"
            for (raw in rawBranches) {
                val b = asMap(raw)
                if (b == null) { errors += "parallel branch must be an object"; continue }
                branches += readGraph(asList(b["elements"]), asList(b["links"]), top = false, errors = errors)
            }
            Node.Parallel(id, branches)
        }
        "enclosing" -> {
            val chart = m["chart"] as? String
            if (chart.isNullOrBlank()) errors += "enclosing step '$id' needs a chart"
            val execution = (m["execution"] as? String) ?: "block"
            if (execution != "block" && execution != "cancel") errors += "enclosing step '$id' execution must be block or cancel"
            val args = ArrayList<EnclosingArg>()
            for (raw in asList(m["parameters"])) {
                val p = asMap(raw)
                if (p == null) continue
                val n = p["name"] as? String ?: continue
                val text = p["expression"] as? String ?: ""
                args += EnclosingArg(n, parseExpr(text.ifBlank { "null" }, "$id parameter $n", errors))
            }
            val returns = ArrayList<EnclosingReturn>()
            for (raw in asList(m["returns"])) {
                val r = asMap(raw)
                if (r == null) continue
                val from = r["from"] as? String
                val to = r["to"] as? String
                if (from.isNullOrBlank() || to.isNullOrBlank()) errors += "enclosing return needs from and to"
                else {
                    if (to in RESERVED) errors += "return target '$to' is reserved"
                    returns += EnclosingReturn(from, to)
                }
            }
            Node.Enclosing(id, chart ?: "", execution, args, returns)
        }
        null -> { errors += "element '$id' needs a type"; Node.Note(id) }
        else -> { errors += "element '$id' has unknown type '$type'"; Node.Note(id) }
    }
}

private val COMPARE = setOf("=", "!=", "<", ">", "<=", ">=")

private fun readActions(raw: Any?, where: String, errors: MutableList<String>): List<StepAction> {
    val out = ArrayList<StepAction>()
    for (item in asList(raw)) {
        val m = asMap(item)
        if (m == null) { errors += "$where action must be an object"; continue }
        val assign = m["assign"] as? String
        if (assign != null && assign in RESERVED) errors += "$where cannot assign '$assign'"
        when (val kind = m["type"] as? String) {
            "expression" -> {
                val text = m["expression"] as? String
                if (text.isNullOrBlank()) errors += "$where expression action needs an expression"
                out += StepAction("expression", assign, parseExpr(text ?: "null", where, errors), null)
            }
            "script" -> {
                val source = m["source"] as? String
                if (source.isNullOrBlank()) errors += "$where script action needs source"
                out += StepAction("script", assign, null, source ?: "")
            }
            else -> errors += "$where action type must be expression or script"
        }
    }
    return out
}

private fun parseExpr(text: String, where: String, errors: MutableList<String>): Expr = try {
    Expressions.parse(text)
} catch (e: RuntimeException) {
    errors += "$where expression: ${e.message}"
    Expr.Lit(null)
}

internal fun asList(v: Any?): List<*> = v as? List<*> ?: emptyList<Any>()

@Suppress("UNCHECKED_CAST")
internal fun asMap(v: Any?): Map<String, Any?>? {
    val m = v as? Map<*, *> ?: return null
    val out = LinkedHashMap<String, Any?>()
    for ((k, value) in m) out[k.toString()] = value
    return out
}
