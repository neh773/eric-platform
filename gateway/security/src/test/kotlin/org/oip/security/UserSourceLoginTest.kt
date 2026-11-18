package org.oip.security

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.OpenBSDBCrypt
import org.bouncycastle.crypto.params.Argon2Parameters
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * SEC-007, SEC-010, SEC-021, SEC-025: logins and user checks on directory, database and hybrid user sources, through
 * [UserSourceAuthenticator] with the real security service (resources, secret store, roles, audit).
 */
class UserSourceLoginTest {
    @TempDir lateinit var tmp: Path
    private var service: SecurityService? = null
    private var directory: TestDirectory? = null

    @AfterEach fun tearDown() {
        service?.close()
        directory?.close()
    }

    private fun security(): SecurityService = openSecurity(tmp).second.also { service = it }

    private fun authenticator(s: SecurityService, connections: UserSourceConnections? = null) = UserSourceAuthenticator(
        s.userSources, s.secrets, { s.roles.list().mapTo(HashSet()) { it.name } }, { connections }, s.audit, { true },
    )

    private fun directory(): TestDirectory = TestDirectory.start().also { directory = it }

    private fun ldapSpec(dir: TestDirectory, type: String, extra: Map<String, Any?> = emptyMap()) = mapOf(
        "type" to type,
        "ldapUrls" to listOf(dir.url),
        "allowPlaintext" to true,
        "bindDn" to TestDirectory.SERVICE_DN,
        "bindPasswordRef" to "secret://usersource-corp",
        "userBaseDn" to "ou=people,${TestDirectory.BASE}",
        "groupBaseDn" to "ou=groups,${TestDirectory.BASE}",
    ) + extra

    private fun sqlite(): Pair<UserSourceConnections, Connection> {
        val url = "jdbc:sqlite:${tmp.resolve("users.db")}"
        val setup = DriverManager.getConnection(url)
        val connections = object : UserSourceConnections {
            override fun <T> use(connection: String, block: (Connection, (String) -> String) -> T): T {
                if (connection != "plant") throw IllegalStateException("database connection '$connection' not found")
                return DriverManager.getConnection(url).use { block(it) { part -> "\"$part\"" } }
            }
        }
        return connections to setup
    }

    private fun success(result: SourceLogin): ExternalUser = assertInstanceOf(SourceLogin.Success::class.java, result).user

    @Test fun aDirectorySourceMapsGroupMembershipOntoRoles() {
        val s = security()
        val dir = directory()
        s.secrets.putText("usersource-corp", TestDirectory.SERVICE_PASSWORD, "test")
        s.userSources.save("corp", ldapSpec(dir, "ldap", mapOf(
            "roleMappings" to listOf(
                mapOf("value" to "cn=admins,ou=groups,${TestDirectory.BASE}", "roles" to listOf(DefaultRoles.ADMINISTRATOR)),
                mapOf("value" to "Operators", "roles" to listOf(DefaultRoles.OPERATOR, "no-such-role")),
            ),
            "defaultRoles" to listOf(DefaultRoles.VIEWER),
        )), "test")
        val auth = authenticator(s)
        val alice = success(auth.authenticate("corp", "alice", "alice-Pass-1".toCharArray(), "10.0.0.5"))
        assertEquals(setOf(DefaultRoles.ADMINISTRATOR, DefaultRoles.VIEWER), alice.roles)
        assertEquals("Alice Example", alice.displayName)
        // Group names match case-insensitively; a role this gateway does not have is dropped.
        assertEquals(setOf(DefaultRoles.OPERATOR, DefaultRoles.VIEWER), success(auth.authenticate("corp", "bob", "bob-Pass-1".toCharArray())).roles)
        assertEquals(setOf(DefaultRoles.VIEWER), success(auth.authenticate("corp", "carol", "carol-Pass-1".toCharArray())).roles)
        assertEquals(SourceLogin.BadCredentials, auth.authenticate("corp", "alice", "wrong".toCharArray()))

        val rows = s.audit.query(AuditQuery()).records
        val ok = rows.first { it.action == AuditActions.LOGIN && it.actor == "alice" }
        assertEquals("source=corp", ok.detail)
        assertEquals("10.0.0.5", ok.actorHost)
        assertTrue(rows.any { it.action == AuditActions.LOGIN_ATTEMPT && it.detail == "source=corp;bad credentials" })
        val text = rows.joinToString { it.toString() }
        for (secret in listOf("alice-Pass-1", "bob-Pass-1", TestDirectory.SERVICE_PASSWORD, "wrong")) assertFalse(text.contains(secret), secret)
    }

