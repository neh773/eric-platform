package org.oip.network

import org.oip.gateway.Json
import org.oip.gateway.PrivateFiles
import org.oip.gateway.TlsCertificates
import org.oip.gateway.TlsMaterial
import java.nio.file.Path
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Local gateway-network identity and the peer certificates waiting for, or holding, approval.
 *
 * An unknown peer certificate is stored as pending and the handshake is rejected. Approving it
 * allows the next attempt. Denying it keeps the peer out. The private key stays in this directory
 * and is not part of the public status document.
 *
 * (FEATURES GAN-003).
 */
internal class LinkIdentity(private val directory: Path, private val commonName: String) {
    @Volatile var material: TlsMaterial = loadOrCreate()
        private set

    fun fingerprint(): String = material.fingerprint()

    fun regenerate(): TlsMaterial {
        val next = mint()
        write(next)
        material = next
        return next
    }

    private fun loadOrCreate(): TlsMaterial {
        val cert = directory.resolve("server.crt")
        val key = directory.resolve("server.key")
        if (cert.exists() && key.exists()) {
            PrivateFiles.restrictKey(key)
            return TlsCertificates.parsePem(cert.readText(), key.readText())
        }
        val created = mint()
        write(created)
        return created
    }

    private fun mint(): TlsMaterial = TlsCertificates.generateMutual(commonName, listOf(commonName, "localhost"))

    private fun write(value: TlsMaterial) {
        PrivateFiles.directory(directory)
        PrivateFiles.writeReadable(directory.resolve("server.crt"), TlsCertificates.certificatePem(value.chain).toByteArray())
        PrivateFiles.writeKey(directory.resolve("server.key"), TlsCertificates.privateKeyPem(value.privateKey).toByteArray())
        PrivateFiles.restrictKey(directory.resolve("server.key"))
        PrivateFiles.writeReadable(directory.resolve("server.source"), "generated".toByteArray())
    }
}

internal data class PeerRecord(
    val fingerprint: String,
    val subject: String,
    val serial: String,
    val pem: String,
    val state: String,
    val connection: String?,
    val gatewayName: String?,
    val seenMillis: Long,
) {
    fun publicView(): Map<String, Any?> = linkedMapOf(
        "fingerprint" to fingerprint,
        "subject" to subject,
        "serial" to serial,
        "state" to state,
        "connection" to connection,
        "gatewayName" to gatewayName,
        "seenMillis" to seenMillis,
    )
}

internal class PeerTrust(private val root: Path) {
    private val lock = Any()
    private val records = ConcurrentHashMap<String, PeerRecord>()
    /** Fingerprints an administrator named before the first handshake (EAM-008 first-boot files), by direction. */
    private val pins = ConcurrentHashMap.newKeySet<String>()
    private val pinFile = root.resolve("pins.json")

    init {
        root.createDirectories()
        if (pinFile.exists()) {
            runCatching { (Json.mapper.readValue(pinFile.readText(), List::class.java) as List<*>).forEach { pins += it.toString() } }
        }
        for (direction in listOf(DIR_INCOMING, DIR_OUTGOING)) {
            val dir = root.resolve(direction)
            if (!dir.exists()) continue
            dir.listDirectoryEntries("*.json").forEach { file ->
                val loaded = read(file) ?: return@forEach
                records[key(direction, loaded.fingerprint)] = loaded
            }
        }
    }

    /** Remember a certificate presented during a handshake. Approved and denied states stick. */
    fun observe(direction: String, certificate: X509Certificate, connection: String?): String {
        val fingerprint = TlsCertificates.fingerprint(certificate)
        synchronized(lock) {
            val existing = records[key(direction, fingerprint)]
            if (existing != null && existing.state != STATE_PENDING) return existing.state
            val pinned = key(direction, fingerprint) in pins
            val record = PeerRecord(
                fingerprint = fingerprint,
                subject = certificate.subjectX500Principal.name,
                serial = certificate.serialNumber.toString(16),
                pem = TlsCertificates.certificatePem(listOf(certificate)),
                state = if (pinned) STATE_APPROVED else STATE_PENDING,
                connection = connection ?: existing?.connection,
                gatewayName = existing?.gatewayName,
                seenMillis = System.currentTimeMillis(),
            )
            records[key(direction, fingerprint)] = record
            write(direction, record)
            return record.state
        }
    }

    /**
     * Approve [fingerprint] for [direction] before it is ever presented. The first handshake that shows it is
     * stored as approved. A record already denied stays denied.
     */
    fun pin(direction: String, fingerprint: String) {
        synchronized(lock) {
            pins += key(direction, fingerprint)
            PrivateFiles.writeReadable(pinFile, Json.mapper.writeValueAsBytes(pins.sorted()))
            val existing = records[key(direction, fingerprint)]
            if (existing != null && existing.state == STATE_PENDING) {
                val next = existing.copy(state = STATE_APPROVED)
                records[key(direction, fingerprint)] = next
                write(direction, next)
            }
        }
    }

    fun approve(direction: String, fingerprint: String): PeerRecord = setState(direction, fingerprint, STATE_APPROVED)

    fun deny(direction: String, fingerprint: String): PeerRecord = setState(direction, fingerprint, STATE_DENIED)

    fun state(direction: String, fingerprint: String): String? = records[key(direction, fingerprint)]?.state

