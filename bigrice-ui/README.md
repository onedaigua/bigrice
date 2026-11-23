# bigrice-ui

**把 DeepSeek Harness 的桌面网页界面改造成手机界面。**

`bigrice-ui` 是 DeepSeek Harness（`dsh`）的一个 **客户端插件**。它不改 dsh 本身，只在自己的 CSS/JS 里补上手机端缺的那一层：抽屉式会话列表、顶部栏、贴底的输入框、全屏设置、横竖屏适配，以及几个只有手机上才需要的行为（返回键逐级后退、首次启动弹窗自动关闭、外链与登录用小窗口打开）。

dsh 自带的 Web UI 是按桌面宽屏设计的：在 411dp 宽的手机上，设置面板会退化成「一行一个汉字」的竖排。这个插件就是为了解决这类问题。

---

## 目录里有什么

```
bigrice-ui/
  bigrice-ui/              源码目录（直接可读，也直接可用）
    package.json           dsh 插件清单（bundle + client 声明）
    index.js               宿主半边（空实现，保持插件契约完整）
    client.js              全部界面逻辑（约 64 KB，含 CSS）
    cordis.patch.yml       bundle 插入行
  test/logic-test.mjs      行为自测（不进 tar，不依赖浏览器）
  bigrice-ui.tar           上面目录的打包结果（安装用）
  README.md                本文件
```

---

## 它具体改了什么

**什么时候算手机**

不是靠 `@media (max-width: 767px)` 判断，而是量出来的：插件用 `ResizeObserver` 盯着宿主那层 `[class*='_frame']` 网格的**实际宽度**，小于 1024px（和宿主自己折叠侧边栏的 `SIDEBAR_AUTO_COLLAPSE` 同一个阈值）就给 `<html>` 打上 `data-bigrice-mobile`，高度小于 520px 再补一个 `data-bigrice-short`。所有手机样式都挂在这两个属性下面，所以：

- 411dp、360dp、320dp、平板、横屏——同一套布局，不会出现"某个分辨率下抽屉被裁掉"；
- 桌面窗口里这两个属性不存在，插件一行 CSS 都不生效；
- 旋转屏幕、改窗口大小会实时切换，不用刷新。

**布局**

- 左侧那条图标栏并进抽屉：主界面只剩内容，顶部左侧一个 ☰ 打开会话列表，右上角留给宿主的「打开右侧边栏」。
- 会话页没有第二条横条：宿主自己的标题行（`[class*='titleRow']`）里已经有标题和右侧两个动作，☰ 就并进这一行，插件不再在它上面单独占 52px（`data-bigrice-head` + `--bigrice-head-top/height`，行高现量，标题用 `padding-left` 让开按钮）。只有没有标题行的页面（设置、插件、启动页）才保留那条插件顶栏。
- 输入框用 `margin-top:auto` 钉在底部，空会话时不会浮在屏幕中间；有对话后照常随内容滚动。
- 抽屉宽 `min(84vw, 300px)`：宿主的侧栏面板带一个来自桌面轨道的内联 `width:280px`，抽屉比面板宽时右边会空出一条。插件在手机模式下把侧栏子树里的内联像素宽度摘掉（原值记在 `data-bigrice-width` 上，回到桌面窗口时还原），面板才真正铺满抽屉。
- 窄屏下宿主侧栏自带的折叠按钮会被隐藏，侧栏状态完全由抽屉接管（右侧面板的展开按钮不受影响，见下）。

**右侧面板**

宿主的右侧面板在窄屏下其实一直是好的：`computeColumns` 只决定中间栏要不要让出轨道，而右侧面板本身在视图宽度小于 768px 时会由 `RightbarSeat` 自己切成 `fullscreen`、宽度 `100vw`，轨道宽度保持 0，所以它本来就是「贴着右边挂在中间栏上方」的一层。插件要做的只有把它当成一整屏：

- `[class*='rightbarCol']` 不再 `display:none`，而是 `position:absolute; inset:0`，比正文高、比插件自己的顶栏和宿主的浮层低（`z-index:35`）；
- 面板收起时它仍在 DOM 里（只是被平移出裁剪区），所以列上挂 `pointer-events:none`，只在 `:has([data-sidebar-right-open])` 时才接管触摸；
- 面板展开时插件顶栏让位（`:has([data-sidebar-right-open]) .bigrice-topbar` 隐藏），免得文档上面堆两条横条；收起按钮就在面板页签栏最右侧，返回键也能关。
- 文档正文按手机可读性放大：纯文本预览 `pre[data-textpreview-page]` 15px/24px，带行号的代码 `pre.shiki` 14px/21px（行号列宽是 `ch`，跟着一起缩放）；只改面板里的正文，聊天里的代码块不动。

