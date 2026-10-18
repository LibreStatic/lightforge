package com.librestatic.lightforge.feature.collections

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.room.Room
import com.librestatic.lightforge.core.data.GalleryDocumentRepository
import com.librestatic.lightforge.core.data.GalleryPhotoStackRepository
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.designsystem.LightforgeTheme

// Debug-only, zero-argument entry points for tools/compose-driver. They run the real screens on an
// empty in-memory database to inspect their full-screen empty and missing states.

@Composable
private fun emptyDatabase(): GalleryDatabase {
    val context = LocalContext.current
    return remember {
        Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).allowMainThreadQueries().build()
    }
}

/** Photo stacks with nothing saved: Back and the title in the top bar, the empty state centered. */
@Composable
fun PhotoStacksEmptyPreview() {
    val repository = GalleryPhotoStackRepository(emptyDatabase())
    LightforgeTheme {
        Surface(Modifier.fillMaxSize()) {
            PhotoStacksContent(repository, thumbnailLoader = null, onBack = {}, onAnalysis = {})
        }
    }
}

/** A saved stack that no longer exists: an explanatory state with the way back to Saved. */
@Composable
fun PhotoStacksMissingPreview() {
    val repository = GalleryPhotoStackRepository(emptyDatabase())
    LightforgeTheme {
        Surface(Modifier.fillMaxSize()) {
            PhotoStacksContent(repository, thumbnailLoader = null, onBack = {}, onAnalysis = {}, initialStackId = "gone")
        }
    }
}

/** Documents with no documents: the category controls above, the empty state centered below. */
@Composable
fun DocumentsEmptyPreview() {
    val repository = GalleryDocumentRepository(emptyDatabase())
    LightforgeTheme {
        Surface(Modifier.fillMaxSize()) {
            DocumentsContent(repository, thumbnailLoader = null, onPdf = {}, onPdfStudio = {}, onBack = {})
        }
    }
}
