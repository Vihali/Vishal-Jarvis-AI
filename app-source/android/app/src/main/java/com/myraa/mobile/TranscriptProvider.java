package com.myraa.mobile;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Narrow, read-only grant for the one user-created transcript in the private cache. */
public final class TranscriptProvider extends ContentProvider {
  static final String AUTHORITY = "com.myraa.mobile.transcripts";
  static final Uri URI = Uri.parse("content://" + AUTHORITY + "/transcript.txt");
  static File file(android.content.Context context) { return new File(new File(context.getCacheDir(), "exports"), "transcript.txt"); }
  private boolean valid(Uri uri) { return uri != null && AUTHORITY.equals(uri.getAuthority()) && "/transcript.txt".equals(uri.getPath()); }
  @Override public boolean onCreate() { return true; }
  @Override public String getType(Uri uri) { return valid(uri) ? "text/plain" : null; }
  @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    if (!valid(uri)) return null;
    String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
    MatrixCursor cursor = new MatrixCursor(columns); Object[] row = new Object[columns.length];
    for (int i = 0; i < columns.length; i++) {
      if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "Vishal-Jarvis-conversation.txt";
      else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file(getContext()).length();
    }
    cursor.addRow(row); return cursor;
  }
  @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!valid(uri) || !"r".equals(mode)) throw new FileNotFoundException("Read-only transcript");
    return ParcelFileDescriptor.open(file(getContext()), ParcelFileDescriptor.MODE_READ_ONLY);
  }
  @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
  @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
  @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
