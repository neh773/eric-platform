package org.oip.testinfra

/** `python3` where that command exists, otherwise `python`. */
object Python {
    fun command(): String {
        for (name in listOf("python3", "python")) {
            val ok = try {
                val process = ProcessBuilder(name, "--version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                process.waitFor() == 0
            } catch (_: Exception) {
                false
            }
            if (ok) return name
        }
        return "python3"
    }
}
