package com.ugallery.app

/** ViewModel-owned draft keeps a pending save and its error across Activity recreation. */
data class AlbumRenameState(
    val albumId: Long,
    val name: String,
    val working: Boolean = false,
    val saveFailed: Boolean = false,
)
