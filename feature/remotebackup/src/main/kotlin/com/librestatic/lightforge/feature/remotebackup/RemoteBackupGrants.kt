package com.librestatic.lightforge.feature.remotebackup

import android.content.Context
import com.librestatic.lightforge.feature.settings.PersistableUriGrants

/**
 * Records prior permission ownership before taking a grant. Completed and cancelled tasks release
 * theirs; RestoringLocally hands the destination over to the local restore ledger.
 */
fun RemoteBackupGrants(context: Context) =
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
