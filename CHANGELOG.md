# 变更记录

本文件记录 **本仓库**（源码发布）的版本；应用与插件的详细改动见 [`docs/HISTORY.md`](docs/HISTORY.md)。

## a0.1.0 — 2026-11-23

首个源码发布。

- 收录 bigrice-native **a2.3**（versionCode 203）完整工程源码：8 个 Java 源文件、资源与清单、`build.sh` 构建脚本、`assets/` 引导包、`libs/arm64-v8a/` 预编译 Node 运行时、测试签名库。
- 收录 bigrice-ui **0.1.33** 插件源码：`client.js`、`cordis.patch.yml`、`index.js`、`package.json`、打包用 `bigrice-ui.tar`、121 项逻辑测试、`docs/0.2.2-compat.md`。
- 新增仓库说明 `README.md`、清单 `MANIFEST.md`、版本文件 `VERSION`、校验和 `SHA256SUMS`、`.gitignore` / `.gitattributes`。
- 原项目 README 的完整版本改动史保留为 `docs/HISTORY.md`。
- 不含：已签名 APK、`app/build/` 中间产物、`_work/` 与 `_arch_probe/` 调试缓存、桌面实验版、旧 proot 版（bigrice-app a1.8）。
