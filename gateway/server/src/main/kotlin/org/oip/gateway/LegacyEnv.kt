package org.oip.gateway

/**
 * `ERIC_*` is the current environment name. `OIP_*` still works.
 * When both are set, `ERIC_*` wins and the old name is reported.
 */
object LegacyEnv {
    /** The process environment with `ERIC_*` copied onto the `OIP_*` names, without warnings. */
    val current: Map<String, String> by lazy { normalize(System.getenv(), warn = false) }

    fun normalize(raw: Map<String, String>, warn: Boolean = true): Map<String, String> {
        val out = LinkedHashMap(raw)
        for (modern in raw.keys.filter { it.startsWith("ERIC_") }) {
            val legacy = "OIP_" + modern.removePrefix("ERIC_")
            if (warn && raw.containsKey(legacy)) warn("$legacy is ignored because $modern is set")
            out[legacy] = raw.getValue(modern)
        }
        if (!warn) return out
        for (legacy in raw.keys.filter { it.startsWith("OIP_") && !raw.containsKey("ERIC_" + it.removePrefix("OIP_")) }) {
            warn("$legacy is deprecated; set ERIC_${legacy.removePrefix("OIP_")}")
        }
        return out
    }

    private fun warn(message: String) {
        System.err.println("warning: $message")
    }
}

/**
 * Folders packaged next to the gateway jar: `lib/app` in a jpackage image (Linux and Windows). The admin UI,
 * Control Canvas and the manual are found there when no environment variable names them (0.1.1 fix).
 */
object Bundled {
    val appDir: java.nio.file.Path? by lazy {
        runCatching {
            java.nio.file.Path.of(Bundled::class.java.protectionDomain.codeSource.location.toURI()).parent
        }.getOrNull()
    }

    fun dir(name: String, root: java.nio.file.Path? = appDir): java.nio.file.Path? =
        root?.resolve(name)?.takeIf { java.nio.file.Files.isDirectory(it) }?.toAbsolutePath()?.normalize()
}
