package com.bigrice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The launch screen: the mascot, one progress bar, one line of text.
 *
 * <p>The pipeline behind it is four stages long and used to be narrated by a
 * scrolling console, which made the first thing a new install shows look like
 * a crash. The console is still there - an install prints thousands of npm
 * lines and they are the only way to diagnose a failure - but it lives behind a
 * tap instead of being the screen.
 *
 * <p>Progress is a single 0..1 value assembled from bands: each stage owns a
 * slice of the bar and reports its own fraction inside it, so the bar advances
 * monotonically across a download, an unpack and an external installer that
 * knows nothing about it. A stage that cannot report a fraction (probing
 * mirrors, npm resolving) shows the indeterminate sweep instead of a bar frozen
 * at an arbitrary number.
 *
 * <p>The same number drives a progress notification, because the download alone
 * can run for minutes: a user who swipes away from the app should still see
 * whether it is moving. The notification is created on the first update and
 * cancelled the moment the interface takes over.
 *
 * <p>After a successful start the screen is not discarded. It is hidden behind
 * the WebView and comes back on Back, so the log of the run that just happened
 * stays reachable without a second launch - and pressing Back there is what
 * leaves the app, instead of a Back in the WebView closing it over the page the
 * user was reading.
 */
final class BootScreen {

    /** Resumes the pipeline after a failure; wired to the retry button. */
    interface Retry {
        void run();
    }

    private static final int BG = 0xFF0E1013;
    private static final int STAGE_COLOR = 0xFF9AA6B8;
    private static final int FAIL_COLOR = 0xFFFF8A8A;
    private static final int HINT_COLOR = 0xFF5C6472;
    private static final int READY_COLOR = 0xFF7FB2FF;

    /** Art side and bar width, in dp. The mascot is a square tile. */
    private static final int ART_DP = 196;
    private static final int BAR_DP = 224;
    private static final int BAR_H_DP = 6;

    private static final String CHANNEL = "bigrice-boot-v2";
    /**
     * One progress card, shared with the keep-alive service: when the app goes to
     * the background mid-boot the service adopts this very notification instead
     * of stacking a second one next to it.
     */
    static final int NOTIFICATION_ID = 0x6272;

    private final Context context;
    private final FrameLayout layer;
    private final ArtView art;
    private final BootBar bar;
    private final TextView stageView;
    private final TextView retryButton;
    private final TextView logButton;
    private final FrameLayout logPanel;
    private final TextView logView;
    private final ScrollView logScroll;
    private TextView notifHint;
    private boolean night;
    private boolean failedNow;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Notifier notifier;

    /** Current band: the slice of the bar the running stage is allowed to fill. */
    private float from = 0f;
    private float to;
    private float value;

    /** True once the WebView is up; the splash then behaves as a log viewer. */
    private boolean done;
    /** Set once a caller reported a real 0..1 percentage; see percent(). */
    private boolean precise;
    private Runnable onReturn;

    BootScreen(Context context, TextView logView, ScrollView logScroll) {
        this.context = context;
        this.logView = logView;
        this.logScroll = logScroll;
        notifier = new Notifier(context);

        layer = new FrameLayout(context);
        layer.setBackgroundColor(BG);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);

        art = new ArtView(context, artwork(context));
        column.addView(art, new LinearLayout.LayoutParams(dp(context, ART_DP), dp(context, ART_DP)));

        column.addView(space(context, 30));

        bar = new BootBar(context);
        LinearLayout.LayoutParams barLp =
                new LinearLayout.LayoutParams(dp(context, BAR_DP), dp(context, BAR_H_DP));
        column.addView(bar, barLp);

