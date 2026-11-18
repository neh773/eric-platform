package org.oip.gateway

import org.oip.security.AuditActions
import org.oip.security.AuditDraft
import org.oip.security.AuditStatus
import org.oip.security.EncryptedSecretStore
import org.oip.security.RuntimeStores
import org.oip.security.SecretKeyRotation
import org.oip.security.SecretKeySource
import org.oip.security.SecretsKeyFile
import org.oip.security.SecurityService
import org.oip.config.FileConfigStore
import org.oip.config.SchemaRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/**
 * `oipctl rotate-secret-keys` (SEC-031): rotate the root key and the key-encryption key of the secret store and
 * re-wrap every stored secret, while the gateway is stopped (it takes the same process lock as `reset-admin`).
 * Secrets keep their names and values; no key or value is printed or put in the audit detail. One audit record is
 * written. The gateway key file is not changed.
 *
 * Doc source: FEATURES SEC-031.
 */
/** What a rotation did. No key or secret value. */
data class SecretKeysRotated(val secrets: Int, val migratedFromLegacy: Int, val rootVersion: Int, val kekVersion: Int)

object SecretKeys {
    fun rotate(dataDir: Path, rotateRoot: Boolean = true, rotateKek: Boolean = true): SecretKeysRotated {
        val control = GatewayControl(dataDir)
        if (!control.tryAcquire()) throw IllegalStateException("gateway is running; stop it before rotating secret keys")
        try {
            for (d in Gateway.LAYOUT) Files.createDirectories(dataDir.resolve(d))
            val key = SecretsKeyFile.load(dataDir, createFileIfMissing = false)
            val result = RuntimeStores.sqlite(dataDir).use { stores ->
                EncryptedSecretStore(stores.secrets, key, Clock.systemUTC()).rotateKeys(rotateRoot, rotateKek)
            }
            var store: FileConfigStore? = null
            var security: SecurityService? = null
            try {
                store = FileConfigStore(dataDir.resolve("config"), SchemaRegistry(), Clock.systemUTC())
                security = SecurityService.open(store, dataDir, SecretKeySource { key })
                security.audit.append(
                    AuditDraft(
                        timestampMillis = Clock.systemUTC().millis(),
                        actor = "oipctl",
                        action = AuditActions.SECRET_KEYS_ROTATED,
                        status = AuditStatus.SUCCESS,
                        target = "secrets",
                        detail = "root=$rotateRoot;kek=$rotateKek;secrets=${result.secrets};rootVersion=${result.rootVersion};kekVersion=${result.kekVersion}",
                        context = "rotate-secret-keys",
                    )
                )
            } finally {
                runCatching { security?.close() }
                runCatching { store?.close() }
            }
            return SecretKeysRotated(result.secrets, result.migratedFromLegacy, result.rootVersion, result.kekVersion)
        } finally {
            control.release()
        }
    }
}
