package org.oip.events

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.consumer.OffsetResetStrategy
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Optional

/**
 * Kafka source and handler, with fake clients behind [KafkaClients] and the client library's mock
 * consumer and producer for the adapter. No broker is needed.
 *
 */
class KafkaStreamTest {
    @TempDir lateinit var dir: Path

    private val clock = FixedClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val fake = FakeKafka()
    private val logs = mutableListOf<String>()
    private lateinit var config: FileConfigStore
    private lateinit var engine: EventStreamEngine

    private fun start(active: () -> Boolean = { true }) {
        config = FileConfigStore(dir.resolve("config"), SchemaRegistry(), clock)
        engine = EventStreamEngine(clock, null, config, inline = true, kafka = fake, actsWhen = active,
            log = { _, _, message -> logs += message })
        engine.attach()
        connection("plant", mapOf("bootstrapServers" to "broker:9092"))
    }

    @AfterEach fun stop() {
        if (::engine.isInitialized) engine.close()
        if (::config.isInitialized) config.close()
    }

    @Test fun groupSourceDeliversPayloadAndMetadata() {
        start()
        stream("orders", mapOf(
            "source" to mapOf("type" to "kafka", "connection" to "plant", "topics" to listOf("orders"), "groupId" to "line-1", "maxRecords" to 50),
            "handlers" to listOf(
                mapOf("type" to "logger", "message" to "{event.data.qty}"),
                mapOf("type" to "logger", "message" to "{event.metadata.topic}"),
                mapOf("type" to "logger", "message" to "{event.metadata.partition}"),
                mapOf("type" to "logger", "message" to "{event.metadata.offset}"),
                mapOf("type" to "logger", "message" to "{event.metadata.key}"),
                mapOf("type" to "logger", "message" to "{event.metadata.headers.format}"),
            ),
        ))
        fake.inbox += record("orders", 2, 41, "k1", """{"qty": 7}""", mapOf("format" to "json"))
        engine.pollSources()
        engine.pump()
        assertEquals(listOf("7", "orders", "2", "41", "k1", "json"), logs)
        val consumer = fake.consumers.single()
        assertEquals(listOf("orders"), consumer.subscribed)
        assertEquals("line-1", consumer.properties["group.id"])
        assertEquals("50", consumer.properties["max.poll.records"])
        assertEquals("latest", consumer.properties["auto.offset.reset"])
        assertEquals(1L, engine.stream("orders")["eventsReceived"])
        assertEquals(true, engine.stream("orders")["sourceConnected"])
    }

    @Test fun partitionSourceAssignsAndSeeks() {
        start()
        stream("replay", mapOf(
            "source" to mapOf("type" to "kafka", "connection" to "plant", "topic" to "audit", "partition" to 3, "offset" to 100, "offsetReset" to "earliest"),
            "encoder" to "string",
            "handlers" to listOf(mapOf("type" to "logger", "message" to "{event.data}")),
        ))
        fake.inbox += record("audit", 3, 100, null, "plain text", emptyMap())
        engine.pollSources()
        engine.pump()
        assertEquals(listOf("plain text"), logs)
        val consumer = fake.consumers.single()
        assertEquals(Triple("audit", 3, 100L), consumer.assigned)
        assertNull(consumer.properties["group.id"])
        assertEquals("earliest", consumer.properties["auto.offset.reset"])
    }

    @Test fun invalidKafkaSettingsMarkTheStreamInvalid() {
        start()
        stream("nowhere", mapOf(
            "source" to mapOf("type" to "kafka", "connection" to "missing", "topics" to listOf("t")),
            "handlers" to listOf(mapOf("type" to "logger", "message" to "x")),
        ))
        assertEquals(false, engine.stream("nowhere")["valid"])
        assertTrue(engine.stream("nowhere")["error"].toString().contains("missing"))
        stream("notopic", mapOf(
            "source" to mapOf("type" to "kafka", "connection" to "plant"),
            "handlers" to listOf(mapOf("type" to "logger", "message" to "x")),
        ))
        assertEquals(false, engine.stream("notopic")["valid"])
        stream("twotopics", mapOf(
            "source" to mapOf("type" to "kafka", "connection" to "plant", "topics" to listOf("a", "b"), "partition" to 0),
            "handlers" to listOf(mapOf("type" to "logger", "message" to "x")),
        ))
        assertEquals(false, engine.stream("twotopics")["valid"])
        stream("nohandlertopic", mapOf(
            "source" to mapOf("type" to "http"),
            "handlers" to listOf(mapOf("type" to "kafka", "connection" to "plant")),
        ))
        assertEquals(false, engine.stream("nohandlertopic")["valid"])
        assertTrue(fake.consumers.isEmpty())
    }

