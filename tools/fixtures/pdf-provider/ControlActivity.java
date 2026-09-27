package com.librestatic.lightforge.pdfprovider.fixture;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;

/** Test APK only: grant/revoke exactly its own fixture documents to the instrumentation package. */
public final class ControlActivity extends Activity {
    static final String AUTHORITY = "com.librestatic.lightforge.pdfprovider.fixture.documents";
    static final String TARGET = "com.librestatic.lightforge.feature.pdfstudio.test";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String action = getIntent().getStringExtra("action");
        String doc = getIntent().getStringExtra("doc");
        if (doc == null) doc = "two";
        try {
            if (!doc.equals("one") && !doc.equals("two")) throw new IllegalArgumentException("Unknown fixture document");
            Uri uri = DocumentsContract.buildDocumentUri(AUTHORITY, doc);
            int access = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            if ("prepare".equals(action)) {
                for (String name : new String[]{"one", "two"}) {
                    Bitmap bitmap = Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);
                    java.util.Random random = new java.util.Random(name.equals("one") ? 9 : 10);
                    int[] pixels = new int[256*256];
                    for (int i=0;i<pixels.length;i++) pixels[i]=random.nextInt() | 0xff000000;
                    bitmap.setPixels(pixels,0,256,0,0,256,256);
                    try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(),name))) { bitmap.compress(Bitmap.CompressFormat.PNG,100,out); }
                    bitmap.recycle();
                    grantUriPermission(TARGET,DocumentsContract.buildDocumentUri(AUTHORITY,name),access | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                }
            } else if ("grant".equals(action)) {
                grantUriPermission(TARGET,uri,access | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            } else if ("revoke".equals(action)) {
                revokeUriPermission(uri,access);
            } else throw new IllegalArgumentException("Unknown fixture action");
            JSONObject result = new JSONObject().put("action",action).put("nonce",getIntent().getStringExtra("nonce")).put("doc",doc).put("uid",android.os.Process.myUid()).put("pid",android.os.Process.myPid());
            try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(),"control.json"))) { out.write(result.toString().getBytes("UTF-8")); }
        } catch (Exception e) { throw new RuntimeException(e); }
        finish();
    }
}
