/* SPDX-License-Identifier: Apache-2.0 */

package io.github.sylirre.terminal.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import io.github.sylirre.terminal.R;

/**
 * Automatic app updates from the fork's GitHub Releases (no store involved).
 *
 * CI publishes every main-branch build as a release tagged
 * {@code build-<versionCode>} carrying the signed APK. On resume, at most
 * every few hours, this checks the latest release; a newer build is
 * downloaded in the background. Installing restarts the app — which ends
 * running shells — so a ready update is installed straight away only when no
 * tab is open (a fresh launch), and otherwise offered in a dialog.
 *
 * The install goes through a {@link PackageInstaller} session that asks for no
 * user action: Android 12+ honours that once A-SH is the installer of record
 * (i.e. from the second self-update on). Before that, or when the system
 * insists, {@link InstallResultReceiver} shows the system confirmation.
 */
final class AppUpdater {

    private static final String TAG = "AppUpdater";
    private static final String PREFS = "app_updater";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_READY_CODE = "ready_code";
    private static final String KEY_READY_NAME = "ready_name";
    private static final long CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000;
    private static final String APK_NAME = "update.apk";

    private static boolean busy;
    private static boolean offeredThisProcess;

    private AppUpdater() {}

    /**
     * Called from {@code onResume}. {@code idle} means no session is open, so
     * installing (which kills the process) loses nothing.
     */
    static void maybeCheck(Activity activity, boolean idle) {
        Context app = activity.getApplicationContext();
        String repo = app.getString(R.string.update_repo);
        if (repo.isEmpty() || busy) return;
        // Debug builds carry the debug key, so a release APK can't replace
        // them anyway — and the instrumented tests must never meet a system
        // install prompt mid-run.
        if ((app.getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) return;
        SharedPreferences prefs = prefs(app);
        long installed = installedVersionCode(app);

        long ready = prefs.getLong(KEY_READY_CODE, 0);
        if (ready > installed && apkFile(app).isFile()) {
            if (idle) install(app);
            else offerInstall(activity, prefs.getString(KEY_READY_NAME, ""));
            return;
        }

        long now = System.currentTimeMillis();
        if (now - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MS) return;
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply();

        busy = true;
        new Thread(() -> {
            try {
                checkAndDownload(app, repo, installed);
            } catch (Exception e) {
                Log.w(TAG, "update check failed: " + e);
            } finally {
                busy = false;
            }
        }, "app-update").start();
    }

    private static void checkAndDownload(Context app, String repo, long installed)
            throws Exception {
        JSONObject rel = new JSONObject(new String(
                get("https://api.github.com/repos/" + repo + "/releases/latest"),
                StandardCharsets.UTF_8));
        String tag = rel.optString("tag_name", "");
        if (!tag.startsWith("build-")) return;
        long code = Long.parseLong(tag.substring("build-".length()));
        if (code <= installed) return;
        String apkUrl = null;
        JSONArray assets = rel.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.optString("name").endsWith(".apk")) {
                apkUrl = a.getString("browser_download_url");
                break;
            }
        }
        if (apkUrl == null) return;

        File apk = apkFile(app);
        File tmp = new File(apk.getPath() + ".part");
        apk.getParentFile().mkdirs();
        download(apkUrl, tmp);
        // Only an APK for this package that's really newer is ever installed.
        android.content.pm.PackageInfo info =
                app.getPackageManager().getPackageArchiveInfo(tmp.getPath(), 0);
        if (info == null || !app.getPackageName().equals(info.packageName)
                || info.getLongVersionCode() <= installed) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(apk)) throw new IOException("cannot move " + tmp);
        prefs(app).edit()
                .putLong(KEY_READY_CODE, info.getLongVersionCode())
                .putString(KEY_READY_NAME, rel.optString("name", info.versionName))
                .apply();
        Log.i(TAG, "update " + info.getLongVersionCode() + " downloaded");
    }

    private static void offerInstall(Activity activity, String name) {
        if (offeredThisProcess || activity.isFinishing()) return;
        offeredThisProcess = true;
        new AlertDialog.Builder(activity)
                .setTitle(R.string.update_ready_title)
                .setMessage(activity.getString(R.string.update_ready_message, name))
                .setPositiveButton(R.string.update_install_now,
                        (d, w) -> install(activity.getApplicationContext()))
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    private static void install(Context app) {
        File apk = apkFile(app);
        PackageInstaller installer = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(app.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(
                    PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
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
            Log.w(TAG, "install failed: " + e);
            Toast.makeText(app, app.getString(R.string.update_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Session result: show the system confirmation when one is required. */
    public static final class InstallResultReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(confirm);
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Log.w(TAG, "install status " + status + ": " + msg);
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(ctx,
                            ctx.getString(R.string.update_failed, msg), Toast.LENGTH_LONG).show());
                }
                // Backing out of the prompt (or of the one-time "install
                // unknown apps" permission) keeps the download: the next
                // fresh launch offers it again. A rejected APK (e.g. signed
                // by another key) must not be retried on every launch; the
                // next check fetches afresh.
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    apkFile(ctx).delete();
                    prefs(ctx).edit().remove(KEY_READY_CODE).apply();
                }
            }
        }
    }

    // --- plumbing -----------------------------------------------------------

    private static long installedVersionCode(Context app) {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0)
                    .getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return Long.MAX_VALUE; // can't tell: never update
        }
    }

    private static File apkFile(Context app) {
        return new File(new File(app.getCacheDir(), "updates"), APK_NAME);
    }

    private static SharedPreferences prefs(Context app) {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static HttpURLConnection open(String url, String accept) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept", accept);
        c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() != 200) {
            int code = c.getResponseCode();
            c.disconnect();
            throw new IOException("HTTP " + code + " for " + url);
        }
        return c;
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection c = open(url, "application/vnd.github+json");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File dest) throws IOException {
        HttpURLConnection c = open(url, "application/octet-stream");
        try (InputStream in = c.getInputStream();
             OutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
        } finally {
            c.disconnect();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
