package com.librestatic.lightforge;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

public class SafCopyDocumentsProvider extends DocumentsProvider {
    @Override public boolean onCreate() { return true; }
    private static void require(boolean value) { if (!value) throw new IllegalArgumentException("Fixture invariant"); }
    private static String uuid(String value) { require(value != null && value.matches("[a-f0-9-]{36}") && UUID.fromString(value).toString().equals(value)); return value; }
    private File root(String id) { return new File(getContext().getCacheDir(), "saf-copy-" + uuid(id)); }
    private File file(String id) {
        String[] parts = id.split(":", -1);
        require(parts.length == 2 && (parts[1].equals("source") || parts[1].equals("copy")));
        return new File(root(parts[0]), parts[1]);
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!Arrays.asList("fixtureSetup", "fixtureStatus", "fixtureCleanup").contains(method)) return super.call(method, arg, extras);
        File dir = root(arg); Bundle out = new Bundle();
        try {
            switch(method) {
                case "fixtureSetup":
                    String mode = extras == null ? null : extras.getString("mode");
                    require(Arrays.asList("normal", "truncate", "corrupt", "source-mutation", "fail-write-once").contains(mode));
                    require(dir.mkdir()); byte[] bytes = new byte[32789];
                    for (int i=0;i<bytes.length;i++) bytes[i]=(byte)(i%251);
                    Files.write(new File(dir,"source").toPath(),bytes);
                    Files.write(new File(dir,"mode").toPath(),mode.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    if (extras.getBoolean("pickerVisible", false)) {
                        require(new File(dir, "picker-visible").createNewFile());
                        getContext().getContentResolver().notifyChange(DocumentsContract.buildRootsUri(
                            getContext().getPackageName() + ".safcopyfixture"), null);
                    }
                    out.putBoolean("ready",true); break;
                case "fixtureStatus":
                    require(dir.isDirectory()); byte[] source=Files.readAllBytes(new File(dir,"source").toPath());
                    StringBuilder hash=new StringBuilder();
                    for(byte b:MessageDigest.getInstance("SHA-256").digest(source)) hash.append(String.format(Locale.ROOT,"%02x",b & 255));
                    out.putString("sourceSHA",hash.toString()); out.putInt("sourceSize",source.length);
                    out.putBoolean("destinationExists",new File(dir,"copy").exists());
                    out.putLong("destinationSize", new File(dir,"copy").length());
                    out.putBoolean("writeFaultFired", new File(dir,"write-fault-fired").exists());
                    out.putBoolean("partialFsynced", new File(dir,"partial-fsynced").exists());
                    out.putBoolean("writeFaultFinished", new File(dir,"write-fault-finished").exists());
                    out.putBoolean("injected",new File(dir,"injected").exists()); break;
                case "fixtureCleanup":
                    require(!new File(dir,"write-fault-fired").exists() || new File(dir,"write-fault-finished").exists());
                    require(dir.isDirectory()); File[] children=dir.listFiles(); require(children!=null);
                    for(File child:children) { require(child.isFile()); require(child.delete()); }
                    require(dir.delete()); out.putBoolean("absent",!dir.exists());
                    getContext().getContentResolver().notifyChange(DocumentsContract.buildRootsUri(
                        getContext().getPackageName() + ".safcopyfixture"), null);
                    getContext().revokeUriPermission(android.net.Uri.parse("content://" + getContext().getPackageName() + ".safcopyfixture"),
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    break;
            }
            return out;
        } catch(Exception e) { throw new IllegalStateException("Fixture IPC " + method,e); }
    }
    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[]{"root_id", "document_id", "title", "flags", "mime_types", "available_bytes"};
        MatrixCursor cursor = new MatrixCursor(columns);
        File[] directories = getContext().getCacheDir().listFiles();
        if (directories == null) return cursor;
        for (File directory : directories) {
            if (!directory.getName().startsWith("saf-copy-") || !new File(directory, "picker-visible").isFile()) continue;
            String id = uuid(directory.getName().substring("saf-copy-".length()));
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) switch (columns[i]) {
                case "root_id": values[i] = id; break;
                case "document_id": values[i] = id + ":root"; break;
                case "title": values[i] = "Lightforge SAF " + id; break;
                case "flags": values[i] = DocumentsContract.Root.FLAG_SUPPORTS_CREATE | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD; break;
                case "mime_types": values[i] = "*/*"; break;
                case "available_bytes": values[i] = 10485760L; break;
            }
            cursor.addRow(values);
        }
        return cursor;
    }
    @Override public Cursor queryDocument(String id,String[] projection) throws FileNotFoundException {
        if (!id.endsWith(":root") && !file(id).isFile()) throw new FileNotFoundException(id);
        String[] columns=projection!=null?projection:new String[]{"document_id","_display_name","mime_type","flags","_size"};
        MatrixCursor cursor=new MatrixCursor(columns); Object[] values=new Object[columns.length];
        for(int i=0;i<columns.length;i++) switch(columns[i]) {
            case "document_id": values[i]=id;break;
            case "_display_name":values[i]=id.endsWith(":root") ? "Lightforge SAF " + id.substring(0,id.indexOf(':')) : id.substring(id.indexOf(':')+1);break;
            case "mime_type":values[i]=id.endsWith(":root")?DocumentsContract.Document.MIME_TYPE_DIR:"application/octet-stream";break;
            case "flags":values[i]=id.endsWith(":root") ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : DocumentsContract.Document.FLAG_SUPPORTS_WRITE|DocumentsContract.Document.FLAG_SUPPORTS_DELETE;break;
            case "_size":values[i]=id.endsWith(":root")?0L:file(id).length();break;
        }
        cursor.addRow(values);return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String sort) {
        return new MatrixCursor(projection!=null?projection:new String[]{"document_id"});
    }
    @Override public String createDocument(String parent,String mime,String name) throws FileNotFoundException {
        require(parent.endsWith(":root"));String id=parent.substring(0,parent.indexOf(':'))+":copy";
        try { require(file(id).createNewFile());return id; } catch(IOException e) { throw new IllegalStateException(e); }
    }
    @Override public boolean isChildDocument(String parent,String child) {
        return parent.substring(0,parent.indexOf(':')).equals(child.substring(0,child.indexOf(':')));
    }
    @Override public void deleteDocument(String id) { require(file(id).delete()); }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException {
        if(signal!=null)signal.throwIfCanceled();File target=file(id);
        if (!target.isFile()) throw new FileNotFoundException(id);
        try {
            if (id.endsWith(":copy") && mode.contains("w") &&
                new String(Files.readAllBytes(new File(target.getParentFile(),"mode").toPath()), java.nio.charset.StandardCharsets.UTF_8).equals("fail-write-once") &&
                new File(target.getParentFile(),"write-fault-fired").createNewFile()) {
                final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
                new Thread(() -> {
                    try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]);
                         FileOutputStream output = new FileOutputStream(target, false)) {
                        byte[] buffer = new byte[4096]; int total = 0;
                        while (total < buffer.length) {
                            int count = input.read(buffer, total, buffer.length - total);
                            if (count < 0) break;
                            total += count;
                        }
                        output.write(buffer, 0, total); output.getFD().sync();
                        if (total > 0) new File(target.getParentFile(),"partial-fsynced").createNewFile();
                        pipe[0].closeWithError("Owned fixture interrupted after partial write");
                    } catch (Exception failure) {
                        try { pipe[0].closeWithError("Owned fixture writer failure"); } catch (IOException ignored) { }
                    } finally {
                        try { new File(target.getParentFile(), "write-fault-finished").createNewFile(); }
                        catch (IOException ignored) { }
                    }
                }, "saf-copy-owned-write-fault").start();
                return pipe[1];
            }
            if(id.endsWith(":copy") && mode.equals("r") && new File(target.getParentFile(),"injected").createNewFile()) {
                String damage=new String(Files.readAllBytes(new File(target.getParentFile(),"mode").toPath()),java.nio.charset.StandardCharsets.UTF_8);
                if(damage.equals("truncate")) { try(RandomAccessFile f=new RandomAccessFile(target,"rw")){f.setLength(7);} }
                if(damage.equals("corrupt")) { try(RandomAccessFile f=new RandomAccessFile(target,"rw")){f.seek(0);f.write(255);} }
                if(damage.equals("source-mutation")) { try(FileOutputStream f=new FileOutputStream(new File(target.getParentFile(),"source"),true)){f.write(99);} }
            }
            return ParcelFileDescriptor.open(target,ParcelFileDescriptor.parseMode(mode));
        } catch(IOException e) { throw new IllegalStateException(e); }
    }
}
