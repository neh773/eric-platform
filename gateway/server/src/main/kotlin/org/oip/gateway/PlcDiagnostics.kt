package org.oip.gateway

/**
 * Line and column from a RuSTy diagnostic. The compiler prints `error[E048]: …`
 * and a following `┌─ file:line:column` row.
 */
data class PlcDiagnostic(val line: Int, val column: Int, val message: String, val code: String)

object PlcDiagnostics {
    private val error = Regex("""error\[([^\]]+)]: (.*)""")
    private val place = Regex("""([^:\s]+):(\d+):(\d+)\s*$""")

    fun parse(text: String): List<PlcDiagnostic> {
        val found = mutableListOf<PlcDiagnostic>()
        var pending: Pair<String, String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val head = error.find(line)
            if (head != null) {
                pending = head.groupValues[1] to head.groupValues[2].trim()
                continue
            }
            val at = place.find(line)
            if (at != null && pending != null) {
                found += PlcDiagnostic(at.groupValues[2].toInt(), at.groupValues[3].toInt(), pending.second, pending.first)
                pending = null
            }
        }
        if (pending != null && found.none { it.message == pending.second }) {
            found += PlcDiagnostic(1, 1, pending.second, pending.first)
        }
        return found
    }
}
