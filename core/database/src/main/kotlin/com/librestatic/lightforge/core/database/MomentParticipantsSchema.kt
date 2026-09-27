package com.librestatic.lightforge.core.database

import androidx.sqlite.db.SupportSQLiteDatabase

internal object MomentParticipantsSchema {
    fun install(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS moment_participant_state (momentId TEXT NOT NULL, mode TEXT NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(momentId), FOREIGN KEY(momentId) REFERENCES moments(momentId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS moment_participants (momentId TEXT NOT NULL, clusterId TEXT NOT NULL, PRIMARY KEY(momentId,clusterId), FOREIGN KEY(momentId) REFERENCES moment_participant_state(momentId) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(clusterId) REFERENCES person_clusters(clusterId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_moment_participants_clusterId ON moment_participants(clusterId)")
    }
}
