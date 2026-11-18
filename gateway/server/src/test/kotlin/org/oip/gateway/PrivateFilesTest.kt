package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes

class PrivateFilesTest {
    @TempDir lateinit var tmp: Path

    @Test fun aPrivateKeyIsOwnerOnly() {
        val dir = tmp.resolve("identity")
        PrivateFiles.directory(dir)
        val key = dir.resolve("server.key")
        PrivateFiles.writeKey(key, "secret-key".toByteArray())
        PrivateFiles.restrictKey(key)
        assertTrue(PrivateFiles.ownerOnly(key))
        assertTrue(String(key.readBytes()) == "secret-key")
    }

    @Test fun aReadableFileCanBeReadBack() {
        val dir = tmp.resolve("certs")
        PrivateFiles.directory(dir)
        val cert = dir.resolve("server.crt")
        PrivateFiles.writeReadable(cert, "certificate".toByteArray())
        assertTrue(Files.isRegularFile(cert))
        assertTrue(String(cert.readBytes()) == "certificate")
    }
}