**设置**

- 设置对话框全屏化（`position: absolute` + 撑满），页签是多行换行的胶囊（左右各 16px 外边距，横屏也不会一侧贴边一侧留白），选项变成卡片。
- 卡片用 `--dsw-alias-bg-layer-1` + `border-l1` 描边——只靠背景色在浅色主题下会白上加白看不见。

**交互**

- **返回键**：注册 `window.__bigriceBack`，宿主调用它时按「设置弹窗 → 任意模态 → 抽屉 → 右侧面板 → 侧边面板」顺序关掉一层，全部关完才把返回交还给系统。
- **首次启动弹窗**：按弹窗标题（`预览版说明` / Preview Notice、`开始使用` / Get started）识别，自动点「继续」或「✕」，避免整屏被挡。
- **外链/登录**：拦截 `window.open`，交给宿主 App 打开一个可关闭的小窗口。

**不认中文**

早期版本用中文字面量（`更多`、`账号菜单`、`登录`）来定位控件，界面切到英文后全部失效——最典型的就是英文下点「More」会把整个抽屉关掉。现在：

- 需要认识的文字一律在**点击那一刻**从语言包取（`ctx.locale.bind(ns)(key)`），切语言立刻生效；
- 控件的"身份"靠结构判断，不看文字：带 `aria-haspopup` / `aria-expanded` 的按钮是菜单触发器（账号菜单、面板「更多」），点它不关抽屉；`_panelRow` 是面板条目，点它才关抽屉并打开面板；
- 侧边栏是否折叠读宿主自己的 `data-sidebar-collapsed` 属性，不去解析内联 `grid-template-columns`。

---

## 安装

### 在 bigrice 里：不用做

`bigrice-release.apk` 首次自举时已经把它装进 `/root/.dsh/profiles/web/node_modules/bigrice-ui` 并注册好了。之后**换 APK 就会自动更新**：`MainActivity.syncPlugin()` 每次启动都对比 APK 里 `bigrice-ui.tar` 的 SHA-256 和 rootfs 里 `/opt/bigrice-ui.sha256`，不一致就重新解包 + 注册，所以不用重置 rootfs。

### 装进别的 dsh（PC 或别的容器）

dsh 的插件是「profile 里的一个 bundle」。手工安装分两步：

```bash
# 1) 解包到 profile 的 node_modules
tar -xf bigrice-ui.tar -C ~/.dsh/profiles/web/node_modules

# 2) 注册到 profile（把 file: 依赖与 bundle 名写进 package.json）
node register-bundle.js ~/.dsh/profiles/web bigrice-ui
```

`register-bundle.js` 就是本包 `../bigrice-app/scripts/register-bundle.js`，做的事是：

```jsonc
// <profile>/package.json
{
  "dependencies": { "bigrice-ui": "file:./node_modules/bigrice-ui" },
  "dsh": { "profile": { "bundles": ["@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app", "bigrice-ui"] } }
}
```

改完 `package.json` 需要重启 `dsh`；只改 `client.js` 的话刷新页面（`location.reload()`）即可。

验证是否生效：页面上应出现 `document.querySelector('style[data-bigrice-ui]')`，或 `dsh --profile web --dump-config | grep bigrice-ui` 能看到那一行。

---

## 和宿主 App 的接口

插件在 `window.BigriceHost` 存在时会用这些方法（bigrice 的 `HostBridge` 注入）；不存在就回退到普通浏览器行为：

