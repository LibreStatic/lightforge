package com.ugallery.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Standalone test-APK process: Android/JDK only, no target APK Kotlin dependency. */
public final class GalleryBackupTaskProbeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String command = intent.getStringExtra("command");
        String method;
        if ("bootstrap".equals(command)) method = "fixture-bootstrap";
        else if ("release".equals(command)) method = "fixture-release-process";
        else if ("state".equals(command)) method = "fixture-process-state";
        else { setResultCode(Activity.RESULT_CANCELED); setResultData("unsupported"); return; }
        try {
            Bundle result = context.getContentResolver().call(
                Uri.parse("content://" + context.getPackageName() + ".backuptasks"), method, null, null);
            setResultCode(Activity.RESULT_OK);
            if ("fixture-process-state".equals(method)) {
                if (result == null) throw new IllegalStateException("Missing fixture state");
                setResultData("blocked=" + result.getBoolean("blocked") +
                    " writerFinished=" + result.getBoolean("writerFinished") +
                    " written=" + result.getLong("written") + " providerPid=" + result.getInt("providerPid"));
            } else setResultData("completed=" + method);
        } catch (Exception error) {
            setResultCode(Activity.RESULT_CANCELED);
            setResultData("failure=" + error.getClass().getSimpleName());
        }
    }
}
