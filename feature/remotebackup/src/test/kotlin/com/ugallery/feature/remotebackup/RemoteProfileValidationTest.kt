package com.ugallery.feature.remotebackup

import com.ugallery.core.remotestorage.RemoteProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteProfileValidationTest {

    private fun sftp(
        name: String = "Home NAS",
        host: String = "nas.example.com",
        port: String = "22",
        username: String = "facu",
        root: String = "/backups/ugallery",
        keyAuth: Boolean = false,
        hasKey: Boolean = false,
    ) =
        validateRemoteDraft(
            name = name,
            protocol = RemoteProtocol.SFTP,
            host = host,
            port = port,
            username = username,
            root = root,
            keyAuth = keyAuth,
            hasKey = hasKey,
        )

    @Test
    fun `empty form reports every required field`() {
        val errors =
            validateRemoteDraft(
                name = "",
                protocol = RemoteProtocol.SFTP,
                host = "",
                port = "",
                username = "",
                root = "",
            )
        assertEquals(
            listOf(RemoteField.NAME, RemoteField.HOST, RemoteField.PORT, RemoteField.USERNAME),
            errors.keys.toList(),
        )
        assertEquals(R.string.remote_error_name_required, errors[RemoteField.NAME])
        assertEquals(R.string.remote_error_host_required, errors[RemoteField.HOST])
        assertEquals(R.string.remote_error_port_invalid, errors[RemoteField.PORT])
        assertEquals(R.string.remote_error_user_required, errors[RemoteField.USERNAME])
    }

    @Test
    fun `valid sftp draft has no errors`() {
        assertEquals(emptyMap<RemoteField, Int>(), sftp())
    }

    @Test
    fun `empty port is rejected without crashing`() {
        assertEquals(R.string.remote_error_port_invalid, sftp(port = "")[RemoteField.PORT])
    }

    @Test
    fun `port zero is rejected`() {
        assertEquals(R.string.remote_error_port_invalid, sftp(port = "0")[RemoteField.PORT])
    }

    @Test
    fun `port above range is rejected`() {
        assertEquals(R.string.remote_error_port_invalid, sftp(port = "70000")[RemoteField.PORT])
    }

    @Test
    fun `non numeric port is rejected without crashing`() {
        assertEquals(R.string.remote_error_port_invalid, sftp(port = "abc")[RemoteField.PORT])
    }

    @Test
    fun `host with a space is invalid`() {
        assertEquals(
            R.string.remote_error_host_invalid,
            sftp(host = "nas example.com")[RemoteField.HOST],
        )
    }

    @Test
    fun `host with a slash is invalid`() {
        assertEquals(
            R.string.remote_error_host_invalid,
            sftp(host = "nas.example.com/share")[RemoteField.HOST],
        )
    }

    @Test
    fun `surrounding whitespace in host is tolerated`() {
        assertFalse(sftp(host = "  nas.example.com  ").containsKey(RemoteField.HOST))
    }

    @Test
    fun `root with a parent segment is invalid`() {
        assertEquals(
            R.string.remote_error_folder_invalid,
            sftp(root = "/backups/../etc")[RemoteField.ROOT],
        )
    }

    @Test
    fun `name over one hundred twenty characters is invalid`() {
        assertEquals(
            R.string.remote_error_name_required,
            sftp(name = "n".repeat(121))[RemoteField.NAME],
        )
    }

    @Test
    fun `smb without a share reports the share field`() {
        val errors =
            validateRemoteDraft(
                name = "Office",
                protocol = RemoteProtocol.SMB,
                host = "192.0.2.10",
                port = "445",
                username = "user",
                root = "photos",
                share = "",
            )
        assertEquals(mapOf(RemoteField.SHARE to R.string.remote_error_share_required), errors)
    }

    @Test
    fun `smb share with illegal characters is rejected`() {
        val errors =
            validateRemoteDraft(
                name = "Office",
                protocol = RemoteProtocol.SMB,
                host = "192.0.2.10",
                port = "445",
                username = "user",
                root = "photos",
                share = "media/photos",
            )
        assertEquals(R.string.remote_error_share_required, errors[RemoteField.SHARE])
    }

    @Test
    fun `smb root with a drive colon is invalid`() {
        val errors =
            validateRemoteDraft(
                name = "Office",
                protocol = RemoteProtocol.SMB,
                host = "192.0.2.10",
                port = "445",
                username = "user",
                root = "C:/photos",
                share = "media",
            )
        assertEquals(R.string.remote_error_folder_invalid, errors[RemoteField.ROOT])
    }

    @Test
    fun `key auth without a loaded key reports the key field`() {
        val errors = sftp(keyAuth = true, hasKey = false)
        assertEquals(mapOf(RemoteField.KEY to R.string.remote_error_key_required), errors)
        assertTrue(sftp(keyAuth = true, hasKey = true).isEmpty())
    }
}
