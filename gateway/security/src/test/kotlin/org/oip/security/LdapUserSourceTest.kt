package org.oip.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket

/** SEC-007: the LDAP client of a directory user source, against an in-memory directory. */
class LdapUserSourceTest {
    private fun source(url: String, extra: Map<String, Any?> = emptyMap()): UserSource = UserSource.of(
        "corp",
        mapOf(
            "type" to "ldap",
            "ldapUrls" to listOf(url),
            "allowPlaintext" to url.startsWith("ldap://"),
            "bindDn" to TestDirectory.SERVICE_DN,
            "bindPasswordRef" to "secret://usersource-corp",
            "userBaseDn" to "ou=people,${TestDirectory.BASE}",
            "userFilter" to "(uid={username})",
        ) + extra,
        1,
    )

    private fun client(source: UserSource, password: String = TestDirectory.SERVICE_PASSWORD) = LdapUserSource(source) { password.toCharArray() }

    private fun found(outcome: DirectoryOutcome): DirectoryUser = assertInstanceOf(DirectoryOutcome.Found::class.java, outcome).user

    @Test fun aServiceAccountLoginFindsTheUserAndReadsItsGroups() {
        TestDirectory.start().use { dir ->
            val ldap = client(source(dir.url, mapOf("groupBaseDn" to "ou=groups,${TestDirectory.BASE}")))
            val alice = found(ldap.authenticate("alice", "alice-Pass-1".toCharArray()))
            assertEquals("uid=alice,ou=people,${TestDirectory.BASE}", alice.dn)
            assertEquals("Alice Example", alice.displayName)
            assertEquals("alice@example.test", alice.email)
            assertEquals(listOf("admins"), alice.groupNames)
            assertEquals(listOf("cn=admins,ou=groups,${TestDirectory.BASE}"), alice.groupDns)
            // Bob has no memberOf; the group search finds his group.
            assertEquals(listOf("operators"), found(ldap.authenticate("BOB", "bob-Pass-1".toCharArray())).groupNames)
            // The service account searched, then the user's own bind checked the password.
            assertEquals(TestDirectory.SERVICE_DN, dir.binds[0].second)
            assertEquals("uid=alice,ou=people,${TestDirectory.BASE}", dir.binds[1].second)
        }
    }

    @Test fun wrongPasswordsUnknownUsersAndEmptyPasswordsAreBadCredentials() {
        TestDirectory.start().use { dir ->
            val ldap = client(source(dir.url))
            assertEquals(DirectoryOutcome.BadCredentials, ldap.authenticate("alice", "wrong".toCharArray()))
            assertEquals(DirectoryOutcome.BadCredentials, ldap.authenticate("nobody", "alice-Pass-1".toCharArray()))
            val before = dir.binds.size
            // A simple bind with no password is an anonymous bind a directory accepts: it is never sent.
            assertEquals(DirectoryOutcome.BadCredentials, ldap.authenticate("alice", CharArray(0)))
            assertEquals(before, dir.binds.size)
        }
    }

    @Test fun filterAndDnMetacharactersCannotWidenALogin() {
        TestDirectory.start().use { dir ->
            val ldap = client(source(dir.url))
            for (name in listOf("*", "a*", "alice)(uid=*", "alice\\2a", "uid=alice,ou=people")) {
                assertEquals(DirectoryOutcome.BadCredentials, ldap.authenticate(name, "alice-Pass-1".toCharArray()), name)
            }
        }
        assertEquals("a\\2a\\28b\\29\\5cc\\00", LdapUserSource.escapeFilterValue("a*(b)\\c\u0000"))
        assertEquals("\\#a\\,b\\+c\\=d\\ ", LdapUserSource.escapeDnValue("#a,b+c=d "))
        assertEquals("Operators", LdapUserSource.rdnValue("CN=Operators,OU=Groups,DC=example,DC=test"))
    }

    @Test fun nestedGroupsAreFollowedToTheConfiguredDepth() {
        TestDirectory.start().use { dir ->
            val groups = mapOf("groupBaseDn" to "ou=groups,${TestDirectory.BASE}")
            val flat = client(source(dir.url, groups))
            assertEquals(listOf("shift-a"), found(flat.authenticate("carol", "carol-Pass-1".toCharArray())).groupNames)
            val two = client(source(dir.url, groups + mapOf("nestedGroups" to true, "nestedGroupDepth" to 2)))
            assertEquals(listOf("shift-a", "plant-staff"), found(two.authenticate("carol", "carol-Pass-1".toCharArray())).groupNames)
            val deep = client(source(dir.url, groups + mapOf("nestedGroups" to true, "nestedGroupDepth" to 5)))
            assertEquals(listOf("shift-a", "plant-staff", "site-staff"), found(deep.authenticate("carol", "carol-Pass-1".toCharArray())).groupNames)
        }
    }