    @Test fun aDisabledOrMissingSourceIsUnavailable() {
        val s = security()
        val dir = directory()
        s.secrets.putText("usersource-corp", TestDirectory.SERVICE_PASSWORD, "test")
        s.userSources.save("corp", ldapSpec(dir, "ldap", mapOf("enabled" to false)), "test")
        val auth = authenticator(s)
        assertInstanceOf(SourceLogin.Unavailable::class.java, auth.authenticate("corp", "alice", "alice-Pass-1".toCharArray()))
        assertInstanceOf(SourceLogin.Unavailable::class.java, auth.authenticate("other", "alice", "alice-Pass-1".toCharArray()))
        // The bind password is read from the secret store at each login: without it the service account cannot bind.
        s.userSources.save("corp", ldapSpec(dir, "ldap"), "test")
        s.secrets.delete("usersource-corp")
        val gone = assertInstanceOf(SourceLogin.Unavailable::class.java, auth.authenticate("corp", "alice", "alice-Pass-1".toCharArray()))
        assertTrue(gone.reason.contains("bind password"), gone.reason)
    }

    @Test fun anLdapInternalSourceChecksTheDirectoryAndKeepsRolesInTheGateway() {
        val s = security()
        val dir = directory()
        s.secrets.putText("usersource-corp", TestDirectory.SERVICE_PASSWORD, "test")
        s.userSources.save("corp", ldapSpec(dir, "ldap-internal", mapOf(
            "users" to listOf(mapOf("username" to "alice", "roles" to listOf(DefaultRoles.OPERATOR))),
            // Directory groups do not give roles to a hybrid source that keeps them internally.
            "roleMappings" to listOf(mapOf("value" to "admins", "roles" to listOf(DefaultRoles.ADMINISTRATOR))),
        )), "test")
        val auth = authenticator(s)
        assertEquals(setOf(DefaultRoles.OPERATOR), success(auth.authenticate("corp", "ALICE", "alice-Pass-1".toCharArray())).roles)
        // The directory knows bob, but the gateway does not list him: no login.
        assertEquals(SourceLogin.BadCredentials, auth.authenticate("corp", "bob", "bob-Pass-1".toCharArray()))
        // The password is still the directory's.
        assertEquals(SourceLogin.BadCredentials, auth.authenticate("corp", "alice", "operator".toCharArray()))

        s.userSources.save("corp", ldapSpec(dir, "ldap-internal", mapOf(
            "users" to listOf(mapOf("username" to "alice", "roles" to listOf(DefaultRoles.OPERATOR))),
            "allowUnlistedUsers" to true,
            "defaultRoles" to listOf(DefaultRoles.VIEWER),
        )), "test")
        assertEquals(setOf(DefaultRoles.VIEWER), success(auth.authenticate("corp", "bob", "bob-Pass-1".toCharArray())).roles)
        assertEquals(setOf(DefaultRoles.OPERATOR, DefaultRoles.VIEWER), success(auth.authenticate("corp", "alice", "alice-Pass-1".toCharArray())).roles)
    }

