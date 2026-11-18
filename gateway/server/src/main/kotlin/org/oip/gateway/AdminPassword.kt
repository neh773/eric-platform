package org.oip.gateway

import org.oip.config.FileConfigStore
import org.oip.config.SchemaRegistry
import org.oip.security.AuditContext
import org.oip.security.CreateUser
import org.oip.security.DefaultRoles
import org.oip.security.SecretKeySource
import org.oip.security.SecretsKeyFile
import org.oip.security.SecurityException
import org.oip.security.SecurityService
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/**
 * Offline administrator recovery.
 *
 * the reference platform's `gwcmd -p` arms a commissioning reset that creates a temporary user source on the
 * next restart
 * (-).
 * This gateway has one internal user source, so recovery sets that user's password while the
 * process is stopped. A missing user is created with the administrator role. The password is
 * not written to the audit detail or to a config file in plaintext.
 */
object AdminPassword {
    fun reset(dataDir: Path, username: String, password: CharArray): String {
        val control = GatewayControl(dataDir)
        if (!control.tryAcquire()) {
            throw IllegalStateException("gateway is running; stop it before resetting the admin password")
        }
        var store: FileConfigStore? = null
        var security: SecurityService? = null
        try {
            for (d in Gateway.LAYOUT) Files.createDirectories(dataDir.resolve(d))
            val schemas = SchemaRegistry()
            store = FileConfigStore(dataDir.resolve("config"), schemas, Clock.systemUTC())
            security = SecurityService.open(
                store,
                dataDir,
                SecretKeySource { SecretsKeyFile.load(dataDir) },
            )
            return AuditContext.call(actor = "oipctl", context = "reset-admin") {
                if (security.users.get(username) == null) {
                    security.users.create(
                        CreateUser(username, password, setOf(DefaultRoles.ADMINISTRATOR)),
                        actor = "oipctl",
                    )
                    "created $username with the administrator role"
                } else {
                    security.users.setPassword(username, password.copyOf(), actor = "oipctl")
                    "updated password for $username"
                }
            }
        } catch (e: SecurityException) {
            throw IllegalArgumentException(e.message ?: "password rejected")
        } finally {
            password.fill('\u0000')
            runCatching { security?.close() }
            runCatching { store?.close() }
            control.release()
        }
    }
}
