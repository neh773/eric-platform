package org.oip.cli

import org.oip.backup.BackupCipher
import org.oip.gateway.Json
import java.io.BufferedReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Thin REST client for a running gateway.
 *
 * the reference platform's gwcmd can back up, restore, install modules, restart the gateway, and reset access
 * (-).
 * oipctl calls `/api/v1` for config, modules, backup, and logs. `start`, `stop`, and `restart` control
 * the gateway process. `reset-admin` sets the internal admin password while that process is stopped.
 */
class Oipctl(
    private val out: java.io.PrintStream = System.out,
    private val err: java.io.PrintStream = System.err,
    private val env: Map<String, String> = System.getenv(),
    private val stdin: BufferedReader? = null,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private var bearer: String? = null

    fun run(args: Array<String>): Int {
        try {
            return dispatch(args)
        } catch (e: IllegalArgumentException) {
            err.println(e.message ?: "bad arguments")
            return 1
        } catch (e: IllegalStateException) {
            err.println(e.message ?: "gateway is already running")
            return 1
        } catch (e: java.io.IOException) {
            err.println(e.message ?: e.javaClass.simpleName)
            return 1
        } catch (e: Abort) {
            return e.code
        } finally {
            out.flush(); err.flush()
        }
    }

    private fun dispatch(args: Array<String>): Int {
        val rest = mutableListOf<String>()
        var url = env["OIP_URL"] ?: "http://127.0.0.1:8090"
        var token = env["OIP_TOKEN"]
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--url" -> url = args.getOrNull(++i) ?: throw IllegalArgumentException("--url needs a value")
                "--token" -> token = args.getOrNull(++i) ?: throw IllegalArgumentException("--token needs a value")
                else -> rest += args[i]
            }
            i++
        }
        bearer = token?.takeIf { it.isNotEmpty() }
        if (rest.isEmpty()) { err.println(USAGE); return 1 }
        if (rest[0] == "help" || rest[0] == "--help" || rest[0] == "-h") { out.println(USAGE); return 0 }
        val cmd = rest[0]
        val tail = rest.drop(1)
        return when (cmd) {
            "health" -> call(url, "GET", "/api/v1/health")
            "config" -> config(url, tail)
            "module" -> module(url, tail)
            "backup" -> backup(url, tail)
            "restore" -> restore(url, tail)
            "logs" -> logs(url, tail)
            "project" -> project(url, tail)
            "start" -> commands().start(tail)
            "stop" -> commands().stop(tail)
            "restart" -> commands().restart(tail)
            "reset-admin" -> commands().resetAdmin(tail)
            "rotate-secret-keys" -> commands().rotateSecretKeys(tail)
            else -> throw IllegalArgumentException("unknown command '$cmd'\n$USAGE")
        }
    }

    private fun commands() = GatewayCommands(out, err, env, stdin, ::readSecret)

    private fun config(url: String, args: List<String>): Int {
        if (args.isEmpty()) throw IllegalArgumentException("config needs a subcommand")
        return when (args[0]) {
            "list" -> {
                val (_, flags) = parse(args.drop(1), setOf("module", "scope", "kind"))
                val q = listOfNotNull(
                    flags["module"]?.let { "module=$it" },
                    flags["scope"]?.let { "scope=$it" },
                    flags["kind"]?.let { "kind=$it" },
                ).joinToString("&")
                call(url, "GET", "/api/v1/config" + if (q.isEmpty()) "" else "?$q")
            }
            "get" -> {
                val (pos, _) = parse(args.drop(1), emptySet())
                if (pos.size != 4) throw IllegalArgumentException("config get MODULE SCOPE KIND NAME")
                call(url, "GET", configPath(pos[0], pos[1], pos[2], pos[3]))
            }
            "put", "apply" -> {
                val (pos, flags) = parse(args.drop(1), setOf("file"))
                if (pos.size != 4) throw IllegalArgumentException("config ${args[0]} MODULE SCOPE KIND NAME --file FILE")
                val file = flags["file"] ?: throw IllegalArgumentException("--file is required")
                call(url, "PUT", configPath(pos[0], pos[1], pos[2], pos[3]), Files.readAllBytes(Path.of(file)), "application/json")
            }
            "delete" -> {
                val (pos, flags) = parse(args.drop(1), setOf("version"))
                if (pos.size != 4) throw IllegalArgumentException("config delete MODULE SCOPE KIND NAME [--version N]")
                val headers = flags["version"]?.let { mapOf("If-Match" to it) } ?: emptyMap()
                call(url, "DELETE", configPath(pos[0], pos[1], pos[2], pos[3]), headers = headers)
            }
            else -> throw IllegalArgumentException("unknown config subcommand '${args[0]}'")
        }
    }

    private fun module(url: String, args: List<String>): Int {
        if (args.isEmpty()) throw IllegalArgumentException("module needs a subcommand")
        return when (args[0]) {
            "list" -> call(url, "GET", "/api/v1/modules")
            "install" -> {
                val (_, flags) = parse(args.drop(1), setOf("file"))
                val file = flags["file"] ?: throw IllegalArgumentException("module install --file FILE")
                call(url, "POST", "/api/v1/modules", Files.readAllBytes(Path.of(file)), "application/zip")
            }
            "uninstall" -> {
                val (pos, _) = parse(args.drop(1), emptySet())
                if (pos.size != 1) throw IllegalArgumentException("module uninstall ID")
                val code = call(url, "DELETE", "/api/v1/modules/${pos[0]}")
                if (code == 0) out.println("uninstalled ${pos[0]}")
                code
            }
            else -> throw IllegalArgumentException("unknown module subcommand '${args[0]}'")
        }
    }

    /**
     * The gateway seals every backup under the passphrase (review-1 F3), so the file written here never holds
     * secrets.key in clear. `--encrypt` is accepted for older scripts; encryption is always on.
     */
    private fun backup(url: String, args: List<String>): Int {
        val (_, flags) = parse(args, setOf("output", "passphrase-file"), setOf("bundle-modules", "encrypt"))
        val dest = Path.of(flags["output"] ?: throw IllegalArgumentException("backup --output FILE"))
        val path = if (flags.containsKey("bundle-modules")) "/api/v1/backups?bundleModules=true" else "/api/v1/backups"
        val pass = readPassphrase(flags, "Backup passphrase: ")
        val body = try {
            BackupCipher.requireNewPassphrase(pass)
            Json.write(mapOf("passphrase" to String(pass))).toByteArray(Charsets.UTF_8)
        } finally {
            pass.fill('\u0000')
        }
        val res = try {
            send(url, "POST", path, body, "application/json")
        } finally {
            body.fill(0)
        }
        if (res.statusCode() >= 400) { err.println(res.body().toString(Charsets.UTF_8)); return 2 }
        if (!BackupCipher.isEncrypted(res.body())) {
            err.println("the gateway returned an unencrypted backup; nothing was written")
            return 2
        }
        dest.parent?.let { Files.createDirectories(it) }
        Files.write(dest, res.body())
        warnSecrets()
        out.println(dest)
        return 0
    }

    /**
     * An encrypted file is checked against the passphrase here first, then sent sealed with the passphrase so
     * the plaintext archive never crosses the network. A plaintext zip from an older version is sent as is.
     */
    private fun restore(url: String, args: List<String>): Int {
        val (_, flags) = parse(args, setOf("file", "passphrase-file"))
        val file = flags["file"] ?: throw IllegalArgumentException("restore --file FILE")
        val stored = Files.readAllBytes(Path.of(file))
        if (!BackupCipher.isEncrypted(stored)) {
            if (flags.containsKey("passphrase-file")) {
                throw IllegalArgumentException("--passphrase-file was given but the backup is not encrypted")
            }
            return call(url, "POST", "/api/v1/backups/restore", stored, "application/zip")
        }
        val pass = readPassphrase(flags, "Backup passphrase: ")
        val body = try {
            BackupCipher.decrypt(stored, pass).fill(0)
            Json.write(mapOf("passphrase" to String(pass), "archive" to java.util.Base64.getEncoder().encodeToString(stored)))
                .toByteArray(Charsets.UTF_8)
        } finally {
            pass.fill('\u0000')
        }
        return call(url, "POST", "/api/v1/backups/restore", body, "application/json")
    }

    private fun readPassphrase(flags: Map<String, String>, prompt: String): CharArray =
        readSecret(flags, "passphrase-file", prompt)

    /** A single trailing newline is the line ending of the file, not part of the secret. */
    private fun readSecret(flags: Map<String, String>, fileFlag: String, prompt: String): CharArray {
        val label = if (fileFlag == "password-file") "password" else "passphrase"
        flags[fileFlag]?.let { return secretFromFile(it, label) }
        return promptSecret(prompt, label)
    }

    private fun secretFromFile(path: String, label: String): CharArray {
        val text = Files.readString(Path.of(path))
        val stripped = when {
            text.endsWith("\r\n") -> text.dropLast(2)
            text.endsWith("\n") -> text.dropLast(1)
            else -> text
        }
        if (stripped.isEmpty()) throw IllegalArgumentException("$label file is empty")
        return stripped.toCharArray()
    }

    private fun promptSecret(prompt: String, label: String): CharArray {
        val console = if (stdin == null) System.console() else null
        err.print(prompt)
        err.flush()
        val chars = if (console != null) {
            console.readPassword() ?: throw IllegalArgumentException("$label required")
        } else {
            val line = (stdin ?: System.`in`.bufferedReader()).readLine()
                ?: throw IllegalArgumentException("$label required")
            line.toCharArray()
        }
        if (chars.isEmpty()) throw IllegalArgumentException("$label must not be empty")
        return chars
    }

    private fun warnSecrets() {
        err.println("warning: this backup contains secrets.key and the secret store, encrypted under the passphrase. " +
            "Anyone who holds the file and its passphrase can decrypt the stored secrets. A lost passphrase cannot be recovered.")
    }

    private fun logs(url: String, args: List<String>): Int {
        if (args.firstOrNull() == "level" || args.firstOrNull() == "set-level") {
            val (pos, _) = parse(args.drop(1), emptySet())
            if (pos.size != 2) throw IllegalArgumentException("logs level LOGGER LEVEL")
            val body = """{"logger":${jsonString(pos[0])},"level":${jsonString(pos[1])}}""".toByteArray()
            return call(url, "PUT", "/api/v1/logs/levels", body, "application/json")
        }
        val (_, flags) = parse(args, setOf("logger", "min-level", "q", "limit"))
        val q = listOfNotNull(
            flags["logger"]?.let { "logger=$it" },
            flags["min-level"]?.let { "minLevel=$it" },
            flags["q"]?.let { "q=$it" },
            flags["limit"]?.let { "limit=$it" },
        ).joinToString("&")
        return call(url, "GET", "/api/v1/logs" + if (q.isEmpty()) "" else "?$q")
    }

    private fun project(url: String, args: List<String>): Int {
        if (args.isEmpty()) throw IllegalArgumentException("project needs a subcommand")
        return when (args[0]) {
            "list" -> call(url, "GET", "/api/v1/projects")
            "export" -> {
                val (pos, flags) = parse(args.drop(1), setOf("output"))
                if (pos.size != 1) throw IllegalArgumentException("project export NAME --output FILE")
                val dest = flags["output"] ?: throw IllegalArgumentException("--output is required")
                download(url, "GET", "/api/v1/projects/${pos[0]}/export", Path.of(dest))
            }
            "import" -> {
                val (pos, flags) = parse(args.drop(1), setOf("file", "policy"))
                if (pos.size != 1) throw IllegalArgumentException("project import NAME --file FILE --policy skip|overwrite")
                val file = flags["file"] ?: throw IllegalArgumentException("--file is required")
                val policy = flags["policy"] ?: throw IllegalArgumentException("--policy is required (skip or overwrite)")
                call(url, "POST", "/api/v1/projects/${pos[0]}/import?policy=$policy", Files.readAllBytes(Path.of(file)), "application/zip")
            }
            else -> throw IllegalArgumentException("unknown project subcommand '${args[0]}'")
        }
    }

    private fun configPath(module: String, scope: String, kind: String, name: String): String {
        val parts = scope.split('/')
        val scopePath = when {
            parts.size == 1 && parts[0] == "gateway" -> "gateway"
            parts.size == 2 && (parts[0] == "project" || parts[0] == "mode") -> "${parts[0]}/${parts[1]}"
            else -> throw IllegalArgumentException("scope must be gateway, project/<name>, or mode/<name>")
        }
        return "/api/v1/config/$module/$scopePath/$kind/$name"
    }

    private fun call(url: String, method: String, path: String, body: ByteArray? = null, type: String = "application/json",
                     headers: Map<String, String> = emptyMap()): Int {
        val res = send(url, method, path, body, type, headers)
        val text = res.body().toString(Charsets.UTF_8)
        if (res.statusCode() >= 400) { err.println(text); return 2 }
        if (res.body().isNotEmpty()) out.println(text)
        return 0
    }

    private fun download(url: String, method: String, path: String, dest: Path): Int {
        val res = send(url, method, path, null, "application/json")
        if (res.statusCode() >= 400) { err.println(res.body().toString(Charsets.UTF_8)); return 2 }
        dest.parent?.let { Files.createDirectories(it) }
        Files.write(dest, res.body())
        out.println(dest)
        return 0
    }

    private fun send(url: String, method: String, path: String, body: ByteArray?, type: String,
                     headers: Map<String, String> = emptyMap()): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI.create(url.trimEnd('/') + path)).timeout(Duration.ofSeconds(60))
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
        if (body != null) builder.header("Content-Type", type)
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        headers.forEach { (k, v) -> builder.header(k, v) }
        return try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: Exception) {
            err.println(e.message ?: e.javaClass.simpleName)
            throw Abort(2)
        }
    }

    private fun parse(args: List<String>, valued: Set<String>, switches: Set<String> = emptySet()): Pair<List<String>, Map<String, String>> {
        val pos = mutableListOf<String>()
        val flags = linkedMapOf<String, String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a == "--") { pos += args.subList(i + 1, args.size); break }
            if (a.startsWith("-")) {
                val raw = if (a.startsWith("--")) a.removePrefix("--") else a.removePrefix("-")
                val name = when (raw) { "o" -> "output"; "f" -> "file"; else -> raw }
                if (name in switches) flags[name] = "true"
                else if (name !in valued) throw IllegalArgumentException("unknown flag $a")
                else flags[name] = args.getOrNull(++i) ?: throw IllegalArgumentException("$a needs a value")
            } else pos += a
            i++
        }
        return pos to flags
    }

    private fun jsonString(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> append(c)
        }
        append('"')
    }

    private class Abort(val code: Int) : RuntimeException()

    companion object {
        val USAGE = """
            oipctl [--url URL] [--token TOKEN] <command>

            health
            config list [--module M] [--scope SCOPE] [--kind KIND]
            config get MODULE SCOPE KIND NAME
            config put|apply MODULE SCOPE KIND NAME --file FILE
            config delete MODULE SCOPE KIND NAME [--version N]
            module list
            module install --file MODULE.zip
            module uninstall ID
            backup --output FILE [--bundle-modules] [--encrypt] [--passphrase-file FILE]
            restore --file FILE [--passphrase-file FILE]
            logs [--logger NAME] [--min-level LEVEL] [--q TEXT] [--limit N]
            logs level LOGGER LEVEL
            project list
            project export NAME --output FILE
            project import NAME --file FILE --policy skip|overwrite
            start [--data-dir DIR] [--port N] [--foreground]
            stop [--data-dir DIR]
            rotate-secret-keys [--data-dir DIR] [--only root|kek]   (gateway stopped)
            restart [--data-dir DIR] [--port N]
            reset-admin [--data-dir DIR] [--user NAME] [--password-file FILE]

            SCOPE is gateway, project/<name>, or mode/<name>.
            URL defaults to OIP_URL or http://127.0.0.1:8090.
            TOKEN defaults to OIP_TOKEN. It is sent as Authorization: Bearer. health does not require it.
            DATA DIR defaults to OIP_DATA_DIR or ./data. start, stop, restart, and reset-admin use it and do not need a token.
            Config writes, log level changes, and project import need gateway.write. Backup and restore need backups.admin. Module install needs modules.admin.
            A backup contains secrets.key and the secret store. The gateway always encrypts it with AES-256-GCM under a key derived with Argon2id from a passphrase of at least 12 characters; --encrypt is accepted and has no further effect.
            backup and restore prompt for the passphrase when --passphrase-file is omitted. A plaintext zip from an older version restores without one.
            reset-admin sets the internal user's password while the gateway is stopped. It prompts when --password-file is omitted. The password is not a command-line argument. A missing user is created with the administrator role.
            start launches the gateway. stop signals the process recorded in gateway.pid. restart stops it when it is running, then starts it. Edit gateway.yaml while it is stopped to change the port or profile, then start.
        """.trimIndent()
    }
}

fun main(args: Array<String>) {
    kotlin.system.exitProcess(Oipctl().run(args))
}