| 方法 | 作用 |
| --- | --- |
| `openPopup(url)` | 打开小窗口加载该地址 |
| `popupNavigateTo(url)` | 让已经打开的小窗口跳转 |
| `openMessagePopup(title, html)` | 开一个显示给定 HTML 的小窗口（宿主能力，插件已不再用它伪造登录页） |
| `toast(message)` | 底部提示条 |
| `readLogs(maxBytes)` | 读应用侧日志，返回 JSON（见下节）；没有它时设置里的 bigrice 页签会说明日志只能在 App 内读 |
| `hideKeyboard()` | 收起输入法。编辑器是 contenteditable，脚本 `blur()` 在安卓上收不掉键盘，插件在点输入栏按钮后调它（现在还有 `focus` 守卫兜底） |
| `getImportDir()` | 当前导入文件夹，返回 `{path, guest}`（`path` 是 `/root` 下的相对路径，`guest` 是客户机绝对路径，例如 `/root/B/download`） |
| `setImportDir(guest)` | 把导入文件夹指到工作区里的某个目录，接受 `/root/B/download` 或相对路径 `download`（插件固定传 `download`，因为客机坐标是工作区相对）；工作区外的绝对路径会被拒绝，返回 `{ok, path, guest}` |
| `deleteEntry(rel)` | 删一个文件或整个文件夹（递归），`rel` 相对 `/root`，`/root` 本身拒绝；返回 `{ok}` 或 `{ok:false, error}` |
| `listDir(rel, showHidden)` | 列客户机 `/root` 下的目录，返回 `{path, guest, parent, ok, missing, entries:[{name, rel, dir, bytes}]}`；`showHidden` 为 `true` 时包含 `.` 开头的条目 |
| `readText(rel, maxBytes)` | 读文本预览，返回 `{ok, bytes, truncated, text}`；二进制文件返回 `{ok:false, error:'binary'}` |
| `readImage(rel)` | 读图片预览，返回 `{ok, mime, dataUrl}`；超过 12 MB 返回 `too-big` |
| `exportZip(rels, name)` | 把选中的文件/文件夹打成 zip；`rels` 是**换行拼接**的路径串，返回 `{ok, name, path, count}` |
| `getExportDir()` | 当前导出目标，返回 `{mode:'download'|'tree', label}` |
| `runShell(command, cwd)` | 在客户机里执行一条 `/bin/sh -c` 命令，立即返回任务 id；输出边跑边通过 `window.__bigriceShellChunk(id, text)` 推给页面，结束时用 `window.__bigriceShellResult(id, {ok, code, ms, truncated, timedOut, stopped, output})` 交结果（桥方法跑在 WebView 的 JS 线程上，不能阻塞） |
| `killShell(id)` | 结束一条还在跑的命令（命令台的「停止」按钮用它） |
| `chooseExportDir()` | 打开系统文件夹选择器（SAF），选完回调 `window.__bigriceExportDir(id)` |

没有宿主时，移动布局、返回键、弹窗自动关闭这些仍然工作，只是外链会走浏览器默认的 `window.open`。

---

## 设置里的 bigrice 页签

插件往宿主的 `settings.section` 槽里注册了一个页签（`id: 'bigrice'`，`order: 90`，标签不随语言变化），宿主设置弹窗的导航是按这个槽里的条目生成的，所以加页签不需要改 dsh。

页签里分三块：

### 导入文件

- 卡片只写一句「点“查看文件”可浏览、预览、导出和删除」，右侧一个 `查看文件` 按钮；不再列出导入过的文件，也不再显示导入路径（它在主界面底部的工作区选择器里已经能看到）。
- 文件夹固定在**当前工作区**的 `download` 子目录：插件每 2 秒（以及打开页签时）推相对路径 `download` 给 App。以前推的是绝对路径 `<cwd>/download`，而 App 的文件 API 是工作区相对的，结果在工作区里堆出了一层 `storage/emulated/0/…` 镜像目录。
- 对话框里的「文件」走另一条路：宿主 `onShowFileChooser` 把选中文件拷进这个文件夹，再把 URI 交回 `<input type=file>` 让 dsh 上传；超过 25 MB 的只落盘，用 `@` 引用。

### 查看文件 / 导出

