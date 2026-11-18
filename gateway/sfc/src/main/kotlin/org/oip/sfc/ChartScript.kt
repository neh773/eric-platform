package org.oip.sfc

/**
 * Step and chart scripts. M5.1 supplies a GraalPy host; this module does not embed a scripting engine.
 *
 * the reference platform runs Python on action steps and chart events:
 *
 * [execute] returns true when the host ran [source]. A host that does not handle SFC scripts returns false
 * and the engine skips that body. Expression actions do not use this interface.
 */
fun interface ChartScriptHost {
    fun execute(source: String, scope: ChartScriptScope): Boolean

    companion object {
        /** Accepts every script and does nothing. */
        val NOOP: ChartScriptHost = ChartScriptHost { _, _ -> false }
    }
}

/**
 * Names the on-redundant-failover script can set.
 */
class RestartAction {
    var cancel: Boolean = false
    var restart: Boolean = false
    var gotoStep: String? = null
}

/**
 * Mutable chart and step maps. The host may write chart-scoped names other than the built-ins.
 * [abortCause] is set only while the chart is aborting.
 */
class ChartScriptScope(
    val chartPath: String,
    val instanceId: String,
    val variables: MutableMap<String, Any?>,
    val step: MutableMap<String, Any?>,
    val abortCause: String?,
    val activeSteps: List<String>,
    val restart: RestartAction,
    val parent: Map<String, Any?>?,
)