    @Test fun connectionPasswordMustBeASecretReference() {
        start()
        assertThrows(RuntimeException::class.java) {
            connection("bad", mapOf("bootstrapServers" to "b:9092", "password" to "hunter2"))
        }
        assertNull(config.get(ResourceId(KAFKA_MODULE, Scope.GATEWAY, KAFKA_CONNECTION_KIND, "bad")))
    }

    @Test fun sourceStopsWhenTheNodeIsNotActive() {
        var active = true
        start { active }
        stream("orders", mapOf(
            "source" to mapOf("type" to "kafka", "connection" to "plant", "topics" to listOf("orders")),
            "handlers" to listOf(mapOf("type" to "logger", "message" to "{event.data}")),
        ))
        engine.pollSources()
        assertEquals(1, fake.consumers.size)
        active = false
        fake.inbox += record("orders", 0, 1, null, "x", emptyMap())
        engine.pollSources()
        assertTrue(fake.consumers.single().closed)
        assertEquals(1, fake.inbox.size)
    }

    @Test fun sourceBacksOffWhileTheStreamHasAFullBacklog() {
        start()
        stream("flood", mapOf(
            "source" to mapOf("type" to "kafka", "connection" to "plant", "topics" to listOf("f"), "maxRecords" to 10_000),
            "handlers" to listOf(mapOf("type" to "logger", "message" to "x")),
        ))
        repeat(EventStreamEngine.MAX_PENDING) { fake.inbox += record("f", 0, it.toLong(), null, "x", emptyMap()) }
        fake.inbox += record("f", 0, 99_999, null, "last", emptyMap())
        fake.batch = EventStreamEngine.MAX_PENDING
        engine.pollSources()
        val polls = fake.consumers.single().polls
        engine.pollSources()
        assertEquals(polls, fake.consumers.single().polls)
        engine.pump()
        engine.pollSources()
        assertEquals(polls + 1, fake.consumers.single().polls)
        assertTrue(fake.inbox.isEmpty())
    }

    @Test fun connectionChangeRestartsTheSource() {
        start()
        stream("orders", mapOf(
            "source" to mapOf("type" to "kafka", "connection" to "plant", "topics" to listOf("orders")),
            "handlers" to listOf(mapOf("type" to "logger", "message" to "x")),
        ))
        engine.pollSources()
        connection("plant", mapOf("bootstrapServers" to "other:9092"))
        assertTrue(fake.consumers[0].closed)
        engine.pollSources()
        assertEquals(2, fake.consumers.size)
        assertEquals("other:9092", fake.consumers[1].connection.bootstrapServers)
    }

    @Test fun scheduledEnginePollsOnItsOwnThreadAndStopsOnClose() {
        config = FileConfigStore(dir.resolve("threaded"), SchemaRegistry(), clock)
        val seen = java.util.concurrent.LinkedBlockingQueue<String>()
        val threaded = EventStreamEngine(Clock.systemUTC(), null, config, kafka = fake, log = { _, _, message -> seen += message })
        threaded.attach()
        connection("plant", mapOf("bootstrapServers" to "broker:9092"))
        fake.inbox += record("orders", 0, 1, null, "hello", emptyMap())
        stream("orders", mapOf(
            "source" to mapOf("type" to "kafka", "connection" to "plant", "topics" to listOf("orders"), "pollTimeoutMillis" to 5),
            "encoder" to "string",
            "handlers" to listOf(mapOf("type" to "logger", "message" to "{event.data}")),
        ))
        threaded.start()
        assertEquals("hello", seen.poll(10, java.util.concurrent.TimeUnit.SECONDS))
        val pollThread = Thread.getAllStackTraces().keys.firstOrNull { it.name == "event-stream-kafka-orders" }
        assertTrue(pollThread != null)
        threaded.close()
        assertTrue(fake.consumers.single().closed)
        assertFalse(pollThread!!.isAlive)
    }

