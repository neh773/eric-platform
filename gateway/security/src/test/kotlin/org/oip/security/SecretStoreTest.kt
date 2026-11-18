package org.oip.security

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import java.nio.file.attribute.PosixFilePermission
import java.sql.DriverManager
import java.util.Base64
import javax.crypto.AEADBadTagException

class SecretStoreTest {
    @TempDir lateinit var tmp: Path
    private val key = SecretKeyLoader.fromBytes(ByteArray(32) { 3 })

    private fun store(blobs: SecretBlobStore = MemorySecretBlobStore()) = EncryptedSecretStore(blobs, key)

    @Test fun roundTripUpdateAndDelete() {
        val secrets = store()
        val ref = secrets.putText("db-password", "s3cret-value", "plant db")
        assertEquals("secret://db-password", ref)
        assertEquals("s3cret-value", secrets.getText("db-password"))
        assertEquals("s3cret-value", secrets.getText(SecretReferences.parse(ref)))
        secrets.putText("db-password", "rotated", "plant db")
        assertEquals("rotated", secrets.getText("db-password"))
        assertEquals(listOf("db-password"), secrets.list().map { it.name })
        assertEquals("plant db", secrets.list().single().description)
        assertFalse(secrets.list().joinToString().contains("rotated"))
        secrets.delete("db-password")
        assertThrows(NotFoundException::class.java) { secrets.get("db-password") }
    }

    @Test fun rejectsBadNamesAndSizes() {
        val secrets = store()
        assertThrows(SecretException::class.java) { secrets.putText("Bad", "x") }
        assertThrows(SecretException::class.java) { secrets.put("ok", ByteArray(0)) }
        assertThrows(SecretException::class.java) { secrets.put("ok", ByteArray(EncryptedSecretStore.MAX_BYTES + 1)) }
        secrets.put("ok", ByteArray(8) { 1 })
        assertThrows(SecretException::class.java) { SecretReferences.parse("secret://") }
        assertFalse(SecretReferences.isReference("plaintext"))
    }

    @Test fun swappedCiphertextAndWrongKeyFail() {
        val path = tmp.resolve("db/secrets.sqlite")
        val blobs = SqliteSecretBlobStore(path)
        val secrets = EncryptedSecretStore(blobs, key)
        secrets.putText("alpha", "alpha-plain-text-value")
        secrets.putText("beta", "beta-plain-text-value")
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { conn ->
            val alpha = conn.createStatement().executeQuery("SELECT ciphertext FROM secret WHERE name = 'alpha'").use { rs ->
                rs.next(); rs.getBytes(1)
            }
            conn.prepareStatement("UPDATE secret SET ciphertext = ? WHERE name = 'beta'").use { ps ->
                ps.setBytes(1, alpha)
                ps.executeUpdate()
            }
        }
        assertEquals("alpha-plain-text-value", secrets.getText("alpha"))
        val failed = assertThrows(SecretException::class.java) { secrets.getText("beta") }
        assertTrue(failed.cause is AEADBadTagException)
        blobs.close()
        val otherBlobs = SqliteSecretBlobStore(path)
        try {
            val other = EncryptedSecretStore(otherBlobs, SecretKeyLoader.fromBytes(ByteArray(32) { 9 }))
            assertThrows(SecretException::class.java) { other.getText("alpha") }
        } finally {
            otherBlobs.close()
        }
    }

    @Test fun sqliteBytesDoNotContainPlaintext() {
        val path = tmp.resolve("db/secrets.sqlite")
        val blobs = SqliteSecretBlobStore(path)
        EncryptedSecretStore(blobs, key).putText("db-password", "unique-plaintext-secret")
        blobs.close()
        val raw = Files.readAllBytes(path)
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("unique-plaintext-secret"))
    }

    @Test fun referencesResolveInsideAConfigSpec() {
        val registry = SchemaRegistry()
        registry.register("sample.mod", "Widget", mapOf(
            "type" to "object",
            "required" to listOf("password"),
            "properties" to mapOf("password" to SecretReferences.schemaProperty("database password")),
        ))
        val config = FileConfigStore(tmp.resolve("config"), registry)
        val secrets = store()
        val ref = secrets.putText("db-password", "unique-plaintext-secret")
        config.put(Scope.GATEWAY, Resource("Widget", Metadata("primary", "sample.mod"), mapOf("password" to ref)))
        val yaml = tmp.resolve("config/sample.mod/gateway/Widget/primary.yaml").readText()
        assertTrue(yaml.contains("secret://db-password"))
        assertFalse(yaml.contains("unique-plaintext-secret"))
        val resolved = SecretReferences.resolveSpec(config.get(org.oip.config.ResourceId("sample.mod", Scope.GATEWAY, "Widget", "primary"))!!.spec) {
            secrets.getText(SecretReferences.parse(it))
        }
        assertEquals("unique-plaintext-secret", resolved["password"])
        assertEquals(listOf(ref, ref), SecretReferences.find(mapOf("password" to ref, "nested" to mapOf("also" to ref))))
        config.close()
    }

    @Test fun keyLoaderEnvFileAndGenerate() {
        val hex = Hex.encode(ByteArray(32) { 4 })
        val b64 = Base64.getEncoder().encodeToString(ByteArray(32) { 5 })
        assertArrayEquals(ByteArray(32) { 4 }, SecretKeyLoader.decode(hex))
        assertArrayEquals(ByteArray(32) { 5 }, SecretKeyLoader.decode(b64))
        assertArrayEquals(ByteArray(32) { 5 }, SecretKeyLoader.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 5 })))
        assertThrows(SecretKeyException::class.java) { SecretKeyLoader.decode("aaaa") }
        assertThrows(SecretKeyException::class.java) { SecretKeyLoader.load(emptyMap(), createFileIfMissing = false) }

        val file = tmp.resolve("keys/secrets.key")
        val generated = SecretKeyLoader.load(mapOf(SecretKeyLoader.ENV_FILE to file.toString()))
        assertTrue(Files.exists(file))
        assertOwnerOnly(file)
        val names = Files.list(file.parent).use { stream -> stream.map { it.fileName.toString() }.toList() }
        assertEquals(listOf(file.fileName.toString()), names)
        SecureFiles.fsyncDirectory(file.parent)
        assertOwnerOnly(file)
        val again = SecretKeyLoader.loadFile(file)
        assertArrayEquals(generated.encoded, again.encoded)
        val inline = SecretKeyLoader.load(mapOf(SecretKeyLoader.ENV_KEY to b64, SecretKeyLoader.ENV_FILE to file.toString()))
        assertArrayEquals(ByteArray(32) { 5 }, inline.encoded)
        Files.write(file, ByteArray(32) { 6 })
        assertArrayEquals(ByteArray(32) { 6 }, SecretKeyLoader.loadFile(file).encoded)
        assertThrows(SecretKeyException::class.java) { SecretKeyLoader.loadFile(tmp.resolve("missing.key"), createFileIfMissing = false) }
    }

    private fun assertOwnerOnly(path: Path) {
        if (!path.fileSystem.supportedFileAttributeViews().contains("posix")) return
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(path),
        )
    }
}
