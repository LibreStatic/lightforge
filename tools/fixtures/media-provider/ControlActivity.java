package com.librestatic.lightforge.mediaprovider.fixture;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONObject;

/** Test-only owner UID. Real URI grants; documents never accept caller writes. */
public final class ControlActivity extends Activity {
    static final String AUTHORITY = "com.librestatic.lightforge.mediaprovider.fixture.documents";
    static final String TARGET = "com.librestatic.lightforge.pdfacceptance";
    static final String SEED_SHA256 = "ca9dc6afcd80d25b6d70c9057312e8584b9a59c1ac642d8beff3c1a50fa82861";
    static final long SEED_SIZE = 145922L;
    static String uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") ||
                !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("Canonical UUID required");
        return value;
    }
    static File document(File files, String doc) { return new File(new File(files, "media-fixtures"), uuid(doc) + ".mp4"); }
    static void syncDirectory(File directory) throws Exception {
        if (!OsConstants.S_ISDIR(Os.lstat(directory.getAbsolutePath()).st_mode)) throw new IllegalStateException("Fixture directory required");
        FileDescriptor fd = Os.open(directory.getAbsolutePath(), OsConstants.O_RDONLY, 0);
        try { Os.fsync(fd); } finally { Os.close(fd); }
    }
    static void requireRegular(File file) throws Exception {
        if (!OsConstants.S_ISREG(Os.lstat(file.getAbsolutePath()).st_mode)) throw new IllegalStateException("Regular owned fixture required");
    }
    static final class Proof {
        final String sha256; final long size;
        Proof(String sha256, long size) { this.sha256 = sha256; this.size = size; }
    }
    static Proof inspect(File file) throws Exception {
        requireRegular(file);
        android.system.StructStat before = Os.lstat(file.getAbsolutePath());
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); long size = 0;
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[65536]; int read;
            while ((read = input.read(bytes)) != -1) { digest.update(bytes, 0, read); size += read; }
        }
        android.system.StructStat after = Os.lstat(file.getAbsolutePath());
        if (before.st_dev != after.st_dev || before.st_ino != after.st_ino || before.st_size != size ||
                after.st_size != size || before.st_mtime != after.st_mtime || before.st_ctime != after.st_ctime)
            throw new IllegalStateException("Fixture changed during inspection");
        StringBuilder hash = new StringBuilder();
        for (byte b : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return new Proof(hash.toString(), size);
    }
    private void receipt(File file, JSONObject value) throws Exception {
        FileDescriptor fd = Os.open(file.getAbsolutePath(), OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL, 0600);
        try (FileOutputStream output = new FileOutputStream(fd)) {
            output.write(value.toString().getBytes(StandardCharsets.UTF_8)); output.flush(); output.getFD().sync();
        }
        syncDirectory(getFilesDir());
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Intent control = getIntent();
            String action = control.getStringExtra("action");
            String doc = uuid(control.getStringExtra("doc"));
            String nonce = uuid(control.getStringExtra("nonce"));
            if (!java.util.Arrays.asList("prepare", "grant", "revoke", "edit", "inspect", "cleanup").contains(action))
                throw new IllegalArgumentException("Unknown action");
            File receiptFile = new File(getFilesDir(), "control-" + nonce + ".json");
            if (receiptFile.exists()) throw new IllegalStateException("Nonce already consumed");
            int targetUid = getPackageManager().getApplicationInfo(TARGET, 0).uid;
            if (targetUid == android.os.Process.myUid()) throw new IllegalStateException("Independent UID required");
            File file = document(getFilesDir(), doc);
            Uri uri = DocumentsContract.buildDocumentUri(AUTHORITY, doc);
            if ("prepare".equals(action)) {
                File directory = file.getParentFile();
                if (!directory.exists() && !directory.mkdir()) throw new IllegalStateException("Fixture directory creation failed");
                syncDirectory(getFilesDir());
                FileDescriptor fd = Os.open(file.getAbsolutePath(), OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL, 0600);
                try (FileOutputStream output = new FileOutputStream(fd); InputStream input = getAssets().open("motion_fixture.mp4")) {
                    byte[] bytes = new byte[65536]; int read;
                    while ((read = input.read(bytes)) != -1) output.write(bytes, 0, read);
                    output.flush(); output.getFD().sync();
                }
                syncDirectory(directory);
            }
            Proof proof = inspect(file);
            if ("prepare".equals(action) && (!proof.sha256.equals(SEED_SHA256) || proof.size != SEED_SIZE))
                throw new IllegalStateException("Packaged MP4 mismatch");
            if ("grant".equals(action) || "edit".equals(action)) grantUriPermission(TARGET, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if ("revoke".equals(action)) {
                revokeUriPermission(TARGET, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                // The package overload excludes Intent/ClipData grants. This UUID is exclusively ours.
                revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            if ("cleanup".equals(action)) {
                String expected = control.getStringExtra("expectedSha256");
                if (expected == null || !expected.matches("[0-9a-f]{64}") || !proof.sha256.equals(expected))
                    throw new IllegalStateException("Cleanup SHA mismatch");
                revokeUriPermission(TARGET, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Proof fresh = inspect(file);
                if (!fresh.sha256.equals(proof.sha256) || fresh.size != proof.size) throw new IllegalStateException("Cleanup proof changed");
                if (!file.delete() || file.exists()) throw new IllegalStateException("Exact fixture deletion failed");
                syncDirectory(file.getParentFile());
            }
            if ("edit".equals(action)) {
                Intent edit = new Intent(Intent.ACTION_EDIT).setDataAndType(uri, "video/mp4").setPackage(TARGET);
                edit.setClipData(ClipData.newRawUri("Owned video fixture", uri));
                edit.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(edit);
            }
            JSONObject result = new JSONObject().put("action", action).put("doc", doc).put("nonce", nonce)
                .put("uri", uri.toString()).put("sha256", proof.sha256).put("size", proof.size)
                .put("uid", android.os.Process.myUid()).put("pid", android.os.Process.myPid())
                .put("targetUid", targetUid).put("targetPackage", TARGET).put("deleted", "cleanup".equals(action));
            receipt(receiptFile, result);
        } catch (Exception error) { throw new RuntimeException("Media fixture control failed", error); }
        finally { finish(); }
    }
}
