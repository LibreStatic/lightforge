package com.librestatic.lightforge.feature.collections

import androidx.compose.runtime.saveable.listSaver
import com.librestatic.lightforge.core.data.MemoryDateRange
import java.time.LocalDate
import java.time.ZoneId

/** Small civil-date input only. Saved state never contains photos, rules or analysis results. */
internal data class MemoryControlsDraft(
    val start: String = "",
    val end: String = "",
    val zone: String = ZoneId.systemDefault().id,
) {
    fun range(): MemoryDateRange? =
        runCatching {
                require(start.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
                require(end.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
                MemoryDateRange(LocalDate.parse(start), LocalDate.parse(end), zone)
            }
            .getOrNull()

    companion object {
        val Saver =
            listSaver<MemoryControlsDraft, String>(
                save = { listOf(it.start, it.end, it.zone) },
                restore = { MemoryControlsDraft(it[0], it[1], it[2]) },
            )
    }
}
