package org.oip.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A class that is in a JAR but is not a JDBC driver. */
class NotADriver

/** The H2 database JAR from the test class path, as bytes: the stand-in for a vendor driver an operator uploads. */
internal fun h2JarBytes(): ByteArray =
    Files.readAllBytes(Path.of(Class.forName("org.h2.Driver").protectionDomain.codeSource.location.toURI()))

internal fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
        for ((name, bytes) in entries) {
            z.putNextEntry(ZipEntry(name))
            z.write(bytes)
            z.closeEntry()
        }
    }
    return out.toByteArray()
}

/**
 * DAT-016: an uploaded JDBC driver is checked, stored under the data directory, and loaded in its own class loader.
 * The H2 JAR plays the vendor driver.
 */
@Timeout(120)
class JdbcDriverUploadTest {
    @TempDir lateinit var tmp: Path

    @Test fun anUploadedJarLoadsInItsOwnClassLoaderAndIsStoredUnderTheDataDirectory() {
        DbHarness(tmp).use { h ->
            val info = h.db.installDriver("h2", h2JarBytes(), null, "admin")
            assertEquals("org.h2.Driver", info.driverClass)
            assertEquals(64, info.sha256.length)
            assertEquals("admin", info.uploadedBy)
            assertTrue(Files.isRegularFile(tmp.resolve("data/jdbc-drivers/h2.jar")))
            val driver = h.db.drivers.driver("h2")
            assertEquals("org.h2.Driver", driver.javaClass.name)
            // The test class path holds its own H2; the uploaded copy comes from the JAR, in a separate loader.
            assertNotSame(Class.forName("org.h2.Driver"), driver.javaClass)
            assertTrue(driver.acceptsURL("jdbc:h2:mem:x"))
            assertFalse(driver.acceptsURL("jdbc:postgresql://db/x"))
            assertEquals(listOf("h2"), h.db.driverViews().map { it["id"] })
        }
    }

    @Test fun anUploadSurvivesARestart() {
        DbHarness(tmp).use { it.db.installDriver("h2", h2JarBytes(), null, "admin") }
        DbHarness(tmp).use { h ->
            assertEquals("org.h2.Driver", h.db.drivers.get("h2")?.driverClass)
            assertEquals("org.h2.Driver", h.db.drivers.driver("h2").javaClass.name)
        }
    }

    @Test fun badUploadsAreRefusedAndLeaveNothingBehind() {
        DbHarness(tmp).use { h ->
            val notZip = assertThrows<QueryRejected> { h.db.installDriver("junk", "just some text".toByteArray(), null, "a") }
            assertTrue(notZip.message!!.contains("not a JAR"), notZip.message)
            // Begins like a ZIP but is cut off.
            assertThrows<QueryRejected> { h.db.installDriver("cut", h2JarBytes().copyOf(2000), null, "a") }
            assertThrows<QueryRejected> { h.db.installDriver("empty", ByteArray(0), null, "a") }
            val noService = assertThrows<QueryRejected> { h.db.installDriver("plain", zipOf("a.txt" to "x".toByteArray()), null, "a") }
            assertTrue(noService.message!!.contains("java.sql.Driver"), noService.message)
            val absent = zipOf("META-INF/services/java.sql.Driver" to "com.example.Missing\n".toByteArray())
            assertThrows<QueryRejected> { h.db.installDriver("absent", absent, null, "a") }
            assertThrows<QueryRejected> { h.db.installDriver("named-absent", zipOf("a.txt" to "x".toByteArray()), "com.example.Missing", "a") }
            val classBytes = NotADriver::class.java.getResourceAsStream("NotADriver.class")!!.readAllBytes()
            val wrongType = zipOf("org/oip/db/NotADriver.class" to classBytes)
            val notDriver = assertThrows<QueryRejected> { h.db.installDriver("wrong", wrongType, "org.oip.db.NotADriver", "a") }
            assertTrue(notDriver.message!!.contains("not a java.sql.Driver"), notDriver.message)
            assertThrows<QueryRejected> { h.db.installDriver("bad-class", h2JarBytes(), "org.h2.Driver; rm -rf /", "a") }
            assertEquals(emptyList<Any>(), h.db.drivers.list())
            val left = Files.list(tmp.resolve("data/jdbc-drivers")).use { it.toList() }
            assertEquals(emptyList<Path>(), left, "a refused upload leaves no file")
        }
    }

