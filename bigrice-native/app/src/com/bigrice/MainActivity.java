package com.bigrice;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 唯一 Activity：全屏 WebView 直接呈现 dsh 界面。
 *
 * 壳层不常驻任何状态/地址/按钮；只在“启动中 / 解压 / 发现新版本 / 更新中 / 更新完成 / 出错”
 * 时顶部浮出一行极简 pill，3 秒（错误 8 秒）后自动淡出。
 * dsh 进程由前台服务 DshService 托管，URL 经 prefs/广播同步到这里。
 */
public class MainActivity extends Activity implements DshService.UpdateSink {
    static final String TAG = "dshtool";
    static final int REQ_NOTIF = 11;

    WebView web;
    HostBridge bridge;
    static final int REQ_EXPORT_DIR = 12;
    private boolean conversationActive;
    private boolean statusPolling;
    TextView pill;
    BootScreen boot;
    TextView logView;
    android.widget.ScrollView logScroll;
    ProgressBar bar;
    SharedPreferences prefs;
    String loadedUrl;
    String lastError;
    int loadAttempts;
    String pillText;
    long pillShownAt;
    boolean updateFinished;
    /** 任何常驻 pill 的兜底超时（10 分钟），防更新器崩溃导致进度条卡死。 */
    final Runnable stuckHide = new Runnable() {
        @Override public void run() {
            DshLog.w(TAG, "pill 超时自动隐藏（10 分钟无终态）: " + pillText);
            updateFinished = true;
            fadePill();
        }
    };

    final Handler ui = new Handler(Looper.getMainLooper());
    BroadcastReceiver receiver;

    final Runnable hidePill = new Runnable() {
        @Override public void run() { fadePill(); }
    };

