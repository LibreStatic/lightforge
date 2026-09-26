package com.ugallery.feature.collage

/** Bundle-native strings only. No cache paths, bitmaps, serialized objects or export replay. */
internal data class CreationCollageDraftSnapshot(
    val draftId: String,
    val identities: List<String>,
    val layout: CreationCollageLayout,
    val selectedSlot: Int,
    val resultUri: String? = null,
    val resultSha256: String? = null,
    val publicationUncertain: Boolean = false,
    val resultNotified: Boolean = false,
    val publicationUsesJournal: Boolean = false,
) {
    fun encode(): ArrayList<String> = arrayListOf("2", draftId, identities.size.toString()).apply {
        addAll(identities)
        add(layout.template.name); add(selectedSlot.toString())
        add(publicationUncertain.toString()); add(resultUri.orEmpty()); add(resultSha256.orEmpty())
        add(resultNotified.toString()); add(publicationUsesJournal.toString())
        addAll(layout.order.map(Int::toString))
        layout.crops.forEach { add(it.zoom.toString()); add(it.horizontal.toString()); add(it.vertical.toString()) }
    }

    companion object {
        // Leaves ample Bundle/list overhead within the 16 KiB feature-state budget.
        private const val MaximumTextBytes = 8 * 1024
        private val outputUri = Regex("content://media/(external|external_primary)/images/media/[1-9][0-9]*")
        private val hash = Regex("[0-9a-f]{64}")

        fun restore(raw: Any?, draftId: String, identities: List<String>): CreationCollageDraftSnapshot? {
            return try {
                require(draftId.isNotBlank() && draftId.length <= 128)
                require(identities.size in 2..4 && identities.distinct().size == identities.size)
                val values = (raw as? List<*>) ?: return null
                require(values.size in setOf(9 + identities.size * 5, 10 + identities.size * 5))
                val strings = values.map { it as? String ?: return null }
                require(strings.all { it.length <= 1024 })
                require(strings.sumOf { it.toByteArray(Charsets.UTF_8).size } <= MaximumTextBytes)
                var at = 0
                fun next() = strings[at++]
                val version = next()
                require(version == "1" || version == "2")
                require(values.size == (if (version == "1") 9 else 10) + identities.size * 5)
                require(next() == draftId && next().toInt() == identities.size)
                val savedIdentities = List(identities.size) { next() }
                require(savedIdentities == identities && savedIdentities.all(String::isNotBlank))
                val template = CreationCollageTemplate.valueOf(next())
                val selected = next().toInt()
                val uncertain = next().toBooleanStrict()
                val result = next().ifEmpty { null }
                val digest = next().ifEmpty { null }
                val notified = next().toBooleanStrict()
                val usesJournal = if (version == "2") next().toBooleanStrict() else false
                require((result == null) == (digest == null))
                require(result == null || (outputUri.matches(result) && hash.matches(requireNotNull(digest))))
                require(!uncertain || result == null)
                require(!notified || result != null)
                val order = List(identities.size) { next().toInt() }
                val crops = List(identities.size) { CreationCollageCrop(next().toFloat(), next().toFloat(), next().toFloat()) }
                require(selected in order.indices)
                CreationCollageDraftSnapshot(draftId, savedIdentities,
                    CreationCollageLayout(template, order, crops), selected, result, digest, uncertain, notified, usesJournal)
            } catch (_: IllegalArgumentException) { null }
        }
    }
}

/** Journal outcome, kept separate from transient preview failures and legacy SavedState hints. */
enum class CreationCollagePublicationUi { Checking, None, RetryableMissing, Incomplete, Conflict, Unreadable, Published }

internal fun collageAllowsNewRender(status: CreationCollagePublicationUi, sourcesAvailable: Boolean): Boolean =
    status == CreationCollagePublicationUi.None && sourcesAvailable

/**
 * The outcome surface sits under the editor controls, so a finished "Save a copy" can land entirely
 * below the fold while every visible control is disabled. Settled publications are scrolled into
 * view, exactly the states that disable editing while no work is running (same rule as GIF).
 */
internal fun collageRevealsOutcome(status: CreationCollagePublicationUi, busy: Boolean): Boolean =
    !busy && status != CreationCollagePublicationUi.None && status != CreationCollagePublicationUi.Checking

internal fun collageKeepsRecovery(status: CreationCollagePublicationUi): Boolean =
    status != CreationCollagePublicationUi.None && status != CreationCollagePublicationUi.Published
