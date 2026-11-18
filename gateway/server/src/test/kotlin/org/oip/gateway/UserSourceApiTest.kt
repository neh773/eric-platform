package org.oip.gateway

import com.unboundid.ldap.listener.InMemoryDirectoryServer
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig
import com.unboundid.ldap.listener.InMemoryListenerConfig
import com.unboundid.ldif.LDIFReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.AuditActions
import org.oip.security.AuditQuery
import org.oip.security.DefaultRoles
import java.io.BufferedReader
import java.io.StringReader
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * SEC-007, SEC-010, SEC-021, SEC-025: user source administration and gateway logins through an LDAP directory, an
 * external database and the two hybrids. The bind password stays in the secret store; the system user source decides
 * which source the login page checks.
 */
class UserSourceApiTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var admin: Http
    private var ldap: InMemoryDirectoryServer? = null

    @BeforeEach fun setUp() {
        gw = Gateway(tmp, port = 0).start()
        admin = Http(gw.port())
        gw.loginAdmin(admin)
    }

    @AfterEach fun tearDown() {
        gw.close()
        ldap?.shutDown(true)
    }

    private fun directory(): String {
        val config = InMemoryDirectoryServerConfig(BASE)
        config.setSchema(null)
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("plain", InetAddress.getLoopbackAddress(), 0, null))
        val server = InMemoryDirectoryServer(config)
        LDIFReader(BufferedReader(StringReader(LDIF))).use { reader -> while (true) server.add(reader.readEntry() ?: break) }
        server.startListening()
        ldap = server
        return "ldap://localhost:${server.getListenPort("plain")}"
    }

    private fun ldapBody(url: String, type: String = "ldap", extra: Map<String, Any?> = emptyMap()) = mapOf(
        "name" to "corp",
        "type" to type,
        "ldapUrls" to listOf(url),
        "allowPlaintext" to true,
        "bindDn" to SERVICE_DN,
        "bindPassword" to SERVICE_PASSWORD,
        "userBaseDn" to "ou=people,$BASE",
        "groupBaseDn" to "ou=groups,$BASE",
    ) + extra

    private fun useForLogin(source: String) {
        val current = gw.security.gatewaySecurity.get()
        gw.security.gatewaySecurity.update(current.copy(systemUserSource = source), "test")
    }

    private fun login(user: String, password: String): Pair<Http, Http.Res> {
        val c = Http(gw.port())
        return c to c.login(user, password)
    }

    private fun assertSecretAbsent(secret: String) {
        Files.walk(tmp.resolve("config")).use { paths ->
            paths.filter { it.isRegularFile() }.forEach { assertFalse(it.readText(Charsets.ISO_8859_1).contains(secret), "secret in $it") }
        }
    }

    @Test fun aDirectorySourceIsAdministeredWithTheBindPasswordInTheSecretStore() {
        val url = directory()
        val created = admin.post(UserSourceApi.BASE, Json.write(ldapBody(url, extra = mapOf(
            "roleMappings" to listOf(mapOf("value" to "admins", "roles" to listOf(DefaultRoles.ADMINISTRATOR))),
        ))))
        assertEquals(201, created.status, created.body)
        assertEquals(true, created.json["bindPasswordSet"])
        assertEquals(false, created.json["inUse"])
        assertFalse(created.body.contains(SERVICE_PASSWORD))
        assertFalse(created.json.containsKey("bindPasswordRef"))
        assertEquals(1, gw.security.secrets.list().count { it.name == "usersource-corp" })
        assertSecretAbsent(SERVICE_PASSWORD)
        val generic = admin.get("/api/v1/config/org.oip.security/gateway/UserSource/corp")
        assertEquals(200, generic.status, generic.body)
        assertFalse(generic.body.contains("bindPasswordRef"))
        // The generic config API does not write a user source; its own route does, with its checks.
        assertTrue(admin.put("/api/v1/config/org.oip.security/gateway/UserSource/corp", """{"spec":{"type":"ldap"}}""").status in 400..499)

        val list = admin.get(UserSourceApi.BASE)
        assertEquals("internal", list.json["systemUserSource"])
        assertFalse(list.body.contains(SERVICE_PASSWORD))

        // Replacing without bindPassword keeps it.
        val version = created.json["version"].toString()
        val kept = admin.send("PUT", "${UserSourceApi.BASE}/corp", Json.write(ldapBody(url) - "bindPassword" - "name" + mapOf("description" to "corp directory")).toByteArray(), headers = mapOf("If-Match" to "\"$version\""))
        assertEquals(200, kept.status, kept.body)
        assertEquals(true, kept.json["bindPasswordSet"])

        for ((body, problem) in listOf(
            ldapBody(url, extra = mapOf("name" to "bad1", "userBaseDn" to "")) to "userBaseDn",
            ldapBody(url, extra = mapOf("name" to "bad2", "allowPlaintext" to false)) to "clear text",
            ldapBody(url, extra = mapOf("name" to "bad3", "defaultRoles" to listOf("ghost"))) to "role 'ghost'",
            ldapBody(url, extra = mapOf("name" to "bad4", "bindPassword" to "")) to "bindDn needs bindPassword",
            mapOf("name" to "bad5", "type" to "database", "connection" to "nowhere", "usersTable" to "users") to "database connection 'nowhere'",
            mapOf("name" to "internal", "type" to "database") to "own user source",
        )) {
            val res = admin.post(UserSourceApi.BASE, Json.write(body))
            assertEquals(422, res.status, res.body)
            assertTrue(res.body.contains(problem), "$problem: ${res.body}")
        }

        useForLogin("corp")
        assertEquals(true, admin.get("${UserSourceApi.BASE}/corp").json["inUse"])
        assertEquals(409, admin.delete("${UserSourceApi.BASE}/corp").status)
        useForLogin("internal")
        assertEquals(204, admin.delete("${UserSourceApi.BASE}/corp").status)
        assertEquals(0, gw.security.secrets.list().count { it.name == "usersource-corp" })
        assertEquals(404, admin.get("${UserSourceApi.BASE}/corp").status)
    }

    @Test fun userSourceAdministrationNeedsUsersAdmin() {
        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        val op = Http(gw.port())
        assertEquals(200, op.login("op", TEST_PASSWORD).status)
        assertEquals(403, op.get(UserSourceApi.BASE).status)
        assertEquals(403, op.post(UserSourceApi.BASE, """{"name":"x","type":"ldap"}""").status)
        assertEquals(403, op.get("${UserSourceApi.BASE}/x").status)
        assertEquals(403, op.get("/api/v1/config/org.oip.security/gateway/UserSource/x").status)
        assertEquals(401, Http(gw.port()).get(UserSourceApi.BASE).status)
    }

    @Test fun gatewayLoginsUseTheSystemUserSourceAndMapGroupsOntoRoles() {
        val url = directory()
        assertEquals(201, admin.post(UserSourceApi.BASE, Json.write(ldapBody(url, extra = mapOf(
            "roleMappings" to listOf(
                mapOf("value" to "admins", "roles" to listOf(DefaultRoles.ADMINISTRATOR)),
                mapOf("value" to "cn=operators,ou=groups,$BASE", "roles" to listOf(DefaultRoles.OPERATOR)),
            ),
        )))).status)
        // Until the gateway is told to use it, logins are the internal source's.
        assertEquals(401, login("alice", "alice-Pass-1").second.status)
        useForLogin("corp")
        val (alice, res) = login("alice", "alice-Pass-1")
        assertEquals(200, res.status, res.body)
        assertEquals(listOf(DefaultRoles.ADMINISTRATOR), res.json["roles"])
        val session = alice.get("/api/v1/auth/session")
        assertEquals(200, session.status, session.body)
        assertEquals("corp", session.json["userSource"])
        assertEquals("Alice Example", session.json["displayName"])
        // Alice's mapped role holds users.admin.
        assertEquals(200, alice.get(UserSourceApi.BASE).status)
        val (_, bob) = login("Bob", "bob-Pass-1")
        assertEquals(listOf(DefaultRoles.OPERATOR), bob.json["roles"])
        assertEquals(401, login("alice", "wrong-Pass").second.status)
        assertEquals(401, login("alice", "").second.status)
        // The internal admin is not on this source.
        assertEquals(401, login("admin", TEST_PASSWORD).second.status)
        val rows = gw.security.audit.query(AuditQuery()).records
        assertTrue(rows.any { it.action == AuditActions.LOGIN && it.actor == "alice" && it.detail == "source=corp" })
        assertTrue(rows.none { it.toString().contains("alice-Pass-1") || it.toString().contains(SERVICE_PASSWORD) })

        // The check shows the groups the directory gave, and the roles they map to.
        val check = admin.post("${UserSourceApi.BASE}/corp/check", Json.write(mapOf("username" to "bob", "password" to "bob-Pass-1")))
        assertEquals(200, check.status, check.body)
        assertEquals(true, check.json["passwordValid"])
        assertEquals(listOf("operators"), check.json["groups"])
        assertEquals(listOf(DefaultRoles.OPERATOR), check.json["roles"])
        assertEquals("uid=bob,ou=people,$BASE", check.json["dn"])
        assertEquals(false, admin.post("${UserSourceApi.BASE}/corp/check", Json.write(mapOf("username" to "bob", "password" to "x"))).json["passwordValid"])

        // Turning the source off ends its sessions.
        val current = admin.get("${UserSourceApi.BASE}/corp").json
        val off = admin.put("${UserSourceApi.BASE}/corp", Json.write(current.filterKeys { it !in setOf("name", "bindPasswordSet", "inUse", "version") } + mapOf("enabled" to false)))
        assertEquals(200, off.status, off.body)
        assertEquals(401, alice.get("/api/v1/auth/session").status)
        assertEquals(503, login("bob", "bob-Pass-1").second.status)
    }

    @Test fun anUnreachableDirectoryIsServiceUnavailableAndCostsNoLockoutSlot() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        assertEquals(201, admin.post(UserSourceApi.BASE, Json.write(ldapBody("ldap://127.0.0.1:$port", extra = mapOf("connectTimeoutMillis" to 500)))).status)
        useForLogin("corp")
        repeat(6) { assertEquals(503, login("alice", "alice-Pass-1").second.status) }
        val check = admin.post("${UserSourceApi.BASE}/corp/check", Json.write(mapOf("username" to "alice", "password" to "alice-Pass-1")))
        assertTrue((check.json["unavailable"] as String).contains("not reachable"), check.body)
    }

    @Test fun anLdapInternalSourceLogsInListedUsersWithTheRolesKeptInTheGateway() {
        val url = directory()
        assertEquals(201, admin.post(UserSourceApi.BASE, Json.write(ldapBody(url, type = "ldap-internal", extra = mapOf(
            "users" to listOf(mapOf("username" to "bob", "roles" to listOf(DefaultRoles.VIEWER))),
        )))).status)
        useForLogin("corp")
        val (_, bob) = login("bob", "bob-Pass-1")
        assertEquals(200, bob.status, bob.body)
        assertEquals(listOf(DefaultRoles.VIEWER), bob.json["roles"])
        assertEquals(401, login("alice", "alice-Pass-1").second.status)
        assertEquals(401, login("bob", "viewer").second.status)
    }

    @Test fun aDatabaseSourceLogsInFromTablesBehindANamedConnection() {
        val db = tmp.resolve("app.db")
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE users (username TEXT, password_hash TEXT)")
                st.execute("CREATE TABLE user_roles (username TEXT, role TEXT)")
                st.execute("INSERT INTO users VALUES ('dana', '${BCRYPT_DANA}')")
                st.execute("INSERT INTO user_roles VALUES ('dana', 'operator')")
            }
        }
        assertEquals(201, admin.post("/api/v1/db/connections", Json.write(mapOf("name" to "appdb", "driver" to "sqlite", "url" to "jdbc:sqlite:$db"))).status)
        val created = admin.post(UserSourceApi.BASE, Json.write(mapOf(
            "name" to "appusers", "type" to "database", "connection" to "appdb",
            "usersTable" to "users", "rolesTable" to "user_roles", "hashScheme" to "bcrypt",
        )))
        assertEquals(201, created.status, created.body)
        useForLogin("appusers")
        val (dana, res) = login("dana", "Dana-pass-1")
        assertEquals(200, res.status, res.body)
        assertEquals(listOf(DefaultRoles.OPERATOR), res.json["roles"])
        assertEquals("appusers", dana.get("/api/v1/auth/session").json["userSource"])
        assertEquals(401, login("dana", "dana-pass-1").second.status)
        assertEquals(401, login("erin", "Dana-pass-1").second.status)
    }

    /** SEC-010 targets PostgreSQL: the same login against a real server, when one answers (compose service or CI). */
    @Test fun aDatabaseSourceLogsInAgainstPostgres() {
        val port = System.getenv("OIP_SIM_POSTGRES_PORT")?.toIntOrNull() ?: 25432
        val url = "jdbc:postgresql://127.0.0.1:$port/oip"
        val reachable = runCatching { DriverManager.getConnection(url, "oip", "oip").use { it.isValid(2) } }.getOrDefault(false)
        assumeTrue(reachable, "no PostgreSQL server on port $port")
        val schema = "us_${System.nanoTime()}"
        DriverManager.getConnection(url, "oip", "oip").use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE SCHEMA $schema")
                st.execute("CREATE TABLE $schema.\"Accounts\" (login TEXT PRIMARY KEY, pw TEXT NOT NULL, active BOOLEAN NOT NULL)")
                st.execute("CREATE TABLE $schema.account_roles (login TEXT, role_name TEXT)")
                st.execute("INSERT INTO $schema.\"Accounts\" VALUES ('dana', '$BCRYPT_DANA', true), ('finn', '$BCRYPT_DANA', false)")
                st.execute("INSERT INTO $schema.account_roles VALUES ('dana', 'operator'), ('dana', 'viewer')")
            }
        }
        try {
            assertEquals(201, admin.post("/api/v1/db/connections", Json.write(mapOf(
                "name" to "pg", "driver" to "postgres", "url" to url, "username" to "oip", "password" to "oip",
            ))).status)
            val created = admin.post(UserSourceApi.BASE, Json.write(mapOf(
                "name" to "pgusers", "type" to "database", "connection" to "pg",
                // A mixed-case table name only works quoted, which the connection's translator does.
                "usersTable" to "$schema.Accounts", "usernameColumn" to "login", "passwordColumn" to "pw", "enabledColumn" to "active",
                "rolesTable" to "$schema.account_roles", "rolesUsernameColumn" to "login", "roleColumn" to "role_name", "hashScheme" to "bcrypt",
            )))
            assertEquals(201, created.status, created.body)
            useForLogin("pgusers")
            val res = login("dana", "Dana-pass-1").second
            assertEquals(200, res.status, res.body)
            assertEquals(listOf(DefaultRoles.OPERATOR, DefaultRoles.VIEWER), res.json["roles"])
            assertEquals(403, login("finn", "Dana-pass-1").second.status)
            assertEquals(401, login("dana", "wrong").second.status)
        } finally {
            DriverManager.getConnection(url, "oip", "oip").use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }

    @Test fun anLdapDatabaseSourceTakesRolesFromTheExternalTables() {
        val url = directory()
        val db = tmp.resolve("roles.db")
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE gw_roles (username TEXT, role TEXT)")
                st.execute("INSERT INTO gw_roles VALUES ('alice', 'viewer')")
            }
        }
        assertEquals(201, admin.post("/api/v1/db/connections", Json.write(mapOf("name" to "rolesdb", "driver" to "sqlite", "url" to "jdbc:sqlite:$db"))).status)
        val created = admin.post(UserSourceApi.BASE, Json.write(ldapBody(url, type = "ldap-database", extra = mapOf("connection" to "rolesdb", "rolesTable" to "gw_roles"))))
        assertEquals(201, created.status, created.body)
        useForLogin("corp")
        val res = login("alice", "alice-Pass-1").second
        assertEquals(200, res.status, res.body)
        assertEquals(listOf(DefaultRoles.VIEWER), res.json["roles"])
        val check = admin.post("${UserSourceApi.BASE}/corp/check", Json.write(mapOf("username" to "alice")))
        assertEquals(listOf("viewer"), check.json["groups"])
        assertEquals(true, check.json["exists"])
    }

    companion object {
        const val BASE = "dc=example,dc=test"
        const val SERVICE_DN = "cn=gateway,ou=system,$BASE"
        const val SERVICE_PASSWORD = "svc-Bind-Secret-9"
        /** bcrypt (cost 4) of `Dana-pass-1`. */
        val BCRYPT_DANA: String = org.bouncycastle.crypto.generators.OpenBSDBCrypt.generate("2b", "Dana-pass-1".toCharArray(), ByteArray(16) { 5 }, 4)

        private val LDIF = """
            dn: $BASE
            objectClass: domain
            dc: example

            dn: ou=people,$BASE
            objectClass: organizationalUnit
            ou: people

            dn: ou=groups,$BASE
            objectClass: organizationalUnit
            ou: groups

            dn: ou=system,$BASE
            objectClass: organizationalUnit
            ou: system

            dn: $SERVICE_DN
            objectClass: person
            cn: gateway
            sn: gateway
            userPassword: $SERVICE_PASSWORD

            dn: uid=alice,ou=people,$BASE
            objectClass: inetOrgPerson
            uid: alice
            cn: Alice Example
            sn: Example
            mail: alice@example.test
            userPassword: alice-Pass-1
            memberOf: cn=admins,ou=groups,$BASE

            dn: uid=bob,ou=people,$BASE
            objectClass: inetOrgPerson
            uid: bob
            cn: Bob Example
            sn: Example
            userPassword: bob-Pass-1

            dn: cn=admins,ou=groups,$BASE
            objectClass: groupOfNames
            cn: admins
            member: uid=alice,ou=people,$BASE

            dn: cn=operators,ou=groups,$BASE
            objectClass: groupOfNames
            cn: operators
            member: uid=bob,ou=people,$BASE
        """.trimIndent()
    }
}
