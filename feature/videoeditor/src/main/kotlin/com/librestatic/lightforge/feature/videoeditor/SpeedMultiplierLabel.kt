package com.librestatic.lightforge.feature.videoeditor

import java.text.NumberFormat
import java.util.Locale

/** "0.5×" with the decimal separator of the app language ("0,5×" in German, Spanish, ...). */
internal fun speedMultiplierLabel(speed: Float, locale: Locale = Locale.getDefault()): String {
    val format = NumberFormat.getNumberInstance(locale)
    format.minimumFractionDigits = 1
    format.maximumFractionDigits = 3
    return format.format(speed.toDouble()) + "×"
}
