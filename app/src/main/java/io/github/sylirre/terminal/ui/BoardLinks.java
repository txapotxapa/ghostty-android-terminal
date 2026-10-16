/* SPDX-License-Identifier: Apache-2.0 */

package io.github.sylirre.terminal.ui;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import io.github.sylirre.terminal.term.SessionCommand;
import io.github.sylirre.terminal.term.TerminalNative;
import io.github.sylirre.terminal.term.UserlandOptions;
import io.github.sylirre.terminal.term.UserlandRootfs;

/**
 * The agent board's connections: one per paired computer, each a hidden
 * userland process running {@code a-sh-board-link NAME}, which keeps an SSH
 * session open to the board bridge on that computer (assets/board).
 *
 * The userland only runs behind a PTY, so a link is a PTY nobody sees: the
 * link script switches it to raw mode first, after which request and reply
 * lines pass through byte for byte. Requests are written only after the
 * bridge's {@code @@READY} line, so they never meet the tty's echo.
 */
final class BoardLinks {

    interface Listener {
        /** state: connecting | online | offline | error. detail: READY info or error text. */
        void onLink(String host, String state, String detail);
        void onReply(String host, String id, int status, String base64);
    }

    private static final String TAG = "BoardLinks";
    private static final String LINK_GUEST = "usr/local/bin/a-sh-board-link";
    private static final String BRIDGE_GUEST = "usr/local/share/a-sh/board-bridge.sh";
    private static final int SIGTERM = 15;

    private final Context app;
    private final Listener listener;
    private final Map<String, Link> links = new HashMap<>();

    BoardLinks(Context ctx, Listener listener) {
        this.app = ctx.getApplicationContext();
        this.listener = listener;
    }

    /** Starts the link unless one is already up or coming up. */
    synchronized void connect(String host, UserlandOptions base) {
        Link l = links.get(host);
        if (l != null && l.alive) return;
        l = new Link(host);
        links.put(host, l);
        l.start(base);
    }

    synchronized void reconnect(String host, UserlandOptions base) {
        Link l = links.remove(host);
        if (l != null) l.stop();
        connect(host, base);
    }

    synchronized void send(String host, String line) {
        Link l = links.get(host);
        if (l != null) l.write(line);
    }

    synchronized void stopAll() {
        for (Link l : links.values()) l.stop();
        links.clear();
    }

    /** Puts the link script and the bridge into the rootfs (every start: keeps them in step with the app). */
    private void installAssets() throws IOException {
        File root = UserlandRootfs.dir(app);
        copyAsset("board/link", new File(root, LINK_GUEST), 0755);
        copyAsset("board/bridge.sh", new File(root, BRIDGE_GUEST), 0644);
    }

    private void copyAsset(String asset, File dest, int mode) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        File tmp = new File(dest.getPath() + ".tmp");
        try (InputStream in = app.getAssets().open(asset);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        try {
            Os.chmod(tmp.getAbsolutePath(), mode);
        } catch (ErrnoException e) {
            throw new IOException("chmod " + tmp + ": " + e.getMessage());
        }
        if (!tmp.renameTo(dest)) throw new IOException("cannot write " + dest);
    }

    private final class Link {
        final String host;
        volatile boolean alive = true;
        volatile boolean ready;
        int pid = -1;
        ParcelFileDescriptor fd;
        FileOutputStream out;
        /** Non-protocol output (ssh's own messages), for the error shown when it dies. */
        final StringBuilder noise = new StringBuilder();

        Link(String host) {
            this.host = host;
        }

