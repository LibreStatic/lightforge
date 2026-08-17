package com.ugallery.core.selection

import java.io.Serializable

/** Immutable description of the rows covered by a select-all operation. */
data class MediaQuery(
    val scope: Scope = Scope.Timeline,
    val kindFilter: KindFilter = KindFilter.ImagesAndVideos,
    val favoriteOnly: Boolean = false,
    val trashedOnly: Boolean = false,
    val fromTimelineMillisInclusive: Long? = null,
    val toTimelineMillisExclusive: Long? = null,
    val sort: Sort = Sort.NewestFirst,
) : Serializable {
    init {
        require(
            fromTimelineMillisInclusive == null ||
                toTimelineMillisExclusive == null ||
                fromTimelineMillisInclusive < toTimelineMillisExclusive,
        ) { "The query time range must not be empty or inverted" }
    }

    enum class KindFilter { Images, Videos, ImagesAndVideos }

    /** The scope is data, never SQL, so restored state cannot inject a query. */
    sealed interface Scope : Serializable {
        data object Timeline : Scope
        data class PhysicalAlbum(val volumeName: String, val bucketId: Long) : Scope {
            init {
                require(volumeName.isNotBlank())
            }
        }
        data class VirtualAlbum(val albumId: Long) : Scope {
            init {
                require(albumId >= 0)
            }
        }
        data class Search(val normalizedQuery: String) : Scope {
            init {
                require(normalizedQuery.isNotBlank())
            }
        }
    }

    enum class Sort { NewestFirst, OldestFirst }
}
