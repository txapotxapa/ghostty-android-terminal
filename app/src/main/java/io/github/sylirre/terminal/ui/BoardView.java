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
        /** New userland tab on computer {@code host}, typing {@code command} at its prompt. */
        void boardOpenTab(String host, String command);
        /** The composer's +: pick by camera, photos or files, then hand back the prepared items. */
        void boardAttach(String how, java.util.function.Consumer<List<Attachments.Item>> done);
        /** A share sent to terminal tab {@code index}: upload, then paste the paths there. */
        void boardShareToTab(int index, List<Attachments.Item> items);
        void boardScanQr();
        void boardPastePairing();
        UserlandOptions boardUserlandOptions();
        /**
         * The page's place for the app's nav bar: its root ("board" or
         * "activity"), agents waiting on you, unseen "needs you" events, and
         * whether back has somewhere to go inside the board.
         */
        void boardNav(String tab, int needs, int unseen, boolean canBack);
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
    /** Link state per computer (connecting | online | offline | error), for uploads. */
    private final java.util.Map<String, String> linkStates = new java.util.concurrent.ConcurrentHashMap<>();
    /** Upload chunks awaiting their bridge reply, by request id ("u…"). */
    private final java.util.Map<String, java.util.concurrent.CompletableFuture<String[]>> uploadReplies =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService uploads =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private int uploadSeq;
    /** Base64 characters per put request: a few PTY buffers, well under any line limit. */
    private static final int CHUNK_CHARS = 48 * 1024;

    /** The app theme as board.css variables, served to the page by {@code AshNative.theme()}. */
    private volatile String themeJson;

    BoardView(Activity activity, ChromePalette palette, TerminalTheme theme, Host host) {
        this.activity = activity;
        this.host = host;
        themeJson = themeJson(activity, palette, theme);
        settings = new AppSettings(activity);
        AppUpdater.addListener(updates);
        web = new WebView(activity);
        web.setBackgroundColor(palette.surfaceBase);
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
                linkStates.put(h, state);
                js("ash.onLink(" + q(h) + "," + q(state) + "," + q(detail) + ")");
            }

            @Override
            public void onReply(String h, String id, int status, String b64) {
                if (id.startsWith("u")) {
                    java.util.concurrent.CompletableFuture<String[]> f = uploadReplies.remove(id);
                    if (f != null) f.complete(new String[]{String.valueOf(status), b64});
                    return;
                }
                js("ash.onReply(" + q(h) + "," + q(id) + "," + status + "," + q(b64) + ")");
            }
        });
        web.addJavascriptInterface(new Native(), "AshNative");
        web.loadUrl(URL);
    }

    View view() {
        return web;
    }

    /** Recolors the page (a terminal-color edit; a new app theme recreates the Activity). */
    void applyTheme(ChromePalette palette, TerminalTheme theme) {
        String json = themeJson(activity, palette, theme);
        if (json.equals(themeJson)) return;
        themeJson = json;
        js("ash.setTheme(" + json + ")");
    }

    /**
     * Every board.css token, derived from the app palette: surfaces and inks
     * map straight across, and the board's status colors are the palette's
     * success (mint: working), warning (amber: waiting) and danger (coral:
     * needs you), each with tints mixed toward the ground for fills and lines.
     */
    private static String themeJson(Context c, ChromePalette p, TerminalTheme t) {
        int ground = p.surfaceBase;
        int text = p.textPrimary;
        int toward = p.isLight ? Color.BLACK : text;
        int mint = Chrome.color(c, R.color.success);
        int amber = Chrome.color(c, R.color.warning);
        int coral = Chrome.color(c, R.color.danger);
        int blue = t.ansi[4];
        JSONObject v = new JSONObject();
        try {
            v.put("ground", css(ground));
            v.put("surface", css(p.surface1));
            v.put("raised", css(p.surface2));
            v.put("raised2", css(p.surface3));
            v.put("sheet", css(p.surface1));
            v.put("well", css(ChromePalette.mix(ground, Color.BLACK, p.isLight ? 0.03f : 0.3f)));
            v.put("line", css(ChromePalette.mix(ground, toward, 0.1f)));
            v.put("edge", css(ChromePalette.mix(ground, toward, 0.14f)));
            v.put("edge-strong", css(ChromePalette.mix(ground, toward, 0.2f)));
            v.put("edge-focus", css(ChromePalette.mix(ground, toward, 0.3f)));
            v.put("well-line", css(ChromePalette.mix(ground, toward, 0.08f)));
            v.put("text", css(text));
            v.put("muted", css(p.textSecondary));
            v.put("faint", css(p.textTertiary));
            v.put("tail", css(p.textSecondary));
            v.put("pill", css(p.textSecondary));
            v.put("code", css(ChromePalette.mix(text, ground, 0.12f)));
            v.put("sheen", p.isLight ? "rgba(0,0,0,.04)" : "rgba(255,255,255,.035)");
            putStatus(v, "mint", mint, ground, text);
            putStatus(v, "amber", amber, ground, text);
            putStatus(v, "coral", coral, ground, text);
            v.put("mint-ink", css(ink(mint, ground, text)));
            v.put("coral-ink", css(ink(coral, ground, text)));
            v.put("me", css(ChromePalette.mix(ground, mint, 0.2f)));
            v.put("blue", css(blue));
            v.put("blue-bg", css(ChromePalette.mix(ground, blue, 0.16f)));
            v.put("violet", css(t.ansi[5]));
            JSONObject o = new JSONObject();
            o.put("vars", v);
            o.put("light", p.isLight);
            org.json.JSONArray ansi = new org.json.JSONArray();
            for (int a : t.ansi) ansi.put(css(a));
            o.put("ansi", ansi);
            o.put("fg", css(t.foreground));
            o.put("bg", css(t.background));
            return o.toString();
        } catch (org.json.JSONException e) {
            return "{}";
        }
    }

    private static void putStatus(JSONObject v, String name, int c, int ground, int text)
            throws org.json.JSONException {
        v.put(name, css(c));
        v.put(name + "-bg", css(ChromePalette.mix(ground, c, 0.14f)));
        v.put(name + "-fg", css(ChromePalette.mix(c, text, 0.2f)));
        v.put(name + "-line", css(ChromePalette.mix(ground, c, 0.32f)));
    }

    /** Ground or text, whichever reads better on {@code c} (fab and record-button ink). */
    private static int ink(int c, int ground, int text) {
        float l = Color.luminance(c);
        float lg = Color.luminance(ground);
        float lt = Color.luminance(text);
        return contrast(l, lg) >= contrast(l, lt) ? ground : text;
    }

    private static float contrast(float a, float b) {
        return (Math.max(a, b) + 0.05f) / (Math.min(a, b) + 0.05f);
    }

    private static String css(int argb) {
        return String.format(java.util.Locale.US, "#%06X", argb & 0xFFFFFF);
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

    /** Back while the board is up: the page closes a sheet or pops a sub-screen, else {@code otherwise} runs. */
    void back(Runnable otherwise) {
        web.evaluateJavascript("ash.back()", r -> {
            if (!"true".equals(r)) otherwise.run();
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

    /** Things shared into the app: the page asks where they go (an agent or a terminal tab). */
    void share(List<Attachments.Item> items, JSONArray tabs, String note) {
        js("ash.share(" + Attachments.json(items) + "," + tabs + "," + q(note) + ")");
    }

    interface UploadDone {
        /** On the main thread: the file's path on the computer, or null and why not. */
        void done(String path, String error);
    }

    /**
     * Puts one attachment in {@code ~/.cache/a-sh/inbox} on computer {@code h},
     * over the board's link to it (bridge verbs put / put-end), waiting a
     * little for the link if it is still coming up.
     */
    void upload(String h, Attachments.Item it, UploadDone cb) {
        uploads.execute(() -> {
            String path = null;
            String error = null;
            try {
                for (int i = 0; i < 100 && !"online".equals(linkStates.get(h)); i++) Thread.sleep(200);
                if (!"online".equals(linkStates.get(h))) throw new IOException(h + " is offline");
                String all = android.util.Base64.encodeToString(it.data, android.util.Base64.NO_WRAP);
                int n = Math.max(1, (all.length() + CHUNK_CHARS - 1) / CHUNK_CHARS);
                for (int i = 0; i < n; i++) {
                    String part = all.substring(i * CHUNK_CHARS, Math.min(all.length(), (i + 1) * CHUNK_CHARS));
                    request(h, "x" + part, "put", it.name, String.valueOf(i));
                }
                path = request(h, null, "put-end", it.name, String.valueOf(it.data.length)).trim();
            } catch (IOException e) {
                error = e.getMessage();
            } catch (InterruptedException e) {
                error = "interrupted";
                Thread.currentThread().interrupt();
            }
            final String p = path;
            final String err = error;
            main.post(() -> cb.done(p, err));
        });
    }

    /** One bridge request from Java; {@code raw} is a pre-encoded last field (put's payload). */
    private String request(String h, String raw, String... args) throws IOException, InterruptedException {
        String id = "u" + (++uploadSeq);
        StringBuilder line = new StringBuilder(id);
        for (String a : args) {
            line.append("\tx").append(android.util.Base64.encodeToString(
                    a.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP));
        }
        if (raw != null) line.append('\t').append(raw);
        java.util.concurrent.CompletableFuture<String[]> f = new java.util.concurrent.CompletableFuture<>();
        uploadReplies.put(id, f);
        links.send(h, line.toString());
        String[] r;
        try {
            r = f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            uploadReplies.remove(id);
            throw new IOException("upload to " + h + " timed out");
        }
        String out = new String(android.util.Base64.decode(r[1], android.util.Base64.DEFAULT),
                java.nio.charset.StandardCharsets.UTF_8);
        if (!"0".equals(r[0])) throw new IOException("upload failed: " + out.trim());
        return out;
    }

    void destroy() {
        uploads.shutdownNow();
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
        public String theme() {
            return themeJson;
        }

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
            main.post(() -> host.boardOpenTab(h, cmd));
        }

        @JavascriptInterface
        public void openShell(String h) {
            if (validHost(h)) main.post(() -> host.boardOpenTab(h, "exec ssh " + h + "-sh"));
        }

        /** The composer's + menu picked {@code how}; items come back via ash.onAttached(target, …). */
        @JavascriptInterface
        public void attach(String target, String how) {
            main.post(() -> host.boardAttach(how, items ->
                    js("ash.onAttached(" + q(target) + "," + Attachments.json(items) + ")")));
        }

        /** Uploads a held attachment to computer {@code h}; answers via ash.onUploaded(reqId, path, error). */
        @JavascriptInterface
        public void upload(String h, String token, String reqId) {
            Attachments.Item it = Attachments.get(token);
            main.post(() -> {
                if (it == null || !validHost(h)) {
                    js("ash.onUploaded(" + q(reqId) + ",''," + q("attachment is gone") + ")");
                    return;
                }
                BoardView.this.upload(h, it, (path, err) ->
                        js("ash.onUploaded(" + q(reqId) + "," + q(path) + "," + q(err) + ")"));
            });
        }

        @JavascriptInterface
        public void dropAttachment(String token) {
            Attachments.drop(token);
        }

        /** A share's destination was terminal tab {@code index}; tokens is a JSON array. */
        @JavascriptInterface
        public void shareToTab(int index, String tokens) {
            List<Attachments.Item> items = new ArrayList<>();
            try {
                JSONArray a = new JSONArray(tokens);
                for (int i = 0; i < a.length(); i++) {
                    Attachments.Item it = Attachments.get(a.getString(i));
                    if (it != null) items.add(it);
                }
            } catch (org.json.JSONException ignored) {
            }
            main.post(() -> host.boardShareToTab(index, items));
        }

        @JavascriptInterface
        public void nav(String tab, int needs, int unseen, boolean canBack) {
            main.post(() -> host.boardNav(tab, needs, unseen, canBack));
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
