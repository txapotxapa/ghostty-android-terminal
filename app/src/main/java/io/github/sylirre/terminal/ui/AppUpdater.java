/* SPDX-License-Identifier: Apache-2.0 */

package io.github.sylirre.terminal.ui;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.sylirre.terminal.R;

/**
 * Self-updates from GitHub Releases, as one visible state machine:
 *
 * <pre>
 *   idle → checking → up to date
 *                   → available → downloading (%) → ready → [permission] → installing
 *   any step can end in failed (with the reason), which Retry restarts.
 * </pre>
 *
 * The automatic path ({@link #maybeCheck}, on resume) checks at most every
 * 15 minutes and downloads by itself only on an unmetered network (or when
 * the user allowed mobile data); the APK is ~130 MB. Everything it does shows
 * up in {@link UpdateDialog} and in the board's banner, and "Check for
 * updates" attaches to whatever is already running instead of being ignored.
 */
final class AppUpdater {

    enum Phase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, PERMISSION, INSTALLING, FAILED }

    /** Immutable snapshot for listeners. */
    static final class State {
        final Phase phase;
        final String installed;
        final String version;     // latest release, when known
        final long size;
        final String notes;
        final long done;
        final long total;
        final String error;
        final long checkedAt;

        State(Phase phase, String installed, String version, long size, String notes,
                long done, long total, String error, long checkedAt) {
            this.phase = phase;
            this.installed = installed;
            this.version = version;
            this.size = size;
            this.notes = notes;
            this.done = done;
            this.total = total;
            this.error = error;
            this.checkedAt = checkedAt;
        }

        int percent() {
            return total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
        }
    }

    interface Listener {
        void onUpdateState(State s);
    }

    private static final String TAG = "AppUpdater";
    private static final String PREFS = "app_updater";
    private static final String KEY_CHECKED = "checked_at";
    private static final String KEY_LATEST = "latest_json";
    private static final String KEY_OFFERED = "offered_code";
    private static final String KEY_LAST_RUN = "last_run_code";
    private static final long AUTO_INTERVAL_MS = 15L * 60 * 1000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    // Guarded by AppUpdater.class.
    private static Phase phase = Phase.IDLE;
    private static Release latest;
    private static long done;
    private static long total;
    private static String error;
    private static boolean restored;
    private static volatile boolean cancel;
    /** The system's install confirmation, when it couldn't be shown right away. */
    static volatile Intent pendingConfirm;

    private AppUpdater() {}

    /** One GitHub release with an APK asset. */
    private static final class Release {
        String tag;
        String name;
        long code;
        String apkUrl;
        long size;
        String notes;

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("tag", tag).put("name", name).put("code", code)
                    .put("url", apkUrl).put("size", size).put("notes", notes);
        }

        static Release fromJson(JSONObject o) {
            Release r = new Release();
            r.tag = o.optString("tag");
            r.name = o.optString("name");
            r.code = o.optLong("code");
            r.apkUrl = o.optString("url");
            r.size = o.optLong("size");
            r.notes = o.optString("notes");
            return r;
        }
    }

    // --- state -------------------------------------------------------------------

    static void addListener(Listener l) {
        listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    static synchronized State state(Context ctx) {
        restore(ctx);
        return new State(phase, installedVersionName(ctx),
                latest == null ? null : versionNameOf(latest),
                latest == null ? 0 : latest.size, latest == null ? "" : latest.notes,
                done, total, error, prefs(ctx).getLong(KEY_CHECKED, 0));
    }

    /** For the board's banner. */
    static String infoJson(Context ctx) {
        State s = state(ctx);
        JSONObject o = new JSONObject();
        try {
            String p = s.phase.name().toLowerCase(java.util.Locale.ROOT);
            o.put("state", s.phase == Phase.PERMISSION ? "ready" : p);
            o.put("version", s.version == null ? "" : s.version);
            o.put("percent", s.percent());
            o.put("error", s.error == null ? "" : s.error);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    private static void set(Context ctx, Phase p, String err) {
        synchronized (AppUpdater.class) {
            phase = p;
            error = err;
        }
        State s = state(ctx);
        if (p != Phase.DOWNLOADING) Log.i(TAG, "-> " + p + (err == null ? "" : ": " + err));
        MAIN.post(() -> {
            for (Listener l : listeners) l.onUpdateState(s);
        });
    }

    /** After a restart: a finished download is still ready, a known release still available. */
    private static void restore(Context ctx) {
        if (restored) return;
        restored = true;
        String j = prefs(ctx).getString(KEY_LATEST, null);
        if (j == null) return;
        try {
            Release r = Release.fromJson(new JSONObject(j));
            if (r.code <= installedVersionCode(ctx)) return;
            latest = r;
            phase = apkFile(ctx).isFile() ? Phase.READY : Phase.AVAILABLE;
        } catch (JSONException ignored) {
        }
    }

    // --- triggers ------------------------------------------------------------------

    /** On resume: quiet automatic check, background download on Wi-Fi, one offer per new version. */
    static void maybeCheck(Activity activity, boolean idle) {
        Context app = activity.getApplicationContext();
        announceIfUpdated(activity);
        if (!enabled(app)) return;
        State s = state(app);
        if (s.phase == Phase.READY) {
            long code;
            synchronized (AppUpdater.class) {
                code = latest == null ? 0 : latest.code;
            }
            if (prefs(app).getLong(KEY_OFFERED, 0) != code) {
                prefs(app).edit().putLong(KEY_OFFERED, code).apply();
                UpdateDialog.show(activity);
            }
            return;
        }
        if (s.phase == Phase.CHECKING || s.phase == Phase.DOWNLOADING || s.phase == Phase.INSTALLING) return;
        if (System.currentTimeMillis() - s.checkedAt < AUTO_INTERVAL_MS) {
            if (s.phase == Phase.AVAILABLE && downloadAllowed(app)) download(app);
            return;
        }
        check(app, false);
    }

    /** "Check for updates": opens the dialog and never does nothing. */
    static void checkNow(Activity activity) {
        Context app = activity.getApplicationContext();
        UpdateDialog.show(activity);
        if (!enabled(app)) {
            set(app, Phase.FAILED, app.getString(R.string.update_debug_build));
            return;
        }
        Phase p = state(app).phase;
        // Already busy, or ready to install: the dialog attaches to that.
        if (p == Phase.CHECKING || p == Phase.DOWNLOADING || p == Phase.INSTALLING
                || p == Phase.READY || p == Phase.PERMISSION) return;
        check(app, true);
    }

    static void check(Context app, boolean userAsked) {
        set(app, Phase.CHECKING, null);
        new Thread(() -> {
            try {
                Release r = fetchLatest(app);
                prefs(app).edit().putLong(KEY_CHECKED, System.currentTimeMillis())
                        .putString(KEY_LATEST, r == null ? null : r.toJson().toString()).apply();
                synchronized (AppUpdater.class) {
                    latest = r;
                }
                if (r == null || r.code <= installedVersionCode(app)) {
                    apkFile(app).delete();
                    set(app, Phase.UP_TO_DATE, null);
                } else if (apkFile(app).isFile() && verify(app, apkFile(app)) == null) {
                    set(app, Phase.READY, null);
                } else {
                    set(app, Phase.AVAILABLE, null);
                    if (userAsked || downloadAllowed(app)) download(app);
                }
            } catch (Exception e) {
                Log.w(TAG, "check failed", e);
                prefs(app).edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply();
                set(app, Phase.FAILED, userMessage(app, e));
            }
        }, "update-check").start();
    }

    static void download(Context app) {
        Release r;
        synchronized (AppUpdater.class) {
            if (phase == Phase.DOWNLOADING) return;
            r = latest;
            if (r == null) return;
            phase = Phase.DOWNLOADING;
            done = 0;
            total = r.size;
            cancel = false;
        }
        set(app, Phase.DOWNLOADING, null);
        new Thread(() -> {
            File apk = apkFile(app);
            File part = new File(apk.getPath() + ".part");
            try {
                apk.getParentFile().mkdirs();
                fetchApk(app, r, part);
                if (cancel) {
                    set(app, Phase.AVAILABLE, null);
                    return;
                }
                String bad = verify(app, part);
                if (bad != null) {
                    part.delete();
                    set(app, Phase.FAILED, bad);
                    return;
                }
                if (!part.renameTo(apk)) throw new IOException("cannot move the download into place");
                set(app, Phase.READY, null);
            } catch (Exception e) {
                Log.w(TAG, "download failed", e);
                set(app, cancel ? Phase.AVAILABLE : Phase.FAILED, cancel ? null : userMessage(app, e));
            }
        }, "update-download").start();
    }

    static void cancelDownload() {
        cancel = true;
    }

    /** Installs the downloaded APK, asking for "install unknown apps" first when needed. */
    static void install(Activity activity) {
        Context app = activity.getApplicationContext();
        File apk = apkFile(app);
        if (!apk.isFile()) {
            download(app);
            return;
        }
        if (!app.getPackageManager().canRequestPackageInstalls()) {
            set(app, Phase.PERMISSION, null);
            return;
        }
        set(app, Phase.INSTALLING, null);
        new Thread(() -> commit(app, apk), "update-install").start();
    }

    /** Opens Android's "install unknown apps" page for this app. */
    static void openInstallPermission(Activity activity) {
        try {
            activity.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:" + activity.getPackageName())));
        } catch (android.content.ActivityNotFoundException e) {
            activity.startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS));
        }
    }

    /** Back from the permission page: carry on when it was granted. */
    static void resumeAfterPermission(Activity activity) {
        if (state(activity).phase == Phase.PERMISSION
                && activity.getPackageManager().canRequestPackageInstalls()) {
            install(activity);
        }
    }

    /** Shows a system install confirmation that couldn't be shown when it arrived. */
    static boolean showPendingConfirm(Activity activity) {
        Intent i = pendingConfirm;
        if (i == null) return false;
        pendingConfirm = null;
        try {
            activity.startActivity(i);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "confirm: " + e);
            return false;
        }
    }

    // --- install -------------------------------------------------------------------

    private static void commit(Context app, File apk) {
        PackageInstaller installer = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(app.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        if (Build.VERSION.SDK_INT >= 34) {
            // Become this app's update owner, so later updates need no prompt
            // even when it was first installed over USB or from a browser.
            params.setRequestUpdateOwnership(true);
        }
        try {
            int id = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(id)) {
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                    copy(in, out);
                    session.fsync(out);
                }
                Intent result = new Intent(app, InstallResultReceiver.class);
                PendingIntent pi = PendingIntent.getBroadcast(app, id, result,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                session.commit(pi.getIntentSender());
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "install failed", e);
            set(app, Phase.FAILED, app.getString(R.string.update_err_generic, e.getMessage()));
        }
    }

    /** Session result. Success kills this process; the other outcomes are shown. */
    public static final class InstallResultReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            Context app = ctx.getApplicationContext();
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Log.i(TAG, "install status " + status + ": " + msg);
            switch (status) {
                case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm == null) {
                        set(app, Phase.FAILED, app.getString(R.string.update_err_generic, String.valueOf(msg)));
                        return;
                    }
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    pendingConfirm = confirm;
                    set(app, Phase.INSTALLING, null);
                    try {
                        app.startActivity(confirm);
                        pendingConfirm = null;
                    } catch (RuntimeException e) {
                        // Background-launch rules: the dialog shows "Continue" instead.
                        Log.w(TAG, "confirm launch: " + e);
                    }
                    return;
                }
                case PackageInstaller.STATUS_SUCCESS:
                    return;
                case PackageInstaller.STATUS_FAILURE_ABORTED:
                    set(app, Phase.READY, app.getString(R.string.update_err_aborted));
                    return;
                case PackageInstaller.STATUS_FAILURE_INVALID:
                    apkFile(app).delete();
                    set(app, Phase.FAILED, app.getString(R.string.update_err_invalid));
                    return;
                default:
                    set(app, Phase.FAILED, describeFailure(app, status, msg));
            }
        }
    }

    private static String describeFailure(Context app, int status, String msg) {
        String detail = msg == null ? "" : msg;
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return app.getString(R.string.update_err_blocked, detail);
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return app.getString(R.string.update_err_conflict, detail);
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return app.getString(R.string.update_err_incompatible, detail);
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return app.getString(R.string.update_err_storage);
            default:
                return app.getString(R.string.update_err_generic, detail);
        }
    }

    // --- "updated to" ------------------------------------------------------------

    /** First launch after an update: say so once and drop the installed download. */
    private static void announceIfUpdated(Activity activity) {
        Context app = activity.getApplicationContext();
        long now = installedVersionCode(app);
        long last = prefs(app).getLong(KEY_LAST_RUN, 0);
        if (last == now) return;
        prefs(app).edit().putLong(KEY_LAST_RUN, now).apply();
        synchronized (AppUpdater.class) {
            if (latest != null && latest.code <= now) {
                latest = null;
                phase = Phase.IDLE;
            }
        }
        apkFile(app).delete();
        if (last != 0 && now > last) {
            android.widget.Toast.makeText(activity,
                    app.getString(R.string.update_done_toast, installedVersionName(app)),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    // --- network -------------------------------------------------------------------

    private static Release fetchLatest(Context app) throws IOException, JSONException {
        String repo = app.getString(R.string.update_repo);
        HttpURLConnection c = open("https://api.github.com/repos/" + repo + "/releases/latest");
        try {
            int code = c.getResponseCode();
            if (code == 404) return null;
            if (code == 403 || code == 429) throw new IOException("rate-limited");
            if (code != 200) throw new IOException("GitHub answered " + code);
            JSONObject rel;
            try (InputStream in = c.getInputStream()) {
                rel = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            }
            Release r = new Release();
            r.tag = rel.optString("tag_name", "");
            r.name = rel.optString("name", r.tag);
            r.code = versionCodeOf(r.tag);
            String notes = rel.optString("body", "").trim();
            r.notes = notes.length() > 1500 ? notes.substring(0, 1500) + "…" : notes;
            JSONArray assets = rel.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").endsWith(".apk")) {
                    r.apkUrl = a.getString("browser_download_url");
                    r.size = a.optLong("size");
                    break;
                }
            }
            return r.code > 0 && r.apkUrl != null ? r : null;
        } finally {
            c.disconnect();
        }
    }

    /** Downloads with resume: a dropped connection continues where it stopped. */
    private static void fetchApk(Context app, Release r, File part) throws IOException {
        long have = part.isFile() ? part.length() : 0;
        if (r.size > 0 && have >= r.size) {
            part.delete();
            have = 0;
        }
        HttpURLConnection c = (HttpURLConnection) new URL(r.apkUrl).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "A-SH-updater");
        if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
        try {
            int code = c.getResponseCode();
            boolean append = code == 206;
            if (code != 200 && code != 206) throw new IOException("download answered " + code);
            if (!append) have = 0;
            long len = c.getContentLengthLong();
            synchronized (AppUpdater.class) {
                done = have;
                total = len > 0 ? have + len : r.size;
            }
            long lastTick = 0;
            try (InputStream in = c.getInputStream();
                 OutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancel) return;
                    out.write(buf, 0, n);
                    synchronized (AppUpdater.class) {
                        done += n;
                    }
                    long t = System.currentTimeMillis();
                    if (t - lastTick > 300) {
                        lastTick = t;
                        set(app, Phase.DOWNLOADING, null);
                    }
                }
            }
            long want;
            synchronized (AppUpdater.class) {
                want = total;
            }
            if (want > 0 && part.length() < want) throw new IOException("connection dropped");
        } finally {
            c.disconnect();
        }
    }

    /** Null when the APK is this app, newer, and signed with this app's key; else why not. */
    private static String verify(Context app, File apk) {
        PackageManager pm = app.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (info == null || !app.getPackageName().equals(info.packageName)) {
            return app.getString(R.string.update_err_invalid);
        }
        if (info.getLongVersionCode() <= installedVersionCode(app)) {
            return app.getString(R.string.update_err_not_newer);
        }
        Signature[] sigs = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        if (sigs == null || sigs.length == 0) return app.getString(R.string.update_err_invalid);
        for (Signature s : sigs) {
            if (!pm.hasSigningCertificate(app.getPackageName(), s.toByteArray(),
                    PackageManager.CERT_INPUT_RAW_X509)) {
                return app.getString(R.string.update_err_conflict, "");
            }
        }
        return null;
    }

    private static String userMessage(Context app, Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("rate-limited")) return app.getString(R.string.update_err_ratelimit);
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.ConnectException) {
            return app.getString(R.string.update_err_offline);
        }
        if (m.contains("connection dropped")) return app.getString(R.string.update_err_dropped);
        return app.getString(R.string.update_err_generic, m);
    }

    // --- plumbing ----------------------------------------------------------------

    static boolean enabled(Context app) {
        return !app.getString(R.string.update_repo).isEmpty()
                // Debug builds carry the debug key, so a release can't replace
                // them, and instrumented tests must never meet an install prompt.
                && (app.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0;
    }

    private static boolean downloadAllowed(Context app) {
        if (new AppSettings(app).updatesOnMobileData()) return true;
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        return cm != null && cm.getActiveNetwork() != null && !cm.isActiveNetworkMetered();
    }

    private static final java.util.regex.Pattern RELEASE_TAG =
            java.util.regex.Pattern.compile("a-sh-qr-pair_v(\\d+)\\.(\\d+)\\.(\\d+)");

    /** versionCode for a release tag (app/build.gradle's formula), or -1. */
    static long versionCodeOf(String tag) {
        java.util.regex.Matcher m = RELEASE_TAG.matcher(tag);
        if (!m.matches()) return -1;
        return Long.parseLong(m.group(1)) * 10000000L
                + Long.parseLong(m.group(2)) * 100000L
                + Long.parseLong(m.group(3));
    }

    private static String versionNameOf(Release r) {
        java.util.regex.Matcher m = RELEASE_TAG.matcher(r.tag);
        return m.matches() ? m.group(1) + "." + m.group(2) + "." + m.group(3) : r.name;
    }

    static long installedVersionCode(Context app) {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0)
                    .getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return Long.MAX_VALUE; // can't tell: never update
        }
    }

    static String installedVersionName(Context app) {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    static String releasesPage(Context app) {
        return "https://github.com/" + app.getString(R.string.update_repo) + "/releases/latest";
    }

    private static File apkFile(Context app) {
        return new File(new File(app.getCacheDir(), "updates"), "update.apk");
    }

    private static SharedPreferences prefs(Context app) {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "A-SH-updater");
        c.setInstanceFollowRedirects(true);
        return c;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
