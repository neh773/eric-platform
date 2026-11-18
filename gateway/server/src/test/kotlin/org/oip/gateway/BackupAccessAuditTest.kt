package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.backup.ZipIo
import org.oip.security.AuditQuery
import org.oip.security.AuditRecord
import org.oip.security.DefaultRoles
import org.oip.security.Permissions
import java.nio.file.Path

/**
 * Review-1 F2 and F4. A backup archive holds the secrets key, the secret store, and every user resource, so
 * creating, downloading, and restoring one is administrator-only. Listing names and sizes stays a gateway read.
 * Backup create, both restore routes, and a log-level change each write one audit record (actor, time, target).
 */
class BackupAccessAuditTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var admin: Http

    @BeforeEach fun setUp() {
        gw = Gateway(tmp, port = 0).start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        gw.security.roles.create("configwriter", setOf(Permissions.GATEWAY_WRITE, Permissions.GATEWAY_ACCESS), actor = "test")
        gw.createUser("writer", setOf("configwriter"))
        admin = client("admin")
    }

    @AfterEach fun tearDown() = gw.close()

    @Test fun onlyAdministratorsCreateDownloadOrRestoreBackups() {
        val created = admin.createBackup()
        assertEquals(200, created.status, created.body)
        val name = created.header("X-Backup-Name")!!
        val op = client("op")
        val writer = client("writer")

        val opDownload = op.get("/api/v1/backups/$name")
        assertEquals(403, opDownload.status)
        assertFalse(opDownload.raw.isNotEmpty() && opDownload.raw[0] == 'P'.code.toByte(), "operator received the archive")
        assertEquals(403, writer.get("/api/v1/backups/$name").status)
        assertEquals(403, writer.createBackup().status)
        assertEquals(403, writer.post("/api/v1/backups/$name/restore", Json.write(mapOf("passphrase" to TEST_BACKUP_PASSPHRASE))).status)
        assertEquals(403, writer.restoreBackup(created.raw).status)

        val download = admin.get("/api/v1/backups/$name")
        assertEquals(200, download.status)
        assertTrue(ZipIo.read(openBackup(download.raw)).containsKey("oip-backup.json"))
        // The listing carries names and sizes only.
        val listed = op.get("/api/v1/backups")
        assertEquals(200, listed.status)
        assertTrue(listed.body.contains(name))
    }

    @Test fun backupsAdminPermissionAloneGrantsBackupAccess() {
        gw.security.roles.create("backupkeeper", setOf(Permissions.BACKUPS_ADMIN, Permissions.GATEWAY_ACCESS), actor = "test")
        gw.createUser("keeper", setOf("backupkeeper"))
        val keeper = client("keeper")
        val created = keeper.createBackup()
        assertEquals(200, created.status, created.body)
        assertEquals(200, keeper.get("/api/v1/backups/${created.header("X-Backup-Name")}").status)
        // The listing is a gateway read, which this role does not hold.
        assertEquals(403, keeper.get("/api/v1/backups").status)
        assertTrue(Permissions.BACKUPS_ADMIN in gw.security.roles.get(DefaultRoles.ADMINISTRATOR)!!.permissions)
        assertFalse(Permissions.BACKUPS_ADMIN in gw.security.roles.get(DefaultRoles.OPERATOR)!!.permissions)
    }

    @Test fun backupCreateWritesOneAuditRecord() {
        val before = rows(BACKUP_CREATED)
        val created = admin.createBackup()
        assertEquals(200, created.status, created.body)
        val name = created.header("X-Backup-Name")!!
        val rows = rows(BACKUP_CREATED)
        assertEquals(before.size + 1, rows.size)
        val row = rows.first()
        assertEquals("admin", row.actor)
        assertEquals(name, row.target)
        assertTrue(row.timestampMillis > 0)
    }

    @Test fun storedRestoreWritesOneAuditRecord() {
        val name = admin.createBackup().header("X-Backup-Name")!!
        val restored = admin.post("/api/v1/backups/$name/restore", Json.write(mapOf("passphrase" to TEST_BACKUP_PASSPHRASE)))
        assertEquals(200, restored.status, restored.body)
        // The audit log itself is restored from the archive, so the restore row is written after it.
        val rows = rows(BACKUP_RESTORED)
        assertEquals(1, rows.size, rows.toString())
        assertEquals("admin", rows[0].actor)
        assertEquals(name, rows[0].target)
    }

    @Test fun uploadedRestoreWritesOneAuditRecord() {
        val archive = admin.createBackup().raw
        val restored = admin.restoreBackup(archive)
        assertEquals(200, restored.status, restored.body)
        val rows = rows(BACKUP_RESTORED)
        assertEquals(1, rows.size, rows.toString())
        assertEquals("admin", rows[0].actor)
        assertEquals("upload", rows[0].target)
        assertTrue(rows[0].detail.contains("bytes="), rows[0].detail)
    }

    @Test fun logLevelChangeWritesOneAuditRecord() {
        val changed = admin.put("/api/v1/logs/levels", """{"logger":"org.oip.http","level":"DEBUG"}""")
        assertEquals(200, changed.status, changed.body)
        val rows = rows(LOG_LEVEL_CHANGED)
        assertEquals(1, rows.size, rows.toString())
        assertEquals("admin", rows[0].actor)
        assertEquals("org.oip.http", rows[0].target)
        assertTrue(rows[0].detail.contains("DEBUG"), rows[0].detail)
        // A rejected change writes nothing.
        assertEquals(422, admin.put("/api/v1/logs/levels", """{"logger":"org.oip.http","level":"LOUD"}""").status)
        assertEquals(1, rows(LOG_LEVEL_CHANGED).size)
    }

    private fun rows(action: String): List<AuditRecord> = gw.security.audit.query(AuditQuery(action = action, limit = 100)).records

    private fun client(user: String): Http {
        val c = Http(gw.port())
        assertEquals(200, c.login(user, TEST_PASSWORD).status)
        return c
    }

    private companion object {
        const val BACKUP_CREATED = "gateway backup created"
        const val BACKUP_RESTORED = "gateway backup restored"
        const val LOG_LEVEL_CHANGED = "log level changed"
    }
}
