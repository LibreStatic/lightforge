package com.librestatic.lightforge.feature.viewer.textselect

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.librestatic.lightforge.feature.viewer.R

/** Intents behind the text-selection toolbar. Every launch fails soft with a toast. */
internal object TextSelectionActions {
    private const val GoogleTranslatePackage = "com.google.android.apps.translate"

    fun copy(context: Context, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.viewer_text_clip_label), text))
        // Android 13+ shows its own clipboard confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.viewer_text_copied, Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, text: String) = launch(
        context,
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
            null,
        ),
    )

    fun canTranslate(context: Context): Boolean = context.packageManager
        .queryIntentActivities(processTextIntent(""), PackageManager.MATCH_DEFAULT_ONLY)
        .isNotEmpty()

    fun translate(context: Context, text: String) {
        val handlers = context.packageManager
            .queryIntentActivities(processTextIntent(text), PackageManager.MATCH_DEFAULT_ONLY)
        val translator = handlers.firstOrNull { it.activityInfo.packageName == GoogleTranslatePackage }
        val intent = when {
            translator != null -> processTextIntent(text)
                .setClassName(translator.activityInfo.packageName, translator.activityInfo.name)
            else -> Intent.createChooser(processTextIntent(text), null)
        }
        launch(context, intent)
    }

    fun searchWeb(context: Context, text: String) =
        launch(context, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, text))

    fun open(context: Context, entity: SmartEntity) {
        val intent = when (entity) {
            is SmartEntity.Link -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse(if (entity.value.contains("://")) entity.value else "https://${entity.value}"),
            )
            is SmartEntity.Email -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${entity.value}"))
            is SmartEntity.Phone -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:${entity.value.filter { it.isDigit() || it == '+' }}"))
            is SmartEntity.Address -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(entity.value)}"))
        }
        launch(context, intent)
    }

    private fun processTextIntent(text: String) = Intent(Intent.ACTION_PROCESS_TEXT)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)

    private fun launch(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.viewer_text_no_app, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, R.string.viewer_text_no_app, Toast.LENGTH_SHORT).show()
        }
    }
}
