package com.ugallery.core.ml

import android.content.Context
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class PetType(val canonicalLabel: String) { Dog("dog"), Cat("cat") }

data class PetCollectionSummary(
    val dogCount: Long = 0,
    val catCount: Long = 0,
    val dogCover: MediaKey? = null,
    val catCover: MediaKey? = null,
)

class PetCollectionSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "pet-collection-settings",
        Context.MODE_PRIVATE,
    )

    fun isEnabled(): Boolean = preferences.getBoolean("enabled", false)
    fun setEnabled(enabled: Boolean) { preferences.edit().putBoolean("enabled", enabled).commit() }
}

class PetCollectionRepository(database: GalleryDatabase) {
    private val dao = database.libraryDao()

    fun summary(): Flow<PetCollectionSummary> = dao.petCollectionSummaryFlow().map {
        PetCollectionSummary(
            dogCount = it.dogCount,
            catCount = it.catCount,
            dogCover = it.dogCoverVolumeName?.let { volume ->
                it.dogCoverMediaStoreId?.let { id -> MediaKey(volume, id) }
            },
            catCover = it.catCoverVolumeName?.let { volume ->
                it.catCoverMediaStoreId?.let { id -> MediaKey(volume, id) }
            },
        )
    }
}
