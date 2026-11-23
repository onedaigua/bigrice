package com.bigrice;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The bridge the phone UI uses for anything a web page cannot do: reading and
 * listing files of the session workspace, running a shell line, exporting a zip
 * and reading the service logs.
 *
 * <p>Every method runs on the WebView's JavaScript bridge thread, so it only
 * touches files, processes and prefs — never views. Anything that must touch the
 * UI is posted to the activity's handler.
 *
 * <p>Path model: the page works with paths relative to the session workspace
 * ({@code /storage/emulated/0/Android/data/com.bigrice/files/work}) and calls
 * that root {@code /root}, matching what the shell console shows.
 */
final class HostBridge {
    private static final String TAG = "dsh";
    private static final String PREFS = "bigrice";
    private static final String EXPORT_TREE_KEY = "exportTree";
    private static final String IMPORT_DIR_KEY = "importDir";
    private static final int LOG_WINDOW = 200_000;
    private static final int SHELL_TIMEOUT_MS = 120_000;
    private static final int SHELL_MAX_BYTES = 1 << 19;

    private final MainActivity activity;
    private final AtomicInteger shellSeq = new AtomicInteger();
    private final ConcurrentHashMap<String, Process> shells = new ConcurrentHashMap<String, Process>();
    private final ConcurrentHashMap<String, Boolean> stopped = new ConcurrentHashMap<String, Boolean>();

    HostBridge(MainActivity activity) {
        this.activity = activity;
    }

    // ------------------------------------------------------------------ small UI

    @android.webkit.JavascriptInterface
    public void toast(final String message) {
        if (message == null || message.isEmpty()) return;
        activity.toast(message);
    }

    @android.webkit.JavascriptInterface
    public void hideKeyboard() {
        activity.hideKeyboard();
    }

    @android.webkit.JavascriptInterface
    public String appInfo() {
        return "{\"versionName\":" + json(activity.versionName())
                + ",\"versionCode\":" + activity.versionCode()
                + ",\"package\":" + json(activity.getPackageName()) + "}";
    }

    // ------------------------------------------------------------------- files

    private File root() {
        File dir = DshService.workspaceDir();
        if (dir == null) dir = activity.getFilesDir();
        if (!dir.isDirectory() && !dir.mkdirs()) return dir;
        return dir;
    }

