package org.oip.db

import java.io.IOException
import java.io.PrintWriter
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Driver
import java.sql.SQLException
import java.time.Clock
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.sql.DataSource

/**
 * JDBC drivers an administrator uploads (DAT-016). Oracle and DB2 drivers are not permissively licensed, so this
 * gateway does not ship them; the operator supplies the JAR.
 *
 * A JAR is stored as `<dir>/<id>.jar` with a `<id>.properties` note beside it. The id is a lowercase resource name,
 * so no upload can name a path outside [dir]. On upload the JAR must be a real ZIP of at most [MAX_BYTES], and must
 * carry a `java.sql.Driver` service entry or the driver class the caller names, whose class file must be inside it and
 * must load as a `java.sql.Driver`. Loading at upload time does not run the driver's static initialiser.
 *
 * Each driver runs in its own [URLClassLoader] whose parent is the platform loader, so an uploaded JAR cannot see
 * or replace the gateway's own classes and two JARs cannot see each other. Uploaded code does run inside the
 * gateway process once a connection opens it; that is why the HTTP route needs the module-administration permission.
 */
class JdbcDriverStore(private val dir: Path, private val clock: Clock = Clock.systemUTC()) : AutoCloseable {
    data class Info(
        val id: String,
        val driverClass: String,
        val driverClasses: List<String>,
        val size: Long,
        val sha256: String,
        val uploadedAtMillis: Long,
        val uploadedBy: String,
    ) {
        fun view(): Map<String, Any?> = linkedMapOf(
            "id" to id, "driverClass" to driverClass, "driverClasses" to driverClasses, "size" to size,
            "sha256" to sha256, "uploadedAt" to java.time.Instant.ofEpochMilli(uploadedAtMillis).toString(), "uploadedBy" to uploadedBy,
        )
    }

    private class Loaded(val sha256: String, val loader: URLClassLoader, val driver: Driver)

    private val loaded = ConcurrentHashMap<String, Loaded>()
    private val lock = Any()

