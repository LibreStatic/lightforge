package com.ugallery.pdfprovider.fixture;

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
    @Override public boolean onCreate() { return true; }
    private File file(String id) throws FileNotFoundException {
        if (!id.equals("one") && !id.equals("two")) throw new FileNotFoundException("Unknown fixture document");
        return new File(getContext().getFilesDir(),id);
    }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor c=new MatrixCursor(new String[]{Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_FLAGS,Root.COLUMN_MIME_TYPES});
        c.addRow(new Object[]{"root","root","Local provider fixture",Root.FLAG_LOCAL_ONLY,"image/png"});return c;
    }
    @Override public Cursor queryDocument(String id,String[] projection) throws FileNotFoundException {
        MatrixCursor c=new MatrixCursor(new String[]{Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS,Document.COLUMN_SIZE});
        c.addRow(new Object[]{id,id,id.equals("root") ? Document.MIME_TYPE_DIR : "image/png",Document.FLAG_SUPPORTS_WRITE,id.equals("root") ? 0 : file(id).length()});return c;
    }
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String order) {
        return new MatrixCursor(new String[]{Document.COLUMN_DOCUMENT_ID});
    }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(id),ParcelFileDescriptor.parseMode(mode));
    }
}