    @Test fun aDatabaseSourceVerifiesStoredHashesAndReadsRoles() {
        val s = security()
        val (connections, db) = sqlite()
        db.use {
            it.createStatement().use { st ->
                st.execute("CREATE TABLE app_users (login TEXT PRIMARY KEY, pw TEXT, active INTEGER, full_name TEXT, mail TEXT)")
                st.execute("CREATE TABLE app_roles (login TEXT, role_name TEXT)")
            }
            val insert = it.prepareStatement("INSERT INTO app_users VALUES (?, ?, ?, ?, ?)")
            fun user(name: String, hash: String, active: Int) = insert.apply {
                setString(1, name); setString(2, hash); setInt(3, active); setString(4, "$name example"); setString(5, "$name@example.test")
            }.executeUpdate()
            user("dana", Hashes.argon2id("Dana-pass-1"), 1)
            user("erin", Hashes.bcrypt("Erin-pass-1"), 1)
            user("finn", Hashes.argon2id("Finn-pass-1"), 0)
            // A plain-text password in the table never matches: there is no plain-text scheme.
            user("gary", "Gary-pass-1", 1)
            it.createStatement().use { st ->
                st.execute("INSERT INTO app_roles VALUES ('dana', 'operator'), ('dana', 'Shift Lead'), ('dana', 'no-such-role'), ('erin', 'viewer')")
            }
        }
        s.userSources.save("plantdb", mapOf(
            "type" to "database", "connection" to "plant",
            "usersTable" to "app_users", "usernameColumn" to "login", "passwordColumn" to "pw", "enabledColumn" to "active",
            "displayNameColumn" to "full_name", "emailColumn" to "mail",
            "rolesTable" to "app_roles", "rolesUsernameColumn" to "login", "roleColumn" to "role_name",
            "roleMappings" to listOf(mapOf("value" to "shift lead", "roles" to listOf(DefaultRoles.ADMINISTRATOR))),
            "hashScheme" to "argon2id",
        ), "test")
        val auth = authenticator(s, connections)
        val dana = success(auth.authenticate("plantdb", "dana", "Dana-pass-1".toCharArray()))
        assertEquals(setOf(DefaultRoles.OPERATOR, DefaultRoles.ADMINISTRATOR), dana.roles)
        assertEquals("dana example", dana.displayName)
        assertEquals("dana@example.test", dana.email)
        assertEquals(SourceLogin.BadCredentials, auth.authenticate("plantdb", "dana", "Dana-pass-2".toCharArray()))
        assertEquals(SourceLogin.Disabled, auth.authenticate("plantdb", "finn", "Finn-pass-1".toCharArray()))
        assertEquals(SourceLogin.BadCredentials, auth.authenticate("plantdb", "gary", "Gary-pass-1".toCharArray()))
        assertEquals(SourceLogin.BadCredentials, auth.authenticate("plantdb", "nobody", "Dana-pass-1".toCharArray()))
        // The scheme is the resource's: a bcrypt row does not verify under argon2id.
        assertEquals(SourceLogin.BadCredentials, auth.authenticate("plantdb", "erin", "Erin-pass-1".toCharArray()))
        // A name with SQL in it is never sent.
        assertEquals(SourceLogin.BadCredentials, auth.authenticate("plantdb", "x' OR '1'='1", "Dana-pass-1".toCharArray()))

        val check = auth.check("plantdb", "dana", null, "admin")!!
        assertTrue(check.check.exists)
        assertEquals(listOf(DefaultRoles.ADMINISTRATOR, DefaultRoles.OPERATOR), check.check.roles)
        assertEquals(true, auth.check("plantdb", "dana", "Dana-pass-1".toCharArray(), "admin")!!.check.passwordValid)
        assertEquals(false, auth.check("plantdb", "dana", "nope".toCharArray(), "admin")!!.check.passwordValid)
        assertFalse(auth.check("plantdb", "nobody", null, "admin")!!.check.exists)

        // The connection is the gateway's: one the source names that does not exist leaves the source unavailable.
        s.userSources.save("plantdb", s.userSources.spec("plantdb")!! + mapOf("connection" to "missing"), "test")
        val down = assertInstanceOf(SourceLogin.Unavailable::class.java, auth.authenticate("plantdb", "dana", "Dana-pass-1".toCharArray()))
        assertTrue(down.reason.contains("missing"), down.reason)
        assertTrue(s.audit.query(AuditQuery()).records.none { it.toString().contains("Dana-pass") })
    }

    @Test fun anLdapDatabaseSourceChecksTheDirectoryAndReadsRolesFromTheDatabase() {
        val s = security()
        val dir = directory()
        s.secrets.putText("usersource-corp", TestDirectory.SERVICE_PASSWORD, "test")
        val (connections, db) = sqlite()
        db.use {
            it.createStatement().use { st ->
                st.execute("CREATE TABLE gw_roles (username TEXT, role TEXT)")
                st.execute("INSERT INTO gw_roles VALUES ('alice', 'operator'), ('alice', 'viewer'), ('bob', 'not-a-role')")
            }
        }
        s.userSources.save("corp", ldapSpec(dir, "ldap-database", mapOf("connection" to "plant", "rolesTable" to "gw_roles")), "test")
        val auth = authenticator(s, connections)
        val alice = success(auth.authenticate("corp", "alice", "alice-Pass-1".toCharArray()))
        assertEquals(setOf(DefaultRoles.OPERATOR, DefaultRoles.VIEWER), alice.roles)
        assertEquals("uid=alice,ou=people,${TestDirectory.BASE}", alice.dn)
        assertEquals(emptySet<String>(), success(auth.authenticate("corp", "bob", "bob-Pass-1".toCharArray())).roles)
        assertEquals(SourceLogin.BadCredentials, auth.authenticate("corp", "alice", "bob-Pass-1".toCharArray()))
        val looked = auth.check("corp", "alice", null, "admin")!!
        assertTrue(looked.check.exists)
        assertEquals(listOf(DefaultRoles.OPERATOR, DefaultRoles.VIEWER), looked.check.roles)
    }

