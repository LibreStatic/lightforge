package com.librestatic.lightforge.core.preferences

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import org.json.JSONTokener

enum class PortablePreferenceGroup {
    Presentation,
    Playback,
    Gestures,
}

/**
 * Only these fields cross devices. Missing fields never reset destination choices, and the
 * removed onboardingShown key of older exports is ignored.
 */
enum class PortablePreferenceField(
    val group: PortablePreferenceGroup,
    val section: String,
    val key: String,
) {
    CollectionOrder(PortablePreferenceGroup.Presentation, "library", "collectionOrder"),
    HiddenCollections(PortablePreferenceGroup.Presentation, "library", "hiddenCollections"),
    Sort(PortablePreferenceGroup.Presentation, "library", "sort"),
    Ascending(PortablePreferenceGroup.Presentation, "library", "ascending"),
    Filter(PortablePreferenceGroup.Presentation, "library", "filter"),
    Grouping(PortablePreferenceGroup.Presentation, "library", "grouping"),
    Crop(PortablePreferenceGroup.Presentation, "thumbnails", "cropToFill"),
    Animate(PortablePreferenceGroup.Presentation, "thumbnails", "animateMedia"),
    Duration(PortablePreferenceGroup.Presentation, "thumbnails", "showVideoDuration"),
    FileType(PortablePreferenceGroup.Presentation, "thumbnails", "showFileType"),
    Favorites(PortablePreferenceGroup.Presentation, "thumbnails", "markFavorites"),
    Columns(PortablePreferenceGroup.Presentation, "thumbnails", "gridColumns"),
    Autoplay(PortablePreferenceGroup.Playback, "playback", "autoplayVideos"),
    Muted(PortablePreferenceGroup.Playback, "playback", "startVideosMuted"),
    Loop(PortablePreferenceGroup.Playback, "playback", "loopVideos"),
    RememberPosition(PortablePreferenceGroup.Playback, "playback", "rememberVideoPosition"),
    MaximumBrightness(PortablePreferenceGroup.Playback, "playback", "maximumBrightness"),
    Scrubbing(PortablePreferenceGroup.Playback, "playback", "videoScrubbingMode"),
    DoubleTap(PortablePreferenceGroup.Gestures, "gestures", "doubleTapZoom"),
    Pinch(PortablePreferenceGroup.Gestures, "gestures", "pinchZoom"),
    SwipeDown(PortablePreferenceGroup.Gestures, "gestures", "swipeDownToClose"),
    PhotoBrightness(PortablePreferenceGroup.Gestures, "gestures", "photoBrightness"),
    VideoBrightness(PortablePreferenceGroup.Gestures, "gestures", "videoBrightness"),
    VideoVolume(PortablePreferenceGroup.Gestures, "gestures", "videoVolume"),
    VideoSeek(PortablePreferenceGroup.Gestures, "gestures", "videoSeek"),
    Rotate(PortablePreferenceGroup.Gestures, "gestures", "rotatePhotos"),
    PhotoZoom(PortablePreferenceGroup.Gestures, "gestures", "photoMaxZoom"),
    VideoZoom(PortablePreferenceGroup.Gestures, "gestures", "videoMaxZoom"),
    SkipSeconds(PortablePreferenceGroup.Gestures, "gestures", "videoSkipSeconds");

    internal fun validate(value: Any): String {
        if (this == CollectionOrder || this == HiddenCollections) {
            require(value is String)
            val ids = CollectionLayoutPolicy.decode(value)
            return if (this == HiddenCollections) ids.sorted().joinToString(",") else value
        }
        val allowed =
            when (this) {
                Sort -> LibrarySort.entries.map { it.name }
                Filter -> LibraryFilter.entries.map { it.name }
                Grouping -> LibraryGrouping.entries.map { it.name }
                Scrubbing -> VideoScrubbingMode.entries.map { it.name }
                else -> null
            }
        if (allowed != null) {
            require(value is String && value in allowed) { "Invalid enum: $name" }
            return value
        }
        if (this in setOf(Columns, SkipSeconds, PhotoZoom, VideoZoom)) {
            require(value is Number) { "Invalid number: $name" }
            val number = value.toDouble()
            require(number.isFinite())
            when (this) {
                Columns -> require(number % 1.0 == 0.0 && (number == 0.0 || number in 2.0..13.0))
                SkipSeconds -> require(number in setOf(5.0, 10.0, 15.0, 30.0))
                else -> require(number in 2.0..8.0)
            }
            return if (this == Columns || this == SkipSeconds) number.toInt().toString()
            else number.toFloat().toString()
        }
        require(value is Boolean) { "Invalid boolean: $name" }
        return value.toString()
    }

    internal fun jsonValue(value: String): Any =
        when (this) {
            Sort,
            Filter,
            Grouping,
            Scrubbing, CollectionOrder, HiddenCollections -> value
            Columns,
            SkipSeconds -> value.toInt()
            PhotoZoom,
            VideoZoom -> value.toFloat()
            else -> value.toBooleanStrict()
        }
}

