/* SPDX-License-Identifier: Apache-2.0 */

package io.github.sylirre.terminal.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import io.github.sylirre.terminal.R;

/**
 * The one place an update is visible: what's installed, what's out, where the
 * download is, and a single button for the next step (Check / Download /
 * Install / Allow installs / Retry). It follows {@link AppUpdater}'s state
 * live, so opening it while a background download runs just shows the
 * progress, and coming back from Android's "install unknown apps" page
 * carries on with the install.
 */
final class UpdateDialog implements AppUpdater.Listener {

    private static UpdateDialog showing;

    private final Activity activity;
    private final AlertDialog dialog;
    private final TextView status;
    private final TextView detail;
    private final ProgressBar bar;
    private final TextView notes;
    private AppUpdater.State last;

    static void show(Activity activity) {
        if (showing != null && showing.activity == activity && showing.dialog.isShowing()) return;
        if (activity.isFinishing()) return;
        new UpdateDialog(activity);
    }

    private UpdateDialog(Activity activity) {
        this.activity = activity;
        int pad = dp(22);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(8), pad, 0);
        status = text(17, true);
        detail = text(14, false);
        detail.setAlpha(0.75f);
        bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        notes = text(13.5f, false);
        notes.setAlpha(0.8f);
        ScrollView sv = new ScrollView(activity);
        sv.addView(notes);
        box.addView(status);
        box.addView(detail, lp(dp(4)));
        box.addView(bar, lp(dp(12)));
        box.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(160)));

        dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.update_dialog_title)
                .setView(box)
                .setPositiveButton(" ", null)
                .setNeutralButton(R.string.update_release_page, null)
                .setNegativeButton(R.string.update_close, null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> primary());
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse(AppUpdater.releasesPage(activity))));
                } catch (android.content.ActivityNotFoundException ignored) {
                }
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                if (last != null && last.phase == AppUpdater.Phase.DOWNLOADING) AppUpdater.cancelDownload();
                else dialog.dismiss();
            });
            onUpdateState(AppUpdater.state(activity));
        });
        dialog.setOnDismissListener(d -> {
            AppUpdater.removeListener(this);
            if (showing == this) showing = null;
        });
        // Back from Settings (install permission) or the system confirm screen.
        dialog.getWindow().getDecorView().getViewTreeObserver().addOnWindowFocusChangeListener(
                new ViewTreeObserver.OnWindowFocusChangeListener() {
                    @Override
                    public void onWindowFocusChanged(boolean hasFocus) {
                        if (!hasFocus) return;
                        AppUpdater.resumeAfterPermission(activity);
                        onUpdateState(AppUpdater.state(activity));
                    }
                });
        AppUpdater.addListener(this);
        showing = this;
        dialog.show();
    }

    private void primary() {
        AppUpdater.State s = last;
        if (s == null) return;
        switch (s.phase) {
            case AVAILABLE:
                AppUpdater.download(activity.getApplicationContext());
                break;
            case READY:
                AppUpdater.install(activity);
                break;
            case PERMISSION:
                AppUpdater.openInstallPermission(activity);
                break;
            case INSTALLING:
                AppUpdater.showPendingConfirm(activity);
                break;
            default:
                AppUpdater.check(activity.getApplicationContext(), true);
        }
    }

    @Override
    public void onUpdateState(AppUpdater.State s) {
        last = s;
        if (!dialog.isShowing()) return;
        Button go = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button close = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        String v = s.version == null ? "" : s.version;
        String size = s.size > 0 ? Formatter.formatShortFileSize(activity, s.size) : "";
        String installed = activity.getString(R.string.update_installed, s.installed);
        bar.setVisibility(View.GONE);
        bar.setIndeterminate(false);
        go.setEnabled(true);
        go.setVisibility(View.VISIBLE);
        close.setText(R.string.update_close);
        notes.setText(s.notes == null || s.notes.isEmpty() || s.version == null ? ""
                : activity.getString(R.string.update_notes, s.notes));
        switch (s.phase) {
            case CHECKING:
                status.setText(R.string.update_checking);
                detail.setText(installed);
                bar.setVisibility(View.VISIBLE);
                bar.setIndeterminate(true);
                go.setVisibility(View.GONE);
                break;
            case UP_TO_DATE:
            case IDLE:
                status.setText(s.phase == AppUpdater.Phase.UP_TO_DATE
                        ? activity.getString(R.string.update_up_to_date_title)
                        : activity.getString(R.string.update_dialog_title));
                detail.setText(installed + (s.checkedAt > 0 ? " · " + activity.getString(R.string.update_checked,
                        DateUtils.getRelativeTimeSpanString(s.checkedAt)) : ""));
                go.setText(R.string.update_check_again);
                notes.setText("");
                break;
            case AVAILABLE:
                status.setText(activity.getString(R.string.update_available_title, v));
                detail.setText(installed + " · " + size);
                go.setText(activity.getString(R.string.update_download, size));
                break;
            case DOWNLOADING:
                status.setText(activity.getString(R.string.update_downloading_title, v));
                detail.setText(Formatter.formatShortFileSize(activity, s.done) + " / "
                        + Formatter.formatShortFileSize(activity, s.total) + " · " + s.percent() + "%");
                bar.setVisibility(View.VISIBLE);
                bar.setProgress(s.percent());
                go.setVisibility(View.GONE);
                close.setText(R.string.update_cancel);
                break;
            case READY:
                status.setText(activity.getString(R.string.update_ready_title_v, v));
                detail.setText(s.error != null ? s.error : activity.getString(R.string.update_ready_detail));
                go.setText(R.string.update_install_now);
                break;
            case PERMISSION:
                status.setText(R.string.update_permission_title);
                detail.setText(R.string.update_permission_detail);
                go.setText(R.string.update_permission_button);
                break;
            case INSTALLING:
                status.setText(activity.getString(R.string.update_installing_title, v));
                detail.setText(R.string.update_installing_detail);
                bar.setVisibility(View.VISIBLE);
                bar.setIndeterminate(true);
                go.setText(R.string.update_continue);
                go.setVisibility(AppUpdater.pendingConfirm != null ? View.VISIBLE : View.GONE);
                break;
            case FAILED:
                status.setText(R.string.update_failed_title);
                detail.setText(s.error == null ? "" : s.error);
                go.setText(R.string.update_retry);
                go.setVisibility(AppUpdater.enabled(activity) ? View.VISIBLE : View.GONE);
                break;
        }
    }

    private TextView text(float sp, boolean bold) {
        TextView t = new TextView(activity);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