- `查看文件` 就在页签内展开：`FileBrowser` 画在「文件」卡片下面（`div.bigrice-browser`，边框 + `--dsw-radius-lg` 圆角，自己的滚动区），再点 `收起` 折回去。设置弹窗全程不关——早先那版是 overlay 层上的独立小窗口，得先关掉设置弹窗才看得见，真机上看起来像"跳回主界面"。
- 抽屉的关闭规则是结构性的：侧栏里 `iconButton` 类的按钮（行操作「…」、归档、置顶、搜索、视图、添加工作区）一律不关；`[role=treeitem]` 中只有会话行（没有 `aria-expanded`）会关，工作区行只展开。这样换语言也不会失灵——`工作区“B”的操作` 那种按钮既没有 `aria-haspopup` 也没有 `aria-expanded`。
- 从客户机 `/root` 开始；`上级` 回退，文件夹点进去，文件显示大小，`.` 开头的目录不列，路径都过 `getCanonicalPath()` 校验防符号链接逃逸。
- 点文件名就地预览：图片（png/jpg/webp/gif/bmp，≤12 MB）用 `readImage` 拿 data URL；文本（≤512 KB）用 `readText`。二进制、超大、其它格式会显示对应提示，不会白屏。
- 每行都能删：点 `删除` 变成 `确认删除`，再点一次才真删（`deleteEntry`，文件夹递归）。
- 每行前面的勾选框用于导出，`全选` / `清空` 是快捷操作。`导出 ZIP` 先出一个**文件名输入框**，预填 `<目录>-YYYYMMDD-HHmm`，改完点 `导出` 才调 `exportZip(selected.join('
'), name)`——**换行拼接而不是数组**，因为 WebView 的 JS 桥对数组不可靠。
- 文件名就是你写的那一个：`sanitizeZipName` 只替换路径分隔符和 `:*?"<>|`、折叠连续连字符、去首尾点与空白，保留中文和空格，不再补时间戳；重名时 `uniqueZipFile()` 试 `name-2.zip`…`name-999.zip`。真机实测导出「我的备份」得到 `Download/我的备份.zip`。
- `保存到 …` 显示目标位置，默认是手机 `Download`（API 29+ 走 `MediaStore`，低版本直写 `Download/`）；`更改` 调 `chooseExportDir()` 用系统文件夹选择器换目录，选完回调 `window.__bigriceExportDir`，卡片立刻刷新。

### 按钮样式

bigrice 页签里只有一套按钮规则（`.bigrice-btn` + `.bigrice-primary`）：

- 次要按钮（每张卡片右上角的 `查看文件`/`命令台`/`日志` 展开按钮，以及 `全选`、`清空`、`更改`、`停止`、`刷新`、`复制`、条目里的`删除`）透明底 + `--dsw-alias-border-l1` 描边，文字用 `--dsw-alias-label-primary`；
- 实心主按钮只留 `导出 ZIP` 与 `运行` 两个，填充 `--dsw-alias-button-primary-fill`、文字 `--dsw-alias-label-primary-inverted`，hover 也保持这个填充；
- 日志文件切换是胶囊页签（`.bigrice-tab`），选中态 `bg-layer-2` + `border-l2`，和按钮区分开。

改动前两种"实心块"在深浅主题下都接近卡片底色，一列按钮看着颜色不齐，所以统一成"描边 + 单个实心主按钮"。

### 命令台

设置 → bigrice → 命令台：一行「工作目录」加一行命令输入。命令交给 App 在客户机（proot 里的 Arch/Debian）执行，输出与退出码留在卡片里，可「清空」。默认工作目录是当前工作区，也可以改成 `/root`、`/root/A` 或相对写法（相对值即相对 `/root`）。

输出是流式的：App 每 200 ms 把新增的字节推给页面，长命令能看见一行行在长；运行中按钮变成「停止」（`killShell`），结束后结果行写「已停止 · 耗时」而不是退出码。超时上限 10 分钟只作兜底，输出总量封顶 200 KB，超出会在结果行标「截断」。

「文件」卡片新增「显示隐藏文件」开关：勾上后列表会包含 `.dsh`、`.ssh` 这类点开头的条目。

## 查看日志

- 卡片标题就是「日志」，小字说明 `dsh 服务端日志、本次启动日志` 不夹带 App 版本号；右侧按钮是 `查看日志` / `收起`。
- 打开时读一次，`自动刷新` 打开后每 3 秒重读；`刷新` / `复制` 是手动入口（复制走 `navigator.clipboard`，不可用时 `toast` 提示"长按选择文字"）。
- 可切换的文件：`本次启动`（应用内存里的启动日志快照）、`dsh`（`files/br/dsh.log`，dsh 服务的 stdout/stderr）、`boot`（`files/boot.log`，只在启动失败时才存在）。
- 每个文件标出字符数与状态：`只显示最后一段`（读的是文件尾部）、`没有这个文件`。

`readLogs(maxBytes)` 的返回：

```jsonc
{
  "app": { "versionName": "0.0.36", "versionCode": 36, "package": "com.bigrice" },
  "files": [
    { "id": "run", "title": "本次启动", "chars": 1234, "missing": false, "truncated": false, "text": "…" }
  ]
}
```

`maxBytes` 是每个文件的窗口（默认 120 000，上限 1 000 000），宿主侧按 UTF-8 从文件尾部读，丢弃被截断的首行，并把 `token=…` 之类的访问令牌打码。

