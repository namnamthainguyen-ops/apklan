package com.lanpower.standalone;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** The only shareable file is the already verified APK in this app's private cache. */
public final class UpdateApkProvider extends ContentProvider {
    static final String FILE_NAME = "room-tn20-update.apk";
    static final String AUTHORITY = "com.lanpower.standalone.updates";
    static final Uri APK_URI = Uri.parse("content://" + AUTHORITY + "/apk/" + FILE_NAME);

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { requireUri(uri); return "application/vnd.android.package-archive"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        requireUri(uri);
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only APK");
        return ParcelFileDescriptor.open(file(), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        requireUri(uri);
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = "RoomTN20-update.apk";
            else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file().length();
        }
        cursor.addRow(values);
        return cursor;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    private File file() { return new File(getContext().getCacheDir(), FILE_NAME); }
    private static void requireUri(Uri uri) { if (!APK_URI.equals(uri)) throw new SecurityException("Unknown update file"); }
}
