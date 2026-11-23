package com.bigrice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 前台服务：把 APK 里的 assets/dshtool.zip（dsh bundle）解压到 filesDir/dsh，
 * 以 /system/bin/linker64（或直接 exec）启动 nativeLibraryDir/libnode.so，
 * 参数为 `--expose-internals lib/node_modules/@deepseek-ai/dsh/lib/bin.js web
 * --host 127.0.0.1 --port 0 --no-open`，从 stdout 抓
 * `dsh web: http://127.0.0.1:<port>/?token=...` 并通过广播/prefs 交给 WebView。
 *
 * 失败路径：日志同时写 Logcat(tag=dshtool, dsh)、filesDir/log/dshtool.log 和内存环形
 * 缓冲（页面“日志”面板可见）；进程异常退出会自动重启（最多 3 次）。
 */
public class DshService extends Service {
    static final String TAG = "dshtool";
    public static final String ACTION_URL = "com.dshtool.DSH_URL";
    public static final String ACTION_STOP = "com.dshtool.DSH_STOP";
    public static final String ACTION_UPDATE = "com.dshtool.DSH_UPDATE";
    public static final String EXTRA_PHASE = "phase";
    public static final String EXTRA_MSG = "msg";
    public static final String KEY_PHASE = "phase";
    public static final String KEY_MSG = "msg";
    public static final String KEY_AVAIL = "avail";
    public static final String KEY_AUTO = "autoUpdate";
    public static final String KEY_RESTART = "restartNeeded";
    public static final String KEY_UPDATED = "updatedVersion";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_ERROR = "error";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String PREFS = "dshtool";
    public static final String KEY_URL = "url";
    public static final String KEY_ERROR = "error";
    public static final String KEY_STATE = "state";
    public static final String KEY_PROGRESS = "progress";

    /** dsh web 打印的地址；token 是 base64url，可能带 -/_。 */
    static final Pattern URL_RE = Pattern.compile("https?://127\\.0\\.0\\.1:\\d{1,5}/\\S*");
    static final int NOTIF_ID = 1001;
    static final String CHANNEL = "bigrice-service";
    static final String LEGACY_CHANNEL = "dsh";
    static final int MAX_RESTARTS = 3;

    /** 首次启动解压进度（0–100），通过 prefs + 广播发给 UI。 */
    interface ZipProgress { void onProgress(int pct); }

    static final class CountingInputStream extends java.io.FilterInputStream {
        long count;
        CountingInputStream(InputStream in) { super(in); }
        @Override public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
    }

    Process proc;
    Thread worker;
    volatile boolean stopRequested = false;
    volatile String currentUrl;
    /** 手动“检查更新”：本次 runOnce 用 --update（较长超时），否则启动时 --auto（10s）。 */
    volatile boolean manualUpdate = false;
    /** DSH 会话默认工作区：外部私有目录 Android/data/<pkg>/files/work（用户可导出），失败回退内部。 */
    volatile File workspace = null;
    /** active.json 记录的版本（与 --version 实测值比对，避免“装 rc.5 启动 rc.1”）。 */
    volatile String activeVersion = null;
    /** 当前 updater 动作：--check / --update / --install-latest（决定结果行的文案与语义）。 */
    volatile String updaterAction = "";
    volatile boolean updating = false;
    volatile boolean autoUpdateStarted = false;
    File lastDshDir;
    File lastNode;
    Map<String, String> lastEnv;

    @Override public IBinder onBind(Intent i) { return null; }

    /** 最近一次通知里的状态行；界面轮询通过 setStatus 维护它。 */
    static volatile DshService live;
    static volatile String statusText = "";
    /** 会话工作区（外部私有目录 work）；HostBridge 的 /root 就是它。 */
    static volatile File workDir;

    static File workspaceDir() {
        File dir = workDir;
        if (dir != null && dir.isDirectory()) return dir;
        DshService svc = live;
        return svc == null ? null : svc.getFilesDir();
    }

    /**
     * 界面是否在前台。a1.x 的规则：只有退到后台才留常驻卡片，界面在前台时不占通知栏
     * （{@code MainActivity} 的 onStart/onStop 调这里）。
     */
    static volatile boolean uiForeground;
    /**
     * 正在跑首启 bootstrap（--install-latest）。这期间 updater 也会发 done，但服务接下来
     * 自己就会 spawn 新版 dsh，再让 Activity 重启一次等于把刚起的进程杀掉重来（实测一次
     * 冷启里出现了两个 dsh、两个端口）。所以 done 只有在手动更新时才触发重启。
     */
    static volatile boolean bootstrapping;

    static void uiVisible(boolean visible) {
        uiForeground = visible;
        DshService svc = live;
        if (svc == null) return;
        if (visible) svc.demote(); else svc.promote();
    }

    /** 退到后台：重新挂成前台服务，卡片带着当前状态行出现。 */
    private void promote() {
        try {
            startForeground(NOTIF_ID, buildNotification(statusText, currentUrl));
        } catch (Throwable t) {
            DshLog.w(TAG, "startForeground refused: " + t);
        }
    }

