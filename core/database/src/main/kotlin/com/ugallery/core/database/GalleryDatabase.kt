package com.ugallery.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MediaItemEntity::class,
        MediaStoreCheckpointEntity::class,
        AlbumAggregateEntity::class,
        VirtualAlbumEntity::class,
        VirtualAlbumMediaEntity::class,
        MediaExifEntity::class,
        MediaLabelRunEntity::class,
        MediaLabelEntity::class,
        LabelSuppressionEntity::class,
        MediaOcrEntity::class,
        DuplicateHashEntity::class,
        SimilarityFeatureEntity::class,
        SimilarityEdgeEntity::class,
        SimilarityMembershipEntity::class,
        SimilarityExclusionEntity::class,
        FaceDetectionRunEntity::class,
        DetectedFaceEntity::class,
        FaceEmbeddingEntity::class,
        PersonClusterEntity::class,
        PersonMembershipEntity::class,
        PersonClusterProjectionEntity::class,
        PersonConstraintEntity::class,
        PersonFaceOverrideEntity::class,
        MeProfileEntity::class,
        MeReferenceEntity::class,
        MeMatchEntity::class,
        MomentEntity::class,
        MomentMemberEntity::class,
        MomentCoverEntity::class,
        MomentRunEntity::class,
        MomentRunCandidateEntity::class,
        EditRecipeEntity::class,
        EditOperationEntity::class,
    ],
    version = 13,
    exportSchema = true,
)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun momentDao(): MomentDao
    abstract fun personDao(): PersonDao
    abstract fun editRecipeDao(): EditRecipeDao
}

object GalleryDatabaseFactory {
    const val DatabaseName = "ugallery-library.db"

    fun open(context: Context, name: String = DatabaseName): GalleryDatabase {
        val database = build(context, name)
        return try {
            database.openHelper.writableDatabase
            database
        } catch (corrupt: SQLiteDatabaseCorruptException) {
            database.close()
            if (!context.deleteDatabase(name)) throw corrupt
            build(context, name).also { it.openHelper.writableDatabase }
        }
    }