    @Test fun handlerStuckInRetryStopsPollingAndCommitsOnlyHandledRecords() {
        start()
        stream("relay", mapOf(
            "source" to mapOf("type" to "kafka", "connection" to "plant", "topics" to listOf("in"), "maxRecords" to 3),
            "buffer" to mapOf("debounceMillis" to 0, "maxWaitMillis" to 0, "maxQueueSize" to 4),
            "handlers" to listOf(mapOf(
                "type" to "kafka", "connection" to "plant", "topic" to "out",
                "failure" to "retry", "retryCount" to 5, "retryDelayMillis" to 1000,
            )),
        ))
        repeat(6) { fake.inbox += record("in", 0, it.toLong(), null, "v$it", emptyMap()) }
        fake.failSends = 1
        engine.pollSources()
        val consumer = fake.consumers.single()
        assertEquals("3", consumer.properties["max.poll.records"])
        engine.pump()
        repeat(3) { engine.pollSources() }
        assertEquals(1, consumer.polls)
        assertTrue(consumer.commits.isEmpty())
        assertEquals(3, fake.inbox.size)

        clock.advance(1000)
        engine.pump()
        assertEquals(3, fake.producers.single().sent.size)
        engine.pollSources()
        assertEquals(mapOf(("in" to 0) to 3L), consumer.commits.single())
        assertEquals(2, consumer.polls)
        engine.pump()
        engine.pollSources()
        assertEquals(mapOf(("in" to 0) to 6L), consumer.commits.last())
    }

    @Test fun unfinishedRecordsAreNotCommittedWhenTheNodeStepsDown() {
        var active = true
        start { active }
        stream("relay", mapOf(
            "source" to mapOf("type" to "kafka", "connection" to "plant", "topics" to listOf("in")),
            "handlers" to listOf(mapOf("type" to "kafka", "connection" to "plant", "topic" to "out", "failure" to "retry", "retryCount" to 5)),
        ))
        repeat(2) { fake.inbox += record("in", 1, 10L + it, null, "v", emptyMap()) }
        fake.failSends = 10
        engine.pollSources()
        engine.pump()
        active = false
        engine.pump()
        engine.pollSources()
        val consumer = fake.consumers.single()
        assertTrue(consumer.closed)
        assertTrue(consumer.commits.isEmpty())
    }

    @Test fun retiredSourceCannotFeedItsReplacement() {
        start()
        val spec = mapOf(
            "source" to mapOf("type" to "kafka", "connection" to "plant", "topics" to listOf("in")),
            "encoder" to "string",
            "handlers" to listOf(mapOf("type" to "logger", "message" to "{event.data}")),
        )
        stream("s", spec)
        engine.pollSources()
        fake.inbox += record("in", 0, 0, null, "stale", emptyMap())
        var replaced = false
        fake.onPoll = {
            if (!replaced) {
                replaced = true
                fake.onPoll = {}
                stream("s", spec + ("handlers" to listOf(mapOf("type" to "logger", "message" to "new:{event.data}"))))
            }
        }
        engine.pollSources()
        engine.pump()
        assertTrue(logs.isEmpty(), logs.toString())
        assertTrue(fake.consumers[0].closed)
    }

    @Test fun bytesPassFromKafkaToKafkaUnchanged() {
        start()
        val mock = MockProducer(true, ByteArraySerializer(), ByteArraySerializer())
        fake.producerFactory = { ApacheProducer(mock) }
        stream("raw", mapOf(
            "source" to mapOf("type" to "kafka", "connection" to "plant", "topics" to listOf("in")),
            "encoder" to "bytes",
            "handlers" to listOf(mapOf("type" to "kafka", "connection" to "plant", "topic" to "out")),
        ))
        val payload = byteArrayOf(0xFF.toByte(), 0x00, 0xC3.toByte(), 0x28, 0x80.toByte())
        fake.inbox += KafkaRecord("in", 0, 0, 0, null, payload, emptyMap())
        engine.pollSources()
        engine.pump()
        assertArrayEquals(payload, mock.history().single().value())
    }

