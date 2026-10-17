/* SPDX-License-Identifier: Apache-2.0 */

package io.github.sylirre.terminal.ui;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Things sent from the phone to an agent or a terminal tab: photos,
 * screenshots, files. Picked with the composer's + (Camera / Photos /
 * Files) or shared into the app, then prepared here — images are shrunk to
 * what an agent can use (long edge ≤ 2048 px, the camera's EXIF rotation
 * applied) — and held in memory under a token until they are sent.
 *
 * Sending is the caller's job: {@link BoardView#upload} puts the bytes on
 * a paired computer, {@link MainActivity} into the phone's own Linux.
 */
final class Attachments {

    /** Largest file accepted, before any shrinking. */
    private static final long MAX_BYTES = 25L << 20;
    private static final int MAX_EDGE = 2048;
    private static final int THUMB_EDGE = 160;
    /** A PNG this small and within MAX_EDGE goes as is: sharp text, no re-encode. */
    private static final int KEEP_PNG_BYTES = 1536 * 1024;

    static final String CAMERA = "camera";
    static final String PHOTOS = "photos";
    static final String FILES = "files";

    static final class Item {
        final String token;
        /** File name on the receiving side: plain, unique, safe for the bridge. */
        final String name;
        final String mime;
        final byte[] data;
        final boolean image;
        /** Small JPEG data: URL for chips, or null for non-images. */
        final String thumb;

        Item(String token, String name, String mime, byte[] data, boolean image, String thumb) {
            this.token = token;
            this.name = name;
            this.mime = mime;
            this.data = data;
            this.image = image;
            this.thumb = thumb;
        }

        JSONObject json() {
            JSONObject o = new JSONObject();
            try {
                o.put("token", token);
                o.put("name", name);
                o.put("image", image);
                o.put("size", data.length);
                if (thumb != null) o.put("thumb", thumb);
            } catch (JSONException ignored) {
            }
            return o;
        }
    }

    private static final Map<String, Item> held = new ConcurrentHashMap<>();
    private static int seq;

    private Attachments() {
    }

    static Item get(String token) {
        return token == null ? null : held.get(token);
    }

    static void drop(String token) {
        if (token != null) held.remove(token);
    }

    static JSONArray json(List<Item> items) {
        JSONArray a = new JSONArray();
        for (Item it : items) a.put(it.json());
        return a;
    }

    // --- picking -------------------------------------------------------------

    /** The system photo picker (no permission needed), several at once. */
    static Intent photosIntent() {
        return new Intent(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10);
    }

    /** Fallback where there is no photo picker (API 29–32 without the backport). */
    static Intent galleryIntent() {
        return new Intent(Intent.ACTION_GET_CONTENT)
                .setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    }

    static Intent filesIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    }

    /** The camera app, writing a full-size photo to {@code out} (a {@link CaptureProvider} uri). */
    static Intent cameraIntent(Uri out) {
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, out)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.setClipData(ClipData.newRawUri("", out));
        return i;
    }

    /** Every uri a picker result or a share intent carries. */
    static List<Uri> uris(Intent data) {
        List<Uri> out = new ArrayList<>();
        if (data == null) return out;
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null && !out.contains(u)) out.add(u);
            }
        }
        if (data.getData() != null && !out.contains(data.getData())) out.add(data.getData());
        if (Intent.ACTION_SEND.equals(data.getAction())) {
            Uri u = data.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null && !out.contains(u)) out.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(data.getAction())) {
            ArrayList<Uri> list = data.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) for (Uri u : list) if (u != null && !out.contains(u)) out.add(u);
        }
        return out;
    }

    // --- preparing -----------------------------------------------------------

    /** Reads and shrinks one picked/shared uri; blocking, so off the main thread. */
    static Item prepare(Context c, Uri uri) throws IOException {
        ContentResolver cr = c.getContentResolver();
        String mime = cr.getType(uri);
        String display = displayName(cr, uri);
        if (mime == null) mime = guessMime(display);
        byte[] raw = readAll(cr, uri);
        boolean image = mime.startsWith("image/") && !mime.equals("image/svg+xml")
                && !mime.equals("image/gif");
        String base = baseName(display, image ? "image" : "file");
        String ext = extension(display);
        byte[] data = raw;
        String thumb = null;
        if (image) {
            int[] size = new int[2];
            Bitmap full = decode(cr, uri, MAX_EDGE, size);
            boolean keep = "image/png".equals(mime) && raw.length <= KEEP_PNG_BYTES
                    && Math.max(size[0], size[1]) <= MAX_EDGE;
            if (!keep) {
                data = jpeg(full, 90);
                mime = "image/jpeg";
                ext = "jpg";
            } else {
                ext = "png";
            }
            float s = Math.min(1f, THUMB_EDGE / (float) Math.max(full.getWidth(), full.getHeight()));
            Bitmap small = Bitmap.createScaledBitmap(full,
                    Math.max(1, Math.round(full.getWidth() * s)),
                    Math.max(1, Math.round(full.getHeight() * s)), true);
            thumb = "data:image/jpeg;base64," + Base64.encodeToString(jpeg(small, 70), Base64.NO_WRAP);
            if (small != full) small.recycle();
            full.recycle();
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String name;
        synchronized (Attachments.class) {
            name = stamp + "-" + (++seq) + "-" + base + (ext.isEmpty() ? "" : "." + ext);
        }
        Item it = new Item(Long.toHexString(System.nanoTime()) + seq, name, mime, data, image, thumb);
        held.put(it.token, it);
        return it;
    }

    /** Decodes with the EXIF orientation applied, scaled so the long edge is ≤ {@code edge}. */
    private static Bitmap decode(ContentResolver cr, Uri uri, int edge, int[] origSize)
            throws IOException {
        ImageDecoder.Source src = ImageDecoder.createSource(cr, uri);
        return ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
            int w = info.getSize().getWidth();
            int h = info.getSize().getHeight();
            origSize[0] = w;
            origSize[1] = h;
            float k = Math.min(1f, edge / (float) Math.max(w, h));
            if (k < 1f) decoder.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }

    private static byte[] jpeg(Bitmap b, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, quality, out);
        return out.toByteArray();
    }

    private static byte[] readAll(ContentResolver cr, Uri uri) throws IOException {
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("can't open " + uri);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new IOException("over " + (MAX_BYTES >> 20) + " MB");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static String displayName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last;
    }

    private static String guessMime(String name) {
        String ext = extension(name).toLowerCase(Locale.US);
        String m = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m == null ? "application/octet-stream" : m;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        String e = dot > 0 ? name.substring(dot + 1) : "";
        return e.matches("[A-Za-z0-9]{1,8}") ? e.toLowerCase(Locale.US) : "";
    }

    /** The display name without its extension, reduced to [A-Za-z0-9_-], ≤ 40 chars. */
    private static String baseName(String name, String fallback) {
        int dot = name.lastIndexOf('.');
        String b = (dot > 0 ? name.substring(0, dot) : name)
                .replaceAll("[^A-Za-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        if (b.length() > 40) b = b.substring(0, 40);
        return b.isEmpty() ? fallback : b;
    }

    /** Where the camera writes a new photo (served by {@link CaptureProvider}). */
    static File captureFile(Context c) {
        File dir = new File(c.getCacheDir(), CaptureProvider.DIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) {
            // Last session's shots are already sent or abandoned.
            if (System.currentTimeMillis() - f.lastModified() > 3_600_000L) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        return new File(dir, "photo-" + System.currentTimeMillis() + ".jpg");
    }
}