    /** 回到界面：摘掉卡片，但服务继续按已开始的顺序跑（进程此刻是前台优先级，不会被回收）。 */
    private void demote() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable t) {
            DshLog.w(TAG, "stopForeground refused: " + t);
        }
    }

    /** 页面轮询到「正在调用工具 / 思考中…」时写入；空串表示对话已结束。 */
    public static void setStatus(String text) {
        String line = text == null ? "" : text.trim();
        DshService svc = live;
        statusText = line;
        if (svc != null) svc.updateNotification(line);
    }

    static String status() { return statusText; }

    /** 上次启动的 dsh 版本（界面插件显示“当前版本”）。 */
    static volatile String runningVersion = "";

    /** 界面插件触发更新时，把进度推给 WebView。 */
    interface UpdateSink { void onUpdate(String phase, int pct, String msg); }

    static volatile UpdateSink updateSink;
    /** 界面插件触发的更新是否在跑（与实例字段 updating 区分：后者是“本次启动要检查”）。 */
    private static volatile boolean updatingFromUi;
    /** --check 这一轮是否打印过结果行；没打印就按「已是最新」收尾。 */
    private boolean checkSawResult;

    static boolean isUpdating() { return updatingFromUi; }
    static String version() { return runningVersion; }

    /** 只检查是否有新版本（不安装）。 */
    static boolean checkUpdate() { return startUpdate("--check", 60000); }

    /** 安装最新版本；完成后由界面调用 restartDsh() 让新版本生效。 */
    static boolean applyUpdate() { return startUpdate("--update", 900000); }

    private static boolean startUpdate(final String action, final long timeoutMs) {
        final DshService svc = live;
        if (svc == null || svc.lastNode == null || svc.lastDshDir == null || svc.lastEnv == null) {
            return false;
        }
        if (updatingFromUi) return false;
        updatingFromUi = true;
        final File dshDir = svc.lastDshDir;
        final File node = svc.lastNode;
        final Map<String, String> env = svc.lastEnv;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    svc.runUpdater(dshDir, node, env, action, timeoutMs);
                } finally {
                    updatingFromUi = false;
                }
            }
        }, "bigrice-update");
        t.setDaemon(true);
        t.start();
        return true;
    }

    @Override public void onCreate() {
        super.onCreate();
        live = this;
        DshLog.init(getFilesDir());
        DshLog.i(TAG, "service onCreate sdk=" + Build.VERSION.SDK_INT
                + " pkg=" + getPackageName() + " nativeDir=" + getApplicationInfo().nativeLibraryDir);
        createChannel();
        // A sticky restart keeps the previous URL, and publishUrl() skips an
        // unchanged one, so seed the card from persisted state instead of letting
        // it sit on the boot line forever.
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, null);
        boolean running = "running".equals(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_STATE, ""));
        if (running && saved != null && !saved.isEmpty()) {
            currentUrl = saved;
            startForeground(NOTIF_ID, buildNotification("运行中：本地 Web 服务已就绪", null));
        } else {
            startForeground(NOTIF_ID, buildNotification("正在启动 DeepSeek Harness…", null));
        }
        // startForegroundService 要求这 5 秒内必须进前台，所以先挂上再按界面可见性收起。
        if (uiForeground) demote();
    }

    void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        // A LOW-importance channel is filed under 静音 by the shade, which reads as
        // "no notification at all" on MIUI/HyperOS, and an existing channel keeps its
        // original importance for the life of the install — hence the renamed id
        // (the old "dsh" channel is dropped) and DEFAULT importance with no sound.
        if (nm.getNotificationChannel(LEGACY_CHANNEL) != null) nm.deleteNotificationChannel(LEGACY_CHANNEL);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "bigrice 服务",
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("DeepSeek Harness 本地 Web 服务（原生 node 运行时）");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
    }

    private int smallIcon() {
        int id = getResources().getIdentifier("ic_launcher_monochrome", "drawable", getPackageName());
        return id != 0 ? id : getApplicationInfo().icon;
    }

    Notification buildNotification(String text, String url) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("bigrice");
        b.setContentText(text != null && !text.isEmpty() ? text : "dsh 后台运行中");
        b.setShowWhen(false);
        b
                .setSmallIcon(smallIcon())
                .setOngoing(true).setContentIntent(pi);
        if (Build.VERSION.SDK_INT >= 26) {
            Intent stop = new Intent(this, DshService.class).setAction(ACTION_STOP);
            PendingIntent sp = PendingIntent.getService(this, 2, stop,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_delete, "停止", sp).build());
        }
        Notification n = b.build();
        return n;
    }

    void updateNotification(String text) {
        // 界面在前台时没有卡片可刷：状态只留在 statusText 里，退到后台 promote() 时带上。
        if (uiForeground) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification(text, null));
        } catch (Throwable t) {
            DshLog.e(TAG, "updateNotification failed: " + t);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            DshLog.i(TAG, "stop requested via notification/action");
            stopRequested = true;
            if (proc != null) proc.destroy();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_UPDATE.equals(intent.getAction())) {
            if (updating) { DshLog.i(TAG, "update already running, ignore"); return START_STICKY; }
            DshLog.i(TAG, "manual update requested");
            manualUpdate = true;
            publishUpdate("queued", 0, "正在检查更新…");
            if (proc != null) proc.destroy(); // 让 runOnce 重新走更新 + 启动
        }
        if (worker == null) {
            worker = new Thread(new Runnable() {
                @Override public void run() {
                    runLoop();
                }
            }, "dsh-main");
            worker.start();
        } else if (proc != null && proc.isAlive() && currentUrl != null) {
            // 进程没死、dsh 还在跑：Activity 被系统回收后重建时拿不到上一次的 URL 广播，
            // 这里补发一次，让它直接加载现成页面，而不是空等（也就不会再从头启动一遍）。
            rebroadcastUrl();
        }
        return START_STICKY;
    }

    /** 把当前活着的 URL 再广播一次（仅当 dsh 进程仍在运行）。 */
    void rebroadcastUrl() {
        String url = currentUrl;
        if (url == null) return;
        Intent i = new Intent(ACTION_URL);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_URL, url);
        sendBroadcast(i);
        DshLog.i(TAG, "re-broadcast live url: " + url);
    }

    void publishUrl(String url) {
        if (url == null || url.equals(currentUrl)) return;
        currentUrl = url;
        DshLog.i(TAG, "dsh ready: " + url);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_URL, url).remove(KEY_ERROR).putString(KEY_STATE, "running")
                .putInt(KEY_PROGRESS, -1).apply();
        Intent i = new Intent(ACTION_URL);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_URL, url);
        sendBroadcast(i);
        updateNotification("运行中：本地 Web 服务已就绪");
    }

    void fail(String msg) {
        DshLog.e(TAG, msg);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_ERROR, msg).putString(KEY_STATE, "error")
                .putInt(KEY_PROGRESS, -1).apply();
        Intent i = new Intent(ACTION_URL);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_ERROR, msg);
        sendBroadcast(i);
        updateNotification("启动失败：" + msg);
    }

    void publishProgress(int pct) {
        if (pct < 0) return;
        if (pct > 100) pct = 100;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_STATE, "extracting").putInt(KEY_PROGRESS, pct).apply();
        Intent i = new Intent(ACTION_URL);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_PROGRESS, pct);
        sendBroadcast(i);
        if (pct < 100 && pct % 5 == 0) {
            updateNotification("首次启动：解压 dsh bundle " + pct + "%（约 379 MiB，低端机 30–60 秒）");
        }
    }

    void runLoop() {
        // 新一轮启动：先清掉上次的 URL/错误，避免页面加载到已经失效的端口
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .remove(KEY_URL).remove(KEY_ERROR).putString(KEY_STATE, "starting")
                .putInt(KEY_PROGRESS, -1).apply();
        currentUrl = null;
        for (int attempt = 0; attempt <= MAX_RESTARTS && !stopRequested; attempt++) {
            try {
                runOnce();
                return; // 正常退出（stopRequested）
            } catch (Throwable t) {
                DshLog.e(TAG, "dsh attempt " + (attempt + 1) + " failed: " + t);
                fail("dsh 启动失败: " + t);
                if (stopRequested) return;
                if (attempt >= MAX_RESTARTS) {
                    fail("dsh 连续 " + (MAX_RESTARTS + 1) + " 次启动失败，停止重试");
                    return;
                }
                try { Thread.sleep(3000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    void runOnce() throws Exception {
        File base = getFilesDir();
        File dshDir = new File(base, "dsh");
        File bundledBinJs = new File(dshDir, "lib/node_modules/@deepseek-ai/dsh/lib/bin.js");
        // bootstrap 模式：assets 只含运行底座，不再内置 @deepseek-ai/dsh；此断言仅兜底旧 assets。
        ensureBundle(dshDir, null);
        writeNodeShim(dshDir);

        File node = resolveNode(dshDir);
        File home = new File(base, "dsh-home");
        if (!home.isDirectory() && !home.mkdirs()) throw new IOException("无法创建 DSH_HOME: " + home);
        File userHome = new File(base, "home");
        if (!userHome.isDirectory() && !userHome.mkdirs()) throw new IOException("无法创建 HOME: " + userHome);
        // npm/pnpm 的“全局安装”默认写到 node 安装目录（nativeLibraryDir，只读）→ EACCES。
        // 全部重定向到 app 可写的 HOME 下；目录先预建，避免首次 install 时才报错。
        File npmGlobal = new File(userHome, ".npm-global");
        File npmCache = new File(userHome, ".npm");
        File pnpmHome = new File(userHome, ".local/share/pnpm");
        File corepackHome = new File(userHome, ".cache/corepack");
        File npmStore = new File(userHome, ".pnpm-store");
        for (File d : new File[] { npmGlobal, npmCache, pnpmHome, corepackHome, npmStore }) {
            // 防御：mkdir 失败不让整个服务起不来（logcat 留痕，后续 install 会再报具体错误）
            if (!d.isDirectory() && !d.mkdirs()) DshLog.e(TAG, "无法创建目录（继续）: " + d);
        }
        writeNpmrc(userHome);

        Map<String, String> env = new java.util.HashMap<String, String>();
        env.put("DSH_HOME", home.getAbsolutePath());
        env.put("DSHTOOL_ROOT", dshDir.getAbsolutePath());
        env.put("HOME", userHome.getAbsolutePath());
        env.put("TMPDIR", getCacheDir().getAbsolutePath());
        env.put("XDG_CACHE_HOME", new File(getCacheDir(), "xdg").getAbsolutePath());
        // dsh/bin 必须进 PATH：plugin-manager 等插件会 spawn("pnpm"/"npm"/"npx")，
        // 只看 PATH；bundle 里的 bin/pnpm、bin/pnpx wrapper 就在这里。
        String path = new File(dshDir, "bin").getAbsolutePath()
                + ":/system/bin:/system/xbin:/product/bin:/apex/com.android.runtime/bin";
        env.put("PATH", path);
        // noexec shim：app data 目录不可执行，某些工具仍会直接 spawn wrapper；
        // packager 提供 bin/dshtool-noexec-shim.cjs，用 NODE_OPTIONS --require 预载到所有 node 子进程。
        File noexecShim = new File(dshDir, "bin/dshtool-noexec-shim.cjs");
        if (noexecShim.isFile()) {
            String prev = env.get("NODE_OPTIONS");
            String req = "--require " + noexecShim.getAbsolutePath();
            env.put("NODE_OPTIONS", (prev == null || prev.trim().isEmpty()) ? req : prev + " " + req);
        } else {
            DshLog.w(TAG, "缺少 noexec shim（继续）: " + noexecShim);
        }
        String nativeDir = getApplicationInfo().nativeLibraryDir;
        // pnpm wrapper 在没有 bin/node 的 APK 里靠这个变量找到 node（packaging 侧约定）。
        env.put("DSHTOOL_NODE", new File(nativeDir, "libnode.so").getAbsolutePath());
        // app data 目录 noexec：bin/* shell wrapper 在真机 spawn 会 EACCES。
        // plugin-manager 已改为 `node <pnpm.cjs>`，这里的绝对路径避免它猜。
        env.put("DSHTOOL_PNPM_CJS",
                new File(dshDir, "lib/node_modules/pnpm/bin/pnpm.cjs").getAbsolutePath());
        // pnpm 的 runNpm（安装后跑 npm 命令）同样不能用 noexec 的 bin/npm wrapper。
        env.put("DSHTOOL_NPM_CLI",
                new File(dshDir, "lib/node_modules/npm/bin/npm-cli.js").getAbsolutePath());
        // npm/pnpm 可写前缀与缓存（大写/小写都被 node/npm 读取，两个都注入）。
        env.put("NPM_CONFIG_PREFIX", npmGlobal.getAbsolutePath());
        env.put("npm_config_prefix", npmGlobal.getAbsolutePath());
        env.put("NPM_CONFIG_CACHE", npmCache.getAbsolutePath());
        env.put("npm_config_cache", npmCache.getAbsolutePath());
        env.put("PNPM_HOME", pnpmHome.getAbsolutePath());
        env.put("COREPACK_HOME", corepackHome.getAbsolutePath());
        env.put("NODE_REPL_HISTORY", new File(userHome, ".node_repl_history").getAbsolutePath());
        env.put("LD_LIBRARY_PATH", nativeDir + ":" + new File(dshDir, "lib").getAbsolutePath());
        // 打包侧（77976d18）约定：三个 addon 变量都是“完整文件路径”，文件存在才注入，
        // 这样即便某个 APK 没带对应 .so 也不会把本来可用的 filesDir .node 兜底顶掉。
        putIfExists(env, "DSHTOOL_PTY_HELPER", new File(nativeDir, "libspawn_helper.so"));
        putIfExists(env, "DSHTOOL_PTY_NODE", new File(nativeDir, "libnodepty.so"));
        putIfExists(env, "DSHTOOL_SYSTEM_NODE", new File(nativeDir, "libsystemnode.so"));

        BigriceUi.ensure(this, dshDir, home, env);

        workspace = resolveWorkspace();
        workDir = workspace;
        // dsh 的默认工作区初始化在 Android 上找不到系统“文档”目录（见 assets/dsh-workspace.yml），
        // 这里把工作区路径交给那条 profile 补丁，首启即可自动建好工作区。
        env.put("BIGRICE_DOCUMENTS", workspace.getAbsolutePath());
        DshLog.i(TAG, "workspace=" + workspace.getAbsolutePath());


        // 后续启动不再联网：dsh 只在首启（没有 active 版本）时装一次，
        // 检查/安装更新改由界面插件通过 BigriceHost.checkUpdate()/applyUpdate() 触发。
        manualUpdate = false;

        // active.json 是唯一启动源：没有 active 版本 → 首启 bootstrap（下载最新版并 androidize）。
        File bundleDir = resolveActiveBundle(dshDir);
        if (bundleDir == null) {
            // bootstrap：本次冷启内完成“下载安装 → 切 active → 启动”，绝不要求用户重启。
            DshLog.p(TAG, "[dshtool] bootstrap start");
            publishUpdate("bootstrap", 0, "首次启动：下载并安装 dsh…");
            bootstrapping = true;
            try {
                runUpdater(dshDir, node, env, "--install-latest", 900000);
            } finally {
                bootstrapping = false;
            }
            bundleDir = resolveActiveBundle(dshDir);
            if (bundleDir == null) {
                DshLog.e(TAG, "[dshtool] bootstrap failed: active.json 仍不可用");
                throw new IOException("bootstrap 安装失败：没有可用的 active 版本（检查网络/日志）");
            }
            DshLog.p(TAG, "[dshtool] bootstrap complete version=" + activeVersion
                    + " dir=" + bundleDir.getAbsolutePath());
            runCleanup(dshDir, node, env);
        }
        File binJs = new File(bundleDir, "lib/node_modules/@deepseek-ai/dsh/lib/bin.js");
        if (!binJs.isFile()) {
            throw new IOException("active 版本缺少 bin.js: " + bundleDir + "（拒绝回退内置，避免版本错乱）");
        }
        String actualVersion = probeVersion(node, binJs, env);
        if (activeVersion != null && actualVersion != null && !activeVersion.equals(actualVersion)) {
            throw new IOException("版本不一致：active.json=" + activeVersion
                    + " 实际 --version=" + actualVersion + "（已停止，避免跑错版本）");
        }
        runningVersion = actualVersion != null ? actualVersion : (activeVersion != null ? activeVersion : "");
        DshLog.p(TAG, "[dshtool] active.version=" + activeVersion + " actual.version=" + actualVersion);
        DshLog.p(TAG, "[dshtool] launch dir=" + bundleDir.getAbsolutePath());
        retargetEnv(env, dshDir, bundleDir);
        lastDshDir = dshDir;
        lastNode = node;
        lastEnv = env;
        DshLog.i(TAG, "spawning: node=" + node.getAbsolutePath() + " bundle=" + bundleDir);
        String[] args = new String[] {
                node.getAbsolutePath(), "--expose-internals", binJs.getAbsolutePath(),
                // dsh 的启动器只认自己的旗标在前：--profile/--patch 之后才是 web 应用自己的参数。
                "--profile", "web",
                "--patch", new File(new File(getFilesDir(), "plugin"), "dsh-workspace.yml").getAbsolutePath(),
                "--host", "127.0.0.1", "--port", "0", "--no-open"
        };
        StringBuilder argv = new StringBuilder();
        for (String a : args) argv.append(avrt(a)).append(' ');
        DshLog.p(TAG, "[dshtool] spawn argv=" + argv);
        DshLog.p(TAG, "[dshtool] cwd=" + workspace.getAbsolutePath());
        for (String k : new String[]{"PATH", "HOME", "NODE_OPTIONS", "DSH_HOME", "DSHTOOL_ROOT",
                "DSHTOOL_NODE", "DSHTOOL_PNPM_CJS", "DSHTOOL_NPM_CLI", "DSHTOOL_PTY_NODE",
                "DSHTOOL_PTY_HELPER", "DSHTOOL_SYSTEM_NODE", "LD_LIBRARY_PATH"}) {
            DshLog.p(TAG, "[dshtool] env " + k + "=" + env.get(k));
        }
        selfCheck(env, bundleDir);
        long t0 = System.currentTimeMillis();
        DshLog.i(TAG, "spawn cwd=" + workspace.getAbsolutePath());
        proc = spawn(args, env, workspace);
        DshLog.i(TAG, "pid=" + pidOf(proc));
        updateNotification("正在启动本地 Web 服务…");

        final Process p = proc;
        final int[] lines = {0};
        final StringBuilder recent = new StringBuilder();
        final boolean[] gotUrl = {false};
        final Object lock = new Object();
        Thread reader = new Thread(new Runnable() {
            @Override public void run() {
                BufferedReader r = null;
                try {
                    r = new BufferedReader(new InputStreamReader(p.getInputStream()), 8192);
                    String line;
                    while ((line = r.readLine()) != null) {
                        lines[0]++;
                        DshLog.raw(line);
                        synchronized (lock) {
                            recent.append(line).append('\n');
                            if (recent.length() > 4000) recent.delete(0, recent.length() - 4000);
                        }
                        Matcher m = URL_RE.matcher(line);
                        if (m.find()) {
                            DshLog.p(TAG, "[dshtool] web url=" + m.group());
                            publishUrl(m.group());
                            synchronized (lock) { gotUrl[0] = true; lock.notifyAll(); }

                        }
                    }
                } catch (Throwable t) {
                    DshLog.w(TAG, "读取子进程输出失败: " + t);
                } finally {
                    if (r != null) try { r.close(); } catch (Throwable ignored) { }
                    synchronized (lock) { lock.notifyAll(); }
                }
            }
        }, "dsh-stdout");
        reader.setDaemon(true);
        reader.start();

        // 30s 内没有 Web 地址就报错（不再无限白屏）；进程活着也显式给出最近输出。
        long deadline = System.currentTimeMillis() + 30000;
        synchronized (lock) {
            while (!gotUrl[0] && System.currentTimeMillis() < deadline && p.isAlive()) {
                try { lock.wait(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        if (!gotUrl[0]) {
            String tailText;
            synchronized (lock) { tailText = recent.toString(); }
            DshLog.e(TAG, "30s 内未输出 Web 地址。alive=" + p.isAlive() + " lines=" + lines[0]
                    + " 最近输出:\n" + tailText);
            try { p.destroy(); } catch (Throwable ignored) { }
            throw new IOException("30 秒内未输出 Web 地址（exit?=" + !p.isAlive() + "），最近输出: "
                    + (tailText.length() > 500 ? tailText.substring(tailText.length() - 500) : tailText));
        }
        int rc = proc.waitFor();
        proc = null;
        if (stopRequested) {
            DshLog.i(TAG, "dsh stopped by user, exit=" + rc);
            return;
        }
        throw new IOException("dsh 进程退出，exit=" + rc + "，输出 " + lines + " 行，"
                + "运行 " + ((System.currentTimeMillis() - t0) / 1000) + "s");
    }

    static String avrt(String a) {
        if (a == null) return "null";
        return a.indexOf(' ') >= 0 ? ("\"" + a + "\"") : a;
    }

    void publishUpdate(String phase, int pct, String msg) {
        UpdateSink sink = updateSink;
        if (sink != null) {
            try { sink.onUpdate(phase == null ? "" : phase, pct, msg == null ? "" : msg); }
            catch (Throwable ignored) { }
        }
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        // done/failed/available 都是「这一轮结束了」：状态行回到运行中，卡片才不会一直转。
        String label = phase == null ? "" : phase;
        boolean settled = "done".equals(label) || "failed".equals(label)
                || "available".equals(label) || "latest".equals(label) || "bootstrap".equals(label);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_STATE, settled ? "running" : "updating").putInt(KEY_PROGRESS, pct)
                .putString(KEY_PHASE, phase == null ? "" : phase)
                .putString(KEY_MSG, msg == null ? "" : msg).apply();
        Intent i = new Intent(ACTION_URL);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_PROGRESS, pct);
        i.putExtra(EXTRA_PHASE, phase == null ? "" : phase);
        i.putExtra(EXTRA_MSG, msg == null ? "" : msg);
        sendBroadcast(i);
        updateNotification(msg == null || msg.length() == 0 ? ("正在检查更新 " + pct + "%") : msg);
    }

    /** 更新器：<dsh>/bin/dsh-update.js（--auto 启动 10s；--update 手动长超时）。失败一律回退内置。 */
    /**
     * 工作区：/storage/emulated/0/Android/data/<pkg>/work —— 文件管理器里最短的那个路径。
     * 环境相关的一切（node 运行时、下载的 dsh 版本、DSH_HOME、插件、HOME）都在内部
     * /data/user/0/<pkg>/files 下，外部存储里只留工作区，避免“工作区里全是环境目录”。
     * 回退顺序：短路径 → 老路径 files/work → 内部 filesDir/work。
     */
    File resolveWorkspace() {
        File ext = null;
        try {
            ext = getExternalFilesDir(null);
        } catch (Throwable t) {
            DshLog.w(TAG, "getExternalFilesDir 抛异常: " + t);
        }
        File parent = ext == null ? null : ext.getParentFile();
        if (parent != null) {
            // 短路径：Android/data/<pkg>/work（files 的兄弟目录），比 files/work 少一级，也不再嵌套 deepseek-harness。
            File shortDir = new File(parent, "work");
            if (ensureDir(shortDir)) {
                File legacy = new File(ext, "work");
                if (!shortDir.equals(legacy) && legacy.isDirectory()) {
                    migrateWorkspace(legacy, shortDir);
                } else {
                    cleanupWorkspaceJunk(shortDir);
                }
                normalizeWorkspaceState(shortDir);
                DshLog.p(TAG, "[dshtool] workspace=" + shortDir.getAbsolutePath());
                return shortDir;
            }
            DshLog.w(TAG, "短路径工作区不可写: " + shortDir.getAbsolutePath() + "，回退 files/work");
        }
        if (ext != null) {
            File legacy = new File(ext, "work");
            if (ensureDir(legacy)) {
                cleanupWorkspaceJunk(legacy);
                normalizeWorkspaceState(legacy);
                DshLog.p(TAG, "[dshtool] workspace=external " + legacy.getAbsolutePath());
                return legacy;
            }
            DshLog.w(TAG, "外部工作区不可写: " + legacy.getAbsolutePath());
        }
        File inner = new File(getFilesDir(), "work");
        ensureDir(inner);
        normalizeWorkspaceState(inner);
        DshLog.p(TAG, "[dshtool] workspace=internal " + inner.getAbsolutePath());
        return inner;
    }

    /** mkdirs + 真写一次：FUSE 目录的 canWrite() 会给出乐观答案。 */
    private static boolean ensureDir(File dir) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            if (!dir.isDirectory()) return false;
            File probe = new File(dir, ".bigrice-write-test");
            boolean ok = probe.createNewFile() || probe.isFile();
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            return ok;
        } catch (Throwable t) {
            DshLog.w(TAG, "ensureDir 失败: " + dir + " : " + t);
            return false;
        }
    }

    /**
     * 老工作区 files/work → 短路径 work 的一次性迁移。
     * 老布局是 files/work/deepseek-harness/default-workspace，这里把它摊平到新根：
     * 用户以后点开工作区看到的就是 …/Android/data/<pkg>/work，没有中间那两级。
     * 同时清掉 storage/、data/ 这类“绝对路径被当相对路径拼进工作区”造出来的空目录树。
     */
    private void migrateWorkspace(File legacy, File target) {
        try {
            File defaultWs = new File(legacy, "deepseek-harness/default-workspace");
            boolean flattened = defaultWs.isDirectory();
            if (flattened) {
                File[] kids = defaultWs.listFiles();
                if (kids != null) for (File k : kids) moveInto(k, target);
            }
            File[] kids = legacy.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    String n = k.getName();
                    if ("deepseek-harness".equals(n) && flattened) {
                        deleteTree(k);
                    } else if (("storage".equals(n) || "data".equals(n)) && looksLikePathMirror(k)) {
                        deleteTree(k);
                    } else {
                        moveInto(k, target);
                    }
                }
            }
            //noinspection ResultOfMethodCallIgnored
            legacy.delete();
            resetWorkspaceState(legacy, target);
            DshLog.p(TAG, "[dshtool] workspace 迁移完成: " + legacy.getAbsolutePath() + " -> " + target.getAbsolutePath());
        } catch (Throwable t) {
            DshLog.w(TAG, "workspace 迁移失败（继续用新目录）: " + t);
        }
    }

    /** 删掉工作区里被错误拼出来的空目录树（<root>/storage/…、<root>/data/…）。 */
    private static void cleanupWorkspaceJunk(File root) {
        try {
            File[] kids = root.listFiles();
            if (kids == null) return;
            for (File k : kids) {
                String n = k.getName();
                if (("storage".equals(n) || "data".equals(n)) && looksLikePathMirror(k)) {
                    deleteTree(k);
                    DshLog.p(TAG, "[dshtool] 清理工作区里的路径镜像: " + k.getAbsolutePath());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 只有目录、没有任何文件的树 = 路径被拼错留下的空壳。 */
    private static boolean looksLikePathMirror(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File k : kids) {
            if (k.isDirectory()) {
                if (!looksLikePathMirror(k)) return false;
            } else {
                return false;
            }
        }
        return true;
    }

    private static void moveInto(File src, File dstDir) {
        File dst = new File(dstDir, src.getName());
        if (dst.exists()) {
            if (src.isDirectory() && dst.isDirectory()) {
                File[] kids = src.listFiles();
                if (kids != null) for (File k : kids) moveInto(k, dst);
                //noinspection ResultOfMethodCallIgnored
                src.delete();
                return;
            }
            deleteTree(dst);
        }
        if (src.renameTo(dst)) return;
        if (src.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dst.mkdirs();
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) moveInto(k, dst);
            deleteTree(src);
            return;
        }
        try {
            copyFile(src, dst);
            //noinspection ResultOfMethodCallIgnored
            src.delete();
        } catch (Throwable t) {
            DshLog.w(TAG, "move 失败: " + src + " : " + t);
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        File dir = dst.getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        try (InputStream in = new java.io.FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
    }

    /** dsh 的会话目录名：/a/b/c → --a-b-c--。 */
    private static String sessionDirName(String absolutePath) {
        String s = absolutePath.startsWith("/") ? absolutePath.substring(1) : absolutePath;
        return "--" + s.replace('/', '-') + "--";
    }

    /**
     * 迁移的第二步：把工作区注册表整体重写成“只有新工作区”这一条。
     * 不做字符串替换——老注册表里还有 dsh 在 HOME 下自动建的字母工作区（proot 时代是 /root/A，
     * 现在是 files/home/A），用户要的是“环境不要和工作区混在一起”，它们必须从列表里消失。
     * 旧路径下的会话也不能改名（zstd 头里记着 cwd），一律归档到 sessions-legacy/。
     * 只在迁移时调用一次。
     */
    private void resetWorkspaceState(File legacy, File target) {
        try {
            File home = new File(getFilesDir(), "dsh-home");
            String oldDefault = new File(legacy, "deepseek-harness/default-workspace").getAbsolutePath();
            String oldRoot = legacy.getAbsolutePath();
            File sessions = new File(home, "sessions");
            File[] dirs = sessions.listFiles();
            if (dirs != null) {
                // 会话日志的 zstd 头里写着 id 和 cwd，目录名必须和 cwd 一致，否则 dsh 判定
                // "corrupt session log" 并拒绝加载。所以这里不做改名，而是把旧路径下的会话
                // 归档到 sessions-legacy/（字节保留，dsh 不再扫描），新工作区从空会话开始。
                File legacySessions = new File(home, "sessions-legacy");
                String from = sessionDirName(oldDefault);
                String fromRoot = sessionDirName(oldRoot);
                for (File d : dirs) {
                    String n = d.getName();
                    boolean old = n.equals(from) || n.startsWith(fromRoot.substring(0, fromRoot.length() - 2) + "-");
                    if (!old) continue;
                    if (!legacySessions.isDirectory()) legacySessions.mkdirs();
                    File moved = new File(legacySessions, n);
                    if (moved.exists()) deleteTree(moved);
                    if (d.renameTo(moved)) {
                        DshLog.p(TAG, "[dshtool] 旧会话归档 " + n + " -> sessions-legacy/");
                    }
                }
            }
            writeWorkspaceRegistry(target, true);
        } catch (Throwable t) {
            DshLog.w(TAG, "改写工作区状态失败: " + t);
        }
    }

    /**
     * 每次启动校正工作区注册表：注册表应当只有当前工作区这一条。
     * 老版本升级上来时里面还有 dsh 自动建的字母工作区（proot 时代是 /root/A，现在落在 HOME 下），
     * 用户的要求是“环境不要和工作区混在一起”，所以这里收敛成一条；不属于当前工作区的
     * 旧会话目录也一并归档（目录名必须等于编码后的 cwd，改名会让 dsh 报 corrupt）。
     */
    private void normalizeWorkspaceState(File workspace) {
        try {
            String abs = workspace.getAbsolutePath();
            archiveForeignSessions(workspace);
            File reg = new File(new File(getFilesDir(), "dsh-home"), "storages/workspace.json");
            boolean ok = false;
            if (reg.isFile()) {
                String json = readAll(reg);
                ok = json != null && json.contains("\"path\": \"" + abs + "\"") && countOf(json, "\"path\"") == 1;
            }
            if (!ok) writeWorkspaceRegistry(workspace, true);
        } catch (Throwable t) {
            DshLog.w(TAG, "校正工作区注册表失败（保留原样）: " + t);
        }
    }

    /** 把不属于当前工作区的会话目录移到 sessions-legacy/（字节保留，dsh 不再扫描）。 */
    private void archiveForeignSessions(File workspace) {
        try {
            File home = new File(getFilesDir(), "dsh-home");
            File[] dirs = new File(home, "sessions").listFiles();
            if (dirs == null) return;
            String keep = sessionDirName(workspace.getAbsolutePath());
            File legacySessions = new File(home, "sessions-legacy");
            for (File d : dirs) {
                if (!d.isDirectory() || keep.equals(d.getName())) continue;
                if (!legacySessions.isDirectory()) legacySessions.mkdirs();
                File moved = new File(legacySessions, d.getName());
                if (moved.exists()) deleteTree(moved);
                if (d.renameTo(moved)) DshLog.p(TAG, "[dshtool] 归档其它工作区的会话 " + d.getName());
            }
        } catch (Throwable t) {
            DshLog.w(TAG, "归档旧会话失败: " + t);
        }
    }

    private static String readAll(File f) {
        if (f == null || !f.isFile()) return null;
        try (InputStream is = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > (1 << 20)) return null;
            }
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static int countOf(String hay, String needle) {
        int c = 0;
        int i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) {
            c++;
            i += needle.length();
        }
        return c;
    }

    /**
     * 写 dsh 自己的工作区注册表（unit workspace v2）：只留一条，path 就是这个目录，title 取目录名。
     * force=false 时已有注册表就不动（只在全新 DSH_HOME 用）；迁移时用 force=true 整体重写。
     */
    private void writeWorkspaceRegistry(File workspace, boolean force) {
        File home = new File(getFilesDir(), "dsh-home");
        File reg = new File(home, "storages/workspace.json");
        if (!force && reg.exists()) return;
        try {
            String id = java.util.UUID.randomUUID().toString();
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
            fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            String now = fmt.format(new java.util.Date());
            String title = workspace.getName();
            String json = "{\n"
                    + "  \"unit\": {\n"
                    + "    \"name\": \"workspace\",\n"
                    + "    \"version\": 2\n"
                    + "  },\n"
                    + "  \"global\": {\n"
                    + "    \"initialized\": true,\n"
                    + "    \"workspaceIds\": [\n"
                    + "      \"" + id + "\"\n"
                    + "    ],\n"
                    + "    \"archivedSessionIds\": [],\n"
                    + "    \"pinnedSessionIds\": [],\n"
                    + "    \"defaultWorkspaceId\": \"" + id + "\"\n"
                    + "  },\n"
                    + "  \"tables\": {\n"
                    + "    \"workspaces\": {\n"
                    + "      \"" + id + "\": {\n"
                    + "        \"path\": \"" + workspace.getAbsolutePath() + "\",\n"
                    + "        \"title\": \"" + title + "\",\n"
                    + "        \"sessionIds\": [],\n"
                    + "        \"createdAt\": \"" + now + "\",\n"
                    + "        \"updatedAt\": \"" + now + "\"\n"
                    + "      }\n"
                    + "    }\n"
                    + "  }\n"
                    + "}\n";
            File dir = reg.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            writeText(reg, json);
            DshLog.p(TAG, "[dshtool] 预置工作区 " + workspace.getAbsolutePath());
        } catch (Throwable t) {
            DshLog.w(TAG, "预置工作区失败（dsh 会自己建默认工作区）: " + t);
        }
    }

    /** 更新器：在（已迁移好的）工作区里跑 bin/dsh-update.js，进度逐行解析成广播。 */
    void runUpdater(File dshDir, File node, Map<String, String> env, String action, long timeoutMs) {
        updaterAction = action;
        checkSawResult = false;
        File updater = new File(dshDir, "bin/dsh-update.js");
        if (!updater.isFile()) return;
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(node.getAbsolutePath(), updater.getAbsolutePath(), action);
            File wd = workspace;
            pb.directory(wd != null ? wd : dshDir);
            pb.environment().putAll(env);
            pb.redirectErrorStream(true);
            p = pb.start();
            final BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()), 8192);
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    String line;
                    try { while ((line = r.readLine()) != null) parseUpdateLine(line); }
                    catch (Exception ignored) { }
                }
            }, "dsh-update");
            reader.start();
            boolean done = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
                DshLog.w(TAG, "更新进程超时（" + timeoutMs + "ms），保持当前版本");
                publishUpdate("failed", 0, "更新超时，已保持当前版本");
                return;
            }
            int rc = p.exitValue();
            if (rc != 0) {
                DshLog.w(TAG, "更新进程退出 rc=" + rc + "，保持当前版本");
                publishUpdate("failed", 0, "更新失败（rc=" + rc + "），已保持当前版本");
                return;
            }
            DshLog.i(TAG, "更新流程完成: " + action + " (rc=" + rc + ")");
            if ("--check".equals(action) && !checkSawResult) {
                String cur = runningVersion == null || runningVersion.isEmpty() ? "" : " " + runningVersion;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_AVAIL).apply();
                publishUpdate("latest", 100, "已是最新" + cur);
            }
        } catch (Throwable t) {
            DshLog.w(TAG, "更新检查失败（回退内置）: " + t);
        } finally {
            if (p != null) p.destroy();
        }
    }

    void parseUpdateLine(String line) {
        int i = line.indexOf('{'), j = line.lastIndexOf('}');
        if (i < 0 || j <= i) return;
        try {
            org.json.JSONObject o = new org.json.JSONObject(line.substring(i, j + 1));
            // --update 的结果行：{ok:true,version,dir,activated:true,...}
            if (o.optBoolean("ok", false) && o.has("version") && o.optBoolean("activated", false)) {
                String v = o.optString("version", "?");
                if ("--install-latest".equals(updaterAction)) {
                    // bootstrap：同一次冷启紧接着启动，禁止“重启后生效”文案。
                    publishUpdate("bootstrap", 100, "安装完成，正在启动…");
                    DshLog.p(TAG, "[dshtool] bootstrap installed version=" + v);
                } else {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_AVAIL)
                            .putString(KEY_UPDATED, v).apply();
                    publishUpdate("done", 100, "已更新到 " + v + "，正在重启 dsh…");
                }
                return;
            }
            // --check 的结果行：{current,latest,updateAvailable,...}
            if (o.optBoolean("updateAvailable", false)) {
                checkSawResult = true;
                String latest = o.optString("latest", "?");
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_AVAIL, latest).apply();
                publishUpdate("available", 0, "发现新版本 " + latest
                        + "（当前 " + o.optString("current", "?") + "），点“检查更新”升级");
                return;
            }
            if (o.has("current") && o.has("latest")) {
                // 没有 updateAvailable 的那条结果行：说明已经是最新。
                checkSawResult = true;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_AVAIL).apply();
                publishUpdate("latest", 100, "已是最新 " + o.optString("current", "?"));
                return;
            }
            String phase = o.optString("phase", "");
            int pct = o.optInt("pct", -1);
            String msg = o.optString("msg", "");
            if (phase.length() == 0 && pct < 0) return;
            publishUpdate(phase, pct < 0 ? 0 : pct, msg);
        } catch (Throwable ignored) { }
    }

    /**
     * 读 active.json：{"version","dir","updatedAt"}，是**唯一启动源**。
     * 无文件/字段缺失/目录缺 bin.js 一律返回 null（不再回退内置 bundle——bootstrap 后内置 dsh 已不存在）。
     */
    File resolveActiveBundle(File dshDir) {
        File active = new File(dshDir, "active.json");
        if (!active.isFile()) {
            DshLog.i(TAG, "无 active.json（首次启动）");
            return null;
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject(readFile(active));
            String version = o.optString("version", "");
            String dir = o.optString("dir", "");
            if (version.trim().length() == 0 || dir.trim().length() == 0) {
                DshLog.w(TAG, "active.json 字段缺失");
                return null;
            }
            File d = new File(dir);
            if (!d.isAbsolute()) d = new File(dshDir, dir);
            if (!new File(d, "lib/node_modules/@deepseek-ai/dsh/lib/bin.js").isFile()) {
                DshLog.w(TAG, "active.json 目录不可用: " + d);
                return null;
            }
            activeVersion = version;
            DshLog.i(TAG, "使用 active 版本 " + version + " @ " + d);
            return d;
        } catch (Throwable t) {
            DshLog.w(TAG, "active.json 损坏: " + t);
            return null;
        }
    }

    /** 启动前实测 `node <bundle>/lib/.../bin.js --version`，用于和 active.json 比对。 */
    String probeVersion(File node, File binJs, Map<String, String> env) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(node.getAbsolutePath(), "--expose-internals",
                    binJs.getAbsolutePath(), "--version");
            File wd = workspace;
            pb.directory(wd != null ? wd : getFilesDir());
            pb.environment().putAll(env);
            pb.redirectErrorStream(true);
            p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()), 512);
            String first = r.readLine();
            boolean done = p.waitFor(8000, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) { p.destroyForcibly(); return null; }
            if (p.exitValue() != 0) return null;
            return first == null ? null : first.trim();
        } catch (Throwable t) {
            DshLog.w(TAG, "probeVersion 失败: " + t);
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** 安装/更新成功后清理旧版本与缓存（由 updater 的 --cleanup 执行，壳只触发并记录释放量）。 */
    void runCleanup(File dshDir, File node, Map<String, String> env) {
        File updater = new File(dshDir, "bin/dsh-update.js");
        if (!updater.isFile()) return;
        Process p = null;
        try {
            publishUpdate("cleanup", 100, "清理旧版本与缓存…");
            ProcessBuilder pb = new ProcessBuilder(node.getAbsolutePath(), updater.getAbsolutePath(), "--cleanup");
            File wd = workspace;
            pb.directory(wd != null ? wd : dshDir);
            pb.environment().putAll(env);
            pb.redirectErrorStream(true);
            p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()), 8192);
            String line, last = null;
            long end = System.currentTimeMillis() + 60000;
            while ((line = r.readLine()) != null) {
                DshLog.i("dsh-cleanup", line);
                last = line;
                if (System.currentTimeMillis() > end) { p.destroyForcibly(); break; }
            }
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (last != null) DshLog.i(TAG, "cleanup: " + last);
            publishUpdate("cleanup", 100, "清理完成");
        } catch (Throwable t) {
            DshLog.w(TAG, "清理失败（不阻塞启动）: " + t);
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** PATH / DSHTOOL 系 / NODE_OPTIONS / LD_LIBRARY_PATH 指向实际使用的 bundle（更新目录优先）。 */
    void retargetEnv(Map<String, String> env, File dshDir, File bundleDir) {
        env.put("PATH", new File(bundleDir, "bin").getAbsolutePath()
                + ":" + new File(dshDir, "bin").getAbsolutePath()
                + ":/system/bin:/system/xbin:/product/bin:/apex/com.android.runtime/bin");
        File pnpm = pickExisting(dshDir, bundleDir, "lib/node_modules/pnpm/bin/pnpm.cjs");
        File npm = pickExisting(dshDir, bundleDir, "lib/node_modules/npm/bin/npm-cli.js");
        File shim = pickExisting(dshDir, bundleDir, "bin/dshtool-noexec-shim.cjs");
        if (pnpm != null) env.put("DSHTOOL_PNPM_CJS", pnpm.getAbsolutePath());
        if (npm != null) env.put("DSHTOOL_NPM_CLI", npm.getAbsolutePath());
        if (shim != null) {
            String req = "--require " + shim.getAbsolutePath();
            String prev = env.get("NODE_OPTIONS");
            if (prev == null || !prev.contains(req)) {
                env.put("NODE_OPTIONS", (prev == null || prev.trim().length() == 0) ? req : prev + " " + req);
            }
        }
        env.put("LD_LIBRARY_PATH", getApplicationInfo().nativeLibraryDir + ":"
                + new File(bundleDir, "lib").getAbsolutePath());
    }

    static File pickExisting(File dshDir, File bundleDir, String rel) {
        File a = new File(bundleDir, rel);
        if (a.isFile()) return a;
        File b = new File(dshDir, rel);
        return b.isFile() ? b : null;
    }

    static String readFile(File f) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        InputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } finally { in.close(); }
        return new String(bos.toByteArray(), "UTF-8");
    }

    /** web 就绪后：若 pref autoUpdate（默认开）且 check 发现新版本 → 后台跑 --update（15 分钟，不阻塞对话）。 */

    /**
     * 真机 noexec 自检：app data 里的 bin/* 不能直接 exec（EACCES），
     * 但 `sh bin/npm -v` 由 /system/bin/sh 解释执行是可行的。启动时各跑一次并写 logcat，
     * 便于在真机上直接判断 npm/npx/pnpm 与注入的 prefix 是否生效。
     */
    void selfCheck(Map<String, String> env, File dshDir) {
        String[][] cases = {
                {"npm",  "bin/npm"},
                {"npx",  "bin/npx"},
                {"pnpm", "bin/pnpm"}
        };
        for (String[] c : cases) {
            File script = new File(dshDir, c[1]);
            if (!script.isFile()) { DshLog.e(TAG, "selfcheck " + c[0] + ": 缺少 " + script); continue; }
            Process p = null;
            try {
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", script.getAbsolutePath(), "-v");
                File wd = workspace;
                pb.directory(wd != null ? wd : dshDir);
                pb.redirectErrorStream(true);
                pb.environment().putAll(env);
                p = pb.start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()), 512);
                String out = r.readLine();
                boolean done = p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
                if (!done) p.destroyForcibly();
                int rc = done ? p.exitValue() : -1;
                String text = out == null ? "" : out.trim();
                if (rc != 0 || text.length() == 0) {
                    DshLog.w(TAG, "selfcheck " + c[0] + " 失败: rc=" + rc + " out=" + (text.length() == 0 ? "<empty>" : text));
                }
            } catch (Exception e) {
                DshLog.e(TAG, "selfcheck " + c[0] + " 失败: " + e);
            } finally {
                if (p != null) p.destroy();
            }
        }
    }

    /**
     * 先直接 exec nativeLibraryDir/libnode.so；若被 EACCES/ENOEXEC 拒绝（部分 ROM 的
     * native 库没有 x 位），改用 /system/bin/linker64 装载同一个 ELF（只需读权限）。
     */
    Process spawn(String[] args, Map<String, String> extraEnv, File cwd) throws IOException {
        try {
            return start(args, extraEnv, cwd);
        } catch (IOException e) {
            File linker = new File("/system/bin/linker64");
            if (!linker.isFile() || args.length < 2) throw e;
            DshLog.e(TAG, "direct exec failed (" + e + ")，回退 linker64 装载");
            String[] alt = new String[args.length + 1];
            alt[0] = linker.getAbsolutePath();
            System.arraycopy(args, 0, alt, 1, args.length);
            return start(alt, extraEnv, cwd);
        }
    }

    Process start(String[] args, Map<String, String> extraEnv, File cwd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(cwd);
        pb.redirectErrorStream(true);
        // stdin 接 /dev/null：Tauri 侧是 Stdio::null()；Java 默认给子进程一个永不 EOF 的管道，
        // 若 dsh 启动阶段读 stdin（TTY 检测/readline），会静默挂住、不打印任何输出。
        File devNull = new File("/dev/null");
        if (devNull.exists()) {
            pb.redirectInput(ProcessBuilder.Redirect.from(devNull));
        }
        pb.environment().putAll(extraEnv);
        Process proc = pb.start();
        try { proc.getOutputStream().close(); } catch (Throwable ignored) { } // 双保险：立刻 EOF
        return proc;
    }

    void ensureBundle(File dshDir, File binJs) throws IOException {
        File stampFile = new File(getFilesDir(), ".bundle-stamp");
        String stamp = packageStamp();
        String have = readText(stampFile);
        // bootstrap 模式下 assets 不含 @deepseek-ai/dsh（binJs == null），底座以 dsh-update.js 为准。
        boolean baseOk = new File(dshDir, "bin/dsh-update.js").isFile()
                || new File(dshDir, "bin/dshtool").isFile();
        boolean need = !baseOk || !stamp.equals(have) || (binJs != null && !binJs.isFile());
        if (!need) {
            DshLog.i(TAG, "bundle ok at " + dshDir + " (stamp " + stamp + ")");
            return;
        }
        DshLog.i(TAG, "extracting assets/dshtool.zip → " + dshDir + " (stamp " + stamp + ")");
        ensureFreeSpace(dshDir.isDirectory() ? 800L : 440L);
        File tmp = new File(getFilesDir(), "dsh.tmp");
        deleteRecursively(tmp);
        if (!tmp.mkdirs()) throw new IOException("无法创建解压目录 " + tmp);
        publishProgress(0);
        long t0 = System.currentTimeMillis();
        long bytes = unzipAsset("dshtool.zip", tmp, new ZipProgress() {
            @Override public void onProgress(int pct) { publishProgress(pct); }
        });
        DshLog.i(TAG, "extract done: " + bytes + " bytes in " + (System.currentTimeMillis() - t0) + " ms");
        publishProgress(100);
        File tmpBin = new File(tmp, "lib/node_modules/@deepseek-ai/dsh/lib/bin.js");
        File tmpUpdater = new File(tmp, "bin/dsh-update.js");
        if (!tmpBin.isFile() && !tmpUpdater.isFile()) {
            deleteRecursively(tmp);
            throw new IOException("assets/dshtool.zip 解压后既无 dsh bin.js 也无 bin/dsh-update.js");
        }
        if (tmpBin.isFile()) DshLog.i(TAG, "assets 内置 dsh（非 bootstrap）");
        else DshLog.i(TAG, "bootstrap assets：不含 dsh，首启将联网安装");
        // 升级路径：先递归 setWritable（packager 的 bundle 文件是 444），再删旧树；
        // 删不掉就改名 dsh.old.<ts> 让位；versions/ 与 active.json 必须跨升级保留。
        File keep = new File(getFilesDir(), "dsh.keep." + System.currentTimeMillis());
        File versionsSrc = new File(dshDir, "versions");
        File activeSrc = new File(dshDir, "active.json");
        boolean haveVersions = versionsSrc.isDirectory();
        boolean haveActive = activeSrc.isFile();
        if (haveVersions || haveActive) {
            if (!keep.mkdirs()) {
                deleteRecursively(tmp);
                throw new IOException("无法创建升级暂存目录 " + keep);
            }
            if (haveVersions) {
                if (!versionsSrc.renameTo(new File(keep, "versions"))) {
                    deleteRecursively(tmp);
                    throw new IOException("无法暂存 " + versionsSrc + "（保留旧树，避免丢已装版本）");
                }
                DshLog.i(TAG, "暂存 versions → " + keep);
            }
            if (haveActive) {
                if (!activeSrc.renameTo(new File(keep, "active.json"))) {
                    DshLog.w(TAG, "active.json 暂存失败（继续，bootstrap 会重建）");
                }
            }
        }

        File old = new File(getFilesDir(), "dsh.old." + System.currentTimeMillis());
        if (dshDir.exists()) {
            makeWritable(dshDir);
            if (deleteTree(dshDir)) {
                DshLog.i(TAG, "旧 dsh 树已删除（setWritable 后）");
            } else if (dshDir.renameTo(old)) {
                DshLog.w(TAG, "旧 dsh 树删除失败，已改名 " + old + "（后台 best-effort 删除）");
            } else {
                restoreKeep(keep, dshDir);
                deleteRecursively(tmp);
                throw new IOException("无法删除或改名旧 dsh 树: " + dshDir + "（保留现场供诊断）");
            }
        }

        if (!tmp.renameTo(dshDir)) {
            // 只做一次回退：清掉可能残留的空壳目录再改名，仍失败才报错。
            makeWritable(dshDir);
            deleteTree(dshDir);
            if (!tmp.renameTo(dshDir)) {
                restoreKeep(keep, dshDir);
                throw new IOException("无法把解压结果移动到 " + dshDir
                        + (old.exists() ? "（旧树保留在 " + old + " 供诊断）" : ""));
            }
        }
        restoreKeep(keep, dshDir);
        if (keep.exists()) backgroundDelete(keep);
        if (old.exists()) backgroundDelete(old);

        if (!new File(dshDir, "bin/dsh-update.js").isFile()
                || !(new File(dshDir, "bin/dshtool").isFile()
                     || new File(dshDir, "lib/node_modules/@deepseek-ai/dsh/lib/bin.js").isFile())) {
            throw new IOException("新 dsh 树缺少 bin/dsh-update.js 或启动器");
        }
        writeText(stampFile, stamp);
        writeNodeShim(dshDir);
        DshLog.i(TAG, "bundle installed: " + bytes + " bytes, stamp=" + stamp);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_STATE, "starting").putInt(KEY_PROGRESS, -1).apply();
    }

    String packageStamp() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            return pi.lastUpdateTime + ":" + pi.versionName;
        } catch (Throwable t) {
            return "0:unknown";
        }
    }

    /**
     * assets 里不含 bin/node（node 以 libnode.so 放在 nativeLibraryDir），
     * 但 bundle 的 bin/npm、bin/npx、bin/pnpm wrapper 都是 exec "$DIR/node"。
     * 这里补一个可执行 shim，让插件进程（plugin-manager 会 spawn pnpm）能通过 PATH 找到 node。
     */
    /**
     * pnpm 默认用 hardlink 把包从 store 链到 node_modules；Android SELinux 对 app_data_file
     * 拒绝 link(2)（真机实测 EACCES），必须 copy。store 也放到可写的 HOME 下。
     */
    void writeNpmrc(File userHome) {
        String want = "package-import-method=copy\n"
                + "prefix=" + new File(userHome, ".npm-global").getAbsolutePath() + "\n"
                + "cache=" + new File(userHome, ".npm").getAbsolutePath() + "\n"
                + "store-dir=" + new File(userHome, ".pnpm-store").getAbsolutePath() + "\n";
        File rc = new File(userHome, ".npmrc");
        String have = readText(rc);
        if (want.equals(have)) return;
        writeText(rc, want);
        DshLog.i(TAG, "wrote npmrc: " + want.replace("\n", "; "));
    }

    void writeNodeShim(File dshDir) {
        File libnode = new File(getApplicationInfo().nativeLibraryDir, "libnode.so");
        if (!libnode.isFile()) return;
        File shim = new File(dshDir, "bin/node");
        String want = "#!/system/bin/sh\nexec \"" + libnode.getAbsolutePath() + "\" \"$@\"\n";
        if (shim.isFile() && want.equals(readText(shim))) {
            if (!shim.canExecute()) shim.setExecutable(true, false);
            return;
        }
        File parent = shim.getParentFile();
        if (parent != null) parent.mkdirs();
        writeText(shim, want);
        shim.setExecutable(true, false);
        shim.setReadable(true, false);
        DshLog.i(TAG, "node shim: " + shim + " -> " + libnode);
    }

    static void putIfExists(Map<String, String> env, String key, File f) {
        if (f.isFile()) env.put(key, f.getAbsolutePath());
    }

    File resolveNode(File dshDir) throws IOException {
        ApplicationInfo ai = getApplicationInfo();
        File libnode = new File(ai.nativeLibraryDir, "libnode.so");
        if (libnode.isFile()) {
            DshLog.i(TAG, "node = " + libnode + " (" + libnode.length() + " bytes, native dir)");
            return libnode;
        }
        File bundled = new File(dshDir, "bin/node");
        if (bundled.isFile()) {
            DshLog.i(TAG, "node = " + bundled + " (" + bundled.length() + " bytes, bundle)");
            if (Build.VERSION.SDK_INT >= 29) {
                DshLog.e(TAG, "警告: filesDir 中的可执行文件在 Android 10+ 受 SELinux W^X 限制，"
                        + "正常安装的 APK 应带 nativeLibraryDir/libnode.so");
            }
            return bundled;
        }
        throw new IOException("找不到 node：nativeLibraryDir/libnode.so 与 dsh/bin/node 都不存在");
    }

    /** 解压 379 MiB 前先确认空间，避免低端机装到一半 ENOSPC 只留一堆半截文件。 */
    void ensureFreeSpace(long needMiB) throws IOException {
        try {
            android.os.StatFs sf = new android.os.StatFs(getFilesDir().getAbsolutePath());
            long free = sf.getAvailableBytes();
            DshLog.i(TAG, "free space: " + (free / 1048576) + " MiB, need " + needMiB + " MiB");
            if (free < needMiB * 1048576L) {
                throw new IOException("存储空间不足：剩余 " + (free / 1048576) + " MiB，首次启动需要约 "
                        + needMiB + " MiB；请清理后重试");
            }
        } catch (IllegalArgumentException ignored) {
            // StatFs 在极少数路径上可能失败，不阻断启动
        }
    }

    long unzipAsset(String asset, File dest, ZipProgress progress) throws IOException {
        long total = 0;
        int entries = 0;
        long assetLen = 0;
        try {
            android.content.res.AssetFileDescriptor afd = getAssets().openFd(asset);
            assetLen = afd.getLength();
            afd.close();
        } catch (IOException ignored) { /* 压缩存储时拿不到长度，进度按解压字节兜底 */ }
        java.io.InputStream raw;
        try {
            raw = getAssets().open(asset);
        } catch (IOException e) {
            throw new IOException("APK 内缺少 assets/" + asset + "（这个包只用于壳验证？）", e);
        }
        CountingInputStream counted = new CountingInputStream(new BufferedInputStream(raw, 65536));
        ZipInputStream zis = new ZipInputStream(counted);
        int lastPct = -1;
        try {
            byte[] buf = new byte[65536];
            ZipEntry e;
            String destCanon = dest.getCanonicalPath() + File.separator;
            while ((e = zis.getNextEntry()) != null) {
                File out = new File(dest, e.getName());
                if (!out.getCanonicalPath().startsWith(destCanon)) continue; // zip-slip 防护
                if (e.isDirectory()) { out.mkdirs(); continue; }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                OutputStream os = new FileOutputStream(out);
                try {
                    int n;
                    while ((n = zis.read(buf)) > 0) { os.write(buf, 0, n); total += n; }
                } finally {
                    os.close();
                }
                entries++;
                if (entries % 2000 == 0) DshLog.i(TAG, "extract " + entries + " files, " + (total / 1048576) + " MiB");
                if (assetLen > 0 && progress != null) {
                    int pct = (int) (counted.count * 100 / assetLen);
                    if (pct >= lastPct + 2) { lastPct = pct; progress.onProgress(Math.min(pct, 99)); }
                }
                String name = e.getName();
                if (name.endsWith(".node") || name.endsWith(".so") || name.startsWith("bin/")) {
                    out.setExecutable(true, false);
                    out.setReadable(true, false);
                }
            }
        } finally {
            zis.close();
        }
        return total;
    }


    /** 递归置可写（packager 的 bundle 文件常为 444，直接 delete 会失败）。 */
    static void makeWritable(File f) {
        if (f == null || !f.exists()) return;
        try { if (!f.setWritable(true, false)) Log.w(TAG, "setWritable 失败: " + f); } catch (Throwable ignored) { }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) makeWritable(k);
        }
    }

    /** 递归删除并返回是否全部成功（比 deleteRecursively 多了可写位修复与失败返回）。 */
    static boolean deleteTree(File f) {
        if (f == null || !f.exists()) return true;
        boolean ok = true;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) ok &= deleteTree(k);
        }
        try { f.setWritable(true, false); } catch (Throwable ignored) { }
        if (!f.delete()) { Log.w(TAG, "failed to delete " + f); ok = false; }
        return ok;
    }

    /** 把暂存的 versions/active.json 放回新树；失败只 W（bootstrap 可重建，但不丢已装版本优先）。 */
    void restoreKeep(File keep, File dshDir) {
        if (keep == null || !keep.isDirectory()) return;
        File v = new File(keep, "versions");
        if (v.isDirectory()) {
            File dst = new File(dshDir, "versions");
            if (!v.renameTo(dst)) {
                deleteRecursively(dst);
                if (v.renameTo(dst)) DshLog.i(TAG, "恢复 versions 完成");
                else DshLog.e(TAG, "恢复 versions 失败（已装版本可能需重新下载）: " + v);
            } else {
                DshLog.i(TAG, "恢复 versions 完成");
            }
        }
        File a = new File(keep, "active.json");
        if (a.isFile()) {
            File dst = new File(dshDir, "active.json");
            if (!a.renameTo(dst)) DshLog.w(TAG, "恢复 active.json 失败（会重新 bootstrap）");
        }
    }

    /** 后台 best-effort 删除旧树/暂存目录，失败只 W，不阻塞启动。 */
    void backgroundDelete(final File target) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    makeWritable(target);
                    if (deleteTree(target)) DshLog.i(TAG, "后台删除完成: " + target);
                    else DshLog.w(TAG, "后台删除未完全成功: " + target);
                } catch (Throwable t) {
                    DshLog.w(TAG, "后台删除异常（忽略）: " + target + " : " + t);
                }
            }
        }, "dsh-cleanup-tree").start();
    }

    static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursively(k);
        }
        if (!f.delete()) Log.w(TAG, "failed to delete " + f);
    }

    static String readText(File f) {
        if (f == null || !f.isFile()) return null;
        InputStream is = null;
        try {
            is = new java.io.FileInputStream(f);
            byte[] b = new byte[(int) Math.min(f.length(), 4096)];
            int n = is.read(b);
            return n <= 0 ? "" : new String(b, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (is != null) try { is.close(); } catch (Throwable ignored) { }
        }
    }

    static void writeText(File f, String s) {
        OutputStream os = null;
        try {
            os = new FileOutputStream(f);
            os.write(s.getBytes("UTF-8"));
        } catch (Throwable t) {
            Log.w(TAG, "write " + f + " failed: " + t);
        } finally {
            if (os != null) try { os.close(); } catch (Throwable ignored) { }
        }
    }

    static String pidOf(Process p) {
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return String.valueOf(f.getInt(p));
        } catch (Throwable t) {
            return "?";
        }
    }

    @Override public void onDestroy() {
        live = null;
        DshLog.i(TAG, "service onDestroy");
        stopRequested = true;
        try { stopForeground(true); } catch (Throwable ignored) { }
        if (proc != null) {
            try { proc.destroy(); } catch (Throwable t) { }
        }
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }
}
