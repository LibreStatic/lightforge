package com.librestatic.lightforge

import android.content.Context
import com.librestatic.lightforge.core.mediastore.AdvancedStorage
import com.librestatic.lightforge.core.mediastore.NoOpAdvancedStorage
import com.librestatic.lightforge.core.storageadvanced.FileAdvancedStorage
import dagger.Module
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
}