---

## 改样式

CSS 全部在 `client.js` 顶部的 `STYLES` 字符串里，选择器依赖 dsh 页面上的类名局部部分（打包后的类名形如 `wSkVaW_scrollBody`，所以用 `[class*='scrollBody']` 这种写法）。

**别用裸的类名片段去命中"那个框架"**。宿主的布局框架是 `mP3x-q_frame`，但聊天里图片卡片的类名是 `n-Yh7q_frame`——`[class*='_frame']` 会一起命中。0.1.2 就栽在这里：给框架写的 `grid-template-columns: 0 … !important` 落到图片卡片上（它本身是 `display:grid`），图片被塞进 0 宽的列，于是聊天里每张图都只剩一个灰框。现在只匹配**框架本体**：

```css
html[data-bigrice-mobile] [class*='_frame']:has(> [class*='centerCol']) { … }
```

`findFrame()` 同样先按这个形状找，找不到再退回"带内联 `grid-template-columns` 的那个元素"，两条路都不会选中内容卡片。

改完先跑行为自测——它用一个手写的小 DOM（`Element`/`closest`/`matches` + 够用的选择器解析）把插件真正挂载起来，驱动真实的事件处理函数：

```bash
node test/logic-test.mjs
# 117 项检查，最后一行是 {"failures":[]} 且退出码 0
```

覆盖的就是这几个曾经出问题的地方：英文界面下点「More」/账号菜单不关抽屉、行内「…」和工作区行也不关（只有会话行、顶部的「新会话」和工作区行里的「新建会话」会关）、点面板条目才关、设置面板的 sheet 对齐变量、预览版弹窗按标题关闭、刚打开的登录弹窗不会被误关、图片卡片不会被当成布局框架测量、框架规则不外溢到内容元素、右侧面板被摆成一整屏且收起时不接触摸、插件顶栏给展开的面板让位、返回键先关面板；新页签部分覆盖注册项（id/label/order）、渲染后的「查看日志」、切换日志文件、复制失败提示，以及没有宿主桥时的说明文字；命令台部分覆盖隐藏文件开关、提交到桥、运行中就能看到输出、运行中可以「停止」、流式输出不重复（测试里带一个小 hooks 运行时，`useState` 会真的重渲染，`useEffect` 按依赖数组决定是否重跑）。

### 在真机上验证

这台机器上没有能跑起来的浏览器（headless Edge 只出空白页），所以观感和点击都直接在手机上验：

```bash
# 1. 出带调试的包：manifest 里要 android:debuggable="true"，WebView 才开 CDP
cd bigrice-app/app && bash build.sh debug
# 用 release 证书重签，才能覆盖已装的正式包且保留 rootfs 与会话
java -jar "$ANDROID/build-tools/36.0.0/lib/apksigner.jar" sign   --ks keystore/release.keystore --ks-pass pass:bigrice-release --key-pass pass:bigrice-release   --v1-signing-enabled true --v2-signing-enabled true   --out build/bigrice-test.apk build/bigrice-debug.apk
adb install -r build/bigrice-test.apk

# 2. 把 WebView 的调试端口接出来
adb forward --remove-all
adb forward tcp:9222 localabstract:webview_devtools_remote_$(adb shell pidof com.bigrice | tr -d '
')
curl -s http://127.0.0.1:9222/json | head

# 3. 用 CDP 点界面并截图（_scratch/verify.mjs、verify2.mjs 就是干这个的）
node _scratch/verify2.mjs
```

`adb shell ls /sdcard/Download` 在 MSYS/Git Bash 里会被路径转换改写，记得加 `MSYS2_ARG_CONV_EXCL='*'`。

然后打包并复制进 App：

```bash
tar -cf bigrice-ui.tar bigrice-ui
cp bigrice-ui.tar ../bigrice-app/app/assets/bigrice-ui.tar
```

需要注意的两点：

- **这个 WebView 里 `position: fixed` 会画成压扁的条**，所以插件里所有浮层都改用 `position: absolute` 定位在 `#root > .frame` 内。
- 不要用字面颜色，一律走 `--dsw-alias-*` token，否则浅色主题下会翻车。

---

## 许可与来源

插件代码是本项目自写；它对接的 dsh 插件契约来自 `@deepseek-ai/dsh`（0.2.1-rc.5）。`package.json` 标了 `private: true`——它是给自己用的本地 bundle，不发布到 npm。
