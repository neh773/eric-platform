package org.oip.cli

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.backup.BackupCipher
import org.oip.gateway.Gateway
import org.oip.gateway.LogLevel
import org.oip.security.AuditActions
import org.oip.security.AuditQuery
import org.oip.security.AuthenticationResult
import org.oip.security.Permissions
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** GAT-015: oipctl drives config, modules, backup/restore, health and logs through the gateway REST API. */
class OipctlTest {
    @TempDir lateinit var tmp: Path

    @Test fun helpAndUnknownCommand() {
        val help = runCli(arrayOf("--help"))
        assertEquals(0, help.code)
        assertTrue(help.stdout.contains("backup"))
        assertTrue(help.stdout.contains("--encrypt"))
        assertTrue(help.stdout.contains("secrets.key"))
        assertTrue(help.stdout.contains("config put|apply"))
        assertTrue(help.stdout.contains("reset-admin"))
        assertTrue(help.stdout.contains("start [--data-dir DIR]"))
        assertTrue(help.stdout.contains("stop [--data-dir DIR]"))
        assertTrue(help.stdout.contains("restart [--data-dir DIR]"))
        assertTrue(help.stdout.contains("password is not a command-line argument"))
        assertEquals(1, runCli(emptyArray()).code)
        assertEquals(1, runCli(arrayOf("nope")).code)
    }

    @Test fun healthAndUnreachableGateway() {
        gateway { port ->
            val ok = cli(port, "health")
            assertEquals(0, ok.code, ok.stderr)
            assertTrue(ok.stdout.contains("\"status\":\"UP\""))
            assertTrue(ok.stdout.contains("\"profile\":\"standard\""))
        }
        val down = cli(1, "health")
        assertEquals(2, down.code)
        assertTrue(down.stderr.isNotBlank())
    }

    @Test fun configCrudAgainstRest() {
        gateway { port ->
            val file = tmp.resolve("thing.json")
            file.writeText("""{"apiVersion":"oip/v1","kind":"Thing","metadata":{"name":"a","module":"m","version":0},"spec":{"x":4}}""")
            val created = cli(port, "config", "put", "m", "gateway", "Thing", "a", "--file", file.toString())
            assertEquals(0, created.code, created.stderr)
            assertTrue(created.stdout.contains("\"version\" : 1") || created.stdout.contains("\"version\": 1") || created.stdout.contains("\"version\":1"))
            val stale = cli(port, "config", "put", "m", "gateway", "Thing", "a", "-f", file.toString())
            assertEquals(2, stale.code)
            assertTrue(stale.stderr.contains("Conflict") || stale.stderr.contains("409"))
            file.writeText("""{"apiVersion":"oip/v1","kind":"Thing","metadata":{"name":"a","module":"m","version":1},"spec":{"x":9}}""")
            val applied = cli(port, "config", "apply", "m", "gateway", "Thing", "a", "--file", file.toString())
            assertEquals(0, applied.code, applied.stderr)
            val got = cli(port, "config", "get", "m", "gateway", "Thing", "a")
            assertEquals(0, got.code, got.stderr)
            assertTrue(got.stdout.contains("9"))
            val listed = cli(port, "config", "list", "--module", "m", "--scope", "gateway")
            assertTrue(listed.stdout.contains("\"name\":\"a\""))
            val project = tmp.resolve("view.json")
            project.writeText("""{"apiVersion":"oip/v1","kind":"Thing","metadata":{"name":"main","module":"m","version":0},"spec":{"x":2}}""")
            assertEquals(0, cli(port, "config", "put", "m", "project/plant", "Thing", "main", "--file", project.toString()).code)
            assertEquals(0, cli(port, "config", "delete", "m", "gateway", "Thing", "a", "--version", "2").code)
            assertEquals(2, cli(port, "config", "get", "m", "gateway", "Thing", "a").code)
            assertEquals(0, cli(port, "config", "get", "m", "project/plant", "Thing", "main").code)
        }
    }

