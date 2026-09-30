package com.librestatic.lightforge

import android.content.Context
import com.librestatic.lightforge.feature.permissions.PermissionCoordinator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object GalleryAppModule {
    @Provides
    @Singleton
    fun permissionCoordinator(@ApplicationContext context: Context): PermissionCoordinator =
        PermissionCoordinator(context)
}
