/* SPDX-License-Identifier: Apache-2.0 */

package io.github.sylirre.terminal.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.github.sylirre.terminal.R;
import io.github.sylirre.terminal.term.UserlandOptions;
import io.github.sylirre.terminal.term.UserlandRootfs;

/**
 * The agent board: a full-screen WebView (assets/board) over the terminal
 * UI, with {@code AshNative} as its bridge to the app. The page does the
 * rendering and the herdr logic; this side owns the SSH links, the native
 * terminal tabs, QR pairing, notifications and dictation.
 */
@SuppressLint("SetJavaScriptEnabled")
final class BoardView {

    /** What the board asks of the terminal side of MainActivity. */
    interface Host {
        void boardShowShell();
        /** New userland tab typing {@code command} at its prompt. */
        void boardOpenTab(String command);
        void boardScanQr();
        void boardPastePairing();
        UserlandOptions boardUserlandOptions();
        /**
         * The page's place for the app's nav bar: its root ("board" or
         * "activity"), agents waiting on you, and unseen "needs you" events.
         */
        void boardNav(String tab, int needs, int unseen);
    }

    static final String EXTRA_HOST = "io.github.sylirre.terminal.board.HOST";
    static final String EXTRA_PANE = "io.github.sylirre.terminal.board.PANE";
    static final int REQ_DICTATE = 4711;
    private static final String CHANNEL_ID = "agents";
    private static final String CHANNEL_DONE = "agents_done";
    private final AppSettings settings;
    private final AppUpdater.Listener updates = s -> js("ash.updateChanged && ash.updateChanged()");
    private static final String URL = "file:///android_asset/board/index.html";

    private final Activity activity;
    private final Host host;
    private final WebView web;
    private final BoardLinks links;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String pendingDictation;
    private boolean visible;
    private boolean loaded;
    private final List<String> queued = new ArrayList<>();

