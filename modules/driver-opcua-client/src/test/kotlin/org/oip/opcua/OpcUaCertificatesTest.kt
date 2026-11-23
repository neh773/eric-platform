package org.oip.opcua

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OpcUaCertificatesTest {
    @TempDir lateinit var tmp: Path

    @Test fun `the client certificate is made once and kept in the PKI folder`() {
        val first = OpcUaCertificates.loadOrCreate(tmp, "urn:eric:test:client", "ERIC client")
        val again = OpcUaCertificates.loadOrCreate(tmp, "urn:eric:test:client", "ERIC client")
        assertArrayEquals(first.certificate.encoded, again.certificate.encoded)
        val stored = Files.list(tmp.resolve("own")).use { it.toList() }
        assertTrue(stored.single().fileName.toString().endsWith(".p12"))
        // A new process (another copy of the folder path, so the in-memory cache misses) reads the same certificate.
        val copy = tmp.resolve("copy")
        Files.createDirectories(copy.resolve("own"))
        Files.copy(stored.single(), copy.resolve("own").resolve(stored.single().fileName))
        val reloaded = OpcUaCertificates.loadOrCreate(copy, "urn:eric:test:client", "ERIC client")
        assertArrayEquals(first.certificate.encoded, reloaded.certificate.encoded)
        assertArrayEquals(first.keyPair.private.encoded, reloaded.keyPair.private.encoded)
    }
}