data class PortablePreferenceDifference(
    val field: PortablePreferenceField,
    val currentValue: String,
    val importedValue: String,
) {
    val changed: Boolean
        get() = currentValue != importedValue
}

data class PortablePreferencesReview(
    val operationId: String,
    val payloadSha256: String,
    val expectedRevision: Long,
    val differences: List<PortablePreferenceDifference>,
    val appliedGroups: Set<PortablePreferenceGroup> = emptySet(),
) {
    val availableGroups: Set<PortablePreferenceGroup>
        get() = differences.map { it.field.group }.toSet()

    val alreadyApplied: Boolean
        get() = appliedGroups.isNotEmpty()
}

data class PortablePreferencesResult(
    val revision: Long,
    val appliedGroups: Set<PortablePreferenceGroup>,
    val alreadyApplied: Boolean,
)

enum class PortablePreferencesFailure {
    InvalidPayload,
    Conflict,
    OperationMismatch,
    ReceiptLimit,
    Storage,
}

class PortablePreferencesException(
    val reason: PortablePreferencesFailure,
    cause: Throwable? = null,
) : IllegalStateException(reason.name, cause)

interface PortablePreferencesPort {
    suspend fun review(bytes: ByteArray, operationId: String): PortablePreferencesReview

    suspend fun apply(
        bytes: ByteArray,
        review: PortablePreferencesReview,
        selectedGroups: Set<PortablePreferenceGroup>,
    ): PortablePreferencesResult
}

internal data class PortablePreferencesDocument(
    val sha256: String,
    val values: Map<PortablePreferenceField, String>,
)

object PortablePreferencesCodec {
    const val MaxBytes = 1024 * 1024
    const val MaxReceipts = 512

    fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer, 0, minOf(buffer.size, MaxBytes + 1 - output.size()))
            if (n < 0) return output.toByteArray()
            require(n > 0) { "Non-progressing input" }
            output.write(buffer, 0, n)
            require(output.size() <= MaxBytes) { "Settings exceed byte limit" }
        }
    }

    internal fun operationId(value: String): String {
        require(UUID.fromString(value).toString() == value) { "Operation must be a canonical UUID" }
        return value
    }

    internal fun decode(bytes: ByteArray): PortablePreferencesDocument {
        try {
            require(bytes.isNotEmpty() && bytes.size <= MaxBytes)
            val text =
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            // Bound nesting before org.json recursion, including ignored sections.
            var depth = 0
            var quoted = false
            var escaped = false
            text.forEach { ch ->
                if (quoted) {
                    if (escaped) escaped = false
                    else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
                } else
                    when (ch) {
                        '"' -> quoted = true
                        '{',
                        '[' -> {
                            depth++
                            require(depth <= 16)
                        }
                        '}',
                        ']' -> {
                            depth--
                            require(depth >= 0)
                        }
                    }
            }
            require(!quoted && depth == 0)
            val tokener = JSONTokener(text)
            val json = tokener.nextValue() as? JSONObject ?: error("Settings object required")
            require(tokener.nextClean() == '\u0000')
            val version = json.opt("schemaVersion") ?: 1
            require(
                version is Number &&
                    version.toDouble() % 1.0 == 0.0 &&
                    version.toDouble().isFinite() &&
                    version.toDouble() in 1.0..GallerySettings.CurrentSchemaVersion.toDouble()
            )
            val values = buildMap {
                PortablePreferenceField.entries.forEach { field ->
                    if (json.has(field.section)) {
                        val section = json.getJSONObject(field.section)
                        if (section.has(field.key))
                            put(field, field.validate(section.get(field.key)))
                    }
                }
            }
            val digest =
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                    "%02x".format(it.toInt() and 255)
                }
            return PortablePreferencesDocument(digest, values)
        } catch (e: Exception) {
            throw PortablePreferencesException(PortablePreferencesFailure.InvalidPayload, e)
        }
    }
}