        void start(UserlandOptions base) {
            listener.onLink(host, "connecting", "");
            new Thread(() -> {
                try {
                    if (!UserlandRootfs.isInstalled(app)) throw new IOException("nolinux");
                    installAssets();
                    UserlandOptions opts = new UserlandOptions(
                            "/bin/sh /" + LINK_GUEST + " " + host, false,
                            base.identity, base.home, "", base.locale, base.path,
                            base.jit, base.jitBufferMb, base.chrootNg);
                    SessionCommand cmd = UserlandRootfs.command(app, opts);
                    int[] pidOut = new int[1];
                    int raw = TerminalNative.ptyCreateEmulator(cmd.argv, cmd.env, cmd.cwd,
                            200, 50, 8, 16, pidOut);
                    synchronized (this) {
                        pid = pidOut[0];
                        fd = ParcelFileDescriptor.adoptFd(raw);
                        out = new FileOutputStream(fd.getFileDescriptor());
                    }
                    if (!alive) { kill(); return; }
                    Thread waiter = new Thread(this::waitLoop, "board-wait-" + host);
                    waiter.setDaemon(true);
                    waiter.start();
                    readLoop();
                } catch (IOException | RuntimeException e) {
                    Log.w(TAG, host + ": " + e);
                    finish("error", e.getMessage());
                }
            }, "board-link-" + host).start();
        }

        private void readLoop() {
            byte[] buf = new byte[65536];
            ByteBuf line = new ByteBuf();
            try (InputStream in = new FileInputStream(fd.getFileDescriptor())) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    for (int i = 0; i < n; i++) {
                        byte b = buf[i];
                        if (b == '\n') {
                            handle(line.toString());
                            line.reset();
                        } else if (b != '\r') {
                            line.add(b);
                        }
                    }
                }
            } catch (IOException ignored) {
                // EIO when the child exits: the waiter reports it.
            }
        }

        private void handle(String l) {
            if (l.startsWith("@@R ")) {
                String[] p = l.split(" ", 4);
                if (p.length < 3) return;
                int status;
                try {
                    status = Integer.parseInt(p[2]);
                } catch (NumberFormatException e) {
                    return;
                }
                listener.onReply(host, p[1], status, p.length > 3 ? p[3] : "");
            } else if (l.startsWith("@@READY")) {
                ready = true;
                listener.onLink(host, "online", l.length() > 8 ? l.substring(8) : "");
            } else if (l.startsWith("@@E ")) {
                synchronized (noise) {
                    noise.setLength(0);
                    noise.append(l.substring(4).trim());
                }
            } else if (!l.trim().isEmpty()) {
                synchronized (noise) {
                    if (noise.length() > 2000) noise.delete(0, noise.length() - 1000);
                    noise.append(l.trim()).append('\n');
                }
            }
        }

        private void waitLoop() {
            int code = TerminalNative.processWaitFor(pid);
            String why;
            synchronized (noise) {
                why = noise.toString().trim();
            }
            if (why.contains("\n")) why = why.substring(why.lastIndexOf('\n') + 1);
            Log.i(TAG, host + " link exited " + code + (why.isEmpty() ? "" : ": " + why));
            finish(code == 2 || code == 3 ? "error" : "offline", why);
        }

        private void finish(String state, String detail) {
            boolean wasAlive = alive;
            alive = false;
            closeFd();
            if (wasAlive) listener.onLink(host, state, detail == null ? "" : detail);
        }

        synchronized void write(String line) {
            if (!ready || out == null) return;
            try {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.w(TAG, host + " write: " + e);
            }
        }

        void stop() {
            alive = false;
            kill();
        }

        private synchronized void kill() {
            if (pid > 0) TerminalNative.processKill(pid, SIGTERM);
            closeFd();
        }

        private synchronized void closeFd() {
            try {
                if (fd != null) fd.close();
            } catch (IOException ignored) {
            }
            fd = null;
            out = null;
        }
    }

    /** Growable byte buffer: lines are decoded as UTF-8 only once complete. */
    private static final class ByteBuf {
        byte[] a = new byte[1024];
        int n;

        void add(byte b) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2);
            a[n++] = b;
        }

        void reset() {
            n = 0;
            if (a.length > 1 << 20) a = new byte[1024];
        }

        @Override
        public String toString() {
            return new String(a, 0, n, StandardCharsets.UTF_8);
        }
    }
}
