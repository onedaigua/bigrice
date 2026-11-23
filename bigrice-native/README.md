# bigrice-native

bigrice 的 **无 proot** 版本：不再携带 Arch Linux rootfs 和 proot，直接用
**原生 Android (bionic) 的 Node** 跑 DeepSeek Harness，界面仍然是 bigrice-ui 手机插件。

运行时、打包脚本来自同机的 `dshtool` 项目（已验证在 Android 8.0+ arm64 上跑通），
bigrice 只补回了自己的三样东西：手机界面插件、通知栏状态行、`BigriceHost` 桥。

## 和 a1.x（proot 版）的区别

| | a1.x | a2.0（本目录） |
| --- | --- | --- |
| 运行时 | proot + Arch rootfs（`assets/proot`、`install.sh`、`dsh-lock.json.gz`、`bigrice-ui.tar`…） | 原生 `libnode.so`（Node 26，bionic）+ `assets/dshtool.zip` 引导包 |
| 安装 | 首启解包 rootfs、装 Node、`npm ci` 装 dsh（分钟级，易卡） | 首启解包 ~10 MB 引导包，直接 `npm install -g @deepseek-ai/dsh`（几十秒） |
| 可执行文件 | rootfs 里的 node/glibc 二进制 | APK `lib/arm64-v8a/`：`libnode.so`、`libc++_shared.so`、`libnodepty.so`、`libspawn_helper.so`、`libsystemnode.so`（Android 只允许从 nativeLibraryDir 执行） |
| dsh 更新 | `update-dsh.sh`（每次启动 curl 比版本） | 只手动：设置 → bigrice → 更新卡片调 `BigriceHost.checkUpdate()/applyUpdate()`（`dsh-update.js`）；启动不检查、不下载。下载沿用 a1.x 的策略：吞吐测速选源 + 多线程 + 百分比进度 |
| 界面插件更新 | `update-ui.sh`：GitHub ETag → tarball | 本版未做 git 更新，`assets/bigrice-ui.tar` 随 APK 走 |
| 包大小 | 0.78 MiB（rootfs 靠首启下载） | **≈35 MiB**（含 Node 运行时） |

## 目录

```
app/
  AndroidManifest.xml      com.bigrice / a2.3 / minSdk 26 / targetSdk 28
  build.sh                 aapt2 → javac → d8 → assemble.py → zipalign → apksigner
  assemble.py              把 classes.dex / assets / lib/<abi> 打进 linked APK
  assets/
    dshtool.zip            引导运行时（node + npm/pnpm + dsh-update.js + noexec shim）
    bigrice-ui.tar         bigrice-ui 0.1.33 插件快照
    register-bundle.js     把 bigrice-ui 注册进 dsh profile
  libs/arm64-v8a/*.so      原生运行时（来自 dshtool 的 cross build）
  res/                     bigrice 图标（mipmap 自适应 + 单色 status-bar 图标）+ strings（app_name=bigrice）
  src/com/bigrice/
    MainActivity.java      WebView 壳：进度 pill、URL 轮询、BigriceHost 桥注入
    DshService.java        前台服务：解包 → 选 node → 装插件 → 起 `dsh web` → 抓 token URL
    BigriceUi.java         解包 bigrice-ui.tar 并跑 register-bundle.js
    HostBridge.java        BigriceHost 契约（文件浏览/读写、导出 zip、shell、日志）
    Tar.java               最小 ustar 解包（不依赖 commons-compress）
    DshLog.java            日志环 + 落盘（`readLogs` 用）
```

## 构建

需要 JDK、Android SDK（`ANDROID_HOME`）、python（打包用）。

```bash
cd app
bash build.sh debug            # build/bigrice-native-debug.apk
bash build.sh release          # build/bigrice-native-release.apk
```

- 默认 `versionCode=203 versionName=a2.3`，可用 `VERSION_CODE` / `VERSION_NAME` 覆盖；
- 签名默认用 `keystore/release.keystore`（口令 `bigrice-release`，别名 `bigrice`），
  没有就回退到 `../bigrice-app/app/keystore/release.keystore`；
