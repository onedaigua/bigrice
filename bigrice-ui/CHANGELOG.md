# bigrice-ui 0.1.31 — DeepSeek Harness 0.2.2 适配

修复 DSH 0.2.2-rc.4 升级后，手机壳插件与新宿主布局叠加造成的显示/交互问题。
仅改 `client.js`（CSS 与少量 DOM/API 探测），不改任何宿主文件。

## 修复

- **双汉堡 / 双遮罩**：宿主在 <768px 自带了抽屉与 `shell.leading` 按钮。插件现在探测
  `[data-shell-leading]`，命中时置 `data-bigrice-hostdrawer`，隐藏自己的 `.bigrice-topbar`
  与 `.bigrice-mask`，把抽屉状态镜像到宿主（`data-sidebar-collapsed`），不再出现两个
  菜单图标、两层遮罩和状态不同步。
- **断点**：插件保留 768–1024 区间的自有抽屉栏（宿主此时只有窄轨道），<768 交给宿主；
  900px 下宿主不再有 leading 按钮，插件栏照常显示，无重叠。
- **会话标题行**：非宿主抽屉模式下，标题行的左缩进由 34px 调整为 56px，避开 44px
  菜单按钮；宿主抽屉模式下完全交给宿主的 `--dsh-frame-leading-clearance`。
- **全局面板双留白**：插件栏隐藏时不再给 `mainPanel[data-global-panel]` 追加
  `padding-top`（宿主已预留 48px）。
- **设置弹窗**：宿主 0.2.2 把设置改为 grid（`navTitle/nav/header/options` 四个直接子节点），
  并在 ≤767 提供全屏单列布局。插件的桌面化改写移入 `@media (min-width:768px)`，选择器
  改为匹配新结构；≤767 不再干预，并去掉 `[role=presentation]` 的 12px 内边距，设置页恢复
  真正全屏。
- **锚定弹层**：删除依赖 `style*='left'` 的居中改写（0.2.2 改用 CSS anchor 定位，规则永不命中），
  通用对话框选择器排除 `[style*='position-anchor']`，避免把锚定 popover 撑到 520px。
- **主题 token**：`--dsw-alias-text-primary` → `--dsw-alias-label-primary`。
- **layout API**：`panelInfo.get()` → `panelInfo.getSnapshot()?.activePanelId`（0.2.2 仅暴露
  getSnapshot/subscribe）。
- **安装源面板**：`[class*='registrySheet'] / [class*='installSource']` 已不存在，选择器收敛为
  `[class*='registry']`。
