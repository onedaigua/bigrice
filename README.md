# bigrice

把 [DeepSeek Harness](https://github.com/deepseek-ai)（`dsh`）装进 Android 手机：APK 内自带原生 Node 运行时，首次启动直接安装并拉起 `dsh web`，再用 `bigrice-ui` 插件把网页界面改造成手机布局（抽屉会话列表、贴底输入框、全屏设置、横竖屏适配、返回键逐级后退）。

- 仓库版本：**a0.1.0**（首个源码发布）
- 应用版本：bigrice **a2.3**（versionCode 203，`com.bigrice`，minSdk 26 / targetSdk 28，arm64-v8a）
- 界面插件：bigrice-ui **0.1.33**

## 仓库内容

| 目录 | 说明 |
| --- | --- |
| [`bigrice-native/`](bigrice-native/README.md) | 主线 Android 工程源码（不用 proot，自带 Node）：`app/src/` Java 源码、资源与清单、`app/build.sh`、`app/assets/` 引导包（`dshtool.zip`、`bigrice-ui.tar`、`dsh-workspace.yml`、`register-bundle.js`）、`app/libs/arm64-v8a/` 预编译运行时（`libnode.so` 等）、测试用签名库 |
| [`bigrice-ui/`](bigrice-ui/README.md) | dsh 客户端插件源码：`client.js`（界面逻辑）、`cordis.patch.yml`（挂载补丁）、打包脚本、`test/logic-test.mjs` 逻辑测试 |
| [`docs/HISTORY.md`](docs/HISTORY.md) | 原项目 README 的完整版本改动记录（0.0.9 → a2.3），保留历史细节 |
| [`MANIFEST.md`](MANIFEST.md) | 本源码包的清单、构建方式、未包含内容与 SHA-256 校验 |
| `VERSION` / `CHANGELOG.md` | 仓库版本号与发布记录 |

> 本仓库只收录**新版原生 Node 版**（a2.x 主线）。旧 proot 版（a1.x）与桌面实验版不在其中，历史记录见 [`docs/HISTORY.md`](docs/HISTORY.md)。

## 下载（Release 附件）

不想自己编译时，直接从 [a0.1.0 Release](https://github.com/onedaigua/bigrice/releases/tag/a0.1.0) 下载：

| 附件 | 大小 | 说明 |
| --- | --- | --- |
| `bigrice-native-a2.3-arm64-v8a.apk` | 35.6 MiB | 已签名安装包（`com.bigrice` a2.3 / versionCode 203 / arm64-v8a），`adb install -r` 即可 |
| `libnode-arm64-v8a.so` | 68.9 MiB | APK 内嵌的 Node 运行时（arm64），等同于 APK 里的 `lib/arm64-v8a/libnode.so`，带 `/system/bin/linker64`，可直接 exec 当 `node` 用 |
| `libc++_shared-arm64-v8a.so` | 1.7 MiB | `libnode.so` 依赖的 C++ 运行库（`DT_NEEDED`），单独跑 node 时需与它同目录 |

SHA-256：

```
0d88c1b22e9f5f4be2ffe62a41522bc0c1bd5c8d4ca81a2b14a8dd73ce752429  bigrice-native-a2.3-arm64-v8a.apk
e916751cfba3921874911dcdf92cb5fac47852a137f0626f0427cc3942574d36  libnode-arm64-v8a.so
f9992c4ba6b7c5a716e3a202fceb1ce029d6a2b0605838ac6b3219f489dd7970  libc++_shared-arm64-v8a.so
```

APK 用的是仓库内自测签名（证书 `CN=bigrice`），只适合自装自测，正式分发请自行重签。

## 构建

```bash
cd bigrice-native/app
bash build.sh release        # 需要 JDK + Android SDK build-tools + platforms/android.jar
# 产物：build/bigrice-native-release.apk
```

可用环境变量覆盖默认值：`ANDROID_HOME`、`BUILD_TOOLS`、`ANDROID_JAR`、`VERSION_CODE`、`VERSION_NAME`、`KEYSTORE`、`KS_PASS`、`KEY_PASS`、`KS_ALIAS`。仓库自带 `app/keystore/release.keystore`（别名 `bigrice` / 口令 `bigrice-release`），仅用于自测，正式分发请换自己的签名。

插件改动后重新打包并放进 APK：

```bash
cd bigrice-ui
node test/logic-test.mjs      # 期望输出 {"failures": []}
tar -cf bigrice-ui.tar bigrice-ui/client.js bigrice-ui/cordis.patch.yml \
    bigrice-ui/index.js bigrice-ui/package.json
cp bigrice-ui.tar ../bigrice-native/app/assets/bigrice-ui.tar
```

更细的说明见 [`bigrice-native/README.md`](bigrice-native/README.md) 与 [`bigrice-ui/README.md`](bigrice-ui/README.md)。

## 使用

源码仓库不含已签名 APK，需要安装包时按上面「构建」自行构建。装机后首次启动会解包并装好 dsh，然后在「左上 ☰ → 更多 → 设置 → 模型」填 DeepSeek API Key 即可开始对话。

## 版本

仓库版本 `a0.1.0` 对应应用 a2.3 与插件 0.1.33 的源码快照，逐条改动见 [`docs/HISTORY.md`](docs/HISTORY.md)，仓库自身发布记录见 [`CHANGELOG.md`](CHANGELOG.md)。

## 许可

本仓库暂未附带开源许可证；在明确授权前，请勿再分发构建产物。