    /** Workspace-relative path → file, refusing anything that escapes the root. */
    private File resolve(String rel) {
        String clean = normalize(rel);
        if (clean == null) return null;
        File base = root();
        File target = clean.isEmpty() ? base : new File(base, clean);
        try {
            String rootPath = base.getCanonicalPath();
            String targetPath = target.getCanonicalPath();
            if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
                return null;
            }
            return new File(targetPath);
        } catch (IOException e) {
            return null;
        }
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        String text = raw.replace('\\', '/').trim();
        StringBuilder out = new StringBuilder(text.length());
        for (String segment : text.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) return null;
            if (segment.indexOf('\0') >= 0) return null;
            if (out.length() > 0) out.append('/');
            out.append(segment);
        }
        return out.toString();
    }

    /**
     * 页面给的路径 → 工作区内的相对路径。两种写法都认：
     * 客机坐标（`/root/B`、`root/B`、`B`）和 `root()` 内部的绝对主机路径；
     * 工作区外的绝对路径拒绝——以前把它当相对串拼进来，会在工作区里长出
     * storage/emulated/0/… 这样的镜像目录树。
     */
    private String rel(String raw) {
        String clean = normalize(raw);
        if (clean == null) return null;
        if (clean.equals("root")) return "";
        if (clean.startsWith("root/")) return clean.substring("root/".length());
        if (raw != null && raw.startsWith("/")) {
            String basePath = normalize(root().getAbsolutePath());
            if (basePath == null) return null;
            if (clean.equals(basePath)) return "";
            if (!clean.startsWith(basePath + "/")) return null;
            return clean.substring(basePath.length() + 1);
        }
        return clean;
    }

    @android.webkit.JavascriptInterface
    public String listDir(final String path, final boolean hidden) {
        String clean = rel(path);
        if (clean == null) return "{\"ok\":false,\"error\":\"invalid path\"}";
        StringBuilder out = new StringBuilder(2048);
        out.append("{\"path\":").append(json(clean))
                .append(",\"guest\":").append(json(clean.isEmpty() ? "/root" : "/root/" + clean));
        int slash = clean.lastIndexOf('/');
        out.append(",\"parent\":").append(json(slash < 0 ? "" : clean.substring(0, slash)));
        File dir = resolve(clean);
        if (dir == null || !dir.isDirectory()) {
            out.append(",\"ok\":false,\"missing\":true,\"entries\":[]}");
            return out.toString();
        }
        File[] children = dir.listFiles();
        if (children == null) children = new File[0];
        Arrays.sort(children, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        out.append(",\"ok\":true,\"missing\":false,\"entries\":[");
        boolean first = true;
        for (File child : children) {
            String name = child.getName();
            if (!hidden && name.startsWith(".")) continue;
            if (!first) out.append(',');
            first = false;
            String childRel = clean.isEmpty() ? name : clean + "/" + name;
            out.append("{\"name\":").append(json(name))
                    .append(",\"rel\":").append(json(childRel))
                    .append(",\"dir\":").append(child.isDirectory())
                    .append(",\"bytes\":").append(child.isDirectory() ? 0 : child.length())
                    .append('}');
        }
        return out.append("]}").toString();
    }

    @android.webkit.JavascriptInterface
    public String readText(final String path, final int maxBytes) {
        File file = resolve(rel(path));
        if (file == null) return "{\"ok\":false,\"error\":\"invalid path\"}";
        if (!file.isFile()) return "{\"ok\":false,\"error\":\"missing\"}";
        int limit = Math.max(1024, Math.min(maxBytes <= 0 ? 262_144 : maxBytes, 524_288));
        long length = file.length();
        byte[] buf;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            buf = new byte[(int) Math.min(length, limit)];
            raf.readFully(buf);
        } catch (IOException e) {
            return "{\"ok\":false,\"error\":\"read\"}";
        }
        for (byte b : buf) {
            if (b == 0) return "{\"ok\":false,\"error\":\"binary\",\"bytes\":" + length + "}";
        }
        String text = new String(buf, StandardCharsets.UTF_8);
        return "{\"ok\":true,\"bytes\":" + length
                + ",\"truncated\":" + (length > buf.length)
                + ",\"text\":" + json(text) + "}";
    }

    @android.webkit.JavascriptInterface
    public String readImage(final String path) {
        File file = resolve(rel(path));
        if (file == null) return "{\"ok\":false,\"error\":\"invalid path\"}";
        if (!file.isFile()) return "{\"ok\":false,\"error\":\"missing\"}";
        String name = file.getName().toLowerCase(Locale.ROOT);
        String mime = null;
        if (name.endsWith(".png")) mime = "image/png";
        else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) mime = "image/jpeg";
        else if (name.endsWith(".webp")) mime = "image/webp";
        else if (name.endsWith(".gif")) mime = "image/gif";
        else if (name.endsWith(".bmp")) mime = "image/bmp";
        if (mime == null) return "{\"ok\":false,\"error\":\"unsupported\"}";
        if (file.length() > 12L * 1024 * 1024) {
            return "{\"ok\":false,\"error\":\"too-big\",\"bytes\":" + file.length() + "}";
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] bytes = new byte[(int) file.length()];
            raf.readFully(bytes);
            String b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            return "{\"ok\":true,\"mime\":\"" + mime + "\",\"bytes\":" + bytes.length
                    + ",\"data\":\"data:" + mime + ";base64," + b64 + "\"}";
        } catch (IOException e) {
            return "{\"ok\":false,\"error\":\"read\"}";
        }
    }

    @android.webkit.JavascriptInterface
    public String deleteEntry(final String path) {
        String clean = rel(path);
        if (clean == null || clean.isEmpty()) return "{\"ok\":false,\"error\":\"invalid path\"}";
        File target = resolve(clean);
        if (target == null || target.equals(root())) return "{\"ok\":false,\"error\":\"invalid path\"}";
        if (!target.exists()) return "{\"ok\":false,\"error\":\"missing\"}";
        boolean removed = deleteTree(target);
        return removed ? "{\"ok\":true,\"rel\":" + json(clean) + "}"
                : "{\"ok\":false,\"error\":\"could not delete\"}";
    }

    // ------------------------------------------------------------------ export

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, MainActivity.MODE_PRIVATE);
    }

    @android.webkit.JavascriptInterface
    public String updateInfo() {
        android.content.SharedPreferences p =
                activity.getSharedPreferences(DshService.PREFS, android.content.Context.MODE_PRIVATE);
        StringBuilder out = new StringBuilder("{");
        out.append("\"version\":").append(json(DshService.version()));
        out.append(",\"updating\":").append(DshService.isUpdating());
        out.append(",\"available\":").append(json(p.getString(DshService.KEY_AVAIL, "")));
        out.append(",\"updated\":").append(json(p.getString(DshService.KEY_UPDATED, "")));
        out.append(",\"state\":").append(json(p.getString(DshService.KEY_STATE, "")));
        return out.append('}').toString();
    }

    /** 只查有没有新版本；进度/结果通过 window.__bigriceUpdate 回传。 */
    @android.webkit.JavascriptInterface
    public boolean checkUpdate() { return DshService.checkUpdate(); }

    /** 安装最新版本；完成后 dsh 会自动重启。 */
    @android.webkit.JavascriptInterface
    public boolean applyUpdate() { return DshService.applyUpdate(); }

    /** 更新后（或用户手动）重启本地 dsh。 */
    @android.webkit.JavascriptInterface
    public void restartDsh() { activity.restartDsh(); }

    @android.webkit.JavascriptInterface
    public String getExportDir() {
        String tree = prefs().getString(EXPORT_TREE_KEY, null);
        if (tree == null || tree.isEmpty()) {
            return "{\"mode\":\"download\",\"label\":\"Download\"}";
        }
        return "{\"mode\":\"tree\",\"label\":" + json(describeTree(tree)) + "}";
    }

    @android.webkit.JavascriptInterface
    public void chooseExportDir() {
        activity.pickExportDir();
    }

    @android.webkit.JavascriptInterface
    public String setImportDir(final String path) {
        String clean = rel(path);
        if (clean == null) return "{\"ok\":false,\"error\":\"invalid path\"}";
        File target = resolve(clean);
        if (target == null) return "{\"ok\":false,\"error\":\"invalid path\"}";
        if (!target.isDirectory() && !target.mkdirs()) {
            return "{\"ok\":false,\"error\":\"could not create\"}";
        }
        String guest = clean.isEmpty() ? "/root" : "/root/" + clean;
        prefs().edit().putString(IMPORT_DIR_KEY, guest).apply();
        return "{\"ok\":true,\"path\":" + json(clean) + ",\"guest\":" + json(guest) + "}";
    }

    @android.webkit.JavascriptInterface
    public String exportZip(final String rels, final String zipName) {
        List<String> wanted = new ArrayList<String>();
        if (rels != null) {
            for (String part : rels.split("\n")) {
                String clean = part.trim();
                if (!clean.isEmpty()) wanted.add(clean);
            }
        }
        if (wanted.isEmpty()) return "{\"ok\":false,\"error\":\"nothing selected\"}";
        File temp = new File(activity.getCacheDir(), "bigrice-export.zip");
        int count = 0;
        try {
            try (ZipOutputStream zip = new ZipOutputStream(
                    new BufferedOutputStream(new FileOutputStream(temp)))) {
                byte[] buf = new byte[64 * 1024];
                for (String one : wanted) {
                    String clean = normalize(one);
                    File target = resolve(one);
                    if (target == null || clean == null || !target.exists()) continue;
                    count += zipTree(zip, target, clean, buf);
                }
            }
        } catch (IOException e) {
            DshLog.w(TAG, "导出失败: " + e);
            temp.delete();
            return "{\"ok\":false,\"error\":\"zip\"}";
        }
        long bytes = temp.length();
        if (count == 0) {
            temp.delete();
            return "{\"ok\":false,\"error\":\"nothing selected\"}";
        }
        String destination = saveZip(temp, sanitize(zipName));
        temp.delete();
        if (destination == null) {
            return "{\"ok\":false,\"error\":\"could not write to that folder\"}";
        }
        return "{\"ok\":true,\"path\":" + json(destination)
                + ",\"count\":" + count + ",\"bytes\":" + bytes + "}";
    }

    private static int zipTree(ZipOutputStream zip, File file, String path, byte[] buf)
            throws IOException {
        if (file.isDirectory()) {
            File[] kids = file.listFiles();
            int count = 0;
            if (kids != null) {
                Arrays.sort(kids, new java.util.Comparator<File>() {
                    @Override public int compare(File a, File b) {
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
                });
                for (File kid : kids) count += zipTree(zip, kid, path + "/" + kid.getName(), buf);
            }
            return count;
        }
        zip.putNextEntry(new ZipEntry(path));
        try (InputStream in = new java.io.FileInputStream(file)) {
            int read;
            while ((read = in.read(buf)) >= 0) zip.write(buf, 0, read);
        }
        zip.closeEntry();
        return 1;
    }

    private static String sanitize(String name) {
        String base = name == null ? "" : name.trim().replaceAll("[\\\\/:*?\"<>|]", "-");
        if (base.isEmpty()) base = "bigrice";
        if (!base.toLowerCase(Locale.ROOT).endsWith(".zip")) base += ".zip";
        return base;
    }

    /** Writes the zip to the chosen SAF tree, or to the app's Download folder. */
    private String saveZip(File zipFile, String name) {
        String tree = prefs().getString(EXPORT_TREE_KEY, null);
        if (tree != null && !tree.isEmpty()) {
            String written = writeToTree(tree, name, zipFile);
            if (written != null) return written;
            DshLog.w(TAG, "SAF 导出失败，回退应用目录: " + tree);
        }
        File dir = new File(activity.getExternalFilesDir(null), "Download");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File out = new File(dir, name);
        try (InputStream in = new java.io.FileInputStream(zipFile);
             OutputStream to = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) >= 0) to.write(buf, 0, read);
        } catch (IOException e) {
            DshLog.w(TAG, "写 zip 失败: " + out + " " + e);
            return null;
        }
        return out.getAbsolutePath();
    }

    private String writeToTree(String tree, String name, File zipFile) {
        if (Build.VERSION.SDK_INT < 21) return null;
        try {
            Uri treeUri = Uri.parse(tree);
            Uri doc = DocumentsContract.createDocument(
                    activity.getContentResolver(), treeUri,
                    "application/zip", name);
            if (doc == null) return null;
            try (OutputStream out = activity.getContentResolver().openOutputStream(doc);
                 InputStream in = new java.io.FileInputStream(zipFile)) {
                if (out == null) return null;
                byte[] buf = new byte[64 * 1024];
                int read;
                while ((read = in.read(buf)) >= 0) out.write(buf, 0, read);
            }
            return outName(doc);
        } catch (Exception e) {
            DshLog.w(TAG, "SAF createDocument 失败: " + e);
            return null;
        }
    }

    private static String outName(Uri doc) {
        try {
            String id = DocumentsContract.getDocumentId(doc);
            int colon = id.indexOf(':');
            return colon >= 0 ? id.substring(colon + 1) : id;
        } catch (Exception e) {
            return doc.toString();
        }
    }

    private static String describeTree(String tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(Uri.parse(tree));
            int colon = id.indexOf(':');
            String path = colon >= 0 ? id.substring(colon + 1) : id;
            if (path == null || path.isEmpty()) return "所选文件夹";
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        } catch (Exception e) {
            return "所选文件夹";
        }
    }

    void onExportTreePicked(Uri tree) {
        if (tree == null) return;
        prefs().edit().putString(EXPORT_TREE_KEY, tree.toString()).apply();
        activity.evalJs("window.__bigriceExportDir && window.__bigriceExportDir({mode:'tree',label:"
                + json(describeTree(tree.toString())) + "})");
    }

    // ------------------------------------------------------------------- shell

    @android.webkit.JavascriptInterface
    public String runShell(final String command, final String cwd) {
        final String id = "sh" + shellSeq.incrementAndGet();
        if (command == null || command.trim().isEmpty()) return id;
        String clean = rel(cwd == null ? "" : cwd);
        if (clean == null) clean = "";
        String guestDir = clean.isEmpty() ? "/root" : "/root/" + clean;
        final File dir = resolve(clean);
        final String line = command.trim();
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                String payload = shellPayload(id, line, guestDir, dir);
                activity.evalJs("window.__bigriceShellResult && window.__bigriceShellResult(\""
                        + id + "\", " + json(payload) + ")");
            }
        }, "bigrice-shell");
        worker.setDaemon(true);
        worker.start();
        return id;
    }

    @android.webkit.JavascriptInterface
    public void killShell(final String id) {
        if (id == null) return;
        stopped.put(id, Boolean.TRUE);
        Process p = shells.remove(id);
        if (p != null) {
            try {
                p.destroy();
            } catch (Throwable ignored) {
                // already gone
            }
        }
    }

    private String shellPayload(String id, String command, String guestDir, File dir) {
        long started = System.currentTimeMillis();
        StringBuilder out = new StringBuilder(1024);
        out.append("{\"command\":").append(json(command))
                .append(",\"cwd\":").append(json(guestDir));
        StringBuilder text = new StringBuilder();
        int code = -1;
        boolean truncated = false;
        boolean timedOut = false;
        Process process = null;
        final long deadline = started + SHELL_TIMEOUT_MS;
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
            pb.directory(dir);
            pb.redirectErrorStream(true);
            pb.environment().put("PATH", "/system/bin:/system/xbin:/product/bin");
            pb.environment().put("HOME", activity.getFilesDir().getAbsolutePath());
            pb.environment().put("TMPDIR", activity.getCacheDir().getAbsolutePath());
            process = pb.start();
            shells.put(id, process);
            StringBuilder pending = new StringBuilder();
            long lastPost = 0;
            try (InputStream in = process.getInputStream()) {
                byte[] buf = new byte[4096];
                int read;
                while ((read = in.read(buf)) >= 0) {
                    String chunk = new String(buf, 0, read, StandardCharsets.UTF_8);
                    if (text.length() < SHELL_MAX_BYTES) {
                        int room = SHELL_MAX_BYTES - text.length();
                        text.append(chunk.length() <= room ? chunk : chunk.substring(0, room));
                    } else {
                        truncated = true;
                    }
                    pending.append(chunk);
                    long now = System.currentTimeMillis();
                    if (now - lastPost > 120 || pending.length() > 8192) {
                        postChunk(id, pending.toString());
                        pending.setLength(0);
                        lastPost = now;
                    }
                    if (now > deadline) {
                        timedOut = true;
                        break;
                    }
                }
            }
            if (pending.length() > 0) postChunk(id, pending.toString());
            if (timedOut) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                    // best effort
                }
            }
            code = process.waitFor();
            if (System.currentTimeMillis() >= deadline) timedOut = true;
        } catch (Exception error) {
            out.append(",\"error\":").append(json(String.valueOf(error)));
        } finally {
            shells.remove(id);
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                    // already gone
                }
            }
        }
        boolean wasStopped = stopped.remove(id) != null;
        out.append(",\"ok\":").append(code == 0 && !timedOut && !wasStopped)
                .append(",\"code\":").append(code)
                .append(",\"truncated\":").append(truncated)
                .append(",\"timedOut\":").append(timedOut)
                .append(",\"stopped\":").append(wasStopped)
                .append(",\"ms\":").append(System.currentTimeMillis() - started)
                .append(",\"output\":").append(json(text.toString()))
                .append('}');
        return out.toString();
    }

    private void postChunk(String id, String chunk) {
        if (chunk.isEmpty()) return;
        activity.evalJs("window.__bigriceShellChunk && window.__bigriceShellChunk(\""
                + id + "\", " + json(chunk) + ")");
    }

    // -------------------------------------------------------------------- logs

    @android.webkit.JavascriptInterface
    public String readLogs(final int maxBytes) {
        int limit = maxBytes <= 0 ? LOG_WINDOW : maxBytes;
        limit = Math.max(16_384, Math.min(limit, 524_288));
        StringBuilder out = new StringBuilder(limit + 1024);
        out.append("{\"app\":{\"versionName\":").append(json(activity.versionName()))
                .append(",\"versionCode\":").append(activity.versionCode())
                .append(",\"package\":").append(json(activity.getPackageName()))
                .append("},\"files\":[");
        String run = DshLog.lines(4000);
        appendLogEntry(out, true, "run", "本次启动", run, limit);
        appendLogEntry(out, false, "dsh", "dsh 输出", run, limit);
        return out.append("]}").toString();
    }

    private static void appendLogEntry(StringBuilder out, boolean first, String id,
                                       String title, String raw, int limit) {
        if (!first) out.append(',');
        String text = raw == null ? "" : raw;
        if (text.length() > limit) text = text.substring(text.length() - limit);
        out.append("{\"id\":").append(json(id))
                .append(",\"title\":").append(json(title))
                .append(",\"chars\":").append(text.length())
                .append(",\"missing\":").append(raw == null)
                .append(",\"truncated\":").append(raw != null && raw.length() > limit)
                .append(",\"text\":").append(json(text))
                .append('}');
    }

    // ------------------------------------------------------------------ helpers

    static String json(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder(value.length() + 16);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static boolean deleteTree(File file) {
        if (file == null || !file.exists()) return true;
        File[] kids = file.listFiles();
        if (kids != null) {
            for (File kid : kids) {
                if (!deleteTree(kid)) return false;
            }
        }
        return file.delete();
    }
}