- `MSYS_NO_PATHCONV` / `MSYS2_ARG_CONV_EXCL` 必须清除（脚本里已 `unset`），否则 aapt2 找不到目录；
- `python3` 在 Windows 上若是商店占位符，脚本会自动改用 `python`（或设 `PYTHON=`）。

## 运行时行为

1. `MainActivity`（或开机广播 `BootReceiver`）启动 `DshService`（前台服务，`dataSync` 类型）；
2. 服务把 `assets/dshtool.zip` 解到 `filesDir/dsh`，写入 `.npmrc`、创建
   `DSH_HOME=filesDir/dsh-home`、`HOME`、npm/pnpm 缓存目录；
3. `BigriceUi.ensure()` 对比 `assets/bigrice-ui.tar` 的 sha256，变了就重新解包到
   `filesDir/plugin/bigrice-ui`，再用 `libnode.so register-bundle.js <DSH_HOME>/profiles/web bigrice-ui <dir>`
   把插件写进 profile（`link:` 依赖 + `dsh.profile.bundles`）；
4. 服务用 `libnode.so --expose-internals <DSH_HOME>/.../dsh/lib/bin.js web --host 127.0.0.1 --port 0 --no-open`
   起 dsh，从 stdout 抓 `dsh web: http://127.0.0.1:<port>/?token=…`，把 URL 通过广播交给 WebView；
5. 页面里注入 `BigriceHost`：`/root` 映射到工作区（`/storage/emulated/0/Android/data/com.bigrice/work`，不可写时回退内部）；
6. `MainActivity` 每 1.5 s 在页面里读一次「当前在干什么」，写进通知栏（见下面「通知栏」）；
7. 通知栏只有一张卡（服务自己的前台通知，`bigrice-service` 渠道、默认重要性、无声、
   带「停止」动作），小图标用 bigrice 的单色图标，不是系统下载图标；**卡片只在退到后台时
   出现**，应用在前台时收起来（`DshService.uiVisible(promote/demote)`，由
   `MainActivity.onStart/onStop` 驱动）。

## 工作区与环境目录（a2.3）

外部存储里只有一个工作区，路径就是文件管理器里最短的那条：

```
/storage/emulated/0/Android/data/com.bigrice/work      ← 工作区（也是 dsh 的 cwd）
/storage/emulated/0/Android/data/com.bigrice/files     ← 系统给应用的 external files，空着不用
```

环境相关的一切都在内部存储，和工作区彻底分开：

```
/data/user/0/com.bigrice/files/dsh            node 运行时 + 下载的 dsh 版本（versions/<ver>）
/data/user/0/com.bigrice/files/dsh-home       DSH_HOME（profiles / sessions / storages）
/data/user/0/com.bigrice/files/home           HOME
/data/user/0/com.bigrice/files/plugin         bigrice-ui 解包目录 + dsh-workspace.yml
/data/user/0/com.bigrice/files/log            日志
```

`DshService.resolveWorkspace()` 的取舍顺序：`Android/data/<pkg>/work` → 老路径 `files/work`
→ 内部 `filesDir/work`（每一级都真建一个探针文件再删，FUSE 的 `canWrite()` 不可信）。

**升级迁移**（老版本第一次启动，`files/work` 还在时跑一次，之后不再触发）：

- `files/work/deepseek-harness/default-workspace` 里的内容**摊平**到新 `work/`，去掉中间两级；
- 旧路径下的会话目录不改名（zstd 头里记着 cwd，改名会让 dsh 报
  `corrupt session log … header id … and cwd identify …`），整体归档到
  `dsh-home/sessions-legacy/`，新工作区从空会话开始；
- 工作区注册表（`dsh-home/storages/workspace.json`）**整体重写**成一条：
  path = 新工作区、title = `work`；dsh 自动建在 HOME 下的字母工作区（proot 时代的 `/root/A`）
  从列表里消失，用户不会再在「加号工作区」里看到环境目录；
- 删除 `work/storage/…`、`work/data/…` 这类空壳（见下）。

**每次启动**还会做两件事：`cleanupWorkspaceJunk()` 删掉工作区里的 `storage/`、`data/` 空壳
（只有目录、没有任何文件的树，就是路径被当相对路径拼出来的），`normalizeWorkspaceState()`
校正注册表只留当前工作区这一条，并把不属于当前工作区的会话目录归档。

