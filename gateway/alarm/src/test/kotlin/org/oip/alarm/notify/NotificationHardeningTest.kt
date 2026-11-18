package org.oip.alarm.notify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.alarm.AlarmEngine
import org.oip.alarm.AlarmJournal
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.config.ValidationException
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Review-3 F9: acknowledgement links expire and survive a restart, addresses with control characters are refused
 * at save time and at send time, and a profile that sends its password in clear text is warned about.
 * Doc source: FEATURES ALA-012.
 */
class NotificationHardeningTest {
    @TempDir lateinit var tmp: Path

    private val clock = Manual(Instant.parse("2026-07-01T00:00:00Z"))

    private fun link(eventId: String = "e1") = AckLinks.Link(eventId, "ada", "src", "High", "tank/High", false, clock.millis())

    @Test fun aLinkExpiresAfterItsTimeToLive() {
        AckLinks(clock, ttl = Duration.ofHours(24)).use { links ->
            links.add("token-1", link())
            clock.now = clock.now.plus(Duration.ofHours(24)).minusMillis(1)
            assertNotNull(links.find("token-1"))
            clock.now = clock.now.plusMillis(1)
            assertNull(links.find("token-1"), "expired")
            assertEquals(0, links.size)
        }
    }

    @Test fun aLinkExpiresShortlyAfterItsEventLeavesTheLiveSet() {
        AckLinks(clock, grace = Duration.ofMinutes(10)).use { links ->
            links.add("token-1", link("e1"))
            links.add("token-2", link("e2"))
            var live = setOf("e1", "e2")
            links.purge { it in live }
            live = setOf("e2")
            links.purge { it in live }
            clock.now = clock.now.plus(Duration.ofMinutes(9))
            links.purge { it in live }
            assertNotNull(links.find("token-1"), "the confirmation page still loads inside the grace")
            clock.now = clock.now.plus(Duration.ofMinutes(1))
            links.purge { it in live }
            assertNull(links.find("token-1"))
            assertNotNull(links.find("token-2"), "a live event's link is kept")
        }
    }

    @Test fun linksSurviveARestartAndOnlyADigestIsStored() {
        val file = tmp.resolve("ack-links.sqlite")
        AckLinks(clock, file).use { it.add("secret-token-value", link()) }
        AckLinks(clock, file).use { links ->
            assertEquals("e1", links.find("secret-token-value")?.eventId)
            assertNull(links.find("another-token"))
        }
        val bytes = String(Files.readAllBytes(file), Charsets.ISO_8859_1)
        assertFalse(bytes.contains("secret-token-value"), "the token itself is not stored")
    }

    @Test fun addressesWithControlCharactersAreRefusedWhenSaved() {
        val registry = SchemaRegistry()
        NotifySchemas.register(registry)
        FileConfigStore(tmp.resolve("config"), registry).use { store ->
            assertThrows(ValidationException::class.java) {
                store.put(Scope.GATEWAY, Resource(NotifyKinds.EMAIL, Metadata("mail", NOTIFY_MODULE), mapOf(
                    "fromAddress" to "alarms@example.com\r\nRCPT TO:<x@evil.example>",
                )))
            }
            assertThrows(ValidationException::class.java) {
                store.put(Scope.GATEWAY, Resource(NotifyKinds.CONTACT, Metadata("ada", NOTIFY_MODULE), mapOf(
                    "contacts" to listOf(mapOf("type" to "email", "value" to "ada@example.com\nBcc: x@evil.example")),
                )))
            }
            store.put(Scope.GATEWAY, Resource(NotifyKinds.EMAIL, Metadata("mail", NOTIFY_MODULE), mapOf("fromAddress" to "alarms@example.com")))
        }
    }

    @Test fun anAddressWithCrLfIsRefusedBeforeAnyConnection() {
        ServerSocket(0).use { server ->
            server.soTimeout = 500
            val endpoint = SmtpEndpoint(
                host = "127.0.0.1", port = server.localPort, useSsl = false, startTls = false, username = "",
                password = CharArray(0), timeoutMillis = 2_000, protocols = listOf("TLSv1.2"), debug = false,
            )
            val mail = OutboundMail(
                from = "alarms@example.com", to = "ada@example.com\r\nDATA", subject = "s", body = "b", testMode = false,
                eventId = "e", username = "ada", profile = "mail", ackLink = false, emailProfile = null, smtpProfile = null,
            )
            val error = assertThrows(SmtpException::class.java) { SmtpClient.send(endpoint, mail) {} }
            assertTrue(error.message!!.contains("control character"), error.message)
            assertThrows(java.net.SocketTimeoutException::class.java) { server.accept().close() }
        }
    }

    @Test fun clearTextPasswordsAreWarnedAbout() {
        val registry = SchemaRegistry()
        val logged = ArrayList<Pair<String, Map<String, Any?>>>()
        FileConfigStore(tmp.resolve("config"), registry).use { store ->
            val alarms = AlarmEngine(clock, AlarmJournal(tmp.resolve("journal.sqlite"), clock = clock))
            try {
                NotificationService(clock, store, alarms, users = { emptyList() }, secrets = { "" }, log = { m, f -> logged += m to f }).use { service ->
                    service.attach()
                    store.put(Scope.GATEWAY, Resource(NotifyKinds.SMTP, Metadata("plain", NOTIFY_MODULE), mapOf(
                        "hostname" to "relay.example", "username" to "mailer", "password" to "secret://mail",
                    )))
                    store.put(Scope.GATEWAY, Resource(NotifyKinds.SMTP, Metadata("tls", NOTIFY_MODULE), mapOf(
                        "hostname" to "relay.example", "username" to "mailer", "password" to "secret://mail", "startTls" to true,
                    )))
                    service.poll()
                }
            } finally {
                alarms.close()
            }
        }
        val warned = logged.filter { it.first.contains("clear text") }.map { it.second["profile"] }.toSet()
        assertEquals(setOf("plain"), warned, "$logged")
    }

    private class Manual(@Volatile var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
        override fun millis(): Long = now.toEpochMilli()
    }
}