    @Test fun credentialPropertiesMustBeSecretReferences() {
        start()
        assertThrows(RuntimeException::class.java) {
            connection("tls", mapOf("bootstrapServers" to "b:9093", "properties" to mapOf("ssl.keystore.password" to "plain")))
        }
        connection("tls", mapOf("bootstrapServers" to "b:9093", "securityProtocol" to "SSL",
            "properties" to mapOf("ssl.keystore.password" to "secret://ks-pw", "ssl.keystore.location" to "/k.p12")))
        assertThrows(RuntimeException::class.java) {
            stream("h", mapOf("source" to mapOf("type" to "http"), "handlers" to listOf(mapOf(
                "type" to "kafka", "connection" to "tls", "topic" to "t",
                "properties" to mapOf("sasl.jaas.config" to "LoginModule required password=\"x\";"),
            ))))
        }
        stream("g", mapOf(
            "source" to mapOf("type" to "kafka", "connection" to "tls", "topics" to listOf("t"), "properties" to mapOf("group.id" to "other")),
            "handlers" to listOf(mapOf("type" to "logger", "message" to "x")),
        ))
        assertEquals(false, engine.stream("g")["valid"])
        assertTrue(engine.stream("g")["error"].toString().contains("group.id"))
        val clients = ApacheKafkaClients { ref -> if (ref == "secret://ks-pw") "resolved" else error(ref) }
        val connection = compileKafkaConnection("tls", mapOf("bootstrapServers" to "b:9093",
            "properties" to mapOf("ssl.keystore.password" to "secret://ks-pw")))
        assertEquals("resolved", clients.properties(connection, emptyMap())["ssl.keystore.password"])
        assertThrows(IllegalArgumentException::class.java) {
            compileKafkaConnection("c", mapOf("bootstrapServers" to "b", "properties" to mapOf("ssl.key.password" to "plain")))
        }
    }

    @Test fun saslNeedsAMechanism() {
        start()
        connection("sasl", mapOf("bootstrapServers" to "b:9093", "securityProtocol" to "SASL_SSL", "username" to "u"))
        stream("s", mapOf(
            "source" to mapOf("type" to "kafka", "connection" to "sasl", "topics" to listOf("t")),
            "handlers" to listOf(mapOf("type" to "logger", "message" to "x")),
        ))
        assertEquals(false, engine.stream("s")["valid"])
        assertTrue(engine.stream("s")["error"].toString().contains("SASL mechanism"), engine.stream("s")["error"].toString())
    }

    @Test fun removedHandlerClosesItsProducer() {
        start()
        stream("out", mapOf(
            "source" to mapOf("type" to "http"),
            "handlers" to listOf(mapOf("type" to "kafka", "connection" to "plant", "topic" to "t")),
        ))
        engine.publishHttp("out", "1", emptyMap())
        engine.pump()
        stream("out", mapOf(
            "source" to mapOf("type" to "http"),
            "handlers" to listOf(mapOf("type" to "logger", "message" to "x")),
        ))
        assertTrue(fake.producers.single().closed)
    }

    @Test fun lz4RecordBatchesRoundTripWithTheReplacementLibrary() {
        val value = ByteArray(4096) { (it % 7).toByte() }
        val records = org.apache.kafka.common.record.MemoryRecords.withRecords(
            org.apache.kafka.common.compress.Compression.lz4().build(),
            org.apache.kafka.common.record.SimpleRecord(1L, "k".toByteArray(), value),
        )
        val batch = records.batches().single()
        assertEquals(org.apache.kafka.common.record.CompressionType.LZ4, batch.compressionType())
        assertTrue(records.sizeInBytes() < value.size)
        val read = records.records().single()
        val out = ByteArray(read.valueSize())
        read.value().get(out)
        assertArrayEquals(value, out)
    }

    @Test fun handlerProducesKeyValueHeadersAndPartition() {
        start()
        stream("out", mapOf(
            "source" to mapOf("type" to "http"),
            "handlers" to listOf(mapOf(
                "type" to "kafka",
                "connection" to "plant",
                "topic" to "{event.data.topic}",
                "key" to "{event.data.id}",
                "partition" to "{event.data.line}",
                "headers" to mapOf("origin" to "gateway"),
                "properties" to mapOf("acks" to "all"),
            )),
        ))
        assertTrue(engine.publishHttp("out", mapOf("id" to "A-1", "line" to 2, "topic" to "line-2"), emptyMap()))
        assertTrue(engine.publishHttp("out", mapOf("id" to "A-2", "line" to 2, "topic" to "line-2"), emptyMap()))
        engine.pump()
        val producer = fake.producers.single()
        assertEquals(mapOf("acks" to "all"), producer.properties)
        assertEquals(2, producer.sent.size)
        val first = producer.sent[0]
        assertEquals("line-2", first.topic)
        assertEquals(2, first.partition)
        assertEquals("A-1", first.key!!.toString(StandardCharsets.UTF_8))
        assertEquals("""{"id":"A-1","line":2,"topic":"line-2"}""", first.value.toString(StandardCharsets.UTF_8))
        assertEquals("gateway", first.headers["origin"]!!.toString(StandardCharsets.UTF_8))
        assertEquals(2L, engine.stream("out")["handlerExecutions"])
    }

