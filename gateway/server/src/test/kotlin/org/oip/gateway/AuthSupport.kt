package org.oip.gateway

import org.oip.security.AuditQuery
import org.oip.security.AuditStatus
import org.oip.security.CreateUser
import org.oip.security.DefaultRoles

const val TEST_PASSWORD = "Correct-horse-1"

fun Gateway.createUser(name: String, roles: Set<String>, password: String = TEST_PASSWORD) {
    security.users.create(CreateUser(name, password.toCharArray(), roles), actor = "test")
}

/** Create `admin` when the directory has none, then open a cookie session on [http]. */
fun Gateway.loginAdmin(http: Http) {
    if (security.users.get("admin") == null) createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
    val loggedIn = http.login("admin", TEST_PASSWORD)
    check(loggedIn.status == 200) { "admin login failed: ${loggedIn.status} ${loggedIn.body}" }
}

fun Gateway.auditCount(action: String, actor: String? = null, status: AuditStatus? = null): Long =
    security.audit.query(AuditQuery(action = action, actor = actor, status = status, limit = 100)).total

fun Gateway.allowWidgets() {
    store.registry.register(
        "org.oip.test", "Widget",
        mapOf("type" to "object", "properties" to mapOf("size" to mapOf("type" to "integer", "minimum" to 1))),
    )
}

fun widgetDoc(name: String) =
    """{"apiVersion":"oip/v1","kind":"Widget","metadata":{"name":"$name","module":"org.oip.test","version":0},"spec":{"size":1}}"""

/** Passphrase the API tests seal backups with (review-1 F3: every REST backup is encrypted). */
const val TEST_BACKUP_PASSPHRASE = "backup passphrase 1"

/** `POST /api/v1/backups[query]` with [passphrase] in the JSON body. */
fun Http.createBackup(query: String = "", passphrase: String = TEST_BACKUP_PASSPHRASE): Http.Res =
    post("/api/v1/backups$query", Json.write(mapOf("passphrase" to passphrase)))

/** Upload [archive] to `POST /api/v1/backups/restore` as JSON (`passphrase`, base64 `archive`). */
fun Http.restoreBackup(archive: ByteArray, passphrase: String? = TEST_BACKUP_PASSPHRASE): Http.Res {
    val body = linkedMapOf<String, Any?>("archive" to java.util.Base64.getEncoder().encodeToString(archive))
    if (passphrase != null) body["passphrase"] = passphrase
    return post("/api/v1/backups/restore", Json.write(body))
}

/** The plaintext zip inside a sealed backup. */
fun openBackup(sealed: ByteArray, passphrase: String = TEST_BACKUP_PASSPHRASE): ByteArray =
    org.oip.backup.BackupCipher.decrypt(sealed, passphrase.toCharArray())
