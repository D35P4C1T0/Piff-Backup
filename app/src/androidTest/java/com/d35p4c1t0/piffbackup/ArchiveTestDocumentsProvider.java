package com.d35p4c1t0.piffbackup;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Public synthetic documents, packaged only in the instrumentation APK.
 * Java keeps the separate provider process independent of the target's Kotlin runtime.
 */
public final class ArchiveTestDocumentsProvider extends ContentProvider {
    private static final byte[] CONTENT = "SAF receipt 😄".getBytes(StandardCharsets.UTF_8);
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        String id = "children".equals(uri.getLastPathSegment()) ? "receipt" : DocumentsContract.getDocumentId(uri);
        String[] columns = projection == null ? new String[] {
            "document_id", "_display_name", "mime_type", "_size", "last_modified", "flags"
        } : projection;
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case "document_id": values[i] = id; break;
                case "_display_name": values[i] = "root".equals(id) ? "Fixture" : "receipt 😄.txt"; break;
                case "mime_type": values[i] = "root".equals(id) ? DocumentsContract.Document.MIME_TYPE_DIR : "text/plain"; break;
                case "_size": values[i] = "root".equals(id) ? 0 : CONTENT.length; break;
                case "last_modified": values[i] = 1000L; break;
                case "flags": values[i] = 0; break;
            }
        }
        MatrixCursor result = new MatrixCursor(columns);
        result.addRow(values);
        return result;
    }
    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        if (!"receipt".equals(DocumentsContract.getDocumentId(uri)) || !"r".equals(mode)) {
            throw new java.io.FileNotFoundException("Unsupported fixture document");
        }
        File file = new File(getContext().getCacheDir(), "saf-fixture.txt");
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(CONTENT); }
        catch (IOException failure) { throw new java.io.FileNotFoundException(failure.getMessage()); }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
