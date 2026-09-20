package com.ugallery.core.remotestorage

import com.ugallery.core.remotestorage.sftp.SftpRemoteConnectionFactory
import com.ugallery.core.remotestorage.smb.SmbRemoteConnectionFactory

class OwnStorageConnectionFactory : RemoteConnectionFactory {
    override fun connect(
        profile: RemoteProfile,
        credentials: RemoteCredentials,
        cancellation: RemoteCancellation,
    ): RemoteConnection {
        profile.validate()
        cancellation.check()
        return when (profile.protocol) {
            RemoteProtocol.SFTP ->
                SftpRemoteConnectionFactory().connect(profile, credentials, cancellation)
            RemoteProtocol.SMB ->
                SmbRemoteConnectionFactory().connect(profile, credentials, cancellation)
        }
    }
}
