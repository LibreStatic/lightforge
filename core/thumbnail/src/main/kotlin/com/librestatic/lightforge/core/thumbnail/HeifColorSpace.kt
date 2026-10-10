package com.librestatic.lightforge.core.thumbnail

import android.graphics.ColorSpace

/** Thin Android mapping for [ResolvedColourSpace]; the decision logic is pure JVM in `IccProfile.kt`. */
internal fun ResolvedColourSpace.toAndroidColorSpace(): ColorSpace = when (this) {
    ResolvedColourSpace.Named.SRGB -> ColorSpace.get(ColorSpace.Named.SRGB)
    ResolvedColourSpace.Named.DISPLAY_P3 -> ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
    ResolvedColourSpace.Named.BT2020 -> ColorSpace.get(ColorSpace.Named.BT2020)
    is ResolvedColourSpace.Custom -> {
        val t = space.transfer
        ColorSpace.Rgb(
            "HEIF ICC profile",
            space.primaries.map { it.toFloat() }.toFloatArray(),
            space.whitePoint.map { it.toFloat() }.toFloatArray(),
            ColorSpace.Rgb.TransferParameters(t.a, t.b, t.c, t.d, t.e, t.f, t.g),
        )
    }
}