    final Runnable poll = new Runnable() {
        @Override public void run() {
            syncFromPrefs();
            ui.postDelayed(this, 300);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DshLog.init(getFilesDir());
        DshLog.i(TAG, "activity onCreate");
        prefs = getSharedPreferences(DshService.PREFS, MODE_PRIVATE);
        WebView.setWebContentsDebuggingEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        DshService.updateSink = this;
        // dsh 进程由前台服务托管：进程还活着就直接复用它的 URL（端口没变，不存在旧端口问题），
        // 界面重建只花 WebView 加载的一秒钟；只有进程真没了才走冷启动并等“本次运行 URL”。
        DshService svc = DshService.live;
        String warmUrl = (svc != null && svc.proc != null && svc.proc.isAlive()) ? svc.currentUrl : null;
        if (warmUrl == null) {
            // 冷启动：端口每次不同，旧 URL 必然 ERR_CONNECTION_REFUSED，清掉等新广播。
            prefs.edit().remove(DshService.KEY_URL).remove(DshService.KEY_ERROR).apply();
        }
        // 先把服务叫起来：node 冷启动最慢，让它和下面创建 WebView 并行。
        startDsh();
        buildUi();
        registerReceiver();
        askNotificationPermission();
        if (warmUrl != null) {
            DshLog.i(TAG, "warm entry: reuse live url");
            loadFresh(warmUrl);
        }
        startStatusWatch();

        ui.post(poll);
        syncFromPrefs();
    }

    void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        web = new WebView(this);
        web.setTag("dsh-webview");
        web.setBackgroundColor(Color.WHITE);
        DshLog.p(TAG, "[dshtool] webview created id=" + System.identityHashCode(web));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(false);
        // 渲染层：默认走硬件层；若 OEM 上 HW 层异常，可用 pref renderLayer=software 回退。
        boolean swLayer = "software".equals(prefs == null ? null : prefs.getString("renderLayer", ""));
        web.setLayerType(swLayer ? View.LAYER_TYPE_SOFTWARE : View.LAYER_TYPE_HARDWARE, null);
        DshLog.p(TAG, "[dshtool] webview settings js/dom/db on, mixed=NEVER, layer="
                + (swLayer ? "software" : "hardware"));
        CookieManager.getInstance().setAcceptCookie(true);
        bridge = new HostBridge(this);
        web.addJavascriptInterface(bridge, "BigriceHost");

        web.setWebChromeClient(new WebChromeClient() {
            // onPageFinished 在 303 跳转后不一定回调（url 已变），进度到 100 才是页面真的画完。
            @Override public void onProgressChanged(WebView v, int p) {
                if (p < 100 || boot == null || boot.isDone()) return;
                DshLog.p(TAG, "[dshtool] webview progress=100，收起开屏页");
                hidePillNow();
                announceReady();
                bootComplete();
            }

            @Override public boolean onConsoleMessage(ConsoleMessage cm) {
                if (cm != null) DshLog.i("webview-console",
                        (cm.messageLevel() + " " + cm.message() + " @" + cm.lineNumber()));
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return handleUrl(req.getUrl());
            }
            @SuppressWarnings("deprecation")
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return handleUrl(Uri.parse(url));
            }
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                DshLog.p(TAG, "[dshtool] webview onPageStarted url=" + url
                        + " id=" + System.identityHashCode(v));
                bar.setVisibility(View.VISIBLE);
            }

            @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req != null && req.isForMainFrame()) {
                    String d = err != null ? (err.getErrorCode() + " " + err.getDescription()) : "unknown";
                    DshLog.e("webview", "load error: " + d + " url=" + loadedUrl);
                    showPill("加载失败，稍后自动重试", 8000);
                    retryLoad();
                }
            }
            @SuppressWarnings("deprecation")
            @Override public void onReceivedError(WebView v, int code, String desc, String url) {
                showPill("加载失败：" + code + " " + desc, 8000);
            }
            @Override public void onPageFinished(WebView v, String url) {
                // 带 token 的地址会 303 到根地址，onPageFinished 拿到的 url 少了 query，
                // 只比前缀才认得出是同一次加载。
                if (loadedUrl != null && !loadedUrl.equals(url) && !loadedUrl.startsWith(url)) return;
                if (loadedUrl != null) {
                    loadAttempts = 0;
                    bar.setVisibility(View.GONE);
                    DshLog.i(TAG, "webview loaded " + url);
                    // 更新已经改成手动、并且在插件里做；这里只收掉启动 pill，
                    // 不再弹「更新完成」这类横幅。
                    prefs.edit().putBoolean(DshService.KEY_RESTART, false).apply();
                    hidePillNow();
                    announceReady();
                    bootComplete();
                }
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
        blp.gravity = Gravity.TOP;
        root.addView(bar, blp);

        pill = new TextView(this);
        pill.setTextSize(12);
        pill.setTextColor(0xFFFFFFFF);
        pill.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6222222);
        bg.setCornerRadius(dp(16));
        pill.setBackground(bg);
        pill.setVisibility(View.GONE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        plp.topMargin = dp(10);
        root.addView(pill, plp);

        // 开屏：和 a1.x 一样的 bigrice 启动页（吉祥物 + 进度条 + 阶段文字 + 查看日志）。
        // 它盖在 WebView 之上，页面加载完成后收起；失败时留在原地并给“重试”。
        logScroll = new android.widget.ScrollView(this);
        logView = new TextView(this);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setTextColor(Color.parseColor("#D8FFD8"));
        logView.setBackgroundColor(Color.parseColor("#101014"));
        logView.setPadding(dp(16), dp(48), dp(16), dp(16));
        logScroll.addView(logView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        boot = new BootScreen(this, logView, logScroll);
        boot.setOnReturn(new Runnable() {
            @Override public void run() { if (boot != null) boot.hide(); }
        });
        root.addView(boot.view(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        boot.stage("正在准备…");

        setContentView(root);
        DshLog.p(TAG, "[dshtool] contentView=root children=" + root.getChildCount()
                + " webId=" + System.identityHashCode(web));
        root.post(new Runnable() {
            @Override public void run() {
                DshLog.p(TAG, "[dshtool] layout root=" + web.getRootView().getWidth() + "x"
                        + web.getRootView().getHeight() + " web=" + web.getWidth() + "x" + web.getHeight()
                        + " attached=" + web.isAttachedToWindow());
            }
        });
    }


    boolean handleUrl(Uri u) {
        if (u == null) return false;
        String scheme = u.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            String host = u.getHost();
            if (host == null || host.equals("127.0.0.1") || host.equals("localhost")) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (Throwable t) {
                Toast.makeText(this, "无法打开链接: " + u, Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return true;
    }

    void registerReceiver() {
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (i == null) return;
                String url = i.getStringExtra(DshService.EXTRA_URL);
                String err = i.getStringExtra(DshService.EXTRA_ERROR);
                int pct = i.getIntExtra(DshService.EXTRA_PROGRESS, -1);
                String phase = i.getStringExtra(DshService.EXTRA_PHASE);
                String msg = i.getStringExtra(DshService.EXTRA_MSG);
                if (err != null) showError(err);
                if (phase != null) showUpdateProgress(phase, pct, msg);
                else if (pct >= 0) showExtractProgress(pct);
                if (url != null) loadFresh(url);
            }
        };
        IntentFilter f = new IntentFilter(DshService.ACTION_URL);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
    }

    void startDsh() {
        Intent i = new Intent(this, DshService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            DshLog.i(TAG, "startForegroundService sent");
        } catch (Throwable t) {
            showError("无法启动前台服务: " + t);
        }
    }

    void syncFromPrefs() {
        if (prefs == null) return;
        String url = prefs.getString(DshService.KEY_URL, null);
        String err = prefs.getString(DshService.KEY_ERROR, null);
        if (err != null && !err.equals(lastError)) showError(err);
        if (err != null) {
            bar.setVisibility(View.GONE);
            ui.removeCallbacks(stuckHide);
        }
        // 注意：不从 prefs 加载 URL（只认广播），避免旧端口。
        if (url == null && err == null) {
            String state = prefs.getString(DshService.KEY_STATE, "starting");
            int pct = prefs.getInt(DshService.KEY_PROGRESS, -1);
            if (prefs.getBoolean(DshService.KEY_RESTART, false)) {
                prefs.edit().putBoolean(DshService.KEY_RESTART, false).apply();
                showPill("正在启动…", 0);
            } else if ("extracting".equals(state) && pct >= 0) {
                showExtractProgress(pct);
            } else if (!"error".equals(state)) {
                showPill("正在启动…", 0);
            }
        }
    }

    void showExtractProgress(int pct) {
        if (pct < 0) return;
        bar.setVisibility(View.VISIBLE);
        if (pct >= 100) {
            bar.setIndeterminate(true);
            bar.setProgress(0);
            showPill("解压完成，正在启动…", 0);
            return;
        }
        bar.setIndeterminate(false);
        bar.setProgress(pct);
        showPill("首次启动：解压 " + pct + "%", 0);
    }

    /** 更新事件专用 pill：“发现新版本”/“正在更新 N%”/“更新完成，重启生效”。 */
    void showUpdateProgress(String phase, int pct, String msg) {
        String text = (msg == null || msg.length() == 0) ? ("正在更新 " + Math.max(pct, 0) + "%") : msg;

        // 失败终态：隐藏进度条，一次性提示 8 秒（进程崩溃/rc!=0/超时）。
        if ("failed".equals(phase) || "fail".equals(phase)) {
            bar.setVisibility(View.GONE);
            ui.removeCallbacks(stuckHide);
            updateFinished = true;
            showPill(text.length() == 0 ? "更新失败，已保持当前版本" : text, 8000);
            DshLog.w(TAG, "更新失败终态: " + text);
            return;
        }
        // bootstrap：同一次冷启会继续启动 dsh，终态文案不出现“重启”（否则用户会以为卡住）。
        if ("bootstrap".equals(phase)) {
            ui.removeCallbacks(stuckHide);
            if (pct >= 100) {
                bar.setIndeterminate(false);
                bar.setProgress(100);
                bar.setVisibility(View.GONE);
                updateFinished = false;
                showPill("安装完成，正在启动…", 3000);
            } else {
                updateFinished = false;
                bar.setVisibility(View.VISIBLE);
                bar.setIndeterminate(false);
                bar.setProgress(Math.max(pct, 0));
                showPill(text, 0);
            }
            return;
        }
        if ("latest".equals(phase)) {
            // 只是“查过了，已经最新”：收掉进度条，绝不重启 dsh。
            bar.setVisibility(View.GONE);
            ui.removeCallbacks(stuckHide);
            hidePillNow();
            DshLog.i(TAG, "检查完成（已是最新）: " + text);
            return;
        }
        // 终态：done 或 100% —— 只提示一次 4 秒，随后彻底不显示（即使 updater 进程还没退）。
        if ("done".equals(phase) || pct >= 100) {
            bar.setIndeterminate(false);
            bar.setProgress(100);
            bar.setVisibility(View.GONE);
            ui.removeCallbacks(stuckHide);
            hidePillNow();
            if (!updateFinished) {
                updateFinished = true;
                DshLog.i(TAG, "更新终态：只用细进度条，不弹横幅（phase=" + phase + " pct=" + pct + "）");
            }
            return;
        }
        // 仅有检查结果、没有真实活动：不要弹 pill（updateAvailable=false 时上游根本不会发这条）。
        if ("available".equals(phase)) {
            DshLog.i(TAG, "发现新版本（不弹横幅）: " + text);
            return;
        }
        if (pct < 0) {
            DshLog.i(TAG, "忽略无进度的更新事件: phase=" + phase + " msg=" + text);
            return;
        }
        updateFinished = false;
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(false);
        bar.setProgress(pct);
        // 进度只留在顶部那条细线上；文字进插件卡片，避免像弹幕一样刷屏。
        DshLog.i(TAG, "更新进度 " + pct + "%: " + text);
    }

    /** 广播过来的“本次运行 URL”：永远重新加载，并重置重试计数。 */
    void loadFresh(String url) {
        if (url == null) return;
        if (url.equals(loadedUrl)) return;
        loadedUrl = url;
        loadAttempts = 0;
        DshLog.p(TAG, "[dshtool] webview load url=" + url + " (fresh)");
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(true);
        web.loadUrl(url);
        scheduleLoadCheck(url, 3000, 0);
    }

    /** 3s/8s 自检：progress 长期 <20 说明导航没真正开始（实例/可见性/渲染层问题），重载一次并打诊断。 */
    void scheduleLoadCheck(final String url, long delayMs, final int round) {
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (web == null || loadedUrl == null || !loadedUrl.equals(url)) return;
                int p = web.getProgress();
                String u = web.getUrl();
                DshLog.p(TAG, "[dshtool] webview check r" + round + " progress=" + p
                        + " getUrl=" + u + " contentHeight=" + web.getContentHeight()
                        + " attached=" + web.isAttachedToWindow()
                        + " size=" + web.getWidth() + "x" + web.getHeight()
                        + (web.getVisibility() == View.VISIBLE ? " vis=VISIBLE" : " vis=NOT_VISIBLE"));
                if (p < 20 && round == 0) {
                    DshLog.w(TAG, "webview 3s 内 progress<20，reload 一次并重试 loadUrl");
                    web.reload();
                    web.loadUrl(url);
                    scheduleLoadCheck(url, 5000, 1);
                } else if (p >= 100) {
                    announceReady();
                    bootComplete();
                } else if (p < 20 && round == 1) {
                    DshLog.e(TAG, "webview 8s 仍 progress<20：检查可见容器/渲染层/id="
                            + System.identityHashCode(web) + " parent=" + web.getParent());
                }
            }
        }, delayMs);
    }

    /** 同一 URL 失败后允许退避重载（1s/2s/3s，最多 3 次）。 */
    void retryLoad() {
        if (loadedUrl == null || loadAttempts >= 3) return;
        loadAttempts++;
        long delay = 1000L * loadAttempts;
        DshLog.w(TAG, "webview retry " + loadAttempts + "/3 in " + delay + "ms url=" + loadedUrl);
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (loadedUrl != null) {
                    bar.setVisibility(View.VISIBLE);
                    bar.setIndeterminate(true);
                    web.loadUrl(loadedUrl);
                }
            }
        }, delay);
    }

    @SuppressWarnings("unused")
    void loadUrl(String url) { loadFresh(url); }

    void showError(String msg) {
        lastError = msg;
        DshLog.e(TAG, "error: " + msg);
        showPill("出错：" + msg, 8000);
        bar.setVisibility(View.GONE);
        bootFailed(msg);
    }

    /** 顶部极简 pill：durationMs<=0 表示常驻（启动/解压/更新中），页面就绪后隐藏。 */
    void showPill(String text, int durationMs) {
        if (text == null || text.length() == 0) return;
        bootStage(text);
        if (text.equals(pillText) && pill.getVisibility() == View.VISIBLE) {
            ui.removeCallbacks(hidePill);
            if (durationMs > 0) ui.postDelayed(hidePill, durationMs);
            return;
        }
        pillText = text;
        pillShownAt = System.currentTimeMillis();
        pill.setText(text);
        pill.setVisibility(View.VISIBLE);
        pill.setAlpha(0f);
        pill.animate().alpha(1f).setDuration(180).start();
        ui.removeCallbacks(hidePill);
        ui.removeCallbacks(stuckHide);
        if (durationMs > 0) {
            ui.postDelayed(hidePill, durationMs);
        } else {
            // 常驻 pill 的兜底：10 分钟后强制隐藏并留 logcat。
            ui.postDelayed(stuckHide, 600000L);
        }
    }

    void fadePill() {
        if (pill.getVisibility() != View.VISIBLE) return;
        pill.animate().alpha(0f).setDuration(250).withEndAction(new Runnable() {
            @Override public void run() {
                pill.setVisibility(View.GONE);
                pillText = null;
            }
        }).start();
    }

    void hidePillNow() {
        ui.removeCallbacks(hidePill);
        pill.setVisibility(View.GONE);
        pillText = null;
    }

    void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, REQ_NOTIF);
    }

    /** 界面在前台：通知栏不留常驻卡片（a1.x 的行为）；退到后台由 onStop 挂回去。 */
    @Override protected void onStart() {
        super.onStart();
        DshService.uiVisible(true);
    }

    @Override protected void onStop() {
        super.onStop();
        DshService.uiVisible(false);
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_NOTIF) {
            boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            DshLog.i(TAG, "POST_NOTIFICATIONS granted=" + ok);
        }
    }

    @Override public void onBackPressed() {
        if (boot != null && boot.logVisible()) {
            boot.closeLog();
            return;
        }
        if (boot != null && boot.isVisible() && boot.isDone()) {
            boot.hide();
            return;
        }
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        syncFromPrefs();
    }

    @Override protected void onDestroy() {
        DshLog.i(TAG, "activity onDestroy");
        DshService.updateSink = null;
        statusPolling = false;
        if (boot != null) boot.dispose();
        ui.removeCallbacksAndMessages(null);
        if (receiver != null) {
            try { unregisterReceiver(receiver); } catch (Throwable ignored) { }
        }
        if (web != null) {
            try { web.destroy(); } catch (Throwable ignored) { }
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------- 状态与桥接

    /**
     * One line describing what the current conversation is doing, for the shade card.
     *
     * A finished step is what {@code [data-state]} records: every settled row keeps
     * {@code data-state=ok} (or {@code error}), so asking it for {@code running} never
     * matches — that query is why the card used to say nothing while a turn ran.
     * A busy turn shows itself three other ways: the composer swaps its send button
     * for 停止生成, a live {@code [data-process-activity]} row reads 正在…, and a tool
     * row that is still executing keeps {@code data-state=running}.
     */
    static final String ACTIVITY_JS = "(function(){try{"
            + "var withTitle=function(line){"
            + "var t=(document.title||'').split(' — ')[0].trim();"
            + "return (t&&t!=='DeepSeek Harness')?t+' · '+line:line};"
            + "var busy=false,i,btns=document.querySelectorAll('button[aria-label]');"
            + "for(i=0;i<btns.length;i++){var la=btns[i].getAttribute('aria-label')||'';"
            + "if(la==='停止生成'||la==='停止'){busy=true;break}}"
            + "var rows=document.querySelectorAll('[data-state]');"
            + "for(i=rows.length-1;i>=0;i--){"
            + "var s=rows[i].getAttribute('data-state')||'';"
            + "if(s==='running'||s==='preparing'||s==='pending'||s==='streaming'){"
            + "busy=true;var n=rows[i].getAttribute('data-tool')||'';"
            + "if(n)return withTitle('正在调用工具 '+n)}}"
            + "var acts=document.querySelectorAll('[data-process-activity]');"
            + "for(i=acts.length-1;i>=0;i--){var t=(acts[i].textContent||'').trim();"
            + "if(t.indexOf('正在')===0){busy=true;var k=t.indexOf(' · ');"
            + "return withTitle(k>0?t.slice(0,k):t)}}"
            + "if(!busy&&!document.querySelector('[data-streaming]'))return '';"
            + "return withTitle('思考中…')"
            + "}catch(e){return ''}})()";

    /** Runs a JS snippet on the page: the bridge calls this from worker threads. */
    void evalJs(final String js) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (web == null) return;
                try {
                    web.evaluateJavascript(js, null);
                } catch (Throwable ignored) {
                    // the page can be mid-navigation; the caller retries on the next tick
                }
            }
        });
    }

    void toast(final String message) {
        ui.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    void hideKeyboard() {
        ui.post(new Runnable() {
            @Override public void run() {
                try {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager)
                                    getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null && web != null) {
                        imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
                    }
                } catch (Throwable ignored) {
                    // no IME attached
                }
            }
        });
    }

    String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    int versionCode() {
        try {
            return (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (Throwable e) {
            return 0;
        }
    }

    void pickExportDir() {
        ui.post(new Runnable() {
            @Override public void run() {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try {
                    startActivityForResult(intent, REQ_EXPORT_DIR);
                } catch (RuntimeException e) {
                    DshLog.w(TAG, "没有文件夹选择器: " + e);
                }
            }
        });
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_EXPORT_DIR || res != RESULT_OK || data == null) return;
        Uri tree = data.getData();
        if (tree == null) return;
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable ignored) {
            // some providers refuse persistable grants; the export still works for this session
        }
        if (bridge != null) bridge.onExportTreePicked(tree);
    }

    /** Polls the page for "tool running / thinking" and mirrors it into the notification. */
    void startStatusWatch() {
        if (statusPolling) return;
        statusPolling = true;
        ui.post(new Runnable() {
            @Override public void run() {
                if (!statusPolling) return;
                if (web != null) {
                    try {
                        web.evaluateJavascript(ACTIVITY_JS, new android.webkit.ValueCallback<String>() {
                            @Override public void onReceiveValue(String raw) {
                                String line = jsonUnquote(raw);
                                if (!line.isEmpty()) {
                                    conversationActive = true;
                                    DshService.setStatus(line);
                                } else if (conversationActive) {
                                    conversationActive = false;
                                    DshService.setStatus("");
                                }
                            }
                        });
                    } catch (Throwable ignored) {
                        // page not ready yet
                    }
                }
                ui.postDelayed(this, 1500);
            }
        });
    }

    /** "已就绪" is news, not a permanent state: it fades unless a turn started. */
    void announceReady() {
        DshService.setStatus("已就绪");
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (!conversationActive) DshService.setStatus("");
            }
        }, 4000);
    }

    /** Turns the JSON string literal returned by evaluateJavascript into text. */
    static String jsonUnquote(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.isEmpty() || text.equals("null")) return "";
        if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            text = text.substring(1, text.length() - 1);
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) {
                out.append(c);
                continue;
            }
            char next = text.charAt(++i);
            switch (next) {
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'u':
                    if (i + 4 < text.length()) {
                        try {
                            out.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            out.append(next);
                        }
                    } else {
                        out.append(next);
                    }
                    break;
                default: out.append(next);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------- 更新（插件触发）

    /** dsh 更新进度：转成页面里的事件，界面插件订阅 window.__bigriceUpdate。 */
    @Override public void onUpdate(final String phase, final int pct, final String msg) {
        // 有百分比就直接灌进开屏页的进度条（a1.x 就是这样）：首启要下几百个包，
        // 条必须真的往前走；没有百分比的阶段（解析依赖、androidize）才退回扫动。
        if (pct >= 0 && boot != null && !boot.isDone()) {
            boot.percent(pct / 100f, msg);
        } else if (msg != null && !msg.isEmpty()) {
            bootStage(msg);
        }
        evalJs("window.__bigriceUpdate&&window.__bigriceUpdate({phase:" + quote(phase)
                + ",pct:" + pct + ",msg:" + quote(msg) + ",version:" + quote(DshService.version())
                + ",updating:" + DshService.isUpdating() + "})");
        if ("done".equals(phase) && !DshService.bootstrapping) {
            // 手动更新完成：让服务带着新版本重新起来（插件那边收到 done 就算完成）。
            // 首启 bootstrap 的 done 不重启：服务紧接着自己 spawn 新版 dsh。
            ui.postDelayed(new Runnable() {
                @Override public void run() { restartDsh(); }
            }, 1500);
        }
    }

    static String quote(String text) {
        if (text == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }

    // ------------------------------------------------------------- 开屏页

    void bootStage(String text) {
        if (boot == null || text == null || text.isEmpty() || boot.isDone()) return;
        boot.indeterminate(boot.position(), text);
    }

    void bootProgress(int pct, String text) {
        if (boot == null || boot.isDone()) return;
        if (text != null && !text.isEmpty()) boot.stage(text);
        if (pct >= 0) boot.progress(pct / 100f);
    }

    void bootComplete() {
        if (boot == null || boot.isDone()) return;
        boot.complete();
        // 页面进度到 100 就已经画完，开屏只留一小段淡出时间，不再白挡 0.9 秒。
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                DshLog.p(TAG, "[dshtool] 开屏页隐藏");
                if (boot != null) boot.hide();
            }
        }, 220);
    }

    void bootFailed(String reason) {
        if (boot == null) return;
        boot.fail(reason == null || reason.isEmpty() ? "启动失败" : reason, new BootScreen.Retry() {
            @Override public void run() {
                if (boot != null) {
                    boot.showAgain();
                    boot.indeterminate(0.02f, "重新启动 dsh…");
                }
                restartDsh();
            }
        });
    }

    void restartDsh() {
        try {
            stopService(new Intent(this, DshService.class));
        } catch (Throwable ignored) {
            // service already gone
        }
        ui.postDelayed(new Runnable() {
            @Override public void run() { startDsh(); }
        }, 900);
    }

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
