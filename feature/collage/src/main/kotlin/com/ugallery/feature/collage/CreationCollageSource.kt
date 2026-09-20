package com.ugallery.feature.collage

import android.net.Uri

/** Generation-added prevents attaching a reused MediaStore row to an earlier selection. */
data class CreationCollageSource(
    val uri: Uri,
    val expectedGeneration: Long? = null,
    val expectedGenerationAdded: Long? = null,
) {
    init {
        require(uri.scheme in setOf("content", "file"))
        require(expectedGeneration == null || expectedGeneration >= 0)
        require(expectedGenerationAdded == null || expectedGenerationAdded >= 0)
        require(expectedGenerationAdded == null || expectedGeneration != null)
        require(expectedGeneration == null || (uri.scheme == "content" && uri.authority == "media"))
    }
    val identity: String get() = "$uri@$expectedGeneration/$expectedGenerationAdded"
}

/** Every existing template plus the four-photo strip shown in the approved creation preview. */
enum class CreationCollageTemplate(val sourceCount: Int, internal val legacy: CollageTemplate?) {
    Grid2(2, CollageTemplate.GRID_2), Grid3(3, CollageTemplate.GRID_3),
    Grid4(4, CollageTemplate.GRID_4), Stack3(3, CollageTemplate.STACK_3),
    Strip3(3, CollageTemplate.STRIP_3), Polaroid3(3, CollageTemplate.POLAROID_3),
    Strip4(4, null);

    internal fun slots(): List<CollageSlot> = legacy?.let(CollageTemplates::getSlots)
        ?: List(4) { CollageSlot(it / 4f, 0f, .25f, 1f) }

    companion object {
        fun forCount(count: Int) = entries.filter { it.sourceCount == count }
    }
}

data class CreationCollageCrop(val zoom: Float = 1f, val horizontal: Float = 0f, val vertical: Float = 0f) {
    init {
        require(zoom.isFinite() && zoom in 1f..3f)
        require(horizontal.isFinite() && horizontal in -1f..1f)
        require(vertical.isFinite() && vertical in -1f..1f)
    }
}

data class CreationCollageLayout(
    val template: CreationCollageTemplate,
    val order: List<Int>,
    val crops: List<CreationCollageCrop> = List(order.size) { CreationCollageCrop() },
) {
    init {
        require(order.size == template.sourceCount)
        require(order.sorted() == order.indices.toList())
        require(crops.size == order.size)
    }
    /** Crops belong to a source, never to a mutable slot. */
    fun move(slot: Int, delta: Int): CreationCollageLayout {
        require(slot in order.indices && slot + delta in order.indices)
        return copy(order = order.toMutableList().apply { add(slot + delta, removeAt(slot)) })
    }
    fun crop(sourceIndex: Int, crop: CreationCollageCrop): CreationCollageLayout {
        require(sourceIndex in order.indices)
        return copy(crops = crops.toMutableList().apply { set(sourceIndex, crop) })
    }
    companion object {
        fun initial(count: Int): CreationCollageLayout = CreationCollageLayout(
            CreationCollageTemplate.forCount(count).first(), (0 until count).toList())
    }
}