`HostBridge` 的文件 API 坐标是工作区相对（`/root` 就是工作区），所以它现在**拒绝**工作区外的
绝对路径：以前页面传了绝对路径，`rel()` 会把它当相对串拼进来，于是在工作区里长出
`work/storage/emulated/0/Android/data/com.bigrice/work/download` 这种镜像树（导入目录
`followWorkspace` 已改成传相对路径 `download`）。

## 通知栏

a1.0 的行为是「只有在后台才挂通知」，a2.1 补回了这套：`onStart` → `uiVisible(true)` →
`stopForeground(REMOVE)`，`onStop` → `uiVisible(false)` → `startForeground(...)`。服务
`onCreate` 里仍然先 `startForeground` 一次（`startForegroundService` 要求 5 秒内进前台），
再按可见性立刻收起。

卡片的正文是当前对话状态。判定顺序（`MainActivity.ACTIVITY_JS`，1.5 s 一次）：

1. 输入框里的发送按钮变成 `button[aria-label="停止生成"]` → 这一轮在跑；
2. 扫 `[data-state]` 有没有 `running|preparing|pending|streaming` → `正在调用工具 X`
   （**注意**：实测 dsh 0.2.2-rc.5 里 `[data-state]` 只写 `ok`/`error`，已结束的行也带着，
   所以这一支基本不命中，只作为兜底）；
3. 取最后一个 `[data-process-activity]`，文本以「正在」开头的就是活行（`正在分析请求 · …`、
   `正在运行命令 · …`），拿 ` · ` 前的部分；
4. 都不忙且没有 `[data-streaming]` → 空，卡片回到「dsh 后台运行中」。

文案会带上会话标题：前台时读 `document.title`（`Greeting — DeepSeek Harness`），退后台后
标题不再变，所以卡片写成 `Greeting · 正在运行命令`。实测（debug 构建 + adb）一个回合跑起来
后按 HOME，14 次采样全部是 `Greeting · 正在运行命令`；空闲时是 `dsh 后台运行中`。
页面内的 `setInterval` 在后台会被节流到约 1 Hz，所以捕获靠 Java 侧的 Handler 轮询，不放在页面里。


## 启动速度

dsh 的 node 冷启动要 6~7 s（加载整套模块），所以「点图标 → 看见界面」的快慢几乎全看
能不能复用已经在跑的服务：

- **复用活着的 URL**：`MainActivity.onCreate` 先看 `DshService.live` 的进程是否还活着，
  活着就直接 `loadFresh(当前 URL)`——端口没变，不存在旧端口问题，界面重建只花 WebView
  加载的 ~1 s；只有进程真没了才清掉持久化 URL、等新的「本次运行 URL」广播。
- **补发广播**：进程活着而 Activity 被系统回收时，`DshService.onStartCommand` 会把当前
  URL 再广播一次（`rebroadcastUrl()`），重建的界面不会空等。
- **开机预热**：`BootReceiver` 在 `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` 时启动服务，
  用户点图标时 dsh 已经在跑（MIUI 需要在「自启动」里允许本应用，否则开机广播被拦）。
- 服务比 WebView 先起（`startDsh()` 在 `buildUi()` 前面），开屏页在 `progress=100` 后
  220 ms 收起（原来 900 ms）。

实测（23013RK75C）：冷启动 ~8-9 s（其中 node 起 dsh 6~7 s）；服务已在跑时进入 ~1 s
（Activity 226 ms + 页面 0.6 s + 开屏 0.2 s）。

## dsh 下载 / 更新（`assets/dshtool.zip` 里的 `bin/dsh-update.js`）

首启装 dsh 和「安装最新版」跑的是同一个脚本，下载策略照搬 a1.x 的 `install.sh`：

- **自动找最快节点**：先两轮量延迟，再对每个源发 `Range: bytes=0-262143` 抓 256 KB 算吞吐
  （KB/s），按「可用 → 吞吐降序 → 延迟升序」排序。跳转要跟（npmmirror 的 tarball 是 302 到
  cdn），不足 64 KB 的响应（跳转页）直接丢弃。手机 WiFi 实测：腾讯 494 KB/s > npmmirror 360 >
  npmjs 116，所以最终选了腾讯。
