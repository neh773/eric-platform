package org.oip.gateway

/**
 * `ERIC_*` is the current environment name. `OIP_*` still works.
 * When both are set, `ERIC_*` wins and the old name is reported.
 */
object LegacyEnv {
    fun normalize(raw: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap(raw)
        for (modern in raw.keys.filter { it.startsWith("ERIC_") }) {
            val legacy = "OIP_" + modern.removePrefix("ERIC_")
            if (raw.containsKey(legacy)) warn("$legacy is ignored because $modern is set")
            out[legacy] = raw.getValue(modern)
        }
        for (legacy in raw.keys.filter { it.startsWith("OIP_") && !raw.containsKey("ERIC_" + it.removePrefix("OIP_")) }) {
            warn("$legacy is deprecated; set ERIC_${legacy.removePrefix("OIP_")}")
        }
        return out
    }

    private fun warn(message: String) {
        System.err.println("warning: $message")
    }
}
