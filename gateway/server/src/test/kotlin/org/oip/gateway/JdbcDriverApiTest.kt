package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.AuditActions
import org.oip.security.AuditQuery
import org.oip.security.AuditStatus
import org.oip.security.DefaultRoles
import org.oip.security.Permissions
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * DAT-016, DAT-027: uploading JDBC driver JARs and editing per-driver defaults and translators over REST. An upload
 * runs foreign code inside the gateway once a connection opens it, so it needs modules.admin, not just gateway.write.
 */
class JdbcDriverApiTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var http: Http
    private val jar: ByteArray = Files.readAllBytes(Path.of(Class.forName("org.h2.Driver").protectionDomain.codeSource.location.toURI()))
    private val type = "application/java-archive"

    @BeforeEach fun setUp() {
        gw = Gateway(tmp, port = 0).start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        gw.createUser("view", setOf(DefaultRoles.VIEWER))
        http = Http(gw.port())
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
        // A database engineer: may write gateway configuration but is not a module administrator.
        val role = http.post("/api/v1/security/roles", """{"name":"dbeng","permissions":["${Permissions.GATEWAY_WRITE}"]}""")
        assertEquals(201, role.status, role.body)
        gw.createUser("eng", setOf("dbeng"))
    }

    @AfterEach fun tearDown() = gw.close()

    private fun as_(user: String): Http = Http(gw.port()).also { assertEquals(200, it.login(user, TEST_PASSWORD).status) }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun anAdministratorUploadsADriverAndTheUploadIsAudited() {
        val up = http.send("POST", "/api/v1/db/drivers?id=h2", jar, type)
        assertEquals(201, up.status, up.body)
        assertEquals("/api/v1/db/drivers/h2", up.header("Location"))
        assertEquals("org.h2.Driver", up.json["driverClass"])
        assertEquals("admin", up.json["uploadedBy"])
        assertTrue(Files.isRegularFile(tmp.resolve("jdbc-drivers/h2.jar")))

        val listed = http.get("/api/v1/db/drivers")
        assertEquals(200, listed.status, listed.body)
        val items = listed.json["items"] as List<*>
        assertEquals("h2", (items.single() as Map<*, *>)["id"])
        assertEquals(128 * 1024 * 1024, (listed.json["maxBytes"] as Number).toInt())

        assertEquals(1, gw.auditCount(AuditActions.JDBC_DRIVER_UPLOADED, "admin", AuditStatus.SUCCESS))
        val row = gw.security.audit.query(AuditQuery(action = AuditActions.JDBC_DRIVER_UPLOADED)).records.single()
        assertEquals("h2", row.target)
        assertTrue(row.detail.contains("class=org.h2.Driver") && row.detail.contains("sha256="), "detail=" + row.detail)

        // A connection names the uploaded driver and a translator; the driver is in use, so it cannot be removed.
        val conn = http.post(
            "/api/v1/db/connections",
            """{"name":"plant","driver":"custom","driverId":"h2","translator":"ORACLE","url":"jdbc:h2:mem:api;MODE=Oracle;DB_CLOSE_DELAY=-1","maxActive":1}""",
        )
        assertEquals(201, conn.status, conn.body)
        assertEquals("Valid", conn.json["status"], conn.body)
        assertEquals("SELECT 1 FROM DUAL", conn.json["validationQuery"])
        assertEquals("h2", conn.json["driverId"])
        val busy = http.delete("/api/v1/db/drivers/h2")
        assertEquals(422, busy.status, busy.body)
        assertEquals(0, gw.auditCount(AuditActions.JDBC_DRIVER_REMOVED))
        assertEquals(204, http.delete("/api/v1/db/connections/plant").status)
        assertEquals(204, http.delete("/api/v1/db/drivers/h2").status)
        assertEquals(1, gw.auditCount(AuditActions.JDBC_DRIVER_REMOVED, "admin", AuditStatus.SUCCESS))
        assertFalse(Files.exists(tmp.resolve("jdbc-drivers/h2.jar")))
        assertEquals(404, http.delete("/api/v1/db/drivers/h2").status)
    }

    @Test fun onlyAModuleAdministratorMayUploadOrRemoveADriver() {
        assertEquals(201, http.send("POST", "/api/v1/db/drivers?id=h2", jar, type).status)
        val anon = Http(gw.port())
        assertEquals(401, anon.get("/api/v1/db/drivers").status)
        for (user in listOf("view", "op", "eng")) {
            val c = as_(user)
            val up = c.send("POST", "/api/v1/db/drivers?id=other", jar, type)
            assertEquals(403, up.status, "$user ${up.body}")
            assertTrue(up.body.contains(Permissions.MODULES_ADMIN), up.body)
            val del = c.delete("/api/v1/db/drivers/h2")
            assertEquals(403, del.status, "$user ${del.body}")
        }
        // The viewer has no gateway.read, so listing is refused; the operator and the engineer may list.
        assertEquals(403, as_("view").get("/api/v1/db/drivers").status)
        for (user in listOf("op", "eng")) assertEquals(200, as_(user).get("/api/v1/db/drivers").status)
        assertEquals(1, (http.get("/api/v1/db/drivers").json["items"] as List<*>).size)
        assertEquals(1, gw.auditCount(AuditActions.JDBC_DRIVER_UPLOADED))
        assertEquals(0, gw.auditCount(AuditActions.JDBC_DRIVER_REMOVED))
    }

    @Test fun badUploadsAreRefusedAndNotAudited() {
        fun up(query: String, body: ByteArray) = http.send("POST", "/api/v1/db/drivers$query", body, type)
        assertEquals(422, up("?id=junk", "not a jar".toByteArray()).status)
        assertEquals(422, up("?id=plain", zip("a.txt" to "x".toByteArray())).status)
        assertEquals(422, http.send("POST", "/api/v1/db/drivers?id=h2&driverClass=com.example.Nope", jar, type).status)
        assertEquals(422, up("?id=..%2Fevil", jar).status)
        assertEquals(422, up("?id=Evil", jar).status)
        assertEquals(400, up("", jar).status)
        assertEquals(400, up("?id=h2&path=/etc", jar).status)
        assertEquals(0, gw.auditCount(AuditActions.JDBC_DRIVER_UPLOADED))
        assertFalse(Files.exists(tmp.resolve("evil.jar")))
        assertEquals(emptyList<Any>(), http.get("/api/v1/db/drivers").json["items"])
    }

    @Test fun driverSettingsAndTranslatorsAreEditedOverRest() {
        assertEquals(201, http.send("POST", "/api/v1/db/drivers?id=vendor", jar, type).status)
        val settings = http.get("/api/v1/db/driver-settings")
        assertEquals(200, settings.status, settings.body)
        val oracle = (settings.json["items"] as List<*>).map { it as Map<*, *> }.single { it["name"] == "oracle" }
        assertEquals(1521, oracle["defaultPort"])
        assertEquals("ORACLE", oracle["translator"])

        val put = http.put("/api/v1/db/driver-settings/oracle", """{"defaultPort":1522,"driverId":"vendor"}""")
        assertEquals(200, put.status, put.body)
        assertEquals(1522, put.json["defaultPort"])
        assertEquals(true, put.json["overridden"])
        // Again, without If-Match: replaces the stored override.
        assertEquals(200, http.put("/api/v1/db/driver-settings/oracle", """{"defaultPort":1523,"driverId":"vendor"}""").status)
        assertEquals(422, http.put("/api/v1/db/driver-settings/oracle", """{"urlTemplate":"jdbc:x:{nope}"}""").status)
        assertEquals(422, http.put("/api/v1/db/driver-settings/nothing", """{"defaultPort":1}""").status)

        // A connection that gives only host and database gets the rest from the settings.
        val conn = http.post("/api/v1/db/connections", """{"name":"ora","driver":"oracle","host":"db.example","database":"SVC","enabled":false}""")
        assertEquals(201, conn.status, conn.body)
        assertEquals("jdbc:oracle:thin:@//db.example:1523/SVC", conn.json["url"])
        assertEquals("vendor", conn.json["driverId"])

        val tr = http.get("/api/v1/db/translators")
        assertEquals(200, tr.status, tr.body)
        val names = (tr.json["items"] as List<*>).map { (it as Map<*, *>)["name"] }
        assertTrue(names.containsAll(listOf("ORACLE", "DB2", "SNOWFLAKE")), names.toString())
        val body = """{"pageTemplate":"{sql} LIMIT {limit} OFFSET {offset}","limitTemplate":"{sql} LIMIT {limit}","currentTimestamp":"NOW()","columnTypes":{"string":"VARCHAR({length})","text":"TEXT","integer":"INT","long":"BIGINT","double":"DOUBLE","boolean":"BOOLEAN","datetime":"TIMESTAMP"}}"""
        assertEquals(200, http.put("/api/v1/db/translators/mine", body).status)
        assertEquals(200, http.put("/api/v1/db/translators/mine", body).status)
        assertEquals(422, http.put("/api/v1/db/translators/mine", body.replace("{limit} OFFSET {offset}", "{limit}")).status)
        assertEquals(204, http.delete("/api/v1/db/translators/mine").status)

        // Writes need gateway.write; the operator reads only.
        val op = as_("op")
        assertEquals(200, op.get("/api/v1/db/translators").status)
        assertEquals(403, op.put("/api/v1/db/translators/mine", body).status)
        assertEquals(403, op.put("/api/v1/db/driver-settings/oracle", """{"defaultPort":1}""").status)
        assertEquals(204, as_("eng").delete("/api/v1/db/driver-settings/oracle").status)
    }
}