    @Test fun storedHashSchemesVerifyAndRejectMalformedOrCostlyRows() {
        val pw = "Pump-station-7"
        for ((scheme, hash) in listOf(
            "argon2id" to Hashes.argon2id(pw),
            "bcrypt" to Hashes.bcrypt(pw),
            "pbkdf2-sha256" to Hashes.pbkdf2("sha256", pw),
            "pbkdf2-sha512" to Hashes.pbkdf2("sha512", pw),
            "pbkdf2-sha256" to Hashes.pbkdf2Text(pw),
        )) {
            assertTrue(ExternalPasswordHashes.verify(scheme, hash, pw.toCharArray()), "$scheme $hash")
            assertFalse(ExternalPasswordHashes.verify(scheme, hash, "pump-station-7".toCharArray()), scheme)
            for (other in ExternalPasswordHashes.SCHEMES - scheme) assertFalse(ExternalPasswordHashes.verify(other, hash, pw.toCharArray()), "$other on $scheme")
        }
        assertFalse(ExternalPasswordHashes.verify("argon2id", pw, pw.toCharArray()))
        assertFalse(ExternalPasswordHashes.verify("plain", pw, pw.toCharArray()))
        // Cost parameters out of range verify as false without being computed.
        assertFalse(ExternalPasswordHashes.verify("argon2id", "\$argon2id\$v=19\$m=4194304,t=2,p=1\$c2FsdHNhbHRzYWx0\$aGFzaGhhc2hoYXNoaGFzaA", pw.toCharArray()))
        assertFalse(ExternalPasswordHashes.verify("pbkdf2-sha256", "\$pbkdf2-sha256\$99999999\$c2FsdHNhbHQ\$aGFzaGhhc2hoYXNoaGFzaA", pw.toCharArray()))
        assertFalse(ExternalPasswordHashes.verify("pbkdf2-sha256", "\$pbkdf2-sha256\$10\$c2FsdHNhbHQ\$aGFzaGhhc2hoYXNoaGFzaA", pw.toCharArray()))
        assertFalse(ExternalPasswordHashes.verifyMissing("bcrypt", pw.toCharArray()))
    }

    /** Hashes as another application would have stored them. */
    private object Hashes {
        private val salt = ByteArray(16) { (it * 7 + 3).toByte() }
        private fun b64(b: ByteArray) = Base64.getEncoder().withoutPadding().encodeToString(b)

        fun argon2id(pw: String): String {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(1024).withIterations(1).withParallelism(1).withSalt(salt).build()
            val out = ByteArray(32)
            Argon2BytesGenerator().apply { init(params) }.generateBytes(pw.toByteArray(), out)
            return "\$argon2id\$v=19\$m=1024,t=1,p=1\$${b64(salt)}\$${b64(out)}"
        }

        fun bcrypt(pw: String): String = OpenBSDBCrypt.generate("2b", pw.toCharArray(), salt, 4)

        private fun derive(algo: String, pw: String, salt: ByteArray, iterations: Int, bytes: Int): ByteArray =
            SecretKeyFactory.getInstance(if (algo == "sha256") "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA512")
                .generateSecret(PBEKeySpec(pw.toCharArray(), salt, iterations, bytes * 8)).encoded

        fun pbkdf2(algo: String, pw: String): String {
            val hash = derive(algo, pw, salt, 1_000, if (algo == "sha256") 32 else 64)
            return "\$pbkdf2-$algo\$1000\$${b64(salt).replace('+', '.')}\$${b64(hash).replace('+', '.')}"
        }

        /** The `pbkdf2_sha256$iterations$salt$hash` form, whose salt is stored as text. */
        fun pbkdf2Text(pw: String): String {
            val textSalt = "plantsalt1234"
            val hash = derive("sha256", pw, textSalt.toByteArray(), 2_000, 32)
            return "pbkdf2_sha256\$2000\$$textSalt\$${Base64.getEncoder().encodeToString(hash)}"
        }
    }
}