    @Test fun handlerFailureRetriesThenSends() {
        start()
        stream("out", mapOf(
            "source" to mapOf("type" to "http"),
            "handlers" to listOf(mapOf(
                "type" to "kafka", "connection" to "plant", "topic" to "t",
                "failure" to "retry", "retryCount" to 2, "retryDelayMillis" to 1000,
            )),
        ))
        fake.failSends = 1
        engine.publishHttp("out", "v", emptyMap())
        engine.pump()
        assertEquals(0, fake.producers.single().sent.size)
        clock.advance(1000)
        engine.pump()
        assertEquals(1, fake.producers.single().sent.size)
        assertTrue(logs.any { it.contains("broker down") })
    }

    @Test fun connectionChangeClosesCachedProducers() {
        start()
        stream("out", mapOf(
            "source" to mapOf("type" to "http"),
            "handlers" to listOf(mapOf("type" to "kafka", "connection" to "plant", "topic" to "t")),
        ))
        engine.publishHttp("out", "1", emptyMap())
        engine.pump()
        engine.publishHttp("out", "2", emptyMap())
        engine.pump()
        assertEquals(1, fake.producers.size)
        connection("plant", mapOf("bootstrapServers" to "next:9092"))
        assertTrue(fake.producers[0].closed)
        engine.publishHttp("out", "3", emptyMap())
        engine.pump()
        assertEquals(2, fake.producers.size)
        engine.close()
        assertTrue(fake.producers[1].closed)
    }

    @Test fun adapterMapsMockConsumerRecords() {
        val mock = MockConsumer<ByteArray, ByteArray>(OffsetResetStrategy.EARLIEST)
        val consumer = ApacheConsumer(mock)
        consumer.assign("t", 0, 5)
        mock.updateBeginningOffsets(mapOf(TopicPartition("t", 0) to 0L))
        val headers = RecordHeaders(listOf(RecordHeader("format", "json".toByteArray())))
        mock.addRecord(ConsumerRecord("t", 0, 5, 1234L, TimestampType.CREATE_TIME, 2, 3, "k".toByteArray(), "v".toByteArray(), headers, Optional.empty()))
        val records = consumer.poll(10)
        assertEquals(1, records.size)
        val r = records[0]
        assertEquals(5L, r.offset)
        assertEquals(1234L, r.timestamp)
        assertArrayEquals("v".toByteArray(), r.value)
        assertEquals(mapOf("format" to "json"), r.headers)
        consumer.wakeup()
        assertEquals(emptyList<KafkaRecord>(), consumer.poll(10))
        consumer.close()
    }

    @Test fun adapterSendsThroughMockProducerAndReportsErrors() {
        val ok = MockProducer(true, ByteArraySerializer(), ByteArraySerializer())
        ApacheProducer(ok).send(KafkaOutgoing("t", 1, 99L, "k".toByteArray(), "v".toByteArray(), mapOf("h" to "x".toByteArray())), 1000)
        val sent = ok.history().single()
        assertEquals("t", sent.topic())
        assertEquals(1, sent.partition())
        assertArrayEquals("v".toByteArray(), sent.value())
        assertArrayEquals("x".toByteArray(), sent.headers().lastHeader("h").value())

        val failing = MockProducer(false, ByteArraySerializer(), ByteArraySerializer())
        val producer = ApacheProducer(failing)
        val thread = Thread {
            val deadline = System.nanoTime() + 5_000_000_000
            while (!failing.errorNext(RuntimeException("not leader")) && System.nanoTime() < deadline) Thread.sleep(5)
        }
        thread.start()
        val error = assertThrows(IllegalStateException::class.java) {
            producer.send(KafkaOutgoing("t", null, null, null, "v".toByteArray(), emptyMap()), 5000)
        }
        thread.join()
        assertTrue(error.message!!.contains("not leader"), error.message)
    }

