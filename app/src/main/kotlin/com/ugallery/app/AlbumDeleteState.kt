package com.ugallery.app

import com.ugallery.core.model.AlbumKey

/** ViewModel-owned confirmation keeps the pending delete and its error across Activity recreation. */
data class AlbumDeleteState(
    val albumId: Long,
    val name: String,
    val working: Boolean = false,
    val deleteFailed: Boolean = false,
)

/**
 * Only virtual albums are app state that can be dropped. Device folders are filesystem directories
 * backed by [AlbumKey.Physical] and are never deletable from here. Returns the row id to delete, or
 * null when the album must not be offered a delete action; the id is also bounds-checked so a bad
 * value never reaches GalleryAlbumRepository.deleteVirtualAlbum's `require(albumId > 0)`.
 */
fun deletableVirtualAlbumId(key: AlbumKey?): Long? =
    (key as? AlbumKey.Virtual)?.albumId?.takeIf { it > 0 }
