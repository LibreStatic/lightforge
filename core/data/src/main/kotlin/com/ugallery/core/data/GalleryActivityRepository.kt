package com.ugallery.core.data

import com.ugallery.core.database.ActivityEventEntity
import com.ugallery.core.database.GalleryDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class GalleryActivityType {
    Archived, Unarchived, Trashed, Restored, Deleted, Exported, Imported, AnalysisCompleted, AnalysisFailed,
}

data class GalleryActivityEvent(
    val id: Long,
    val type: GalleryActivityType,
    val occurredAtMillis: Long,
    val itemCount: Long,
    val detail: String?,
)

class GalleryActivityRepository(
    database: GalleryDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.activityDao()

    fun latest(limit: Int = 100): Flow<List<GalleryActivityEvent>> {
        require(limit in 1..100)
        return dao.observeLatest(limit).map { events -> events.mapNotNull { it.toModel() } }
    }

    suspend fun record(type: GalleryActivityType, itemCount: Long, detail: String? = null) {
        require(itemCount >= 0)
        dao.insertAndPrune(ActivityEventEntity(type = type.name, occurredAtMillis = nowMillis(), itemCount = itemCount, detail = detail))
    }

    private fun ActivityEventEntity.toModel(): GalleryActivityEvent? =
        runCatching { GalleryActivityType.valueOf(type) }.getOrNull()?.let {
            GalleryActivityEvent(eventId, it, occurredAtMillis, itemCount, detail)
        }
}
