import pathlib

p = pathlib.Path('app/src/com/bigrice/MainActivity.java')
s = p.read_text(encoding='utf-8')

# --- fields --------------------------------------------------------------
old = '    TextView pill;\n'
new = ('    TextView pill;\n'
       '    BootScreen boot;\n'
       '    TextView logView;\n'
       '    android.widget.ScrollView logScroll;\n')
assert old in s
s = s.replace(old, new, 1)

# --- boot screen layer on top of the WebView ----------------------------
old = '        root.addView(pill, plp);\n'
new = '''        root.addView(pill, plp);

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
'''
assert old in s
s = s.replace(old, new, 1)

# --- page ready -> complete the splash ----------------------------------
old = '                    announceReady();\n'
new = '                    announceReady();\n                    bootComplete();\n'
assert old in s
s = s.replace(old, new, 1)

# --- errors surface on the splash ---------------------------------------
old = '''    void showError(String msg) {
        lastError = msg;
        DshLog.e(TAG, "error: " + msg);
        showPill("出错：" + msg, 8000);
        bar.setVisibility(View.GONE);
    }
'''
new = '''    void showError(String msg) {
        lastError = msg;
        DshLog.e(TAG, "error: " + msg);
        showPill("出错：" + msg, 8000);
        bar.setVisibility(View.GONE);
        bootFailed(msg);
    }
'''
assert old in s
s = s.replace(old, new, 1)

# --- pill text also drives the splash stage -----------------------------
old = '''    void showPill(String text, int durationMs) {
        if (text == null || text.length() == 0) return;
'''
new = '''    void showPill(String text, int durationMs) {
        if (text == null || text.length() == 0) return;
        bootStage(text);
'''
assert old in s
s = s.replace(old, new, 1)

# --- back / destroy -----------------------------------------------------
old = '''    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) {
'''
new = '''    @Override public void onBackPressed() {
        if (boot != null && boot.logVisible()) {
            boot.closeLog();
            return;
        }
        if (boot != null && boot.isVisible() && boot.isDone()) {
            boot.hide();
            return;
        }
        if (web != null && web.canGoBack()) {
'''
assert old in s
s = s.replace(old, new, 1)

old = '        statusPolling = false;\n'
new = '        statusPolling = false;\n        if (boot != null) boot.dispose();\n'
assert old in s
s = s.replace(old, new, 1)

# --- helpers ------------------------------------------------------------
old = '    int dp(int v) {'
helpers = '''    // ------------------------------------------------------------- 开屏页

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
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (boot != null) boot.hide();
            }
        }, 900);
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

    int dp(int v) {'''
assert old in s
s = s.replace(old, helpers, 1)

p.write_text(s, encoding='utf-8')
print('MainActivity wired to BootScreen')