    @Test fun moduleInstallAndUninstall() {
        val jar = Path.of(System.getProperty("oip.helloJar"))
        val manifest = java.util.zip.ZipFile(jar.toFile()).use { z -> z.getInputStream(z.getEntry("module.yaml")).readBytes() }
        gateway { port ->
            val zip = tmp.resolve("hello.zip")
            zipOf(zip, "module.yaml" to manifest, "hello-world.jar" to Files.readAllBytes(jar))
            val installed = cli(port, "module", "install", "--file", zip.toString())
            assertEquals(0, installed.code, installed.stderr)
            assertTrue(installed.stdout.contains("org.oip.hello-world"))
            assertTrue(installed.stdout.contains("RUNNING"))
            assertTrue(cli(port, "module", "list").stdout.contains("org.oip.hello-world"))
            assertEquals(2, cli(port, "module", "install", "-f", zip.toString()).code)
            val removed = cli(port, "module", "uninstall", "org.oip.hello-world")
            assertEquals(0, removed.code, removed.stderr)
            assertTrue(removed.stdout.contains("uninstalled"))
            assertFalse(cli(port, "module", "list").stdout.contains("org.oip.hello-world"))
        }
    }

    @Test fun backupAndRestore() {
        gateway { port ->
            val file = tmp.resolve("thing.json")
            file.writeText("""{"apiVersion":"oip/v1","kind":"Thing","metadata":{"name":"a","module":"m","version":0},"spec":{"x":6}}""")
            assertEquals(0, cli(port, "config", "put", "m", "gateway", "Thing", "a", "-f", file.toString()).code)
            val archive = tmp.resolve("gw.oipbk")
            val pass = tmp.resolve("pass.txt")
            pass.writeText("battery-horse-staple\n")
            // Review-1 F3: without --encrypt the gateway still seals the archive; no plaintext file is written.
            val saved = cli(port, "backup", "-o", archive.toString(), "--passphrase-file", pass.toString())
            assertEquals(0, saved.code, saved.stderr)
            assertTrue(archive.exists())
            assertTrue(BackupCipher.isEncrypted(Files.readAllBytes(archive)))
            assertFalse(Files.readAllBytes(archive)[0] == 'P'.code.toByte())
            val short = tmp.resolve("short.txt")
            short.writeText("elevenchars\n")
            val refused = cli(port, "backup", "-o", tmp.resolve("short.oipbk").toString(), "--passphrase-file", short.toString())
            assertEquals(1, refused.code)
            assertTrue(refused.stderr.contains("at least 12"), refused.stderr)
            assertFalse(tmp.resolve("short.oipbk").exists())
            assertEquals(0, cli(port, "config", "delete", "m", "gateway", "Thing", "a", "--version", "1").code)
            assertEquals(2, cli(port, "config", "get", "m", "gateway", "Thing", "a").code)
            val restored = cli(port, "restore", "--file", archive.toString(), "--passphrase-file", pass.toString())
            assertEquals(0, restored.code, restored.stderr)
            val got = cli(port, "config", "get", "m", "gateway", "Thing", "a")
            assertEquals(0, got.code, got.stderr)
            assertTrue(got.stdout.contains("6"))
            assertTrue(saved.stderr.contains("secrets.key"))
            assertTrue(saved.stderr.contains("Anyone who holds the file and its passphrase can decrypt the stored secrets."))
        }
    }

    @Test fun encryptedBackupRoundTripAndWrongPassphrase() {
        gateway { port ->
            val file = tmp.resolve("thing.json")
            file.writeText("""{"apiVersion":"oip/v1","kind":"Thing","metadata":{"name":"a","module":"m","version":0},"spec":{"x":6}}""")
            assertEquals(0, cli(port, "config", "put", "m", "gateway", "Thing", "a", "-f", file.toString()).code)
            val archive = tmp.resolve("gw.enc")
            val pass = tmp.resolve("pass.txt")
            pass.writeText("battery-horse-staple\n")
            val saved = cli(port, "backup", "-o", archive.toString(), "--encrypt", "--passphrase-file", pass.toString())
            assertEquals(0, saved.code, saved.stderr)
            assertTrue(saved.stderr.contains("secrets.key"))
            assertTrue(saved.stderr.contains("passphrase"))
            val bytes = Files.readAllBytes(archive)
            assertTrue(BackupCipher.isEncrypted(bytes))
            assertFalse(bytes[0] == 'P'.code.toByte())
            assertEquals(0, cli(port, "config", "delete", "m", "gateway", "Thing", "a", "--version", "1").code)
            val bad = tmp.resolve("bad.txt")
            bad.writeText("nope\n")
            val rejected = cli(port, "restore", "--file", archive.toString(), "--passphrase-file", bad.toString())
            assertEquals(1, rejected.code)
            assertTrue(rejected.stderr.contains("wrong passphrase"))
            assertEquals(2, cli(port, "config", "get", "m", "gateway", "Thing", "a").code)
            val restored = cli(port, "restore", "--file", archive.toString(), "--passphrase-file", pass.toString())
            assertEquals(0, restored.code, restored.stderr)
            assertTrue(cli(port, "config", "get", "m", "gateway", "Thing", "a").stdout.contains("6"))
        }
    }

