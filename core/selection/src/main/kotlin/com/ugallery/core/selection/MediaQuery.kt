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
    val sortField: SortField = SortField.DateTaken,
    val grouping: Grouping = Grouping.Day,
    val folderMode: FolderMode = FolderMode.AllExceptExcluded,
    val includedFolders: Set<PhysicalFolder> = emptySet(),
    val excludedFolders: Set<PhysicalFolder> = emptySet(),
) : Serializable {
    init {
        require(
            fromTimelineMillisInclusive == null ||
                toTimelineMillisExclusive == null ||
                fromTimelineMillisInclusive < toTimelineMillisExclusive,
        ) { "The query time range must not be empty or inverted" }
    }

    enum class KindFilter { Images, Videos, Animated, Raw, ImagesAndVideos }

    enum class SortField { DateTaken, DateModified, Name, Size }
    enum class Grouping { Day, Month, Year, None }
    enum class FolderMode { AllExceptExcluded, OnlyIncluded }
    data class PhysicalFolder(val volumeName: String, val bucketId: Long) : Serializable {
        init { require(volumeName.isNotBlank()) }
    }

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
        data class LargeVideos(val minimumBytes: Long) : Scope {
            init { require(minimumBytes > 0) }
        }
        data object Screenshots : Scope
        data class BlurryCandidates(val maximumScore: Float, val algorithmVersion: String) : Scope {
            init {
                require(maximumScore >= 0f)
                require(algorithmVersion.matches(Regex("[a-zA-Z0-9._-]{1,80}")))
            }
        }
        data class ExactDuplicateGroup(
            val sha256: String,
            val sizeBytes: Long,
            val hashVersion: String,
        ) : Scope {
            init {
                require(sha256.matches(Regex("[a-fA-F0-9]{64}")))
                require(sizeBytes > 0)
                require(hashVersion.matches(Regex("[a-zA-Z0-9._-]{1,80}")))
            }
        }
    }

    enum class Sort { NewestFirst, OldestFirst }
}
