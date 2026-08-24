package com.ugallery.core.ml

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAnalysisOnboardingStoreDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun clearState() {
        context.getSharedPreferences("local-analysis-onboarding", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.deleteDatabase(com.ugallery.core.database.GalleryDatabaseFactory.DatabaseName)
    }

    @Test
    fun freshInstallStartsPendingAndPersistsAnswer() {
        val store = LocalAnalysisOnboardingStore(context, freshInstall = { true })

        assertEquals(LocalAnalysisOnboardingDecision.Pending, store.decision())
        store.setDecision(LocalAnalysisOnboardingDecision.Declined)

        assertEquals(
            LocalAnalysisOnboardingDecision.Declined,
            LocalAnalysisOnboardingStore(context, freshInstall = { true }).decision(),
        )
    }

    @Test
    fun upgradeIsResolvedWithoutChangingTaskPreferences() {
        val store = LocalAnalysisOnboardingStore(context, freshInstall = { false })

        assertEquals(LocalAnalysisOnboardingDecision.Accepted, store.decision())
        assertEquals(LocalAnalysisOnboardingDecision.Accepted, store.decision())
    }

    @Test
    fun clearedStorageIsPendingEvenWhenPackageInstallTimestampIsOld() {
        val store = LocalAnalysisOnboardingStore(context)

        assertEquals(LocalAnalysisOnboardingDecision.Pending, store.decision())
    }

    @Test
    fun existingGalleryDatabaseMarksAnUpgradeAsResolved() {
        val database = context.getDatabasePath(
            com.ugallery.core.database.GalleryDatabaseFactory.DatabaseName,
        )
        database.parentFile?.mkdirs()
        database.createNewFile()

        assertEquals(
            LocalAnalysisOnboardingDecision.Accepted,
            LocalAnalysisOnboardingStore(context).decision(),
        )
    }
}
