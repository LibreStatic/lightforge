package com.librestatic.lightforge.core.editing.video

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal object VideoAnnotationCodec {
    private const val BinaryVersion = 1
    private const val MaxLayers = 256
    private const val MaxPointsPerLayer = 20_000
    private const val MaxKeyframesPerLayer = 20_000

    fun encode(layers: List<VideoAnnotationLayer>): String {
        require(layers.size <= MaxLayers)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(BinaryVersion)
            output.writeInt(layers.size)
            layers.forEach { layer ->
                output.writeUTF(layer.id)
                output.writeInt(layer.shape.ordinal)
                output.writeInt(layer.style.appearance.ordinal)
                output.writeInt(layer.style.colorArgb)
                output.writeFloat(layer.style.strokeWidth)
                output.writeFloat(layer.style.opacity)
                output.writeBoolean(layer.style.filled)
                output.writeFloat(layer.style.intensity)
                output.writeLong(layer.startMillis)
                output.writeLong(layer.endMillis)
                output.writeInt(layer.trackingMode.ordinal)
                require(layer.points.size <= MaxPointsPerLayer)
                output.writeInt(layer.points.size)
                layer.points.forEach { point ->
                    output.writeFloat(point.x)
                    output.writeFloat(point.y)
                }
                require(layer.keyframes.size <= MaxKeyframesPerLayer)
                output.writeInt(layer.keyframes.size)
                layer.keyframes.forEach { keyframe ->
                    output.writeLong(keyframe.timeMillis)
                    with(keyframe.transform) {
                        output.writeFloat(translationX)
                        output.writeFloat(translationY)
                        output.writeFloat(scaleX)
                        output.writeFloat(scaleY)
                        output.writeFloat(rotationDegrees)
                    }
                    output.writeFloat(keyframe.confidence)
                }
            }
        }
        return java.util.Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    fun decode(encoded: String): List<VideoAnnotationLayer> = DataInputStream(
        ByteArrayInputStream(java.util.Base64.getDecoder().decode(encoded)),
    ).use { input ->
        require(input.readInt() == BinaryVersion) { "Unsupported annotation recipe version" }
        val layerCount = input.readInt().also { require(it in 0..MaxLayers) }
        List(layerCount) {
            val id = input.readUTF()
            val shape = enumAt<VideoAnnotationShape>(input.readInt())
            val style = VideoAnnotationStyle(
                appearance = enumAt(input.readInt()),
                colorArgb = input.readInt(),
                strokeWidth = input.readFloat(),
                opacity = input.readFloat(),
                filled = input.readBoolean(),
                intensity = input.readFloat(),
            )
            val startMillis = input.readLong()
            val endMillis = input.readLong()
            val tracking = enumAt<VideoAnnotationTrackingMode>(input.readInt())
            val pointCount = input.readInt().also { require(it in 1..MaxPointsPerLayer) }
            val points = List(pointCount) { NormalizedPoint(input.readFloat(), input.readFloat()) }
            val keyframeCount = input.readInt().also { require(it in 0..MaxKeyframesPerLayer) }
            val keyframes = List(keyframeCount) {
                VideoAnnotationKeyframe(
                    timeMillis = input.readLong(),
                    transform = VideoAnnotationTransform(
                        translationX = input.readFloat(),
                        translationY = input.readFloat(),
                        scaleX = input.readFloat(),
                        scaleY = input.readFloat(),
                        rotationDegrees = input.readFloat(),
                    ),
                    confidence = input.readFloat(),
                )
            }
            VideoAnnotationLayer(id, shape, points, style, startMillis, endMillis, tracking, keyframes)
        }
    }

    private inline fun <reified T : Enum<T>> enumAt(ordinal: Int): T =
        enumValues<T>().getOrNull(ordinal) ?: error("Unknown ${T::class.java.simpleName} value")
}