    @Test fun encryptedBackupPromptsForThePassphrase() {
        gateway { port ->
            val file = tmp.resolve("thing.json")
            file.writeText("""{"apiVersion":"oip/v1","kind":"Thing","metadata":{"name":"a","module":"m","version":0},"spec":{"x":4}}""")
            assertEquals(0, cli(port, "config", "put", "m", "gateway", "Thing", "a", "-f", file.toString()).code)
            val archive = tmp.resolve("prompted.enc")
            val saved = runCli(
                arrayOf("--url", "http://127.0.0.1:$port", "--token", token, "backup", "-o", archive.toString(), "--encrypt"),
                input = "prompted-secret",
            )
            assertEquals(0, saved.code, saved.stderr)
            assertTrue(saved.stderr.contains("Backup passphrase:"))
            assertTrue(BackupCipher.isEncrypted(Files.readAllBytes(archive)))
            assertEquals(0, cli(port, "config", "delete", "m", "gateway", "Thing", "a", "--version", "1").code)
            val wrong = runCli(
                arrayOf("--url", "http://127.0.0.1:$port", "--token", token, "restore", "--file", archive.toString()),
                input = "other-secret",
            )
            assertEquals(1, wrong.code)
            assertTrue(wrong.stderr.contains("Backup passphrase:"))
            assertTrue(wrong.stderr.contains("wrong passphrase"))
            assertEquals(2, cli(port, "config", "get", "m", "gateway", "Thing", "a").code)
            val restored = runCli(
                arrayOf("--url", "http://127.0.0.1:$port", "--token", token, "restore", "--file", archive.toString()),
                input = "prompted-secret",
            )
            assertEquals(0, restored.code, restored.stderr)
            assertTrue(cli(port, "config", "get", "m", "gateway", "Thing", "a").stdout.contains("4"))
        }
    }

    @Test fun logsLevelAndView() {
        gateway { port ->
            val g = gateways.last()
            assertFalse(g.logs.log(LogLevel.DEBUG, "org.oip.probe", "before-level"))
            assertFalse(cli(port, "logs", "--logger", "org.oip.probe").stdout.contains("before-level"))
            val set = cli(port, "logs", "level", "org.oip.probe", "DEBUG")
            assertEquals(0, set.code, set.stderr)
            assertTrue(g.logs.log(LogLevel.DEBUG, "org.oip.probe", "probe-marker"))
            val viewed = cli(port, "logs", "--logger", "org.oip.probe", "--q", "probe-marker")
            assertEquals(0, viewed.code, viewed.stderr)
            assertTrue(viewed.stdout.contains("probe-marker"))
            assertTrue(viewed.stdout.contains("\"level\":\"DEBUG\""))
        }
    }

