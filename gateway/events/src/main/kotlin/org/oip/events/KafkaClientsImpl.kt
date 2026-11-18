package org.oip.events

import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Properties
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Kafka clients from the Apache client library. [readSecret] resolves `secret://` references: the
 * connection password and any client property whose value is a reference (credential keys must be one).
 * Plaintext only goes into the client's settings, never into config or logs.
 *
 * Connection properties apply first, then the stage's own properties, then the byte-array serializers,
 * which cannot be overridden because the stream works on raw bytes. Offsets are committed by the
 * source after the stream has handled the records, so automatic commit is always off.
 */
class ApacheKafkaClients(private val readSecret: (reference: String) -> String) : KafkaClients {
    override fun consumer(connection: KafkaConnection, properties: Map<String, String>): KafkaConsumerClient {
        val props = properties(connection, properties)
        props.putIfAbsent(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
        props[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] = "false"
        return ApacheConsumer(withLoader { KafkaConsumer(props, ByteArrayDeserializer(), ByteArrayDeserializer()) })
    }

    override fun producer(connection: KafkaConnection, properties: Map<String, String>): KafkaProducerClient {
        val props = properties(connection, properties)
        props.putIfAbsent(ProducerConfig.MAX_BLOCK_MS_CONFIG, "10000")
        return ApacheProducer(withLoader { KafkaProducer(props, ByteArraySerializer(), ByteArraySerializer()) })
    }

    /** Client settings for [connection]. Visible for tests; the result holds the resolved password. */
    internal fun properties(connection: KafkaConnection, extra: Map<String, String>): Properties {
        val props = Properties()
        for ((key, value) in connection.properties + extra) {
            props[key] = if (isSecretReference(value)) readSecret(value) else value
        }
        props["bootstrap.servers"] = connection.bootstrapServers
        props.putIfAbsent("client.id", connection.clientId)
        props["security.protocol"] = connection.securityProtocol
        val mechanism = connection.saslMechanism
        if (connection.securityProtocol.startsWith("SASL") && mechanism != null) {
            props["sasl.mechanism"] = mechanism
            val user = connection.username.orEmpty()
            val password = connection.passwordRef?.let(readSecret).orEmpty()
            val module = if (mechanism == "PLAIN") {
                "org.apache.kafka.common.security.plain.PlainLoginModule"
            } else {
                "org.apache.kafka.common.security.scram.ScramLoginModule"
            }
            props["sasl.jaas.config"] = "$module required username=\"${jaas(user)}\" password=\"${jaas(password)}\";"
        }
        props.remove("key.serializer")
        props.remove("value.serializer")
        props.remove("key.deserializer")
        props.remove("value.deserializer")
        return props
    }

    private fun jaas(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    /** The client loads its plugins through the context class loader; use the one that loaded it. */
    private fun <T> withLoader(block: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = KafkaProducer::class.java.classLoader
        try {
            return block()
        } finally {
            thread.contextClassLoader = previous
        }
    }
}

/** Wraps a client consumer. Tests pass the library's mock consumer. */
class ApacheConsumer(private val consumer: Consumer<ByteArray, ByteArray>) : KafkaConsumerClient {
    override fun subscribe(topics: List<String>) = consumer.subscribe(topics)

    override fun assign(topic: String, partition: Int, offset: Long?) {
        val tp = TopicPartition(topic, partition)
        consumer.assign(listOf(tp))
        if (offset != null) consumer.seek(tp, offset)
    }

    override fun poll(timeoutMillis: Long): List<KafkaRecord> {
        val records = try {
            consumer.poll(Duration.ofMillis(timeoutMillis))
        } catch (_: WakeupException) {
            return emptyList()
        }
        return records.map { record ->
            val headers = LinkedHashMap<String, String>()
            for (header in record.headers()) {
                headers[header.key()] = header.value()?.toString(StandardCharsets.UTF_8) ?: ""
            }
            KafkaRecord(
                topic = record.topic(),
                partition = record.partition(),
                offset = record.offset(),
                timestamp = record.timestamp(),
                key = record.key(),
                value = record.value(),
                headers = headers,
            )
        }
    }

    override fun commit(offsets: Map<Pair<String, Int>, Long>) {
        if (offsets.isEmpty()) return
        consumer.commitSync(offsets.entries.associate { (tp, next) -> TopicPartition(tp.first, tp.second) to OffsetAndMetadata(next) })
    }

    override fun wakeup() = consumer.wakeup()

    override fun close() = consumer.close(Duration.ofSeconds(5))
}

/** Wraps a client producer. Tests pass the library's mock producer. */
class ApacheProducer(private val producer: Producer<ByteArray, ByteArray>) : KafkaProducerClient {
    override fun send(record: KafkaOutgoing, timeoutMillis: Long) {
        val headers = record.headers.map { (key, value) -> RecordHeader(key, value) }
        val outgoing = ProducerRecord(record.topic, record.partition, record.timestamp, record.key, record.value, headers)
        try {
            producer.send(outgoing).get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw IllegalStateException("kafka send to ${record.topic} failed: ${e.cause?.message ?: e.message}")
        } catch (_: TimeoutException) {
            throw IllegalStateException("kafka send to ${record.topic} timed out after $timeoutMillis ms")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("kafka send to ${record.topic} interrupted")
        } catch (e: org.apache.kafka.common.KafkaException) {
            throw IllegalStateException("kafka send to ${record.topic} failed: ${e.message}")
        }
    }

    override fun close() = producer.close(Duration.ofSeconds(5))
}