- **多线程**：`npm install … --maxsockets=64 --fetch-retries=3 --fetch-retry-*timeout --fetch-timeout=120000`。
- **真进度**：只数 `http fetch … .tgz`（元数据请求不算），`pct = 44 + 44 × min(1, fetches/600)`。
  600 是 dsh 0.2.2-rc.5 依赖树的经验值（实测 663 个包）。没有改成先 `npm --dry-run` 问准确
  包数：冷缓存下它自己要 ~70 s 才超时返回 0，会把整次安装从 150 s 拖到 222 s。
- 百分比全程单调：2 bootstrap → 4..40 测速 → 41..43 选版本/换源 → 44..88 下载 → 90 androidize
  → 94 smoke → 96 activate → 98/99 清理 → 100。

开屏页的进度条接的就是这个 pct（`BootScreen.percent()`）：以前 `onUpdate` 把 pct 丢掉、条只会
扫动，现在按百分比走，旁边写「下载依赖 214」这类文字。

### 首启进度条为什么以前不显示（a2.2 修）

`onUpdate` 一直在带 pct 到达（`boot.percent(pct/100f, msg)`），但开屏页上的条始终只是来回扫：
`syncFromPrefs()` 由 `poll` 每 300 ms 调一次，在没有 URL 时走 `showPill("正在启动…", 0)`，而
`showPill` 第一行就是 `bootStage(text)` → `boot.indeterminate(...)`，于是每 300 ms 把刚写进去的
百分比和文案重新改成扫动。修法是给 `BootScreen` 一个 `precise` 标记：

- `percent()` 把它置为 `true`（有真实百分比的一轮），`indeterminate()` / `band()` 在
  `precise && !done` 时直接 return，`fail()` / `showAgain()` 复位；
- 下载全命中缓存时 npm 不再打 `http fetch`，一次 pct 都不会涨：`runNpmInstall` 里没有 fetch
  样本时按时间爬（40 s 爬到 74%，封顶 87），真开始下载就立刻由 fetch 接管；
- 首启 bootstrap 的 `phase=done` 不再触发 `restartDsh()`（`DshService.bootstrapping` 期间
  Activity 跳过重启）：服务紧接着自己 spawn 新版 dsh，重启等于把刚起的进程杀掉重来——实测一次
  冷启曾出现两个 dsh、两个端口、一次 `selfcheck npm 失败: InterruptedException`。

实测（debug 构建，人为清 `versions/` 触发 bootstrap）：条从 44% 走到 74%，文字「正在安装依赖」，
随后 88 → 96 → 98/99 → 100 并进界面；日志里只有一次 `spawn argv`（修前是两次）。

实测（host，同一网络、各自冷缓存）：老脚本 165 s，新脚本 150 s。两者接近是因为这条宽带被带宽
限制（~0.5 MB/s）而不是被并发限制，多线程的收益要等高延迟弱网；换源那部分的收益是实打实的
（npmjs 116 KB/s → 腾讯 494 KB/s）。

## 已知限制

- **界面插件不再从 git 自动更新**：本版随 APK 的 `assets/bigrice-ui.tar` 走，
  需要新插件时重新打包（或后续按 `update-ui.sh` 的思路加回来）；
- 首次启动需要联网装 dsh（镜像可用性同 dshtool：阿里/腾讯/npmjs/UOS）；
- `targetSdk 28`：不需要通知运行时权限，但 MIUI/HyperOS 上仍可能装好后是关闭状态
  （`POST_NOTIFICATIONS: granted=false`，表现为「没有通知」）。闪屏底部会显示
  「通知已关闭 · 点此开启」，点它打开本应用的通知设置；调试时也可
  `adb shell pm grant com.bigrice android.permission.POST_NOTIFICATIONS`；
- `libnode.so` 以压缩方式放进 APK（`extractNativeLibs=true`），安装时会解压到
  `nativeLibraryDir`，所以包体 ~35 MiB、安装后占用 ~110 MiB。