    @Test fun projectExportImport() {
        gateway { port ->
            val file = tmp.resolve("view.json")
            file.writeText("""{"apiVersion":"oip/v1","kind":"Thing","metadata":{"name":"main","module":"m","version":0},"spec":{"x":3}}""")
            assertEquals(0, cli(port, "config", "put", "m", "project/plant", "Thing", "main", "-f", file.toString()).code)
            val zip = tmp.resolve("plant.zip")
            assertEquals(0, cli(port, "project", "export", "plant", "-o", zip.toString()).code)
            file.writeText("""{"apiVersion":"oip/v1","kind":"Thing","metadata":{"name":"main","module":"m","version":1},"spec":{"x":8}}""")
            assertEquals(0, cli(port, "config", "apply", "m", "project/plant", "Thing", "main", "-f", file.toString()).code)
            val skipped = cli(port, "project", "import", "plant", "--file", zip.toString(), "--policy", "skip")
            assertEquals(0, skipped.code, skipped.stderr)
            assertTrue(skipped.stdout.contains("\"skipped\":1"))
            val got = cli(port, "config", "get", "m", "project/plant", "Thing", "main")
            assertTrue(got.stdout.contains("8"), got.stdout)
            val replaced = cli(port, "project", "import", "plant", "-f", zip.toString(), "--policy", "overwrite")
            assertEquals(0, replaced.code, replaced.stderr)
            assertTrue(cli(port, "config", "get", "m", "project/plant", "Thing", "main").stdout.contains("3"))
        }
    }

    @Test fun resetAdminRejectsAWeakPasswordAndACommandLinePassword() {
        val dir = tmp.resolve("weak")
        val bad = tmp.resolve("weak.txt")
        bad.writeText("short\n")
        val rejected = runCli(arrayOf("reset-admin", "--data-dir", dir.toString(), "--password-file", bad.toString()))
        assertEquals(1, rejected.code, rejected.stdout)
        assertTrue(rejected.stderr.contains("at least 8"), rejected.stderr)
        assertFalse(rejected.stderr.contains("short"))
        val flagged = runCli(arrayOf("reset-admin", "--data-dir", dir.toString(), "--password", "Fresh-gate-2"))
        assertEquals(1, flagged.code)
        assertTrue(flagged.stderr.contains("unknown flag"))
    }

    @Test fun resetAdminStartStopAndRestart() {
        val dir = tmp.resolve("proc")
        Files.createDirectories(dir)
        dir.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        val pass = tmp.resolve("admin-pass.txt")
        pass.writeText("Fresh-gate-2\n")
        val reset = runCli(arrayOf("reset-admin", "--data-dir", dir.toString(), "--password-file", pass.toString()))
        assertEquals(0, reset.code, reset.stderr)
        assertTrue(reset.stdout.contains("created admin"))
        val userFile = dir.resolve("config/org.oip.security/gateway/User/admin.yaml")
        assertTrue(userFile.exists())
        assertFalse(userFile.readText().contains("Fresh-gate-2"))

        Gateway(dir, port = 0).use { running ->
            val created = running.security.audit.query(AuditQuery(actor = "oipctl", action = AuditActions.CONFIG_ADDED))
            assertTrue(created.records.any { it.target.endsWith("User/admin") && !it.detail.contains("Fresh-gate-2") })
            val auth = running.security.users.authenticate("admin", "Fresh-gate-2".toCharArray(), "cli-test")
            assertTrue(auth is AuthenticationResult.Success)
            running.start()
            val denied = runCli(arrayOf("reset-admin", "--data-dir", dir.toString(), "--password-file", pass.toString()))
            assertEquals(1, denied.code, denied.stdout)
            assertTrue(denied.stderr.contains("running"), denied.stderr)
        }

        val started = runCli(arrayOf("start", "--data-dir", dir.toString(), "--port", "0"))
        try {
            assertEquals(0, started.code, started.stderr + "\n" + started.stdout)
            val url = Files.readString(dir.resolve("gateway.http")).trim()
            assertTrue(started.stdout.contains(url), started.stdout)
            assertEquals(401, login(url, "admin", "Correct-horse-1"))
            assertEquals(200, login(url, "admin", "Fresh-gate-2"))
            val health = runCli(arrayOf("--url", url, "health"))
            assertEquals(0, health.code, health.stderr)
            val restarted = runCli(arrayOf("restart", "--data-dir", dir.toString()))
            assertEquals(0, restarted.code, restarted.stderr + "\n" + restarted.stdout)
            val url2 = Files.readString(dir.resolve("gateway.http")).trim()
            assertEquals(200, login(url2, "admin", "Fresh-gate-2"))
            val stopped = runCli(arrayOf("stop", "--data-dir", dir.toString()))
            assertEquals(0, stopped.code, stopped.stderr)
            assertTrue(stopped.stdout.contains("stopped"))
            assertEquals(2, runCli(arrayOf("--url", url2, "health")).code)
        } finally {
            runCli(arrayOf("stop", "--data-dir", dir.toString()))
        }
    }