        stageView = new TextView(context);
        stageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        stageView.setTextColor(STAGE_COLOR);
        stageView.setGravity(Gravity.CENTER);
        stageView.setSingleLine(true);
        stageView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        stageView.setPadding(dp(context, 32), dp(context, 16), dp(context, 32), 0);
        stageView.setText("正在准备…");
        column.addView(stageView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // A failure needs an obvious door out: the reason is text, this is the
        // control. Two lines both saying "点此重试" was the version that shipped
        // in the mock-up and read as part of the error message.
        retryButton = new TextView(context);
        retryButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        retryButton.setTextColor(0xFFD6DEEB);
        retryButton.setText("重试");
        retryButton.setGravity(Gravity.CENTER);
        retryButton.setPadding(dp(context, 30), dp(context, 11), dp(context, 30), dp(context, 11));
        android.graphics.drawable.GradientDrawable outline =
                new android.graphics.drawable.GradientDrawable();
        outline.setCornerRadius(dp(context, 20));
        outline.setStroke(dp(context, 1), 0xFF3B424E);
        outline.setColor(0x1AFFFFFF);
        retryButton.setBackground(outline);
        retryButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        retryLp.topMargin = dp(context, 18);
        column.addView(retryButton, retryLp);

        FrameLayout.LayoutParams columnLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        columnLp.gravity = Gravity.CENTER;
        layer.addView(column, columnLp);

        logButton = new TextView(context);
        logButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logButton.setTextColor(HINT_COLOR);
        logButton.setText("查看日志");
        logButton.setPadding(dp(context, 20), dp(context, 14), dp(context, 20), dp(context, 14));
        // 日志入口不放在开屏页上：启动界面只留吉祥物、进度条和阶段文字。
        logButton.setVisibility(View.GONE);

        logPanel = new FrameLayout(context);
        logPanel.setBackgroundColor(BG);
        logPanel.setVisibility(View.GONE);
        logPanel.addView(logScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView close = new TextView(context);
        close.setText("收起日志  ✕");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        close.setTextColor(0xFFB6C0CF);
        close.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.gravity = Gravity.TOP | Gravity.END;
        closeLp.topMargin = dp(context, 8);
        logPanel.addView(close, closeLp);
        layer.addView(logPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Some ROMs install apps with notifications off. The progress report is a
        // convenience, not a requirement, so instead of failing silently the splash
        // says so once and offers the settings screen.
        notifHint = new TextView(context);
        notifHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        notifHint.setTextColor(HINT_COLOR);
        notifHint.setText("通知已关闭 · 点此开启");
        notifHint.setPadding(dp(context, 20), dp(context, 10), dp(context, 20), dp(context, 10));
        notifHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        hintLp.bottomMargin = dp(context, 60);
        layer.addView(notifHint, hintLp);
        notifHint.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,
                            BootScreen.this.context.getPackageName());
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    BootScreen.this.context.startActivity(i);
                } catch (Throwable e) {
                    android.util.Log.w("bigrice", "notification settings: " + e);
                }
            }
        });
        refreshNotifHint();

        logButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logPanel.setVisibility(View.VISIBLE);
            }
        });
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logPanel.setVisibility(View.GONE);
            }
        });
        // Once the run is over the splash is only reachable on purpose, so a tap
        // anywhere on the artwork is the way back to the interface.
        art.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (done) resume();
            }
        });
        stageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (done) resume();
            }
        });

        to = 1f;
        applyTheme();
    }

    /**
     * Paints the splash for the current day/night setting. The artwork is cut
     * out of its white backdrop, so the page colour is what the character sits
     * on: near-black in dark mode, near-white in light mode. Without this the
     * light theme showed a photo on a black page, which reads as a broken layout
     * rather than a choice.
     */
    void applyTheme() {
        android.content.res.Configuration c = context.getResources().getConfiguration();
        night = (c.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;

        int bg = night ? BG : 0xFFF7F8FA;
        int stageColor = night ? STAGE_COLOR : 0xFF5A6472;
        int hint = night ? HINT_COLOR : 0xFF98A2B3;
        int ready = night ? READY_COLOR : 0xFF2563EB;

        layer.setBackgroundColor(bg);
        logPanel.setBackgroundColor(bg);
        if (!failedNow) stageView.setTextColor(done ? ready : stageColor);
        logButton.setTextColor(hint);
        notifHint.setTextColor(hint);
        retryButton.setTextColor(night ? 0xFFD6DEEB : 0xFF1F2430);
        bar.setNight(night);

        // The console is part of the same page: a black terminal under a white
        // splash looked like a different application.
        logView.setTextColor(night ? 0xFFD8FFD8 : 0xFF1E293B);
        logView.setBackgroundColor(bg);
    }

    View view() {
        return layer;
    }

    /** The callback a tap on the finished splash uses to go back to the UI. */
    void setOnReturn(Runnable r) {
        onReturn = r;
    }

    boolean isVisible() {
        return layer.getVisibility() == View.VISIBLE;
    }

    boolean isDone() {
        return done;
    }

    boolean logVisible() {
        return logPanel.getVisibility() == View.VISIBLE;
    }

    void closeLog() {
        logPanel.setVisibility(View.GONE);
    }

    /** Brings the splash back over the WebView, log panel closed. */
    void showAgain() {
        precise = false;
        logPanel.setVisibility(View.GONE);
        layer.setVisibility(View.VISIBLE);
        layer.bringToFront();
        if (done) {
            stageView.setTextColor(night ? READY_COLOR : 0xFF2563EB);
            stageView.setText("启动完成 · 点此回到界面");
            logButton.setText("查看日志");
        }
    }

    void hide() {
        layer.setVisibility(View.GONE);
    }

    /** Opens a band: the running stage fills {@code [from, to]} of the bar. */
    void band(float from, float to) {
        if (precise && !done) return;
        this.from = from;
        this.to = to;
        this.value = from;
        ui.post(new Runnable() {
            @Override
            public void run() {
                bar.setIndeterminate(false);
                bar.setValue(BootScreen.this.value);
            }
        });
    }

    /** Progress inside the open band, 0..1. */
    void progress(final float fraction) {
        final float v = from + (to - from) * Math.max(0f, Math.min(1f, fraction));
        // Never let the bar walk backwards: a resumed stage can report less than
        // the previous attempt did, and a shrinking bar reads as a bug.
        if (v <= value) return;
        value = v;
        ui.post(new Runnable() {
            @Override
            public void run() {
                bar.setValue(value);
                notifier.progress(currentStage(), false, value);
            }
        });
    }

    /**
     * Absolute 0..1 progress for a pipeline that reports its own percentage
     * (the dsh updater does: mirror speed test, package download, install).
     *
     * <p>This is the only entry point that ignores bands, because the caller's
     * number already describes the whole pipeline. The bar still never walks
     * backwards, and the text is replaced in the same post so the label and the
     * bar cannot disagree.
     */
    void percent(final float fraction, final String text) {
        // A pipeline that knows its own percentage owns the bar from now on: the
        // generic "正在启动…" sweep comes from a 300 ms poll and would otherwise
        // repaint the bar sweeping on top of every real update (first-run
        // bootstrap showed no percentage at all because of that).
        precise = true;
        from = 0f;
        to = 1f;
        final float v = Math.max(0f, Math.min(1f, fraction));
        if (v > value) value = v;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (done) return;
                bar.setIndeterminate(false);
                bar.setValue(value);
                if (text != null && !text.isEmpty()) {
                    stageView.setTextColor(night ? STAGE_COLOR : 0xFF5A6472);
                    stageView.setText(text);
                }
                notifier.progress(currentStage(), false, value);
            }
        });
    }

    /** Where the bar currently sits, 0..1; used to keep a sweep from jumping back. */
    float position() {
        return value;
    }

    /** A stage with no measurable fraction: the bar sweeps instead of lying. */
    void indeterminate(final float at, final String text) {
        if (precise && !done) return;
        from = at;
        to = Math.max(at, 1f);
        value = at;
        ui.post(new Runnable() {
            @Override
            public void run() {
                bar.setValue(at);
                bar.setIndeterminate(true);
                if (!failedNow) stageView.setTextColor(night ? STAGE_COLOR : 0xFF5A6472);
                stageView.setText(text);
                notifier.progress(text, true, at);
            }
        });
    }

    void stage(final String text) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (done) return;
                stageView.setTextColor(night ? STAGE_COLOR : 0xFF5A6472);
                stageView.setText(text);
                notifier.progress(text, bar.isIndeterminate(), value);
            }
        });
    }

    private String currentStage() {
        CharSequence s = stageView.getText();
        return s == null ? "" : s.toString();
    }

    void complete() {
        done = true;
        value = 1f;
        ui.post(new Runnable() {
            @Override
            public void run() {
                bar.setIndeterminate(false);
                bar.setValue(1f);
                stageView.setTextColor(night ? READY_COLOR : 0xFF2563EB);
                stageView.setText("启动完成 · 点此回到界面");
                notifier.done();
            }
        });
    }

    void fail(final String reason, final Retry retry) {
        done = false;
        precise = false;
        ui.post(new Runnable() {
            @Override
            public void run() {
                bar.setIndeterminate(false);
                bar.setFailed(true);
                failedNow = true;
                stageView.setTextColor(night ? FAIL_COLOR : 0xFFB42318);
                stageView.setText(reason);
                stageView.setSingleLine(false);
                stageView.setEllipsize(null);
                retryButton.setVisibility(View.VISIBLE);
                retryButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        retryButton.setVisibility(View.GONE);
                        failedNow = false;
                        stageView.setTextColor(night ? STAGE_COLOR : 0xFF5A6472);
                        stageView.setText("正在重试…");
                        stageView.setSingleLine(true);
                        stageView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                        bar.setFailed(false);
                        retry.run();
                    }
                });
                logPanel.setVisibility(View.GONE);
                notifier.failed(reason);
            }
        });
    }

    /** Kept for callers that used to stop the GIF; the artwork is static now. */
    /** The notification currently on screen for the boot stage, if any. */
    Notification lastNotification() {
        return notifier.last();
    }

    /** Restores that notification after a foreground service removed it. */
    void repostNotification() {
        notifier.resume();
    }

    /** The splash is off screen: the keep-alive card is the only one in the shade. */
    void suspendNotification() {
        notifier.suspend();
    }

    /** The install is over: a card still saying "启动中" is stale, so it goes. */
    void clearNotification() {
        notifier.done();
    }

    void stop() {
    }

    /** Releases the notification when the activity goes away. */
    void dispose() {
        notifier.done();
    }

    /** Shows the "notifications are off" hint when the system refuses them. */
    private void refreshNotifHint() {
        if (notifHint == null) return;
        notifHint.setVisibility(notifier.enabled() ? View.GONE : View.VISIBLE);
    }

    private void resume() {
        if (onReturn != null) onReturn.run();
    }

    // ------------------------------------------------------------- artwork

    private static Bitmap artwork(Context context) {
        try {
            // A still image rather than the animated GIF: the file is 86 KB
            // instead of 1.6 MB, and the launch screen is read once, not watched.
            // It is a cutout (see tools/boot-art.py), so no white card.
            return BitmapFactory.decodeStream(context.getAssets().open("boot.png"));
        } catch (Throwable e) {
            // A missing or unreadable asset must not take the launch screen with
            // it: the bar and the text still carry the pipeline.
            android.util.Log.w("bigrice", "boot.jpg unavailable: " + e);
            return null;
        }
    }

    /** Draws the mascot as a rounded card; the artwork is a square tile. */
    private static final class ArtView extends View {
        private final Bitmap bitmap;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private final Path clip = new Path();

        ArtView(Context context, Bitmap bitmap) {
            super(context);
            this.bitmap = bitmap;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (bitmap == null) return;
            float w = getWidth();
            float h = getHeight();
            float side = Math.min(w, h);
            bounds.set((w - side) / 2f, (h - side) / 2f, (w + side) / 2f, (h + side) / 2f);

            // A cutout, so it simply sits on the page. The clip is kept only for
            // the very edge of the artwork's own rounded silhouette.
            clip.reset();
            clip.addRoundRect(bounds, side * 0.02f, side * 0.02f, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            android.graphics.Rect src = new android.graphics.Rect(0, 0,
                    bitmap.getWidth(), bitmap.getHeight());
            canvas.drawBitmap(bitmap, src, bounds, paint);
            canvas.restore();
        }
    }

    // ---------------------------------------------------------- notification

    /** One card, one place: the keep-alive service owns the shade.
     *
     *  The splash screen's stage line is forwarded to the service's card instead of
     *  being posted as a second notification, so a boot that happens while the app
     *  is in the background shows exactly one card that keeps saying where the
     *  install is ("启动会出现两条通知" was this duplicate).
     */
    private static final class Notifier {
        private final Context context;

        Notifier(Context context) {
            this.context = context.getApplicationContext();
        }

        private NotificationManager manager() {
            return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        }

        boolean enabled() {
            try {
                return manager().areNotificationsEnabled();
            } catch (Throwable e) {
                return false;
            }
        }

        void progress(String text, boolean indeterminate, float fraction) {
            DshService.setStatus(text);
        }

        /** The stage alone decides what the card says; nothing else to repost. */
        void suspend() {
        }

        void resume() {
        }

        void repost() {
        }

        Notification last() {
            return null;
        }

        void done() {
            // Earlier builds posted their own card under this id; clearing it here
            // is what removes one left over from a previous install.
            try {
                manager().cancel(NOTIFICATION_ID);
            } catch (Throwable ignored) {
            }
        }

        void failed(String reason) {
            DshService.setStatus(reason == null || reason.isEmpty()
                    ? "启动失败，点开应用重试"
                    : reason);
        }
    }

    // ------------------------------------------------------------ progress

    /** A 6 dp capsule: filled when the stage knows its fraction, sweeping when
     *  it does not, red after a failure. */
    private static final class BootBar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sweep = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Handler ui = new Handler(Looper.getMainLooper());
        private final RectF r = new RectF();
        private float value;
        private float phase;
        private boolean indeterminate;
        private boolean failed;

        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!indeterminate) return;
                phase += 0.02f;
                if (phase > 1.3f) phase = -0.3f;
                invalidate();
                ui.postDelayed(this, 16);
            }
        };

        BootBar(Context context) {
            super(context);
            track.setColor(0xFF22262E);
            sweep.setShader(new LinearGradient(0, 0, 1, 0,
                    new int[]{0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            setFailed(false);
        }

        void setValue(float v) {
            value = v;
            if (!indeterminate) invalidate();
        }

        void setNight(boolean night) {
            track.setColor(night ? 0xFF22262E : 0xFFE2E6EC);
            invalidate();
        }

        void setFailed(boolean f) {
            failed = f;
            fill.setShader(f ? null : new LinearGradient(0, 0, 0, 1, 0xFF3B82F6, 0xFF60A5FA,
                    Shader.TileMode.CLAMP));
            fill.setColor(0xFFFF6B6B);
            invalidate();
        }

        void setIndeterminate(boolean on) {
            if (indeterminate == on) return;
            indeterminate = on;
            ui.removeCallbacks(tick);
            if (on) {
                phase = -0.3f;
                ui.post(tick);
            }
            invalidate();
        }

        boolean isIndeterminate() {
            return indeterminate;
        }

        @Override
        protected void onDetachedFromWindow() {
            ui.removeCallbacks(tick);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight();
            float radius = h / 2f;
            r.set(0, 0, getWidth(), h);
            canvas.drawRoundRect(r, radius, radius, track);

            if (failed) return;

            if (indeterminate) {
                // A segment a third of the width wide, sliding across the track.
                float seg = getWidth() * 0.32f;
                float left = (getWidth() + seg) * phase - seg;
                sweep.setShader(new LinearGradient(left, 0, left + seg, 0,
                        new int[]{0x333B82F6, 0xFF60A5FA, 0x333B82F6},
                        new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                r.set(left, 0, left + seg, h);
                canvas.save();
                r.set(Math.max(0, left), 0, Math.min(getWidth(), left + seg), h);
                canvas.drawRoundRect(r, radius, radius, sweep);
                canvas.restore();
                return;
            }

            float w = getWidth() * Math.max(0f, Math.min(1f, value));
            if (w <= 0) return;
            fill.setShader(new LinearGradient(0, 0, 0, h, 0xFF3B82F6, 0xFF60A5FA,
                    Shader.TileMode.CLAMP));
            r.set(0, 0, w, h);
            canvas.drawRoundRect(r, radius, radius, fill);
        }
    }

    // ------------------------------------------------------------- helpers

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static View space(Context context, int heightDp) {
        View v = new View(context);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(context, heightDp)));
        return v;
    }
}