    val Migration1To2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE media_store_checkpoints ADD COLUMN deltaTargetGeneration INTEGER")
            db.execSQL(
                "ALTER TABLE media_store_checkpoints ADD COLUMN deltaGenerationCursor INTEGER " +
                    "NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE media_store_checkpoints ADD COLUMN deltaMediaStoreIdCursor INTEGER " +
                    "NOT NULL DEFAULT -1",
            )
        }
    }

    val Migration2To3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `virtual_albums` (
                    `albumId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL, `normalizedName` TEXT NOT NULL,
                    `createdAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL)""",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_virtual_album_name` " +
                    "ON `virtual_albums` (`normalizedName`)",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `virtual_album_media` (
                    `albumId` INTEGER NOT NULL, `volumeName` TEXT NOT NULL,
                    `mediaStoreId` INTEGER NOT NULL, `addedAtMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`albumId`, `volumeName`, `mediaStoreId`),
                    FOREIGN KEY(`albumId`) REFERENCES `virtual_albums`(`albumId`)
                    ON UPDATE NO ACTION ON DELETE CASCADE)""",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_virtual_album_media_key` " +
                    "ON `virtual_album_media` (`volumeName`, `mediaStoreId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_virtual_album_added` " +
                    "ON `virtual_album_media` (`albumId`, `addedAtMillis`)",
            )
        }
    }

    val Migration3To4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `media_exif_cache` (
                    `volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL,
                    `generationModified` INTEGER NOT NULL, `orientation` INTEGER NOT NULL,
                    `dateTimeOriginal` TEXT, `offsetTimeOriginal` TEXT, `make` TEXT, `model` TEXT,
                    `lensModel` TEXT, `focalLength` TEXT, `aperture` TEXT, `exposureTime` TEXT,
                    `iso` INTEGER, `latitude` REAL, `longitude` REAL,
                    `locationReadWithPermission` INTEGER NOT NULL, `cachedAtMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`volumeName`, `mediaStoreId`))""",
            )
        }
    }

    val Migration4To5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE media_items ADD COLUMN dateExpiresSeconds INTEGER")
        }
    }

    val Migration5To6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `media_label_runs` (`volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL, `generationModified` INTEGER NOT NULL, `modelVersion` TEXT NOT NULL, `completedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`volumeName`, `mediaStoreId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_label_run_version` ON `media_label_runs` (`modelVersion`, `generationModified`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `media_labels` (`volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL, `canonicalLabel` TEXT NOT NULL, `rawLabel` TEXT NOT NULL, `confidence` REAL NOT NULL, `modelVersion` TEXT NOT NULL, PRIMARY KEY(`volumeName`, `mediaStoreId`, `canonicalLabel`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_label_canonical` ON `media_labels` (`canonicalLabel`, `confidence`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `label_suppressions` (`canonicalLabel` TEXT NOT NULL, `suppressedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`canonicalLabel`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `media_ocr` (`volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL, `generationModified` INTEGER NOT NULL, `modelVersion` TEXT NOT NULL, `rawText` TEXT NOT NULL, `normalizedText` TEXT NOT NULL, `blocksJson` TEXT NOT NULL, `completedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`volumeName`, `mediaStoreId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_ocr_version` ON `media_ocr` (`modelVersion`, `generationModified`)")
        }
    }

    val Migration6To7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `duplicate_hashes` (
                    `volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL,
                    `generationModified` INTEGER NOT NULL, `sizeBytes` INTEGER NOT NULL,
                    `hashVersion` TEXT NOT NULL, `sampleSha256` TEXT NOT NULL,
                    `sha256` TEXT, `updatedAtMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`volumeName`, `mediaStoreId`),
                    FOREIGN KEY(`volumeName`, `mediaStoreId`) REFERENCES `media_items`(`volumeName`, `mediaStoreId`)
                    ON UPDATE NO ACTION ON DELETE CASCADE)""",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_duplicate_sample` ON `duplicate_hashes` (`hashVersion`, `sizeBytes`, `sampleSha256`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_duplicate_full` ON `duplicate_hashes` (`hashVersion`, `sizeBytes`, `sha256`)")
        }
    }

    val Migration7To8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `similarity_features` (`volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL, `generationModified` INTEGER NOT NULL, `algorithmVersion` TEXT NOT NULL, `pHash` INTEGER NOT NULL, `compactEmbedding` BLOB NOT NULL, `lsh0` INTEGER NOT NULL, `lsh1` INTEGER NOT NULL, `lsh2` INTEGER NOT NULL, `lsh3` INTEGER NOT NULL, `blurScore` REAL NOT NULL, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`volumeName`, `mediaStoreId`), FOREIGN KEY(`volumeName`, `mediaStoreId`) REFERENCES `media_items`(`volumeName`, `mediaStoreId`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_similarity_lsh0` ON `similarity_features` (`algorithmVersion`, `lsh0`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_similarity_lsh1` ON `similarity_features` (`algorithmVersion`, `lsh1`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_similarity_lsh2` ON `similarity_features` (`algorithmVersion`, `lsh2`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_similarity_lsh3` ON `similarity_features` (`algorithmVersion`, `lsh3`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `similarity_edges` (`aVolumeName` TEXT NOT NULL, `aMediaStoreId` INTEGER NOT NULL, `bVolumeName` TEXT NOT NULL, `bMediaStoreId` INTEGER NOT NULL, `score` REAL NOT NULL, PRIMARY KEY(`aVolumeName`, `aMediaStoreId`, `bVolumeName`, `bMediaStoreId`), FOREIGN KEY(`aVolumeName`, `aMediaStoreId`) REFERENCES `similarity_features`(`volumeName`, `mediaStoreId`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`bVolumeName`, `bMediaStoreId`) REFERENCES `similarity_features`(`volumeName`, `mediaStoreId`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_similarity_edge_b` ON `similarity_edges` (`bVolumeName`, `bMediaStoreId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `similarity_memberships` (`volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL, `clusterId` TEXT NOT NULL, `bestScore` REAL NOT NULL, PRIMARY KEY(`volumeName`, `mediaStoreId`), FOREIGN KEY(`volumeName`, `mediaStoreId`) REFERENCES `similarity_features`(`volumeName`, `mediaStoreId`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_similarity_cluster` ON `similarity_memberships` (`clusterId`, `bestScore`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `similarity_exclusions` (`volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL, `excludedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`volumeName`, `mediaStoreId`), FOREIGN KEY(`volumeName`, `mediaStoreId`) REFERENCES `media_items`(`volumeName`, `mediaStoreId`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        }
    }

    val Migration8To9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `face_detection_runs` (`volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL, `generationModified` INTEGER NOT NULL, `modelVersion` TEXT NOT NULL, `acceptedFaceCount` INTEGER NOT NULL, `completedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`volumeName`, `mediaStoreId`), FOREIGN KEY(`volumeName`, `mediaStoreId`) REFERENCES `media_items`(`volumeName`, `mediaStoreId`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_face_run_version` ON `face_detection_runs` (`modelVersion`, `generationModified`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `detected_faces` (`volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL, `faceOrdinal` INTEGER NOT NULL, `modelVersion` TEXT NOT NULL, `leftPermille` INTEGER NOT NULL, `topPermille` INTEGER NOT NULL, `rightPermille` INTEGER NOT NULL, `bottomPermille` INTEGER NOT NULL, `cropLeftPermille` INTEGER NOT NULL, `cropTopPermille` INTEGER NOT NULL, `cropRightPermille` INTEGER NOT NULL, `cropBottomPermille` INTEGER NOT NULL, `eulerX` REAL NOT NULL, `eulerY` REAL NOT NULL, `eulerZ` REAL NOT NULL, `qualityScore` REAL NOT NULL, `landmarksJson` TEXT NOT NULL, PRIMARY KEY(`volumeName`, `mediaStoreId`, `faceOrdinal`), FOREIGN KEY(`volumeName`, `mediaStoreId`) REFERENCES `face_detection_runs`(`volumeName`, `mediaStoreId`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_detected_face_media` ON `detected_faces` (`volumeName`, `mediaStoreId`)")
        }
    }

    val Migration9To10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `moments` (`momentId` TEXT NOT NULL, `origin` TEXT NOT NULL, `state` TEXT NOT NULL, `algorithmVersion` TEXT NOT NULL, `startMillis` INTEGER NOT NULL, `endMillis` INTEGER NOT NULL, `title` TEXT, `titleMode` TEXT NOT NULL, `isUserEdited` INTEGER NOT NULL, `createdAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`momentId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_moment_state_updated` ON `moments` (`state`,`updatedAtMillis`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_moment_state_start` ON `moments` (`state`,`startMillis`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `moment_members` (`momentId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL, `generationModifiedAtSelection` INTEGER NOT NULL, `origin` TEXT NOT NULL, `score` REAL NOT NULL, PRIMARY KEY(`momentId`,`ordinal`), FOREIGN KEY(`momentId`) REFERENCES `moments`(`momentId`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`volumeName`,`mediaStoreId`) REFERENCES `media_items`(`volumeName`,`mediaStoreId`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_moment_member_key` ON `moment_members` (`volumeName`,`mediaStoreId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_moment_member_order` ON `moment_members` (`momentId`,`ordinal`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `moment_covers` (`momentId` TEXT NOT NULL, `volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL, `isUserSelected` INTEGER NOT NULL, PRIMARY KEY(`momentId`), FOREIGN KEY(`momentId`) REFERENCES `moments`(`momentId`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`volumeName`,`mediaStoreId`) REFERENCES `media_items`(`volumeName`,`mediaStoreId`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_moment_cover_key` ON `moment_covers` (`volumeName`,`mediaStoreId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `moment_runs` (`algorithmVersion` TEXT NOT NULL, `runId` TEXT NOT NULL, `status` TEXT NOT NULL, `afterTimelineSortMillis` INTEGER, `afterMediaStoreId` INTEGER, `afterVolumeName` TEXT, `openStartMillis` INTEGER, `openEndMillis` INTEGER, `openLastMillis` INTEGER, `openItemCount` INTEGER NOT NULL, `processedItems` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`algorithmVersion`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `moment_run_candidates` (`algorithmVersion` TEXT NOT NULL, `rank` INTEGER NOT NULL, `volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL, `generationModified` INTEGER NOT NULL, `timelineSortMillis` INTEGER NOT NULL, `score` REAL NOT NULL, `timeBucket` INTEGER NOT NULL, `visualBucket` TEXT NOT NULL, PRIMARY KEY(`algorithmVersion`,`rank`), FOREIGN KEY(`algorithmVersion`) REFERENCES `moment_runs`(`algorithmVersion`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`volumeName`,`mediaStoreId`) REFERENCES `media_items`(`volumeName`,`mediaStoreId`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_moment_run_candidate_key` ON `moment_run_candidates` (`volumeName`,`mediaStoreId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_moment_scan` ON `media_items` (`isAccessible`,`isTrashed`,`timelineSortMillis`,`mediaStoreId`,`volumeName`)")
        }
    }

    val Migration10To11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `face_embeddings` (`volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL, `faceOrdinal` INTEGER NOT NULL, `detectionModelVersion` TEXT NOT NULL, `embeddingModelVersion` TEXT NOT NULL, `quantizedVector` BLOB NOT NULL, `completedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`volumeName`, `mediaStoreId`, `faceOrdinal`), FOREIGN KEY(`volumeName`, `mediaStoreId`, `faceOrdinal`) REFERENCES `detected_faces`(`volumeName`, `mediaStoreId`, `faceOrdinal`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_face_embedding_version` ON `face_embeddings` (`embeddingModelVersion`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_face_embedding_media` ON `face_embeddings` (`volumeName`, `mediaStoreId`)")
        }
    }

    val Migration11To12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `person_clusters` (`clusterId` TEXT NOT NULL, `algorithmVersion` TEXT NOT NULL, `centroidVector` BLOB NOT NULL, `memberCount` INTEGER NOT NULL, `displayName` TEXT, `isHidden` INTEGER NOT NULL, `isUserEdited` INTEGER NOT NULL, `createdAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`clusterId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_person_cluster_visible` ON `person_clusters` (`algorithmVersion`, `isHidden`, `updatedAtMillis`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `person_memberships` (`volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL, `faceOrdinal` INTEGER NOT NULL, `clusterId` TEXT NOT NULL, `algorithmVersion` TEXT NOT NULL, `assignmentSource` TEXT NOT NULL, `similarity` REAL NOT NULL, `assignedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`volumeName`, `mediaStoreId`, `faceOrdinal`), FOREIGN KEY(`volumeName`, `mediaStoreId`, `faceOrdinal`) REFERENCES `face_embeddings`(`volumeName`, `mediaStoreId`, `faceOrdinal`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`clusterId`) REFERENCES `person_clusters`(`clusterId`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_person_membership_cluster` ON `person_memberships` (`clusterId`, `similarity`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `person_cluster_projections` (`clusterId` TEXT NOT NULL, `band` INTEGER NOT NULL, `q0` INTEGER NOT NULL, `q1` INTEGER NOT NULL, `q2` INTEGER NOT NULL, `q3` INTEGER NOT NULL, `q4` INTEGER NOT NULL, `q5` INTEGER NOT NULL, PRIMARY KEY(`clusterId`, `band`), FOREIGN KEY(`clusterId`) REFERENCES `person_clusters`(`clusterId`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_person_projection_lookup` ON `person_cluster_projections` (`band`, `q0`, `q1`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `person_constraints` (`leftVolumeName` TEXT NOT NULL, `leftMediaStoreId` INTEGER NOT NULL, `leftFaceOrdinal` INTEGER NOT NULL, `rightVolumeName` TEXT NOT NULL, `rightMediaStoreId` INTEGER NOT NULL, `rightFaceOrdinal` INTEGER NOT NULL, `relation` TEXT NOT NULL, `preferredClusterId` TEXT, `createdAtMillis` INTEGER NOT NULL, PRIMARY KEY(`leftVolumeName`, `leftMediaStoreId`, `leftFaceOrdinal`, `rightVolumeName`, `rightMediaStoreId`, `rightFaceOrdinal`), FOREIGN KEY(`leftVolumeName`, `leftMediaStoreId`, `leftFaceOrdinal`) REFERENCES `detected_faces`(`volumeName`, `mediaStoreId`, `faceOrdinal`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`rightVolumeName`, `rightMediaStoreId`, `rightFaceOrdinal`) REFERENCES `detected_faces`(`volumeName`, `mediaStoreId`, `faceOrdinal`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_person_constraint_right` ON `person_constraints` (`rightVolumeName`, `rightMediaStoreId`, `rightFaceOrdinal`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `person_face_overrides` (`volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL, `faceOrdinal` INTEGER NOT NULL, `clusterId` TEXT NOT NULL, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`volumeName`, `mediaStoreId`, `faceOrdinal`), FOREIGN KEY(`volumeName`, `mediaStoreId`, `faceOrdinal`) REFERENCES `detected_faces`(`volumeName`, `mediaStoreId`, `faceOrdinal`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`clusterId`) REFERENCES `person_clusters`(`clusterId`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_person_face_override_cluster` ON `person_face_overrides` (`clusterId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `me_profiles` (`profileId` INTEGER NOT NULL, `embeddingModelVersion` TEXT NOT NULL, `centroidVector` BLOB NOT NULL, `matchThreshold` REAL NOT NULL, `referenceCount` INTEGER NOT NULL, `state` TEXT NOT NULL, `afterVolumeName` TEXT, `afterMediaStoreId` INTEGER, `afterFaceOrdinal` INTEGER, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`profileId`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `me_references` (`profileId` INTEGER NOT NULL, `volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL, `faceOrdinal` INTEGER NOT NULL, `addedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `volumeName`, `mediaStoreId`, `faceOrdinal`), FOREIGN KEY(`profileId`) REFERENCES `me_profiles`(`profileId`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`volumeName`, `mediaStoreId`, `faceOrdinal`) REFERENCES `face_embeddings`(`volumeName`, `mediaStoreId`, `faceOrdinal`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_me_reference_face` ON `me_references` (`volumeName`, `mediaStoreId`, `faceOrdinal`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `me_matches` (`profileId` INTEGER NOT NULL, `volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL, `faceOrdinal` INTEGER NOT NULL, `similarity` REAL NOT NULL, `matchedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `volumeName`, `mediaStoreId`, `faceOrdinal`), FOREIGN KEY(`profileId`) REFERENCES `me_profiles`(`profileId`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`volumeName`, `mediaStoreId`, `faceOrdinal`) REFERENCES `face_embeddings`(`volumeName`, `mediaStoreId`, `faceOrdinal`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_me_match_score` ON `me_matches` (`profileId`, `similarity`)")
        }
    }

    val Migration12To13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `edit_recipes` (
                    `recipeId` TEXT NOT NULL, `volumeName` TEXT NOT NULL,
                    `mediaStoreId` INTEGER NOT NULL, `sourceGenerationModified` INTEGER NOT NULL,
                    `revision` INTEGER NOT NULL, `createdAtMillis` INTEGER NOT NULL,
                    `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`recipeId`),
                    FOREIGN KEY(`volumeName`,`mediaStoreId`) REFERENCES `media_items`(`volumeName`,`mediaStoreId`)
                    ON UPDATE NO ACTION ON DELETE CASCADE)""",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_edit_recipe_source` ON `edit_recipes` (`volumeName`,`mediaStoreId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_edit_recipe_updated` ON `edit_recipes` (`updatedAtMillis`)")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `edit_operations` (
                    `recipeId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL,
                    `encodedOperation` TEXT NOT NULL, PRIMARY KEY(`recipeId`,`ordinal`),
                    FOREIGN KEY(`recipeId`) REFERENCES `edit_recipes`(`recipeId`)
                    ON UPDATE NO ACTION ON DELETE CASCADE)""",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_edit_operation_recipe` ON `edit_operations` (`recipeId`,`ordinal`)")
        }
    }

    private fun build(context: Context, name: String): GalleryDatabase = Room.databaseBuilder(
        context.applicationContext,
        GalleryDatabase::class.java,
        name,
    ).addMigrations(
        Migration1To2, Migration2To3, Migration3To4, Migration4To5, Migration5To6, Migration6To7,
        Migration7To8, Migration8To9, Migration9To10, Migration10To11, Migration11To12,
        Migration12To13,
    ).build()
}
