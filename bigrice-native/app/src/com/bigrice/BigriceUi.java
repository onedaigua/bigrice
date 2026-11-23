package com.bigrice;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Installs the phone UI into the harness profile.
 *
 * <p>The plugin ships inside the APK as {@code assets/bigrice-ui.tar} and is
 * unpacked to {@code filesDir/plugin/bigrice-ui} on every launch where the
 * archive digest changed. Registration is done by the harness's own
 * {@code register-bundle.js}, which writes the dependency as {@code link:<dir>}:
 * a pnpm {@code file:} import would hard-link files, and Android denies
 * {@code link(2)} to this application domain.
 *
 * <p>Nothing here touches the runtime bundle — this only decides which UI the
 * harness loads, so a failure logs and lets dsh start with the stock desktop UI.
 */
final class BigriceUi {
    private static final String TAG = "dsh";
    private static final String ASSET_TAR = "bigrice-ui.tar";
    private static final String ASSET_REGISTER = "register-bundle.js";
    /** dsh 的 profile 补丁：把首启默认工作区钉到 App 自己的工作区目录。 */
    private static final String ASSET_PATCH = "dsh-workspace.yml";

    private BigriceUi() { }

    static void ensure(Context ctx, File dshDir, File home, Map<String, String> env) {
        try {
            File pluginRoot = new File(ctx.getFilesDir(), "plugin");
            File uiDir = new File(pluginRoot, "bigrice-ui");
            String digest = assetDigest(ctx.getAssets(), ASSET_TAR);
            File stamp = new File(pluginRoot, ".bigrice-ui");
            String have = readText(stamp);
            if (digest == null) {
                DshLog.w(TAG, "缺少 assets/" + ASSET_TAR + "，跳过界面插件");
                return;
            }
            if (!digest.equals(have.trim()) || !new File(uiDir, "client.js").isFile()) {
                DshLog.i(TAG, "解包界面插件 " + ASSET_TAR + " → " + uiDir);
                deleteTree(uiDir);
                if (!pluginRoot.isDirectory() && !pluginRoot.mkdirs()) {
                    throw new IOException("无法创建 " + pluginRoot);
                }
                try (InputStream in = ctx.getAssets().open(ASSET_TAR)) {
                    Tar.extract(in, pluginRoot);
                }
                if (!new File(uiDir, "client.js").isFile()) {
                    throw new IOException("解包后缺少 client.js");
                }
                writeText(stamp, digest);
            }
            File register = new File(pluginRoot, "register-bundle.js");
            copyAsset(ctx.getAssets(), ASSET_REGISTER, register);
            // 每次启动都覆盖，内容只有一行配置，开销可以忽略，改一次就生效。
            File patch = new File(pluginRoot, ASSET_PATCH);
            copyAsset(ctx.getAssets(), ASSET_PATCH, patch);
            File profile = new File(home, "profiles/web");
            File node = new File(ctx.getApplicationInfo().nativeLibraryDir, "libnode.so");
            if (!node.isFile()) node = new File(dshDir, "bin/node");
            if (!node.isFile()) {
                DshLog.w(TAG, "找不到 node，界面插件跳过注册");
                return;
            }
            String out = run(node, new String[] {
                    node.getAbsolutePath(), register.getAbsolutePath(), profile.getAbsolutePath(),
                    "bigrice-ui", uiDir.getAbsolutePath()
            }, env);
            DshLog.i(TAG, "bigrice-ui 注册完成：\n" + out);

            // dsh 只在 profile 的 node_modules 里找 bundle（createRequire 的解析顺序），
            // `link:` 依赖本身不会替我们建目录；这里补一条指向插件目录的软链接。
            File modules = new File(profile, "node_modules");
            if (!modules.isDirectory() && !modules.mkdirs()) {
                throw new IOException("无法创建 " + modules);
            }
            File link = new File(modules, "bigrice-ui");
            java.nio.file.Path want = uiDir.getAbsoluteFile().toPath();
            try {
                if (java.nio.file.Files.isSymbolicLink(link.toPath())) {
                    if (!java.nio.file.Files.readSymbolicLink(link.toPath()).equals(want)) {
                        java.nio.file.Files.delete(link.toPath());
                        java.nio.file.Files.createSymbolicLink(link.toPath(), want);
                        DshLog.i(TAG, "bigrice-ui 链接已更新 → " + want);
                    }
                } else if (!link.exists()) {
                    java.nio.file.Files.createSymbolicLink(link.toPath(), want);
                    DshLog.i(TAG, "bigrice-ui 链接已建立 → " + want);
                }
            } catch (Throwable t) {
                DshLog.w(TAG, "创建 bigrice-ui 链接失败: " + t);
            }
        } catch (Throwable t) {
            DshLog.e(TAG, "界面插件安装失败（继续用内置界面）: " + t);
        }
    }

    private static String run(File node, String[] argv, Map<String, String> env) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(node.getParentFile());
        pb.redirectErrorStream(true);
        pb.environment().putAll(env);
        Process p = pb.start();
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
            // the child never reads stdin
        }
        StringBuilder out = new StringBuilder();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[4096];
            int read;
            while ((read = in.read(buf)) >= 0) {
                out.append(new String(buf, 0, read, "UTF-8"));
                if (out.length() > 6000) break;
            }
        }
        try {
            p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out.toString();
    }

    private static void copyAsset(AssetManager assets, String name, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建 " + parent);
        }
        try (InputStream in = assets.open(name); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) >= 0) out.write(buf, 0, read);
        }
    }

    private static String assetDigest(AssetManager assets, String name) {
        try (InputStream in = assets.open(name)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) >= 0) md.update(buf, 0, read);
            StringBuilder out = new StringBuilder();
            for (byte b : md.digest()) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            Log.w(TAG, "digest failed: " + e);
            return null;
        }
    }

    static String readText(File file) {
        if (file == null || !file.isFile()) return "";
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) Math.min(file.length(), 4096)];
            int off = 0;
            while (off < buf.length) {
                int read = in.read(buf, off, buf.length - off);
                if (read < 0) break;
                off += read;
            }
            return new String(buf, 0, off, "UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    static void writeText(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes("UTF-8"));
        }
    }

    static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] kids = file.listFiles();
        if (kids != null) {
            for (File kid : kids) deleteTree(kid);
        }
        file.delete();
    }
}
