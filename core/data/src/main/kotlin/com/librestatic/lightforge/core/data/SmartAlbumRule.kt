package com.librestatic.lightforge.core.data

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * All non-null criteria combine with AND. Persisted civil-year boundaries never drift with device
 * timezone.
 */
data class SmartAlbumRule(
    val topic: String? = null,
    val personClusterId: String? = null,
    val personAlgorithmVersion: String? = null,
    val year: Int? = null,
    val zoneId: String = ZoneId.systemDefault().id,
    val favoritesOnly: Boolean = false,
) {
    init {
        require(topic == null || (topic.isNotBlank() && topic.length <= 128))
        require((personClusterId == null) == (personAlgorithmVersion == null))
        require(
            personClusterId == null ||
                (personClusterId.isNotBlank() && personClusterId.length <= 200)
        )
        require(
            personAlgorithmVersion == null ||
                (personAlgorithmVersion.isNotBlank() && personAlgorithmVersion.length <= 200)
        )
        require(year == null || year in 1..9998)
        ZoneId.of(zoneId)
    }

    fun normalized() = copy(topic = topic?.trim()?.lowercase(Locale.ROOT))

    fun bounds(): Pair<Long?, Long?> =
        if (year == null) null to null
        else {
            val zone = ZoneId.of(zoneId)
            LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli() to
                LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        }

    companion object {
        fun normalizeName(name: String): String =
            name.trim().replace(Regex("\\s+"), " ").also {
                require(it.isNotBlank() && it.length <= 80)
            }
    }
}
