package com.librestatic.lightforge.core.remotestorage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RemoteManagedFilesDeviceTest {
    private val args get() = InstrumentationRegistry.getArguments()
    private fun digest(data: ByteArray) = RemoteDigest(data.size.toLong(), MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) })
    private fun exercise(protocol: RemoteProtocol) {
        require(args.getString("sftpFixture") == "lightforge-wave34")
        val profile = if (protocol == RemoteProtocol.SFTP)
            RemoteProfile(name="Managed SSH fixture",protocol=protocol,host="10.0.2.2",port=22234,username="lightforgefixture",root="/data/archive",trustedHostKey=requireNotNull(args.getString("sftpPin")))
        else RemoteProfile(name="Managed SMB fixture",protocol=protocol,host="10.0.2.2",port=requireNotNull(args.getString("smbPort")).toInt(),username="lightforgefixture",root="",share="backup",domain="WORKGROUP")
        fun connect(): RemoteManagedConnection {
            val password = if (protocol == RemoteProtocol.SFTP) "lightforge-fixture-password" else requireNotNull(args.getString("smbPassword"))
            return OwnStorageConnectionFactory().connect(profile,RemoteCredentials.Password(password.toCharArray()),RemoteCancellation()) as RemoteManagedConnection
        }
        val segments=listOf("Lightforge-Sync-${UUID.randomUUID()}","Año 2026","Vacaciones")
        val data=ByteArray(256_123) { (it*31).toByte() };val expected=digest(data)
        val collision="existing user's bytes".toByteArray()
        connect().use { parent ->
            assertThrows(RemoteStorageException::class.java) { parent.directory(segments,false).close() }
            parent.directory(segments,true).use { directory ->
                directory.createExclusive("photo.jpg").use { it.write(data) }
                directory.createExclusive("occupied.jpg").use { it.write(collision) }
                assertEquals(RemoteManagedMoveState.VerifiedMoved,directory.moveManagedNoReplace("photo.jpg",".lightforge-quarantine-test",expected).state)
                assertNull(directory.stat("photo.jpg"))
                assertArrayEquals(data,directory.openRead(".lightforge-quarantine-test").use { it.readBytes() })
                assertEquals(RemoteManagedMoveState.AlreadyMoved,directory.moveManagedNoReplace("photo.jpg",".lightforge-quarantine-test",expected).state)
                assertEquals(RemoteManagedMoveState.TargetOccupied,directory.moveManagedNoReplace(".lightforge-quarantine-test","occupied.jpg",expected).state)
                assertArrayEquals(collision,directory.openRead("occupied.jpg").use { it.readBytes() })
            }
            assertTrue(parent.list(10000).any { it.name==segments.first() })
            assertThrows(RemoteStorageException::class.java) { parent.directory(listOf(".."),true).close() }
        }
        connect().use { parent ->
            val directory=parent.directory(segments,false)
            assertTrue(directory.moveManagedNoReplace(".lightforge-quarantine-test","photo.jpg",expected).verified)
            assertEquals(RemoteManagedMoveState.SourceChanged,directory.moveManagedNoReplace("photo.jpg",".lightforge-quarantine-wrong",digest("wrong".toByteArray())).state)
            assertArrayEquals(data,directory.openRead("photo.jpg").use { it.readBytes() })
            assertNull(directory.stat(".lightforge-quarantine-wrong"))
            parent.close()
            assertThrows(RemoteStorageException::class.java) { directory.stat("photo.jpg") }
            directory.close()
        }
    }
    @Test fun sftpNestedDirectoriesQuarantineReopenAndRestorePreserveCollisions() = exercise(RemoteProtocol.SFTP)
    @Test fun smbNestedDirectoriesQuarantineReopenAndRestorePreserveCollisions() = exercise(RemoteProtocol.SMB)
}
