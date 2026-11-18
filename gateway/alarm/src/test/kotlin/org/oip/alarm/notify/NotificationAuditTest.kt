package org.oip.alarm.notify

import org.junit.jupiter.api.Assertions.assertEquals
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
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Every notification the gateway sends for an alarm is reported to the audit sink, so it can be recorded to the audit
 * profile. Doc source: FEATURES ALA-036.
 */
class NotificationAuditTest {
    @TempDir lateinit var tmp: Path

    private class Clk : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.parse("2026-06-01T09:00:00Z")
    }

    private fun run(transport: MailTransport, body: (TagEngine, MutableList<NotificationAudit>) -> Unit) {
        val clock = Clk()
        val registry = SchemaRegistry()
        FileConfigStore(tmp.resolve("config"), registry).use { store ->
            val tags = TagEngine(clock)
            val alarms = AlarmEngine(clock, AlarmJournal(tmp.resolve("journal.sqlite"), clock = clock))
            alarms.attach(tags)
            val audited = ArrayList<NotificationAudit>()
            try {
                NotificationService(
                    clock, store, alarms,
                    users = { listOf(DirectoryUser("ada", "Ada", "ada@example.com", "", true), DirectoryUser("bob", "Bob", "bob@fail.example", "", true)) },
                    secrets = { "" },
                    transport = transport,
                ).use { service ->
                    service.attach()
                    service.auditSink = { audited += it }
                    store.put(Scope.GATEWAY, Resource(NotifyKinds.EMAIL, Metadata("mail", NOTIFY_MODULE), mapOf(
                        "hostname" to "127.0.0.1", "fromAddress" to "alarms@example.com",
                    )))
                    store.put(Scope.GATEWAY, Resource(NotifyKinds.ROSTER, Metadata("operators", NOTIFY_MODULE), mapOf("users" to listOf("ada", "bob"))))
                    store.put(Scope.GATEWAY, Resource(NotifyKinds.PIPELINE, Metadata("page", NOTIFY_MODULE), mapOf(
                        "blocks" to listOf(
                            mapOf("id" to "s", "type" to "start", "next" to "n"),
                            mapOf("id" to "n", "type" to "notification", "profile" to "mail", "roster" to "operators", "subject" to "{name} on {Area}"),
                        ),
                    )))
                    tags.configure(TagConfig.fromSpec(linkedMapOf(
                        "path" to "tank/level", "type" to "memory", "dataType" to "Double", "value" to 0.0,
                        "alarms" to listOf(linkedMapOf(
                            "name" to "High", "mode" to "AboveValue", "setpointA" to 100, "priority" to "High",
                            "activePipeline" to "page", "associatedData" to linkedMapOf("Area" to "Mixing"),
                        )),
                    )))
                    body(tags, audited)
                }
            } finally {
                alarms.close()
            }
        }
    }

    @Test fun eachSentAndEachFailedNotificationIsReportedToTheAuditSink() {
        val sent = ArrayList<String>()
        val transport = MailTransport { _, mail, _ ->
            if (mail.to.endsWith("fail.example")) throw SmtpException("relay refused")
            sent += mail.to
        }
        run(transport) { tags, audited ->
            tags.write("[default]tank/level", 150)
            assertEquals(listOf("ada@example.com"), sent)
            assertEquals(2, audited.size, audited.toString())
            val ok = audited.single { it.recipient == "ada" }
            assertEquals("sent", ok.outcome)
            assertEquals("mail", ok.profile)
            assertEquals("High on Mixing", ok.subject)
            assertEquals("High", ok.alarm)
            assertTrue(ok.source.endsWith(":/alm:High"), ok.source)
            val failed = audited.single { it.recipient == "bob" }
            assertEquals("failed", failed.outcome)
            assertTrue(failed.detail.contains("relay refused"), failed.detail)
        }
    }
}
