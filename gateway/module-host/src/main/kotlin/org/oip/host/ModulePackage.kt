package org.oip.host

import org.oip.sdk.ManifestException
import org.oip.sdk.ManifestParser
import org.oip.sdk.ModuleManifest
import java.io.ByteArrayInputStream
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlin.io.path.*

class UpgradeException(message: String) : RuntimeException(message)

/**
 * Module package: a zip whose root holds `module.yaml` and one or more `*.jar` files; nothing else
 * (no directories, no path traversal). Max 64 MiB uncompressed.
 */
class ModulePackage private constructor(val manifest: ModuleManifest, private val files: Map<String, ByteArray>) {
    /** Write the package contents into [dir] (must not exist). */
    fun extractTo(dir: Path) {
        dir.createDirectories()
        files.forEach { (n, b) -> dir.resolve(n).writeBytes(b) }
    }

    companion object {
        private const val MAX = 64L * 1024 * 1024
        private val NAME = Regex("""^[A-Za-z0-9._-]+\.jar$""")

        fun read(bytes: ByteArray): ModulePackage {
            val files = linkedMapOf<String, ByteArray>(); var total = 0L
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val n = e.name
                        if (e.isDirectory || (n != "module.yaml" && !NAME.matches(n)))
                            throw ManifestException("invalid package entry '$n' (only module.yaml and *.jar at the root are allowed)")
                        if (n in files) throw ManifestException("duplicate package entry '$n'")
                        val b = z.readAllBytes(); total += b.size
                        if (total > MAX) throw ManifestException("package exceeds $MAX bytes")
                        files[n] = b
                    }
                }
            } catch (e: ZipException) { throw ManifestException("not a zip package: ${e.message}", e) }
            if (files.isEmpty()) throw ManifestException("not a zip package (no entries)")
            val yaml = files["module.yaml"] ?: throw ManifestException("package has no module.yaml")
            if (files.keys.none { it.endsWith(".jar") }) throw ManifestException("package has no jar")
            return ModulePackage(ManifestParser.parse(yaml.decodeToString()), files)
        }
    }
}

/**
 * Isolated module class loader. Only the module API (`org.oip.sdk`, `org.oip.config`) and platform/shared
 * libraries (JDK, Kotlin stdlib, Jackson, SLF4J) come from the host; everything else must be in the module's
 * own jars. Host internals (e.g. `org.oip.host`) are not visible to modules.
 */
class ModuleClassLoader(jars: Array<URL>, private val host: ClassLoader, val moduleId: String) :
    URLClassLoader("module:$moduleId", jars, getPlatformClassLoader()) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
        if (SHARED.any { name.startsWith(it) }) return host.loadClass(name)
        return super.loadClass(name, resolve) // platform first, then own jars
    }
    companion object {
        val SHARED = listOf("org.oip.sdk.", "org.oip.config.", "kotlin.", "org.jetbrains.annotations.",
            "com.fasterxml.jackson.", "org.slf4j.")
    }
}
