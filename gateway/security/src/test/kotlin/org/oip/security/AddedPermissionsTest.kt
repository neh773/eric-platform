package org.oip.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import java.nio.file.Path

/**
 * `backups.admin` (review-1 F2) is new. An administrator role seeded before it existed gains it on the next start,
 * and a role that was not full keeps its permissions.
 */
class AddedPermissionsTest {
    @TempDir lateinit var tmp: Path

    @Test fun fullRolesGainAddedPermissionsOnStartAndOthersDoNot() {
        val dir = tmp.resolve("gw")
        val older = (Permissions.ALL - AddedPermissions.ADDED).toList()
        openSecurity(dir).let { (store, svc) ->
            try {
                assertTrue(Permissions.BACKUPS_ADMIN in svc.roles.get(DefaultRoles.ADMINISTRATOR)!!.permissions)
                assertFalse(Permissions.BACKUPS_ADMIN in svc.roles.get(DefaultRoles.OPERATOR)!!.permissions)
                // Write the roles as a gateway seeded before backups.admin would have them.
                for ((name, perms) in listOf(DefaultRoles.ADMINISTRATOR to older, "copy" to older, "writer" to listOf(Permissions.GATEWAY_WRITE))) {
                    val cur = store.get(org.oip.config.ResourceId(SECURITY_MODULE, Scope.GATEWAY, Kinds.ROLE, name))
                    store.put(Scope.GATEWAY, Resource(Kinds.ROLE, Metadata(name, SECURITY_MODULE, version = cur?.metadata?.version ?: 0L),
                        mapOf("description" to "", "permissions" to perms)))
                }
                assertFalse(Permissions.BACKUPS_ADMIN in svc.roles.get(DefaultRoles.ADMINISTRATOR)!!.permissions)
            } finally {
                svc.close()
            }
        }
        openSecurity(dir).let { (_, svc) ->
            try {
                assertEquals(Permissions.ALL, svc.roles.get(DefaultRoles.ADMINISTRATOR)!!.permissions)
                assertEquals(Permissions.ALL, svc.roles.get("copy")!!.permissions)
                assertEquals(setOf(Permissions.GATEWAY_WRITE), svc.roles.get("writer")!!.permissions)
                assertFalse(Permissions.BACKUPS_ADMIN in svc.roles.get(DefaultRoles.OPERATOR)!!.permissions)
            } finally {
                svc.close()
            }
        }
    }
}
