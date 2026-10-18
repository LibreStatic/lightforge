package com.librestatic.lightforge

import android.content.Context
import com.librestatic.lightforge.core.mediastore.AdvancedStorage
import com.librestatic.lightforge.core.mediastore.NoOpAdvancedStorage
import com.librestatic.lightforge.core.storageadvanced.FileAdvancedStorage
import com.librestatic.lightforge.core.preferences.GallerySettingsRepository
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The single binding point for advanced storage (ADR-046); delete with `:core:storage-advanced` to remove the feature. */
@Module
@InstallIn(SingletonComponent::class)
object AdvancedStorageModule {
    @Provides
    @Singleton
    fun advancedStorage(@ApplicationContext context: Context): AdvancedStorage =
        if (BuildConfig.ALL_FILES_ACCESS) FileAdvancedStorage(context) else NoOpAdvancedStorage

    /** One repository over the app-wide settings store, shared with the advanced storage status. */
    @Provides
    @Singleton
    fun gallerySettingsRepository(@ApplicationContext context: Context): GallerySettingsRepository =
        GallerySettingsRepository(context)
}

/** Lets composables that are not Hilt-injected reach the app-level capability source. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AdvancedStorageEntryPoint {
    fun status(): AdvancedStorageStatus
}
