package com.ugallery.mediaprovider.fixture;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;

public final class FixtureDocumentsProvider extends DocumentsProvider {
    private static final String[] DOCUMENT_COLUMNS = { Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED };
    @Override public boolean onCreate() { return true; }
    private File file(String id) throws FileNotFoundException {
        try {
            File file = ControlActivity.document(getContext().getFilesDir(), id);
            ControlActivity.requireRegular(file);
            return file;
        } catch (Exception error) {
            FileNotFoundException failure = new FileNotFoundException("Fixture document unavailable");
            failure.initCause(error); throw failure;
        }
    }
    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection == null ? new String[]{Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES} : projection;
        MatrixCursor cursor = new MatrixCursor(columns); MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            Object value = null;
            if (Root.COLUMN_ROOT_ID.equals(column) || Root.COLUMN_DOCUMENT_ID.equals(column)) value = "root";
            else if (Root.COLUMN_TITLE.equals(column)) value = "Local video fixture";
            else if (Root.COLUMN_FLAGS.equals(column)) value = Root.FLAG_LOCAL_ONLY;
            else if (Root.COLUMN_MIME_TYPES.equals(column)) value = "video/mp4";
            row.add(value);
        }
        return cursor;
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        addDocument(cursor, id); return cursor;
    }
    private void addDocument(MatrixCursor cursor, String id) throws FileNotFoundException {
        File file = "root".equals(id) ? null : file(id);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            if (Document.COLUMN_DOCUMENT_ID.equals(column)) value = id;
            else if (Document.COLUMN_DISPLAY_NAME.equals(column)) value = file == null ? "Local video fixture" : id + ".mp4";
            else if (Document.COLUMN_MIME_TYPE.equals(column)) value = file == null ? Document.MIME_TYPE_DIR : "video/mp4";
            else if (Document.COLUMN_FLAGS.equals(column)) value = 0;
            else if (Document.COLUMN_SIZE.equals(column)) value = file == null ? 0L : file.length();
            else if (Document.COLUMN_LAST_MODIFIED.equals(column)) value = file == null ? 0L : file.lastModified();
            row.add(value);
        }
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String order) throws FileNotFoundException {
        if (!"root".equals(parent)) throw new FileNotFoundException("Unknown parent");
        // Direct grants only: no discovery of another test's UUID documents.
        return new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only fixture");
        if (signal != null) signal.throwIfCanceled();
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