    @Test fun aJarOverTheSizeCapIsRefused() {
        DbHarness(tmp).use { h ->
            val big = ByteArray(JdbcDriverStore.MAX_BYTES + 1)
            big[0] = 'P'.code.toByte(); big[1] = 'K'.code.toByte(); big[2] = 3; big[3] = 4
            val e = assertThrows<QueryRejected> { h.db.installDriver("big", big, null, "a") }
            assertTrue(e.message!!.contains("larger than"), e.message)
            assertNull(h.db.drivers.get("big"))
        }
    }

    @Test fun aDriverIdCannotNamePathsOutsideTheDriverDirectory() {
        DbHarness(tmp).use { h ->
            val jar = h2JarBytes()
            for (id in listOf("../evil", "..", "a/b", "a\\b", "/etc/passwd", "UPPER", "x.jar", "", ".hidden", "a b", "evil\u0000")) {
                assertThrows<QueryRejected>("id '$id'") { h.db.installDriver(id, jar, null, "a") }
                assertThrows<QueryRejected>("remove '$id'") { h.db.removeDriver(id) }
                assertNull(h.db.drivers.get(id))
            }
            assertFalse(Files.exists(tmp.resolve("data/evil.jar")))
            assertFalse(Files.exists(tmp.resolve("evil.jar")))
            assertEquals(emptyList<Any>(), h.db.drivers.list())
        }
    }

    @Test fun aDriverInUseCannotBeRemovedAndAReplacementReopensItsConnections() {
        DbHarness(tmp).use { h ->
            h.db.installDriver("h2", h2JarBytes(), null, "admin")
            h.connection("plant", driver = "custom", url = "jdbc:h2:mem:inuse;MODE=Oracle;DB_CLOSE_DELAY=-1", fields = mapOf("driverId" to "h2", "translator" to "ORACLE", "maxActive" to 1))
            assertEquals("Valid", h.db.connection("plant")["status"])
            val e = assertThrows<QueryRejected> { h.db.removeDriver("h2") }
            assertTrue(e.message!!.contains("plant"), e.message)
            assertEquals(listOf("plant"), h.db.driverViews().single()["usedBy"])
            val before = h.db.drivers.driver("h2")
            h.db.installDriver("h2", h2JarBytes(), null, "admin2")
            assertNotSame(before, h.db.drivers.driver("h2"))
            assertEquals("Valid", h.db.connection("plant")["status"])
            h.db.deleteConnection("plant", null)
            h.db.removeDriver("h2")
            assertEquals(emptyList<Any>(), h.db.driverViews())
            assertFalse(Files.exists(tmp.resolve("data/jdbc-drivers/h2.jar")))
        }
    }

    @Test fun aConnectionToAMissingDriverIsRefusedAndOneFaultedUntilTheDriverArrives() {
        DbHarness(tmp).use { h ->
            val e = assertThrows<QueryRejected> { h.connection("plant", driver = "custom", url = "jdbc:h2:mem:x", fields = mapOf("driverId" to "h2", "translator" to "ORACLE")) }
            assertTrue(e.message!!.contains("not uploaded"), e.message)
            assertThrows<QueryRejected> { h.connection("noid", driver = "custom", url = "jdbc:h2:mem:x", fields = mapOf("translator" to "ORACLE")) }
            assertThrows<QueryRejected> { h.connection("pg", driver = "postgres", url = "jdbc:postgresql://127.0.0.1:1/x", fields = mapOf("driverId" to "h2")) }
        }
    }
}
