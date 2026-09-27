package com.librestatic.lightforge.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

/**
 * Per-app language below Android 13, where `LocaleManager` does not exist. The chosen tag is
 * kept in plain SharedPreferences so it can be read synchronously from `attachBaseContext`.
 */
object LegacyAppLanguage {
    private const val PREFS = "lightforge_app_language"
    private const val KEY_TAG = "language_tag"

    val isNeeded: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    /** Stored language tag, or "" for the system default. */
    fun tag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, "").orEmpty()

    fun setTag(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TAG, tag).commit()
    }

    /** Returns [base] with the stored language applied, or [base] itself on 33+ / system default. */
    fun wrap(base: Context): Context {
        if (!isNeeded) return base
        val tag = tag(base)
        if (tag.isEmpty()) return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(config)
    }

    internal fun activity(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
