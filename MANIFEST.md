# bigrice · 源码包清单（a0.1.0）

- 打包时间：2026-11-23
- 仓库版本：`a0.1.0`（见 [`VERSION`](VERSION)）
- 应用：bigrice **a2.3**（versionCode 203，包名 `com.bigrice`，minSdk 26 / targetSdk 28，arm64-v8a）
- 界面插件：bigrice-ui **0.1.33**
- 规模：54 个文件 / 约 82 MiB（预编译 Node 运行时与引导包占绝大部分）
- 来源：本地工程 `bigrice-native/`、`bigrice-ui/` 工作树，入库前逐文件 SHA-256 校验一致

## 版本对应

| 组件 | 版本 | 对应文件 |
| --- | --- | --- |
| 本仓库 | a0.1.0 | `VERSION` |
| bigrice 应用 | a2.3（versionCode 203） | `bigrice-native/app/AndroidManifest.xml` |
| bigrice-ui 插件 | 0.1.33 | `bigrice-ui/bigrice-ui/package.json` |

## 目录结构

```
bigrice-github/
├── README.md                    仓库说明（构建 / 使用入口）
├── MANIFEST.md                  本文件
├── CHANGELOG.md                 仓库发布记录
├── VERSION                      仓库版本号 a0.1.0
├── SHA256SUMS                   全部文件校验和
├── .gitignore / .gitattributes  忽略规则与二进制属性
├── docs/
│   └── HISTORY.md               原项目 README 快照（完整历史改动记录）
├── bigrice-native/              Android 工程源码（原生 Node，主线 a2.x）
│   ├── README.md                运行时行为、工作区布局、已知限制
│   ├── _patch_*.py              开发期一次性改动脚本（历史保留，构建不用）
│   └── app/
│       ├── AndroidManifest.xml  versionCode 203 / versionName a2.3
│       ├── build.sh             构建脚本（aapt2 + javac + d8 + zipalign + apksigner）
│       ├── assemble.py          资源/清单辅助脚本
│       ├── src/com/bigrice/     MainActivity、DshService、BootScreen、BootReceiver、
│       │                        HostBridge、BigriceUi、DshLog、Tar 等 8 个 Java 源文件
│       ├── res/                 图标、布局、字符串等资源
│       ├── assets/              dshtool.zip、bigrice-ui.tar、dsh-workspace.yml、
│       │                        register-bundle.js、boot.png
│       ├── libs/arm64-v8a/      libnode.so、libc++_shared.so、libnodepty.so、
│       │                        libspawn_helper.so、libsystemnode.so
│       └── keystore/            release.keystore（仅自测用）
└── bigrice-ui/                  dsh 手机界面插件源码
    ├── README.md / CHANGELOG.md
    ├── bigrice-ui.tar           可安装包（126 KB）
    ├── bigrice-ui/              client.js、index.js、cordis.patch.yml、package.json
    ├── test/logic-test.mjs       121 项逻辑测试
    └── docs/0.2.2-compat.md
```

## 构建

```bash
cd bigrice-native/app
bash build.sh release
# 产物 build/bigrice-native-release.apk
```

可用环境变量覆盖：`ANDROID_HOME`、`BUILD_TOOLS`、`ANDROID_JAR`、`VERSION_CODE`、`VERSION_NAME`、`KEYSTORE`、`KS_PASS`、`KEY_PASS`、`KS_ALIAS`。

注意：`bigrice-native/README.md` 里提到的签名回退路径 `../bigrice-app/app/keystore/release.keystore` 属于旧 proot 版目录，本仓库不含该目录；本仓库自带 `bigrice-native/app/keystore/release.keystore`（别名 `bigrice`，口令 `bigrice-release`），仅用于自测，正式分发请替换。

## 关键文件 SHA-256

| 文件 | 字节数 | SHA-256 |
| --- | --- | --- |
| `bigrice-native/app/libs/arm64-v8a/libnode.so` | 72,219,640 | `e916751cfba3921874911dcdf92cb5fac47852a137f0626f0427cc3942574d36` |
| `bigrice-native/app/assets/dshtool.zip` | 10,145,123 | `45bee8014d284919127e758a7d7465048bb27c33e4ec3985e1051eeb90c5992b` |
| `bigrice-native/app/libs/arm64-v8a/libc++_shared.so` | 1,794,776 | `f9992c4ba6b7c5a716e3a202fceb1ce029d6a2b0605838ac6b3219f489dd7970` |
| `bigrice-native/app/assets/bigrice-ui.tar` | 126,976 | `934b7376e4d2181041915687f799506c17be475f6fda45f71f13114deb4fe8bb` |
| `bigrice-native/app/keystore/release.keystore` | 2,634 | `b06a3ba82cdb0591309ed1ccc92ba888834a9c231a2490904e801e44d0b8722b` |
| `bigrice-ui/bigrice-ui/client.js` | 118,762 | `62dd01fb1c47f475d929594f0e6e60d10a0077c73c680bef1b629c66afcea579` |

全部文件的校验和见 [`SHA256SUMS`](SHA256SUMS)：

```bash
sha256sum -c SHA256SUMS
```

## 未包含

| 未包含项 | 原因 |
| --- | --- |
| `bigrice-app/`（旧 proot 版 a1.8） | 本仓库只收录新版原生 Node 版（a2.x 主线） |
| 已签名 APK（`*.apk`、`*.apk.idsig`） | 不进仓库，作为 [a0.1.0 Release](https://github.com/onedaigua/bigrice/releases/tag/a0.1.0) 附件提供；源码仓库只放源码 |
| `*/app/build/` 中间产物 | 构建时生成 |
| `_work/`、`_arch_probe/` | 本机调试缓存与截图（约 4 GB） |
| `bigrice-linux/`、`bigrice-all/` | 桌面实验与旧的整包快照 |
| `bigrice-app/bigrice.gif` | 体积大的演示动图，与源码无关 |

## 安全

- 仓库不含 DeepSeek API Key 或任何用户凭据；`assets/dsh-workspace.yml` 中只有默认配置。
- `keystore/release.keystore` 是公开的自测签名库，口令写在 `README.md` 与编译脚本默认值中，不可用于生产签名。