    private fun login(url: String, user: String, password: String): Int {
        val body = """{"username":"$user","password":"$password"}"""
        val request = HttpRequest.newBuilder(URI.create("$url/api/v1/auth/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    private val gateways = mutableListOf<Gateway>()
    private var token = ""

    private fun gateway(block: (Int) -> Unit) {
        val g = Gateway(tmp.resolve("gw-${gateways.size}"), port = 0)
        gateways += g
        g.store.registry.register("m", "Thing", mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "integer"))))
        g.use {
            it.start()
            token = it.security.apiKeys.create(
                "oipctl",
                setOf(Permissions.GATEWAY_WRITE, Permissions.MODULES_ADMIN, Permissions.BACKUPS_ADMIN),
                secureConnectionRequired = false,
                actor = "test",
            ).token
            block(it.port())
        }
    }

    private fun cli(port: Int, vararg args: String) = runCli(arrayOf("--url", "http://127.0.0.1:$port", "--token", token) + args)

    private fun runCli(args: Array<String>, input: String? = null): Out {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val stdin = input?.byteInputStream(StandardCharsets.UTF_8)?.bufferedReader(StandardCharsets.UTF_8)
        val code = Oipctl(
            PrintStream(stdout, true, StandardCharsets.UTF_8),
            PrintStream(stderr, true, StandardCharsets.UTF_8),
            stdin = stdin,
        ).run(args)
        return Out(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8))
    }

    private fun zipOf(dest: Path, vararg entries: Pair<String, ByteArray>) {
        ZipOutputStream(dest.toFile().outputStream()).use { zip ->
            for ((name, bytes) in entries) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
    }

    private data class Out(val code: Int, val stdout: String, val stderr: String)

    /** SEC-031. Doc source: FEATURES SEC-031. */
    @Test fun rotateSecretKeysRewrapsStoredSecretsAndAuditsOnce() {
        val dir = tmp.resolve("rotate")
        Files.createDirectories(dir)
        dir.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        Gateway(dir, port = 0).use { gw -> gw.security.secrets.putText("plant-db", "s3cret-value-xyz", "db") }
        val rawBefore = Files.readAllBytes(dir.resolve("db/secrets.sqlite"))

        val flagged = runCli(arrayOf("rotate-secret-keys", "--data-dir", dir.toString(), "--only", "both"))
        assertEquals(1, flagged.code)
        val rotated = runCli(arrayOf("rotate-secret-keys", "--data-dir", dir.toString()))
        assertEquals(0, rotated.code, rotated.stderr)
        assertTrue(rotated.stdout.contains("1 secrets re-wrapped"), rotated.stdout)
        assertFalse(rotated.stdout.contains("s3cret-value-xyz") || rotated.stderr.contains("s3cret-value-xyz"))
        assertFalse(rawBefore.contentEquals(Files.readAllBytes(dir.resolve("db/secrets.sqlite"))))

        Gateway(dir, port = 0).use { gw ->
            assertEquals("s3cret-value-xyz", gw.security.secrets.getText("plant-db"))
            val audit = gw.security.audit.query(AuditQuery(action = AuditActions.SECRET_KEYS_ROTATED))
            assertEquals(1, audit.records.size)
            assertEquals("oipctl", audit.records[0].actor)
            assertFalse(audit.records[0].detail.contains("s3cret"))
            assertTrue(gw.security.audit.verify().ok)
        }
        val again = runCli(arrayOf("rotate-secret-keys", "--data-dir", dir.toString(), "--only", "kek"))
        assertEquals(0, again.code, again.stderr)
        Gateway(dir, port = 0).use { gw -> assertEquals("s3cret-value-xyz", gw.security.secrets.getText("plant-db")) }
    }
}
