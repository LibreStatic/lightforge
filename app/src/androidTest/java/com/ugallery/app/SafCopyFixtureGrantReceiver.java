package com.ugallery.app;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
public class SafCopyFixtureGrantReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String target=intent.getStringExtra("target");
        String owner=context.getPackageName();
        String expected=owner.endsWith(".test")?owner.substring(0,owner.length()-5):owner;
        if(!expected.equals(target))throw new IllegalArgumentException("Unexpected fixture target");
        String operation = intent.getStringExtra("operation");
        if (operation != null) {
            if (!operation.equals("setupPicker") && !operation.equals("cleanupPicker"))
                throw new IllegalArgumentException("Unexpected fixture operation");
            android.os.Bundle options = new android.os.Bundle();
            options.putString("mode", "normal"); options.putBoolean("pickerVisible", true);
            android.os.Bundle result = context.getContentResolver().call(Uri.parse("content://" + owner + ".safcopyfixture"),
                operation.equals("setupPicker") ? "fixtureSetup" : "fixtureCleanup", intent.getStringExtra("uuid"), options);
            setResultExtras(result); setResultCode(android.app.Activity.RESULT_OK);
            return; // Fixture administration never grants the target access to the picker tree.
        }
        android.util.Log.i("SafCopyFixture", "Grant requested from " + owner + " to " + target);
        context.grantUriPermission(target,Uri.parse("content://"+owner+".safcopyfixture"),
            Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        setResultData("granted=" + target);
    }
}
