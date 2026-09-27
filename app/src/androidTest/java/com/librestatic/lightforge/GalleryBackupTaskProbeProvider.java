package com.librestatic.lightforge;

import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Standalone owner-UID fixture provider. Its entire namespace is private test-APK cache. */
public final class GalleryBackupTaskProbeProvider extends DocumentsProvider {
    private String authority() { return getContext().getPackageName() + ".backuptasks"; }
    private String target() {
        String packageName = getContext().getPackageName();
        if (!packageName.endsWith(".test")) throw new IllegalStateException("Expected isolated test APK");
        return packageName.substring(0, packageName.length() - ".test".length());
    }
    private volatile boolean corruptReads, rejectWrites, denyAccess, holdSource, sourceOpened;
    private CountDownLatch sourceGate = new CountDownLatch(0);
    @Override public boolean onCreate() { return true; }

    private File root() {
        File result = new File(getContext().getCacheDir(), "durable-backup-process-fixture");
        result.mkdirs();
        return result;
    }
    private File file(String id) {
        if (!"root".equals(id) && !id.startsWith("root/")) throw new IllegalArgumentException("Invalid fixture ID");
        File root = root();
        File result = "root".equals(id) ? root : new File(root, id.substring(5));
        try {
            String base = root.getCanonicalPath();
            String path = result.getCanonicalPath();
            if (!path.equals(base) && !path.startsWith(base + "/")) throw new IllegalArgumentException("Fixture path escaped");
        } catch (IOException error) { throw new IllegalArgumentException(error); }
        return result;
    }
    private String id(File file) {
        File root = root();
        return file.equals(root) ? "root" : "root/" + root.toPath().relativize(file.toPath()).toString();
    }
    private List<File> children(File parent) {
        File[] files = parent.listFiles();
        List<File> result = new ArrayList<>();
        if (files != null) for (File item : files) if (!item.getName().startsWith(".")) result.add(item);
        return result;
    }
    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[] {
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS };
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for(int i=0;i<columns.length;i++) {
            String col=columns[i];
            if(col.equals(DocumentsContract.Root.COLUMN_ROOT_ID)) row[i]="fixture";
            else if(col.equals(DocumentsContract.Root.COLUMN_DOCUMENT_ID)) row[i]="root";
            else if(col.equals(DocumentsContract.Root.COLUMN_TITLE)) row[i]="Lightforge isolated test";
            else if(col.equals(DocumentsContract.Root.COLUMN_FLAGS)) row[i]=DocumentsContract.Root.FLAG_SUPPORTS_CREATE;
        }
        cursor.addRow(row);return cursor;
    }
    private Cursor cursor(String[] projection, List<File> files) {
        String[] columns = projection != null ? projection : new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS };
        MatrixCursor cursor = new MatrixCursor(columns);
        for(File file:files) {
            Object[] row=new Object[columns.length];
            for(int i=0;i<columns.length;i++) {
                String col=columns[i];
                if(col.equals(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) row[i]=id(file);
                else if(col.equals(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) row[i]=file.getName();
                else if(col.equals(DocumentsContract.Document.COLUMN_MIME_TYPE)) row[i]=file.isDirectory()?DocumentsContract.Document.MIME_TYPE_DIR:"application/octet-stream";
                else if(col.equals(DocumentsContract.Document.COLUMN_SIZE)) row[i]=file.length();
                else if(col.equals(DocumentsContract.Document.COLUMN_FLAGS)) row[i]=
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE | DocumentsContract.Document.FLAG_SUPPORTS_WRITE |
                    (file.isDirectory()?DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE:0);
            }
            cursor.addRow(row);
        }
        return cursor;
    }
    @Override public Cursor queryDocument(String documentId,String[] projection) { return cursor(projection,Collections.singletonList(file(documentId))); }
    @Override public Cursor queryChildDocuments(String parentDocumentId,String[] projection,String sortOrder) { return cursor(projection,children(file(parentDocumentId))); }
    @Override public String createDocument(String parentDocumentId,String mimeType,String displayName) {
        if(denyAccess) throw new SecurityException("Injected destination revocation");
        if(rejectWrites) throw new IllegalStateException("Injected write rejection");
        if(displayName == null || displayName.trim().isEmpty() || displayName.equals(".") || displayName.equals("..")) throw new IllegalArgumentException("Invalid fixture name");
        for(int i=0;i<displayName.length();i++) { char c=displayName.charAt(i);if(c=='/' || c=='\\' || c<32) throw new IllegalArgumentException("Invalid fixture name"); }
        File parent=file(parentDocumentId);String name=displayName;int count=0;
        while(new File(parent,name).exists()) name=(++count)+"-"+displayName;
        File target=new File(parent,name);
        try {
            if(!(DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)?target.mkdir():target.createNewFile())) throw new IllegalStateException("Fixture creation failed");
        } catch(IOException error) { throw new IllegalStateException(error); }
        return id(target);
    }
    @Override public boolean isChildDocument(String parentDocumentId,String documentId) {
        try { return file(documentId).getCanonicalPath().startsWith(file(parentDocumentId).getCanonicalPath()+"/"); }
        catch(IOException error) { throw new IllegalArgumentException(error); }
    }
    private static boolean remove(File file) {
        File[] children=file.listFiles();
        if(children!=null) for(File child:children) if(!remove(child)) return false;
        return !file.exists() || file.delete();
    }
    @Override public void deleteDocument(String documentId) {
        if(denyAccess) throw new SecurityException("Injected destination revocation");
        if("root".equals(documentId) || !remove(file(documentId))) throw new IllegalStateException("Fixture deletion failed");
    }
    private static void copy(InputStream input,OutputStream output) throws IOException {
        byte[] buffer=new byte[16384];int count;
        while((count=input.read(buffer))>=0) if(count>0) output.write(buffer,0,count);
    }
    private static void text(File file,String text) {
        try(FileOutputStream output=new FileOutputStream(file)) { output.write(text.getBytes(StandardCharsets.UTF_8)); }
        catch(IOException error) { throw new IllegalStateException(error); }
    }
    private static String text(File file) {
        try(FileInputStream input=new FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            copy(input,output);return new String(output.toByteArray(),StandardCharsets.UTF_8);
        } catch(IOException error) { throw new IllegalStateException(error); }
    }
    private static void close(ParcelFileDescriptor descriptor) { try { descriptor.close(); } catch(IOException ignored) {} }
    private static void start(Runnable action) { Thread thread=new Thread(action);thread.setDaemon(true);thread.start(); }

    @Override public ParcelFileDescriptor openDocument(String documentId,String mode,CancellationSignal signal) throws FileNotFoundException {
        if(denyAccess && !"r".equals(mode)) throw new SecurityException("Injected destination revocation");
        if(!"r".equals(mode) && rejectWrites) throw new IllegalStateException("Injected write rejection");
        try {
            if("r".equals(mode) && holdSource && documentId.endsWith("slow.jpg")) {
                ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();sourceOpened=true;CountDownLatch gate=sourceGate;
                start(()->{try { gate.await(30,TimeUnit.SECONDS);
                    try(OutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);InputStream input=new FileInputStream(file(documentId))) { copy(input,output); }
                } catch(Exception error) { close(pipe[1]); }});
                return pipe[0];
            }
            if(!"r".equals(mode) && documentId.endsWith("process.zip") && new File(root(),".hold").exists()) {
                ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createReliablePipe();File target=file(documentId);
                start(()->{
                    try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]);OutputStream output=new FileOutputStream(target)) {
                        byte[] buffer=new byte[16384];long written=0;int count;
                        while((count=input.read(buffer))>=0) {
                            if(count==0) continue;
                            output.write(buffer,0,count);output.flush();written+=count;
                            if(written>=65536 && new File(root(),".hold").exists()) {
                                text(new File(root(),".blocked"),Long.toString(written));
                                while(new File(root(),".hold").exists()) Thread.sleep(50);
                            }
                        }
                    } catch(Exception error) { close(pipe[0]); }
                    finally { text(new File(root(),".writerFinished"),"closed"); }
                });
                return pipe[1];
            }
            File target=file(documentId);
            if("r".equals(mode) && corruptReads && documentId.contains("Lightforge-restored-")) {
                target=new File(getContext().getCacheDir(),"corrupt-read");
                try(FileOutputStream output=new FileOutputStream(target)) { output.write(99); }
            }
            return ParcelFileDescriptor.open(target,ParcelFileDescriptor.parseMode(mode));
        } catch(IOException error) {
            FileNotFoundException wrapped=new FileNotFoundException(error.toString());wrapped.initCause(error);throw wrapped;
        }
    }
    private int grantFlags() { return Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION; }
    @Override public Bundle call(String method,String arg,Bundle extras) {
        switch(method) {
            case "fixture-reset":
                sourceGate.countDown();holdSource=false;sourceOpened=false;remove(root());root();corruptReads=false;rejectWrites=false;denyAccess=false;break;
            case "fixture-hold-source":sourceGate=new CountDownLatch(1);holdSource=true;break;
            case "fixture-release-source":sourceGate.countDown();break;
            case "fixture-source-state": { Bundle result=new Bundle();result.putBoolean("opened",sourceOpened);return result; }
            case "fixture-corrupt-restored-reads":corruptReads=true;break;
            case "fixture-reject-writes":rejectWrites=true;break;
            case "fixture-bootstrap":getContext().grantUriPermission(target(),Uri.parse("content://"+authority()),grantFlags());break;
            case "fixture-grant-process":
                getContext().grantUriPermission(target(),DocumentsContract.buildTreeDocumentUri(authority(),"root"),grantFlags());
                for(File item:children(root())) getContext().grantUriPermission(target(),DocumentsContract.buildDocumentUri(authority(),id(item)),grantFlags());break;
            case "fixture-arm-process":new File(root(),".blocked").delete();new File(root(),".writerFinished").delete();text(new File(root(),".hold"),"hold");break;
            case "fixture-release-process":new File(root(),".hold").delete();break;
            case "fixture-process-state": {
                Bundle result=new Bundle();result.putBoolean("blocked",new File(root(),".blocked").exists());result.putBoolean("writerFinished",new File(root(),".writerFinished").exists());
                File marker=new File(root(),".blocked");long written=0;
                if(marker.exists()) try { written=Long.parseLong(text(marker)); } catch(NumberFormatException ignored) {}
                result.putLong("written",written);result.putInt("providerPid",android.os.Process.myPid());return result;
            }
            case "fixture-deny-access":denyAccess=true;break;
            case "fixture-allow-access":denyAccess=false;break;
            default:Bundle value=super.call(method,arg,extras);return value!=null?value:Bundle.EMPTY;
        }
        return Bundle.EMPTY;
    }
}
