package com.ugallery.feature.remotebackup

import android.content.Context
import com.ugallery.feature.settings.PersistableUriGrants

/**
 * Records prior permission ownership before taking a grant. Completed and cancelled tasks release
 * theirs; RestoringLocally keeps the destination for the local restore it handed off to.
 */
internal fun RemoteBackupGrants(context: Context) =
    PersistableUriGrants(context, "remote-backup-grants.json") {
        RemoteBackupStore(context)
            .list()
            .filter {
                it.status != RemoteBackupStatus.Completed &&
                    it.status != RemoteBackupStatus.Cancelled
            }
            .map { it.id }
            .toSet()
    }