    fun list(): List<Info> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { s ->
            s.map { it.fileName.toString() }.filter { it.endsWith(".properties") }
                .map { it.removeSuffix(".properties") }.filter { ID.matches(it) }.toList()
        }.mapNotNull { get(it) }.sortedBy { it.id }
    }

    fun get(id: String): Info? {
        if (!ID.matches(id)) return null
        val meta = file(id, "properties")
        val jar = file(id, "jar")
        if (!Files.isRegularFile(meta) || !Files.isRegularFile(jar)) return null
        val p = Properties()
        try {
            Files.newInputStream(meta).use { p.load(it) }
        } catch (_: IOException) {
            return null
        }
        val classes = (p.getProperty("driverClasses") ?: "").split(',').filter { it.isNotBlank() }
        return Info(
            id = id,
            driverClass = p.getProperty("driverClass") ?: return null,
            driverClasses = classes,
            size = p.getProperty("size")?.toLongOrNull() ?: Files.size(jar),
            sha256 = p.getProperty("sha256") ?: return null,
            uploadedAtMillis = p.getProperty("uploadedAt")?.toLongOrNull() ?: 0L,
            uploadedBy = p.getProperty("uploadedBy") ?: "",
        )
    }

    /** Check and store [bytes] as driver [id]. An existing driver of that id is replaced. */
    fun install(id: String, bytes: ByteArray, requestedClass: String?, actor: String): Info {
        if (!ID.matches(id)) throw QueryRejected("driver id must be a lowercase resource name")
        if (bytes.isEmpty()) throw QueryRejected("the driver upload is empty")
        if (bytes.size > MAX_BYTES) throw QueryRejected("the driver JAR is larger than ${MAX_BYTES / (1024 * 1024)} MiB")
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte() || bytes[2] != 3.toByte() || bytes[3] != 4.toByte()) {
            throw QueryRejected("the upload is not a JAR (ZIP) file")
        }
        if (requestedClass != null && !CLASS_NAME.matches(requestedClass)) throw QueryRejected("driverClass is not a class name")
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, ".upload-", ".tmp")
        try {
            Files.write(temp, bytes)
            val classes = findDrivers(temp, requestedClass)
            val chosen = requestedClass ?: classes.first()
            checkLoads(temp, chosen)
            val digest = sha256(bytes)
            synchronized(lock) {
                evict(id)
                Files.move(temp, file(id, "jar"), StandardCopyOption.REPLACE_EXISTING)
                val p = Properties()
                p.setProperty("driverClass", chosen)
                p.setProperty("driverClasses", (listOf(chosen) + classes).distinct().joinToString(","))
                p.setProperty("size", bytes.size.toString())
                p.setProperty("sha256", digest)
                p.setProperty("uploadedAt", clock.millis().toString())
                p.setProperty("uploadedBy", actor.take(128).filter { it >= ' ' })
                Files.newOutputStream(file(id, "properties")).use { p.store(it, null) }
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        return get(id) ?: throw QueryRejected("the driver could not be stored")
    }

    fun remove(id: String) {
        if (!ID.matches(id)) throw QueryRejected("driver id must be a lowercase resource name")
        synchronized(lock) {
            if (get(id) == null) throw QueryNotFound("driver '$id' is not uploaded")
            evict(id)
            Files.deleteIfExists(file(id, "properties"))
            Files.deleteIfExists(file(id, "jar"))
        }
    }

    /** The driver instance of [id], loaded once in its own class loader. */
    fun driver(id: String): Driver {
        val info = get(id) ?: throw QueryRejected("driver '$id' is not uploaded")
        loaded[id]?.let { if (it.sha256 == info.sha256) return it.driver }
        synchronized(lock) {
            loaded[id]?.let { if (it.sha256 == info.sha256) return it.driver }
            evict(id)
            val loader = URLClassLoader(arrayOf(file(id, "jar").toUri().toURL()), ClassLoader.getPlatformClassLoader())
            try {
                val cls = Class.forName(info.driverClass, true, loader)
                val driver = cls.getDeclaredConstructor().newInstance() as? Driver ?: throw QueryRejected("driver '$id' is not a java.sql.Driver")
                loaded[id] = Loaded(info.sha256, loader, driver)
                return driver
            } catch (e: QueryRejected) {
                runCatching { loader.close() }
                throw e
            } catch (e: Throwable) {
                runCatching { loader.close() }
                throw QueryRejected("driver '$id' could not be loaded (${e.javaClass.simpleName})")
            }
        }
    }

    fun dataSource(id: String, url: String, user: String?, password: String?, extra: Map<String, String>): DataSource =
        DriverDataSource(driver(id), url, user, password, extra, loaderOf(id))

    private fun loaderOf(id: String): ClassLoader? = loaded[id]?.loader

    private fun evict(id: String) {
        loaded.remove(id)?.let { runCatching { it.loader.close() } }
    }

    override fun close() {
        synchronized(lock) { for (id in loaded.keys.toList()) evict(id) }
    }

    private fun file(id: String, extension: String): Path {
        val path = dir.resolve("$id.$extension").normalize()
        if (path.parent != dir.normalize()) throw QueryRejected("driver id is invalid")
        return path
    }

    private fun findDrivers(jar: Path, requestedClass: String?): List<String> {
        val zip = try {
            ZipFile(jar.toFile())
        } catch (e: ZipException) {
            throw QueryRejected("the upload is not a readable JAR (ZIP) file")
        } catch (e: IOException) {
            throw QueryRejected("the upload is not a readable JAR (ZIP) file")
        }
        zip.use { z ->
            if (z.size() > MAX_ENTRIES) throw QueryRejected("the JAR has too many entries")
            if (requestedClass != null) {
                if (z.getEntry(requestedClass.replace('.', '/') + ".class") == null) {
                    throw QueryRejected("driver class $requestedClass is not in the JAR")
                }
                return listOf(requestedClass)
            }
            val entry = z.getEntry(SERVICE) ?: throw QueryRejected("the JAR has no $SERVICE entry; name the driver class")
            if (entry.size > 65_536) throw QueryRejected("$SERVICE is too large")
            val text = z.getInputStream(entry).use { String(it.readNBytes(65_536), StandardCharsets.UTF_8) }
            val names = text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toList()
            val present = names.filter { CLASS_NAME.matches(it) && z.getEntry(it.replace('.', '/') + ".class") != null }
            if (present.isEmpty()) throw QueryRejected("no java.sql.Driver class named in $SERVICE is in the JAR")
            return present
        }
    }

    private fun checkLoads(jar: Path, className: String) {
        val loader = URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader())
        try {
            val cls = try {
                Class.forName(className, false, loader)
            } catch (e: Throwable) {
                throw QueryRejected("driver class $className cannot be loaded (${e.javaClass.simpleName})")
            }
            if (!Driver::class.java.isAssignableFrom(cls)) throw QueryRejected("$className is not a java.sql.Driver")
        } finally {
            runCatching { loader.close() }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_BYTES = 128 * 1024 * 1024
        private const val MAX_ENTRIES = 200_000
        private const val SERVICE = "META-INF/services/java.sql.Driver"
        val ID = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
        private val CLASS_NAME = Regex("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$")
    }
}

/** Opens connections through one driver instance, with its class loader as the thread's context loader meanwhile. */
internal class DriverDataSource(
    private val driver: Driver,
    private val url: String,
    private val user: String?,
    private val password: String?,
    private val extra: Map<String, String>,
    private val loader: ClassLoader?,
) : DataSource {
    private var loginTimeout = 0

    override fun getConnection(): Connection = open(user, password)
    override fun getConnection(username: String?, password: String?): Connection = open(username, password)

    private fun open(username: String?, secret: String?): Connection {
        val props = Properties()
        for ((k, v) in extra) props.setProperty(k, v)
        if (!username.isNullOrEmpty()) props.setProperty("user", username)
        if (!secret.isNullOrEmpty()) props.setProperty("password", secret)
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        if (loader != null) thread.contextClassLoader = loader
        try {
            return driver.connect(url, props) ?: throw SQLException("the driver does not accept this url")
        } finally {
            thread.contextClassLoader = previous
        }
    }

    override fun getLogWriter(): PrintWriter? = null
    override fun setLogWriter(out: PrintWriter?) {}
    override fun setLoginTimeout(seconds: Int) { loginTimeout = seconds }
    override fun getLoginTimeout(): Int = loginTimeout
    override fun getParentLogger(): Logger = Logger.getLogger("org.oip.db")
    override fun <T : Any?> unwrap(iface: Class<T>): T = if (iface.isInstance(this)) iface.cast(this) else throw SQLException("not a wrapper")
    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
}
