package com.bigrice;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

/**
 * 极小的日志总线：node 的 stdout/stderr、服务启动步骤、WebView 错误都写到这里。
 * 同时写 Logcat（tag=dshtool / dsh）和 filesDir/log/dshtool.log，页面顶部的
 * "日志" 面板直接读 {@link #tail(int)}，这样没有 adb 也能看到失败原因。
 */
final class DshLog {
    private static final int MAX_LINES = 500;
    private static final long MAX_FILE = 512 * 1024;
    private static final LinkedList<String> LINES = new LinkedList<String>();
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private static File file;
    private static long written;

    private DshLog() { }

    static synchronized void init(File dir) {
        if (file != null || dir == null) return;
        File d = new File(dir, "log");
        if (!d.isDirectory() && !d.mkdirs()) return;
        file = new File(d, "dshtool.log");
        written = file.length();
        if (written > MAX_FILE) {
            if (!file.delete()) written = 0;
        }
    }

    /** verbose 门控：release/默认只在 logcat 落 WARN/ERROR；I 仅进内存环形缓冲。 */
    static volatile boolean verbose = false;

    static void i(String tag, String msg) { write("I", tag, msg); }

    /** 策略行：release 也强制落 logcat（仅关键启动事实，不做通用日志）。 */
    static void p(String tag, String msg) { write("P", tag, msg); }

    /** 子进程输出：release 也落 logcat（用 android.util.Log 直写，正常前缀），不进文件、不进环形缓冲。 */
    static void raw(String msg) {
        if (msg == null) return;
        int n = msg.length();
        if (n > 900) msg = msg.substring(0, 900) + "…";
        Log.i("dshtool", "[dsh-out] " + msg);
    }

    static void w(String tag, String msg) { write("W", tag, msg); }

    static void e(String tag, String msg) { write("E", tag, msg); }

    private static synchronized void write(String level, String tag, String msg) {
        String line = TS.format(new Date()) + " " + level + "/" + tag + " " + (msg == null ? "" : msg);
        if (LINES.size() >= MAX_LINES) LINES.removeFirst();
        LINES.addLast(line);
        if ("E".equals(level)) Log.e("dshtool", line);
        else if ("W".equals(level)) Log.w("dshtool", line);
        else if ("P".equals(level) || verbose) Log.i("dshtool", line);
        if (file == null || (!"E".equals(level) && !"W".equals(level) && !"P".equals(level))) return;
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8");
            w.write(line);
            w.write('\n');
            w.flush();
            written += line.getBytes("UTF-8").length + 1;
            if (written > MAX_FILE) { } // 轮转在下一次 init 时做，避免频繁删写
        } catch (IOException ignored) {
        } finally {
            if (w != null) try { w.close(); } catch (IOException ignored) { }
        }
    }

    static synchronized java.io.File file() { return file; }

    static synchronized String lines(int maxLines) {
        int from = Math.max(0, LINES.size() - Math.max(1, maxLines));
        StringBuilder out = new StringBuilder();
        for (String line : LINES.subList(from, LINES.size())) out.append(line).append('\n');
        return out.toString();
    }

    static synchronized String tail(int n) {
        int from = Math.max(0, LINES.size() - Math.max(0, n));
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < LINES.size(); i++) {
            sb.append(LINES.get(i)).append('\n');
        }
        return sb.toString();
    }

    static synchronized void clear() {
        LINES.clear();
        if (file != null && file.exists()) file.delete();
        written = 0;
    }
}