    @Test fun clientSettingsCarrySaslLoginAndKeepByteSerializers() {
        val clients = ApacheKafkaClients { ref -> if (ref == "secret://kafka-pw") "p\"w" else error("unexpected $ref") }
        val connection = compileKafkaConnection("c", mapOf(
            "bootstrapServers" to "b1:9093,b2:9093",
            "securityProtocol" to "SASL_SSL",
            "saslMechanism" to "SCRAM-SHA-512",
            "username" to "gw",
            "password" to "secret://kafka-pw",
            "properties" to mapOf("value.serializer" to "x", "linger.ms" to "5"),
        ))
        val props = clients.properties(connection, mapOf("acks" to "all"))
        assertEquals("b1:9093,b2:9093", props["bootstrap.servers"])
        assertEquals("SASL_SSL", props["security.protocol"])
        assertEquals("SCRAM-SHA-512", props["sasl.mechanism"])
        assertEquals("5", props["linger.ms"])
        assertEquals("all", props["acks"])
        assertNull(props["value.serializer"])
        val jaas = props["sasl.jaas.config"].toString()
        assertTrue(jaas.startsWith("org.apache.kafka.common.security.scram.ScramLoginModule required"))
        assertTrue(jaas.contains("username=\"gw\""))
        assertTrue(jaas.contains("password=\"p\\\"w\""))
        assertThrows(IllegalArgumentException::class.java) {
            compileKafkaConnection("c", mapOf("bootstrapServers" to "b", "password" to "plaintext"))
        }
    }

    private fun connection(name: String, spec: Map<String, Any?>) {
        val version = config.get(ResourceId(KAFKA_MODULE, Scope.GATEWAY, KAFKA_CONNECTION_KIND, name))?.metadata?.version ?: 0
        config.put(Scope.GATEWAY, Resource(KAFKA_CONNECTION_KIND, Metadata(name, KAFKA_MODULE, version = version), spec), "test")
    }

    private fun stream(name: String, spec: Map<String, Any?>) {
        val full = LinkedHashMap(spec)
        full.putIfAbsent("buffer", mapOf("debounceMillis" to 0, "maxWaitMillis" to 0))
        val version = config.get(ResourceId(EVENTS_MODULE, Scope.GATEWAY, STREAM_KIND, name))?.metadata?.version ?: 0
        config.put(Scope.GATEWAY, Resource(STREAM_KIND, Metadata(name, EVENTS_MODULE, version = version), full), "test")
    }

    private fun record(topic: String, partition: Int, offset: Long, key: String?, value: String, headers: Map<String, String>) =
        KafkaRecord(topic, partition, offset, 1_700_000_000_000, key?.toByteArray(), value.toByteArray(), headers)
}

private class FakeKafka : KafkaClients {
    val inbox = ArrayDeque<KafkaRecord>()
    var batch = 500
    var failSends = 0
    var onPoll: () -> Unit = {}
    var producerFactory: ((Map<String, String>) -> KafkaProducerClient)? = null
    val consumers: MutableList<FakeConsumer> = java.util.Collections.synchronizedList(mutableListOf())
    val producers = mutableListOf<FakeProducer>()

    override fun consumer(connection: KafkaConnection, properties: Map<String, String>): KafkaConsumerClient =
        FakeConsumer(connection, properties).also { consumers += it }

    override fun producer(connection: KafkaConnection, properties: Map<String, String>): KafkaProducerClient =
        producerFactory?.invoke(properties) ?: FakeProducer(properties).also { producers += it }

    inner class FakeConsumer(val connection: KafkaConnection, val properties: Map<String, String>) : KafkaConsumerClient {
        var subscribed: List<String>? = null
        var assigned: Triple<String, Int, Long?>? = null
        var polls = 0
        val commits = mutableListOf<Map<Pair<String, Int>, Long>>()
        @Volatile var closed = false
        override fun subscribe(topics: List<String>) { subscribed = topics }
        override fun assign(topic: String, partition: Int, offset: Long?) { assigned = Triple(topic, partition, offset) }
        override fun poll(timeoutMillis: Long): List<KafkaRecord> {
            check(!closed)
            polls++
            onPoll()
            val out = ArrayList<KafkaRecord>()
            val max = minOf(batch, properties["max.poll.records"]?.toInt() ?: batch)
            synchronized(inbox) { while (inbox.isNotEmpty() && out.size < max) out += inbox.removeFirst() }
            return out
        }
        override fun commit(offsets: Map<Pair<String, Int>, Long>) { commits += offsets }
        override fun wakeup() {}
        override fun close() { closed = true }
    }

    inner class FakeProducer(val properties: Map<String, String>) : KafkaProducerClient {
        val sent = mutableListOf<KafkaOutgoing>()
        var closed = false
        override fun send(record: KafkaOutgoing, timeoutMillis: Long) {
            check(!closed)
            if (failSends > 0) {
                failSends--
                throw IllegalStateException("broker down")
            }
            sent += record
        }
        override fun close() { closed = true }
    }
}

private class FixedClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
    override fun millis(): Long = now.toEpochMilli()
    fun advance(millis: Long) { now = now.plusMillis(millis) }
}
