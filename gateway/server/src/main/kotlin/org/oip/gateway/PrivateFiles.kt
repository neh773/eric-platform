package org.oip.gateway

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists

/** Writes a private key so the file is owner-read/write (mode 0600) from the moment it exists. */
object PrivateFiles {
    val KEY: Set<PosixFilePermission> = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    private val DIR: Set<PosixFilePermission> = setOf(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
    )
    private val READABLE: Set<PosixFilePermission> = setOf(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ,
    )

    fun directory(path: Path) {
        path.createDirectories()
        restrict(path, DIR)
    }

    fun writeKey(path: Path, bytes: ByteArray) = write(path, bytes, KEY)

    fun writeReadable(path: Path, bytes: ByteArray) = write(path, bytes, READABLE)

    fun restrictKey(path: Path) {
        restrict(path, KEY)
        if (!ownerOnly(path)) throw IllegalStateException("private key mode is not 0600")
    }

    /** True when the file is mode 0600, or on Windows when only its owner can read or write it. */
    fun ownerOnly(path: Path): Boolean {
        return try {
            Files.getPosixFilePermissions(path) == KEY
        } catch (_: UnsupportedOperationException) {
            windowsOwnerOnly(path)
        }
    }

    private fun write(path: Path, bytes: ByteArray, permissions: Set<PosixFilePermission>) {
        path.parent?.let { directory(it) }
        val tmp = path.resolveSibling("${path.fileName}.tmp-${UUID.randomUUID()}")
        try {
            openNew(tmp, permissions).use { channel ->
                channel.write(ByteBuffer.wrap(bytes))
                channel.force(true)
            }
            restrict(tmp, permissions)
            Files.move(tmp, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            restrict(path, permissions)
        } catch (e: Exception) {
            tmp.deleteIfExists()
            throw e
        }
    }

    private fun openNew(path: Path, permissions: Set<PosixFilePermission>): FileChannel {
        val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        return try {
            FileChannel.open(path, options, PosixFilePermissions.asFileAttribute(permissions))
        } catch (_: UnsupportedOperationException) {
            FileChannel.open(path, options)
        }
    }

    private fun restrict(path: Path, permissions: Set<PosixFilePermission>) {
        try {
            Files.setPosixFilePermissions(path, permissions)
        } catch (_: UnsupportedOperationException) {
            restrictWindows(path, permissions)
        }
    }

    private fun restrictWindows(path: Path, permissions: Set<PosixFilePermission>) {
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
            ?: throw IllegalStateException("private key permissions could not be restricted")
        val owner = view.owner ?: throw IllegalStateException("private key permissions could not be restricted")
        val directory = Files.isDirectory(path)
        val entries = mutableListOf(
            allow(owner, ownerPermissions(permissions, directory)),
        )
        if (PosixFilePermission.GROUP_READ in permissions || PosixFilePermission.OTHERS_READ in permissions) {
            val execute = PosixFilePermission.GROUP_EXECUTE in permissions || PosixFilePermission.OTHERS_EXECUTE in permissions
            entries += allow(everyone(path), readPermissions(directory, execute))
        }
        view.acl = entries
    }

    private fun windowsOwnerOnly(path: Path): Boolean {
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return false
        val owner = view.owner ?: return false
        val acl = view.acl ?: return false
        val ownerCanRead = acl.any { entry ->
            entry.type() == AclEntryType.ALLOW && sameUser(entry.principal(), owner) &&
                AclEntryPermission.READ_DATA in entry.permissions()
        }
        val someoneElse = acl.any { entry ->
            entry.type() == AclEntryType.ALLOW && !sameUser(entry.principal(), owner) &&
                (AclEntryPermission.READ_DATA in entry.permissions() || AclEntryPermission.WRITE_DATA in entry.permissions())
        }
        return ownerCanRead && !someoneElse
    }

    private fun ownerPermissions(permissions: Set<PosixFilePermission>, directory: Boolean): Set<AclEntryPermission> {
        val allowed = linkedSetOf<AclEntryPermission>()
        if (PosixFilePermission.OWNER_READ in permissions) allowed += readPermissions(directory, execute = false)
        if (PosixFilePermission.OWNER_WRITE in permissions) {
            allowed += setOf(
                AclEntryPermission.WRITE_DATA,
                AclEntryPermission.APPEND_DATA,
                AclEntryPermission.WRITE_ATTRIBUTES,
                AclEntryPermission.WRITE_NAMED_ATTRS,
                AclEntryPermission.DELETE,
                AclEntryPermission.SYNCHRONIZE,
            )
            if (directory) {
                allowed += AclEntryPermission.DELETE_CHILD
            }
        }
        if (directory && PosixFilePermission.OWNER_EXECUTE in permissions) {
            allowed += AclEntryPermission.EXECUTE
        }
        return allowed
    }

    private fun readPermissions(directory: Boolean, execute: Boolean): Set<AclEntryPermission> {
        val allowed = linkedSetOf(
            AclEntryPermission.READ_DATA,
            AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.READ_NAMED_ATTRS,
            AclEntryPermission.READ_ACL,
            AclEntryPermission.SYNCHRONIZE,
        )
        if (directory && execute) allowed += AclEntryPermission.EXECUTE
        return allowed
    }

    private fun allow(principal: UserPrincipal, permissions: Set<AclEntryPermission>): AclEntry =
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(principal)
            .setPermissions(permissions)
            .build()

    private fun everyone(path: Path): UserPrincipal {
        val lookup = path.fileSystem.userPrincipalLookupService
        for (name in listOf("Everyone", "EVERYONE")) {
            try {
                return lookup.lookupPrincipalByName(name)
            } catch (_: Exception) {
                // Try the next spelling. A localized Windows install may use a different name.
            }
        }
        throw IllegalStateException("private key permissions could not be restricted")
    }

    private fun sameUser(a: UserPrincipal?, b: UserPrincipal?): Boolean {
        if (a == null || b == null) return false
        return a.name.substringAfterLast('\\').equals(b.name.substringAfterLast('\\'), ignoreCase = true)
    }
}
