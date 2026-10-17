/* SPDX-License-Identifier: Apache-2.0 */

package io.github.sylirre.terminal.ui;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Hands the camera app one file to write a photo into (the composer's +
 * → Camera), without AndroidX's FileProvider. Serves only plain names in
 * {@code cache/capture}; not exported, so the camera reaches it solely
 * through the uri permission the capture intent grants.
 */
public final class CaptureProvider extends ContentProvider {

    static final String DIR = "capture";

    static Uri uriFor(Context c, File f) {
        return new Uri.Builder().scheme("content")
                .authority(c.getPackageName() + ".capture")
                .appendPath(f.getName())
                .build();
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("[A-Za-z0-9._-]{1,80}") || name.startsWith(".")) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        return new File(new File(getContext().getCacheDir(), DIR), name);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