    fun list(direction: String): List<PeerRecord> =
        records.filterKeys { it.startsWith("$direction/") }.values.sortedBy { it.seenMillis }

    fun latestOutgoing(connection: String): PeerRecord? =
        list(DIR_OUTGOING).filter { it.connection == connection }.maxByOrNull { it.seenMillis }

    fun noteGatewayName(direction: String, fingerprint: String, gatewayName: String) {
        synchronized(lock) {
            val existing = records[key(direction, fingerprint)] ?: return
            if (existing.gatewayName == gatewayName) return
            val next = existing.copy(gatewayName = gatewayName)
            records[key(direction, fingerprint)] = next
            write(direction, next)
        }
    }

    private fun setState(direction: String, fingerprint: String, state: String): PeerRecord {
        synchronized(lock) {
            val existing = records[key(direction, fingerprint)]
                ?: throw NoSuchElementException("no $direction certificate $fingerprint")
            val next = existing.copy(state = state)
            records[key(direction, fingerprint)] = next
            write(direction, next)
            return next
        }
    }

    private fun write(direction: String, record: PeerRecord) {
        val dir = root.resolve(direction)
        PrivateFiles.directory(dir)
        val body = linkedMapOf<String, Any?>(
            "fingerprint" to record.fingerprint,
            "subject" to record.subject,
            "serial" to record.serial,
            "pem" to record.pem,
            "state" to record.state,
            "connection" to record.connection,
            "gatewayName" to record.gatewayName,
            "seenMillis" to record.seenMillis,
        )
        PrivateFiles.writeReadable(dir.resolve("${record.fingerprint}.json"), Json.mapper.writeValueAsBytes(body))
    }

    private fun read(file: Path): PeerRecord? = try {
        @Suppress("UNCHECKED_CAST")
        val map = Json.mapper.readValue(file.readText(), Map::class.java) as Map<String, Any?>
        val fingerprint = map["fingerprint"] as? String ?: return null
        PeerRecord(
            fingerprint = fingerprint,
            subject = map["subject"] as? String ?: "",
            serial = map["serial"] as? String ?: "",
            pem = map["pem"] as? String ?: "",
            state = map["state"] as? String ?: STATE_PENDING,
            connection = map["connection"] as? String,
            gatewayName = map["gatewayName"] as? String,
            seenMillis = (map["seenMillis"] as? Number)?.toLong() ?: 0L,
        )
    } catch (_: Exception) {
        null
    }

    private fun key(direction: String, fingerprint: String) = "$direction/$fingerprint"
}

internal class PolicyTable(private val file: Path) {
    private val lock = Any()
    private val policies = ArrayList<ServicePolicy>()

    init {
        if (file.exists()) {
            try {
                @Suppress("UNCHECKED_CAST")
                val list = Json.mapper.readValue(file.readText(), List::class.java) as List<Map<String, Any?>>
                list.forEach { policies += policyFrom(it) }
            } catch (_: Exception) {
                policies.clear()
            }
        }
    }

    fun put(policy: ServicePolicy) {
        synchronized(lock) {
            policies.removeIf { it.peer == policy.peer && it.service == policy.service }
            policies += policy
            save()
        }
    }

    fun remove(peer: String, service: String): Boolean {
        synchronized(lock) {
            val removed = policies.removeIf { it.peer == peer && it.service == service }
            if (removed) save()
            return removed
        }
    }

    fun list(): List<ServicePolicy> = synchronized(lock) { policies.toList() }

    /**
     * The most specific matching policy wins: certificate fingerprint, then gateway name, then `*`.
     * With no policy, tag access is allowed and other services are not. That matches the default
     * zone's read-only tag access for a peer that has been approved onto the network.
     *
     * `allow` is query and storage. `read` is query only, so a history, audit, or alarm-journal
     * push is rejected. Storage is how a hub accepts edge sync.
     */
    fun decide(fingerprint: String, gatewayName: String?, service: String): ServiceAccess {
        val match = synchronized(lock) {
            policies.firstOrNull { it.service == service && it.peer.equals(fingerprint, ignoreCase = true) }
                ?: policies.firstOrNull { it.service == service && gatewayName != null && it.peer.equals(gatewayName, ignoreCase = true) }
                ?: policies.firstOrNull { it.service == service && it.peer == "*" }
        }
        if (match == null) return ServiceAccess(allowed = service == SERVICE_TAGS, roles = emptySet())
        val allowed = match.access.equals("allow", true) || match.access.equals("read", true)
        val allow = match.access.equals("allow", true)
        return ServiceAccess(allowed, match.roles.toSet(), storage = allow, canWrite = allow)
    }

    private fun save() {
        val body = policies.map {
            linkedMapOf<String, Any?>(
                "peer" to it.peer,
                "service" to it.service,
                "access" to it.access,
                "roles" to it.roles,
            )
        }
        PrivateFiles.writeReadable(file, Json.mapper.writeValueAsBytes(body))
    }

    private fun policyFrom(map: Map<String, Any?>): ServicePolicy {
        val roles = (map["roles"] as? Collection<*>)?.mapNotNull { it as? String } ?: emptyList()
        return ServicePolicy(
            peer = map["peer"] as? String ?: "*",
            service = map["service"] as? String ?: SERVICE_TAGS,
            access = map["access"] as? String ?: "deny",
            roles = roles,
        )
    }
}
