package org.oip.security

import org.oip.config.FileConfigStore
import org.oip.config.SchemaRegistry
import java.nio.file.Path
import java.time.Clock
import javax.crypto.SecretKey

internal fun testKey(): SecretKey = SecretKeyLoader.fromBytes(ByteArray(32) { 7 })

internal fun openSecurity(
    tmp: Path,
    clock: Clock = Clock.systemUTC(),
    hasher: PasswordHasher = Argon2idHasher.fast(),
    keys: SecretKeySource = SecretKeySource { testKey() },
    registry: SchemaRegistry = SchemaRegistry(),
    runtime: RuntimeStores? = null,
): Pair<FileConfigStore, SecurityService> {
    val store = FileConfigStore(tmp.resolve("config"), registry, clock)
    val service = if (runtime == null) SecurityService.open(store, tmp, keys, clock, hasher)
    else SecurityService.open(store, runtime, keys, clock, hasher)
    return store to service
}
