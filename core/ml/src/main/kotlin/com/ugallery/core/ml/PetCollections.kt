package com.ugallery.core.ml

import android.content.Context
import com.ugallery.core.database.GalleryDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class PetType(val canonicalLabel: String) { Dog("dog"), Cat("cat") }

data class PetCollectionSummary(val dogCount: Long = 0, val catCount: Long = 0)

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
        PetCollectionSummary(it.dogCount, it.catCount)
    }
}
