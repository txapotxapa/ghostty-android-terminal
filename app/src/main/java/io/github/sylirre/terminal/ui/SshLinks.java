/* SPDX-License-Identifier: Apache-2.0 */

package io.github.sylirre.terminal.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.sylirre.terminal.term.UserlandRootfs;

/**
 * QR SSH pairing links: {@code ash://ssh?h=…&p=…&u=…&n=…[&hk=…&k=…&t=…]},
 * printed as a QR by {@code tools/a-sh-pair} on the home computer and opened
 * here from the phone's camera (or pasted). The app side stays thin: it
 * validates the link, drops the {@code a-sh-ssh} helper asset into the
 * userland and types {@code a-sh-ssh '<link>'} into a new tab — the helper
 * does the key exchange, ssh_config entry and connect inside the guest. The
 * host names paired that way are remembered for one-tap "SSH → name" tabs.
 */
final class SshLinks {

    private static final String HELPER_ASSET = "a-sh-ssh";
    private static final String HELPER_GUEST_PATH = "usr/local/bin/a-sh-ssh";
    private static final String PREFS = "ssh_links";
    private static final String KEY_HOSTS = "hosts";

    /**
     * The link is pasted into a shell command inside single quotes, so only
     * URL-safe characters (no quote, space or shell metacharacter that
     * survives quoting) are accepted; a-sh-ssh re-validates every field.
     */
    private static final Pattern SAFE_LINK =
            Pattern.compile("ash://ssh\\?[A-Za-z0-9%._~:/=&+-]{1,2000}");
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private SshLinks() {}

    /** The pairing link an intent carries (camera scan, browser), or null. */
    static String fromIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return null;
        Uri data = intent.getData();
        return data == null ? null : validate(data.toString());
    }

    /** {@code text} trimmed when it is a well-formed pairing link, else null. */
    static String validate(String text) {
        if (text == null) return null;
        String link = text.trim();
        if (!SAFE_LINK.matcher(link).matches()) return null;
        String name = Uri.parse(link).getQueryParameter("n");
        String host = Uri.parse(link).getQueryParameter("h");
        if (name == null || !SAFE_NAME.matcher(name).matches()) return null;
        if (host == null || host.isEmpty()) return null;
        return link;
    }

    /** The {@code n} (ssh alias) of a validated link. */
    static String hostName(String link) {
        return Uri.parse(link).getQueryParameter("n");
    }

    /** Shell line that pairs and connects; {@code link} must come from {@link #validate}. */
    static String pairCommand(String link) {
        return "a-sh-ssh '" + link + "'\r";
    }

    /** Shell line for a saved host; {@code name} must come from {@link #savedHosts}. */
    static String connectCommand(String name) {
        return "ssh " + name + "\r";
    }

    /**
     * Copies the helper into the installed rootfs (every time: cheap, and it
     * keeps the guest copy in step with app updates). Works for any distro —
     * the helper is POSIX sh.
     */
    static void installHelper(Context ctx) throws IOException {
        File dest = new File(UserlandRootfs.dir(ctx), HELPER_GUEST_PATH);
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        File tmp = new File(dest.getPath() + ".tmp");
        try (InputStream in = ctx.getAssets().open(HELPER_ASSET);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        try {
            Os.chmod(tmp.getAbsolutePath(), 0755);
        } catch (ErrnoException e) {
            throw new IOException("chmod " + tmp + ": " + e.getMessage());
        }
        if (!tmp.renameTo(dest)) throw new IOException("cannot write " + dest);
    }

    static List<String> savedHosts(Context ctx) {
        List<String> hosts = new ArrayList<>();
        for (String h : prefs(ctx).getStringSet(KEY_HOSTS, Collections.emptySet())) {
            if (SAFE_NAME.matcher(h).matches()) hosts.add(h);
        }
        Collections.sort(hosts);
        return hosts;
    }

    static void saveHost(Context ctx, String name) {
        Set<String> hosts = new HashSet<>(
                prefs(ctx).getStringSet(KEY_HOSTS, Collections.emptySet()));
        if (hosts.add(name)) prefs(ctx).edit().putStringSet(KEY_HOSTS, hosts).apply();
    }

    static void forgetHost(Context ctx, String name) {
        Set<String> hosts = new HashSet<>(
                prefs(ctx).getStringSet(KEY_HOSTS, Collections.emptySet()));
        if (hosts.remove(name)) prefs(ctx).edit().putStringSet(KEY_HOSTS, hosts).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