    BoardView(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        settings = new AppSettings(activity);
        AppUpdater.addListener(updates);
        web = new WebView(activity);
        web.setBackgroundColor(Color.parseColor("#121314"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);           // file:///android_asset only
        ws.setAllowContentAccess(false);
        ws.setTextZoom(100);
        ws.setMediaPlaybackRequiresUserGesture(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                // Links in agent replies open in the browser, never in here.
                Uri u = req.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                loaded = true;
                for (String js : queued) web.evaluateJavascript(js, null);
                queued.clear();
                js("ash.setVisible(" + visible + ")");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    android.util.Log.w("Board", m.message() + " @" + m.lineNumber());
                }
                return true;
            }
        });
        links = new BoardLinks(activity, new BoardLinks.Listener() {
            @Override
            public void onLink(String h, String state, String detail) {
                js("ash.onLink(" + q(h) + "," + q(state) + "," + q(detail) + ")");
            }

            @Override
            public void onReply(String h, String id, int status, String b64) {
                js("ash.onReply(" + q(h) + "," + q(id) + "," + status + "," + q(b64) + ")");
            }
        });
        web.addJavascriptInterface(new Native(), "AshNative");
        web.loadUrl(URL);
    }

    View view() {
        return web;
    }

    boolean isVisible() {
        return visible;
    }

    void setVisible(boolean v) {
        visible = v;
        web.setVisibility(v ? View.VISIBLE : View.GONE);
        if (v) {
            web.requestFocus();
            js("ash.hostsChanged()");
        }
        js("ash.setVisible(" + v + ")");
    }

    /** Back gesture while the board is up: the page pops its own stack, else the app goes to the background. */
    void back() {
        web.evaluateJavascript("ash.back()", r -> {
            if (!"true".equals(r)) activity.moveTaskToBack(true);
        });
    }

    /** The nav bar picked a board root ("board" or "activity"): it opens at the top, sheets closed. */
    void showTab(String tab) {
        js("ash.tab(" + q(tab) + ")");
    }

    void openAgent(String h, String pane) {
        js("ash.openAgent(" + q(h) + "," + q(pane) + ")");
    }

    /** Drops focus from the page's text fields so the keyboard doesn't open by itself on return. */
    void blur() {
        js("document.activeElement && document.activeElement.blur && document.activeElement.blur()");
        web.clearFocus();
    }

    void hostsChanged() {
        js("ash.hostsChanged()");
    }

    void onDictation(int resultCode, Intent data) {
        String id = pendingDictation;
        pendingDictation = null;
        if (id == null) return;
        String text = "";
        if (resultCode == Activity.RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) text = r.get(0);
        }
        js("ash.onDictation(" + q(id) + "," + q(text) + ")");
    }

    void destroy() {
        AppUpdater.removeListener(updates);
        links.stopAll();
        web.destroy();
    }

    private void js(String code) {
        main.post(() -> {
            if (!loaded) {
                queued.add(code);
                return;
            }
            web.evaluateJavascript(code, null);
        });
    }

    private static String q(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    // --- paired hosts ----------------------------------------------------------

    /** Paired computers with the address and user from their ssh_config block. */
    private JSONArray hostsJson() {
        JSONArray out = new JSONArray();
        File cfg = sshConfig();
        for (String name : SshLinks.savedHosts(activity)) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", name);
                String[] hu = hostAndUser(cfg, name);
                o.put("address", hu[0]);
                o.put("user", hu[1]);
            } catch (org.json.JSONException ignored) {
            }
            out.put(o);
        }
        return out;
    }

    private File sshConfig() {
        UserlandOptions o = host.boardUserlandOptions();
        String home = o.home == null || o.home.trim().isEmpty() ? "/root" : o.home.trim();
        return new File(UserlandRootfs.dir(activity), home.substring(1) + "/.ssh/config");
    }

    private static String[] hostAndUser(File cfg, String name) {
        String[] r = {"", ""};
        try (BufferedReader br = new BufferedReader(new FileReader(cfg))) {
            boolean in = false;
            String l;
            while ((l = br.readLine()) != null) {
                String t = l.trim();
                if (t.equals("# a-sh:" + name + " begin")) in = true;
                else if (t.equals("# a-sh:" + name + " end")) break;
                else if (in && t.startsWith("HostName ")) r[0] = t.substring(9).trim();
                else if (in && t.startsWith("User ")) r[1] = t.substring(5).trim();
            }
        } catch (IOException ignored) {
        }
        return r;
    }

    // --- notifications -----------------------------------------------------------

    /** kind: "needs" (high importance, heads-up) or "done" (default importance). */
    private void notifyAgent(String key, String title, String body, String kind) {
        NotificationManager nm = activity.getSystemService(NotificationManager.class);
        if (nm == null) return;
        boolean done = "done".equals(kind);
        String channel = done ? CHANNEL_DONE : CHANNEL_ID;
        if (nm.getNotificationChannel(channel) == null) {
            NotificationChannel ch = done
                    ? new NotificationChannel(CHANNEL_DONE, activity.getString(R.string.board_channel_done_name),
                            NotificationManager.IMPORTANCE_DEFAULT)
                    : new NotificationChannel(CHANNEL_ID, activity.getString(R.string.board_channel_name),
                            NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription(activity.getString(done ? R.string.board_channel_done_description
                    : R.string.board_channel_description));
            nm.createNotificationChannel(ch);
        }
        int bar = key.indexOf('|');
        Intent open = new Intent(activity, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_HOST, bar > 0 ? key.substring(0, bar) : "")
                .putExtra(EXTRA_PANE, bar > 0 ? key.substring(bar + 1) : "");
        PendingIntent pi = PendingIntent.getActivity(activity, key.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(activity, channel)
                .setSmallIcon(R.drawable.ic_stat_terminal)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build();
        nm.notify(CHANNEL_ID, key.hashCode(), n);
    }

    private void clearNotification(String key) {
        NotificationManager nm = activity.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(CHANNEL_ID, key.hashCode());
    }

    // --- the page's bridge -------------------------------------------------------

    /** Called on the WebView's binder thread; UI work hops to main. */
    private final class Native {
        @JavascriptInterface
        public String hosts() {
            return hostsJson().toString();
        }

        @JavascriptInterface
        public void connect(String h) {
            if (validHost(h)) links.connect(h, host.boardUserlandOptions());
        }

        @JavascriptInterface
        public void reconnect(String h) {
            if (validHost(h)) links.reconnect(h, host.boardUserlandOptions());
        }

        @JavascriptInterface
        public void send(String h, String line) {
            if (line != null && line.indexOf('\n') < 0) links.send(h, line);
        }

        @JavascriptInterface
        public void openTerminal(String h, String pane) {
            if (!validHost(h)) return;
            String cmd;
            if (pane != null && pane.matches("[A-Za-z0-9:_.-]{1,64}")) {
                // A plain ssh tab attached to that one pane; leaving it ends the tab.
                cmd = "exec ssh -t " + h + "-sh 'exec \"$SHELL\" -lc \"herdr agent attach " + pane + "\"'";
            } else {
                cmd = "exec ssh " + h;
            }
            main.post(() -> host.boardOpenTab(cmd));
        }

        @JavascriptInterface
        public void openShell(String h) {
            if (validHost(h)) main.post(() -> host.boardOpenTab("exec ssh " + h + "-sh"));
        }

        @JavascriptInterface
        public void showShell() {
            main.post(host::boardShowShell);
        }

        @JavascriptInterface
        public void nav(String tab, int needs, int unseen) {
            main.post(() -> host.boardNav(tab, needs, unseen));
        }

        @JavascriptInterface
        public void scanQr() {
            main.post(host::boardScanQr);
        }

        @JavascriptInterface
        public void pastePairing() {
            main.post(host::boardPastePairing);
        }

        @JavascriptInterface
        public void copy(String text) {
            main.post(() -> {
                ClipboardManager cm = activity.getSystemService(ClipboardManager.class);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("A-SH", text));
            });
        }

        @JavascriptInterface
        public void toast(String text) {
            main.post(() -> Toast.makeText(activity, text, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void haptic() {
            main.post(() -> web.performHapticFeedback(HapticFeedbackConstants.CONFIRM));
        }

        @JavascriptInterface
        public void dictate(String id) {
            main.post(() -> {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT,
                                activity.getString(R.string.board_dictate_prompt));
                try {
                    pendingDictation = id;
                    activity.startActivityForResult(i, REQ_DICTATE);
                } catch (ActivityNotFoundException e) {
                    pendingDictation = null;
                    js("ash.onDictation(" + q(id) + ",'')");
                    Toast.makeText(activity, R.string.board_no_dictation, Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void notify(String key, String title, String body, String kind) {
            main.post(() -> notifyAgent(key, title, body, kind));
        }

        @JavascriptInterface
        public String prefs() {
            JSONObject o = new JSONObject();
            try {
                o.put("needs", settings.notifyNeeds());
                o.put("done", settings.notifyDone());
                o.put("mutes", settings.notifyFollowMutes());
            } catch (org.json.JSONException ignored) {
            }
            return o.toString();
        }

        @JavascriptInterface
        public String updateInfo() {
            return AppUpdater.enabled(activity) ? AppUpdater.infoJson(activity) : "{}";
        }

        @JavascriptInterface
        public void openUpdates() {
            main.post(() -> AppUpdater.checkNow(activity));
        }

        @JavascriptInterface
        public void clearNotification(String key) {
            main.post(() -> BoardView.this.clearNotification(key));
        }
    }

    private boolean validHost(String h) {
        return h != null && h.matches("[A-Za-z0-9._-]{1,64}") && SshLinks.savedHosts(activity).contains(h);
    }
}