    @Test fun aBindPatternLogsInWithoutAServiceAccount() {
        TestDirectory.start().use { dir ->
            val s = source(dir.url, mapOf("bindDn" to "", "bindPasswordRef" to "", "userBaseDn" to "", "userBindPattern" to "uid={username},ou=people,${TestDirectory.BASE}"))
            assertEquals(emptyList<String>(), s.problems())
            val ldap = LdapUserSource(s) { null }
            val alice = found(ldap.authenticate("alice", "alice-Pass-1".toCharArray()))
            assertEquals(listOf("admins"), alice.groupNames)
            assertEquals(DirectoryOutcome.BadCredentials, ldap.authenticate("alice", "nope".toCharArray()))
            assertTrue(dir.binds.none { it.second == TestDirectory.SERVICE_DN })
            assertInstanceOf(DirectoryOutcome.Unavailable::class.java, ldap.lookup("alice"))
        }
    }

    @Test fun ldapsChecksTheCertificateAndTheHostName() {
        val tls = TestTls("localhost")
        TestDirectory.start(TestDirectory.Mode.LDAPS, tls).use { dir ->
            val ldap = client(source(dir.url))
            // Not in the gateway's trust: refused before any bind.
            val refused = assertInstanceOf(DirectoryOutcome.Unavailable::class.java, ldap.authenticate("alice", "alice-Pass-1".toCharArray()))
            assertTrue(refused.reason.startsWith("TLS was refused"), refused.reason)
            assertTrue(dir.binds.isEmpty())
            tls.trust().use {
                assertEquals(listOf("admins"), found(ldap.authenticate("alice", "alice-Pass-1".toCharArray())).groupNames)
            }
        }
        val wrongHost = TestTls("directory.other.test")
        TestDirectory.start(TestDirectory.Mode.LDAPS, wrongHost).use { dir ->
            wrongHost.trust().use {
                val outcome = client(source(dir.url)).authenticate("alice", "alice-Pass-1".toCharArray())
                assertInstanceOf(DirectoryOutcome.Unavailable::class.java, outcome)
                assertTrue(dir.binds.isEmpty())
            }
        }
    }

    @Test fun startTlsRunsEveryBindOnTheUpgradedConnection() {
        val tls = TestTls("localhost")
        TestDirectory.start(TestDirectory.Mode.STARTTLS, tls).use { dir ->
            val s = source(dir.url, mapOf("startTls" to true, "allowPlaintext" to false))
            assertEquals(emptyList<String>(), s.problems())
            tls.trust().use {
                val ldap = client(s)
                assertEquals("Alice Example", found(ldap.authenticate("alice", "alice-Pass-1".toCharArray())).displayName)
                assertEquals(DirectoryOutcome.BadCredentials, ldap.authenticate("alice", "wrong".toCharArray()))
            }
            // Two logins, each a service account connection and a user connection, each upgraded by StartTLS and each
            // carrying one bind. A bind on a second, plain connection would have a connection number past the upgraded ones.
            assertEquals(4, dir.startTlsConnections.size)
            assertEquals(4, dir.binds.size)
            assertEquals(setOf(0L, 1L, 2L, 3L), dir.binds.map { it.first }.toSet(), dir.binds.toString())
        }
    }

    @Test fun aRefusedServiceBindOrADeadDirectoryIsUnavailableAndNamesNoSecret() {
        TestDirectory.start().use { dir ->
            val outcome = client(source(dir.url), password = "not-the-Bind-Secret").authenticate("alice", "alice-Pass-1".toCharArray())
            val reason = assertInstanceOf(DirectoryOutcome.Unavailable::class.java, outcome).reason
            assertTrue(reason.contains("service account"), reason)
            assertFalse(reason.contains("not-the-Bind-Secret"))
            assertFalse(reason.contains("alice-Pass-1"))
        }
        val closedPort = ServerSocket(0).use { it.localPort }
        val dead = client(source("ldap://127.0.0.1:$closedPort", mapOf("connectTimeoutMillis" to 500)))
        assertInstanceOf(DirectoryOutcome.Unavailable::class.java, dead.authenticate("alice", "alice-Pass-1".toCharArray()))
    }

    @Test fun plainLdapNeedsAnExplicitOptIn() {
        val plain = UserSource.of("corp", mapOf("type" to "ldap", "ldapUrls" to listOf("ldap://dir.example.test"), "userBaseDn" to "dc=example,dc=test"), 1)
        assertTrue(plain.problems().any { it.contains("clear text") }, plain.problems().toString())
        val mixed = UserSource.of("corp", mapOf("type" to "ldap", "ldapUrls" to listOf("ldaps://a.example.test", "ldap://b.example.test"), "userBaseDn" to "dc=x"), 1)
        assertTrue(mixed.problems().any { it.contains("same scheme") })
        val both = UserSource.of("corp", mapOf("type" to "ldap", "ldapUrls" to listOf("ldaps://a.example.test"), "startTls" to true, "userBaseDn" to "dc=x"), 1)
        assertTrue(both.problems().any { it.contains("startTls cannot") })
    }
}
