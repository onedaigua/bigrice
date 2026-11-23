window.__ModuleLoader__.load({
  id: 'bigrice-ui',
  factory(require) {
    const React = require('react')
    const h = React.createElement

    /* Phone shell: the icon rail becomes an off-canvas conversation drawer, a
       top bar carries the one action a phone needs (open the list), and the
       composer sits under the thumb. Everything else keeps living in settings,
       which reads as stacked cards.

       Two rules make this work on any phone:
       - Mobile mode is measured from the host frame (the same 1024px breakpoint
         the host itself collapses at), not from a media query. A 411dp phone, a
         500dp tablet-y phone and a phone held sideways all take this path, and a
         desktop window keeps its own layout.
       - Nothing matches a translated string. Labels come from the locale
         service at click time, and the rest is structural (aria-haspopup,
         data-* attributes, module class suffixes). */

    /** The host collapses its sidebar at this viewport width (`SIDEBAR_AUTO_COLLAPSE`). */
    const MOBILE_MAX = 1024
    /** Under this frame height a phone is held sideways and needs tighter chrome. */
    const SHORT_MAX = 520

    const STYLES = `
html[data-bigrice-mobile] {
  /* One row of chrome; the drawer is wide enough to read a session title but
     never wider than the glass. */
  --bigrice-bar: 52px;
  --bigrice-drawer: min(84vw, 300px);
  --bigrice-sheet-left: 16px;
  --bigrice-sheet-right: 16px;
}

html[data-bigrice-short] {
  --bigrice-bar: 44px;
}

/* ---- Frame: the rail leaves the layout and becomes a drawer ---------------- */
/* Only the host's app frame: it is the member that owns the centre column.
   A bare [class*='_frame'] also matches content frames such as the image card
   (n-Yh7q_frame), whose zero-width grid track would collapse the picture. */
html[data-bigrice-mobile] [class*='_frame']:has(> [class*='centerCol']) {
  grid-template-columns: 0 minmax(0, 1fr) 0 !important;
  overflow: hidden !important;
}

/* The overlay seat escapes the grid so the bar can sit at the frame's top
   edge; absolute (not fixed) because this WebView scales fixed layers against
   the layout viewport. */
html[data-bigrice-mobile] [class*='overlayLayer'] {
  position: absolute !important;
  inset: 0 !important;
  z-index: 40;
  pointer-events: none;
}

html[data-bigrice-mobile] [class*='sidebarCol'] {
  position: absolute !important;
  top: 0;
  bottom: 0;
  left: 0;
  width: var(--bigrice-drawer) !important;
  max-width: 100vw !important;
  z-index: 60;
  background: var(--dsw-specific-sidebar-fill) !important;
  border-right: 0 !important;
  overflow: hidden !important;
  transform: translateX(-102%);
  visibility: hidden;
  transition: transform 200ms ease, visibility 200ms ease;
  will-change: transform;
}

/* The host sizes its panel with an inline width taken from the desktop track
   (280px). A drawer is measured in vw, so the panel has to follow the column
   instead or a narrow phone clips its right edge; the second level covers the
   wrapper the slot may put between the column and the panel. */
html[data-bigrice-mobile] [class*='sidebarCol'] > *,
html[data-bigrice-mobile] [class*='sidebarCol'] > * > * {
  width: 100% !important;
  max-width: 100% !important;
  min-width: 0 !important;
  box-sizing: border-box;
}

html[data-bigrice-mobile][data-bigrice-drawer] [class*='sidebarCol'] {
  transform: none;
  visibility: visible;
  box-shadow: 0 10px 34px rgba(0, 0, 0, 0.5);
}

/* The rail is out of flow, so the conversation column claims every track. */
html[data-bigrice-mobile] [class*='centerCol'] {
  grid-column: 1 / -1 !important;
  padding-top: var(--bigrice-bar);
}

/* A global panel (plugins, market, automation) gets its own 48px clearance
   from the host, so the plugin bar would be a second reservation above it. */
html[data-bigrice-mobile]:not([data-bigrice-hostdrawer]) [class*='mainPanel'][data-global-panel] {
  padding-top: 0 !important;
}

/* The header's expand control is the way back into the right panel; it only
   exists while that panel is collapsed, so nothing has to hide it. */

/* The host's own collapse toggle would fight the drawer's open state. */
html[data-bigrice-mobile] [class*='sidebarCol'] button[class*='_toggle'] {
  display: none !important;
}

/* A conversation already owns a header row with the title on the left and its
   actions on the right, so the menu button joins that row instead of standing
   on a bar of its own: no reserved band, one row of chrome, the title inset by
   the button, the actions still flush with the right edge. Pages without that
   row (settings, plugins, the launcher) keep the bar. */
html[data-bigrice-mobile][data-bigrice-head] {
  --bigrice-bar: 0px;
}

html[data-bigrice-mobile][data-bigrice-head] .bigrice-topbar {
  top: var(--bigrice-head-top, 0px);
  height: var(--bigrice-head-height, 44px);
}

html[data-bigrice-mobile][data-bigrice-head]:not([data-bigrice-hostdrawer]) [class*='centerCol'] [class*='header'] [class*='titleRow'] {
  padding-left: 56px;
}

/* ---- Top bar -------------------------------------------------------------- */
/* The bar is mounted everywhere but only a phone renders it; a desktop window
   keeps its own header. */
.bigrice-topbar,
.bigrice-mask {
  display: none;
}

html[data-bigrice-mobile] .bigrice-topbar {
  position: absolute;
  inset: 0 0 auto 0;
  height: var(--bigrice-bar, 52px);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 6px;
  pointer-events: none;
  z-index: 50;
}

html[data-bigrice-mobile] .bigrice-topbar button {
  pointer-events: auto;
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 12px;
  background: transparent;
  color: var(--dsw-alias-label-primary);
  transition: background 120ms ease;
}

html[data-bigrice-mobile] .bigrice-topbar button:active {
  background: var(--dsw-alias-interactive-bg-hover);
}

html[data-bigrice-mobile] .bigrice-topbar svg {
  display: block;
}

html[data-bigrice-mobile] .bigrice-mask {
  position: absolute;
  inset: 0;
  z-index: 55;
  background: rgba(0, 0, 0, 0.4);
  display: none;
}

html[data-bigrice-mobile][data-bigrice-drawer] .bigrice-mask {
  display: block;
}

/* 0.2.2 ships the narrow-screen drawer itself: a leading hamburger in
   the shell.leading slot, a drawer mask and a transform on the sidebar
   column. When that
   drawer owns the state the plugin only mirrors it (the sync effect writes
   data-bigrice-drawer from data-sidebar-collapsed); a second bar and a second
   scrim would otherwise stack on the host's and the plugin's button, sitting on
   top of the host's, would swallow its taps. */
html[data-bigrice-mobile][data-bigrice-hostdrawer] .bigrice-topbar,
html[data-bigrice-mobile][data-bigrice-hostdrawer] .bigrice-mask {
  display: none !important;
}

/* The host reserves its own band for the leading control through
   --dsh-frame-leading-clearance, so the plugin must not add a second one. */
html[data-bigrice-mobile][data-bigrice-hostdrawer] {
  --bigrice-bar: 0px;
}

/* ---- Right panel: the host's dock as a phone screen ---------------------- */
/* The host already resolves a narrow frame by itself: below 768px it reports
   the column fullscreen, sizes the panel to 100vw and leaves the grid track at
   zero, so the panel hangs off the frame's right edge as an overlay and the
   conversation keeps the whole width behind it. The plugin only has to stage
   it as a screen of its own, and to keep the empty column from swallowing taps
   while the panel is closed. */
html[data-bigrice-mobile] [class*='rightbarCol'] {
  display: block !important;
  position: absolute !important;
  inset: 0 !important;
  z-index: 35 !important;
  pointer-events: none !important;
}

/* A collapsed panel is still in there, translated out of the clip; only the
   open pane may take input. */
html[data-bigrice-mobile] [class*='rightbarCol']:has([data-sidebar-right-open]) {
  pointer-events: auto !important;
}

/* The pane brings its own tab strip and its own collapse control, so the plugin
   bar steps aside instead of stacking a second strip above it. */
html[data-bigrice-mobile]:has([data-sidebar-right-open]) .bigrice-topbar {
  display: none;
}

/* The host sizes a document for a desktop column. On a phone the file body is
   the one place that has to grow: prose reads at 15px, code at 14px, and the
   numbered variant keeps its gutter because the width is in ch units. */
html[data-bigrice-mobile] [data-sidebar-right-panel] pre[data-textpreview-page] {
  font-size: 15px !important;
  line-height: 24px !important;
}

html[data-bigrice-mobile] [data-sidebar-right-panel] pre.shiki {
  font-size: 14px !important;
  line-height: 21px !important;
}


html[data-bigrice-mobile] .bigrice-previewSheet {
  position: absolute;
  inset: var(--bigrice-bar, 52px) 0 0 0;
  z-index: 70;
  display: flex;
  flex-direction: column;
  background: var(--dsw-alias-bg-base);
}

html[data-bigrice-mobile] .bigrice-lightbox {
  position: absolute;
  inset: 0;
  z-index: 76;
  display: grid;
  place-items: center;
  background: rgba(0, 0, 0, 0.88);
  overflow: hidden;
  touch-action: none;
}

html[data-bigrice-mobile] .bigrice-lightbox[data-text] {
  background: var(--dsw-alias-bg-layer-1);
}

html[data-bigrice-mobile] .bigrice-lightboxCloseDisc {
  fill: var(--dsw-alias-bg-layer-2);
}

html[data-bigrice-mobile] .bigrice-lightboxCloseGlyph {
  stroke: currentColor;
  stroke-width: 2.4;
  stroke-linecap: round;
  fill: none;
}

html[data-bigrice-mobile] .bigrice-lightboxImage {
  max-width: 92vw;
  max-height: 76vh;
  object-fit: contain;
  will-change: transform;
  transition: transform 60ms linear;
  user-select: none;
  -webkit-user-drag: none;
}

html[data-bigrice-mobile] .bigrice-lightboxClose {
  position: absolute;
  top: 8px;
  right: 10px;
  width: 32px !important;
  height: 32px !important;
  padding: 0 !important;
  border: 0;
  background: none;
  display: grid;
  place-items: center;
  color: var(--dsw-alias-label-primary);
  filter: drop-shadow(0 2px 8px rgba(0, 0, 0, 0.35));
}

/* The lightbox scrim is dark in both themes, so its text is a fixed light
   colour rather than the theme's label token. */
html[data-bigrice-mobile] .bigrice-lightboxText {
  margin: 0;
  padding: 56px 16px 24px;
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  overflow: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
  line-height: 1.55;
  color: var(--dsw-alias-label-primary);
  white-space: pre-wrap;
  word-break: break-word;
  user-select: text;
}

html[data-bigrice-mobile] .bigrice-previewHead {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--dsw-alias-border-l1);
}

html[data-bigrice-mobile] .bigrice-previewTitle {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 14px;
  line-height: 20px;
  color: var(--dsw-alias-label-primary);
}

html[data-bigrice-mobile] .bigrice-previewBody {
  flex: 1;
  min-height: 0;
  overflow: auto;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 12px;
}

html[data-bigrice-mobile] .bigrice-previewImage {
  max-width: 100%;
  max-height: 100%;
  border-radius: var(--dsw-radius-md);
}

html[data-bigrice-mobile] .bigrice-previewText {
  align-self: stretch;
  margin: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  line-height: 18px;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--dsw-alias-label-primary);
}


/* ---- Conversation: the composer belongs under the thumb ------------------- */
/* An auto top margin takes the free space first, so the seat lands at the
   bottom on an empty conversation yet still scrolls from the top once a
   transcript overflows. */
html[data-bigrice-mobile] [class*='composerSeat'] {
  margin-top: auto;
  padding-bottom: max(12px, env(safe-area-inset-bottom)) !important;
}


/* ---- Popovers ------------------------------------------------------------ */
/* 0.2.2 positions popovers with CSS anchor positioning and sizes them to the
   viewport itself, so they need no recentring here: the blanket width below
   skips any element that carries an anchor. */

/* ---- Dialogues: a phone-sized sheet in any language ----------------------- */
/* Every portalled modal centres a role=dialog element inside a fixed
   presentation layer. Give that layer the frame's height and the dialog a ceiling, or a
   narrow phone clips the title and the action row. */
html[data-bigrice-mobile] [role='presentation']:has(> [role='dialog']) {
  padding: 12px !important;
  overflow: hidden !important;
}

/* The settings dialog is the exception: below 768px the host paints it edge
   to edge, so the generic sheet inset would turn the full-screen page back
   into a floating card. */
html[data-bigrice-mobile] [role='presentation']:has(> [role='dialog'][data-shortcut-modal='settings']) {
  padding: 0 !important;
}

html[data-bigrice-mobile] [role='dialog']:not([data-shortcut-modal='settings']):not([style*='position-anchor']) {
  width: min(100%, 520px) !important;
  max-width: 100% !important;
  max-height: 100% !important;
  overflow-y: auto !important;
  overscroll-behavior: contain;
}

/* Menus are anchored to their trigger and sized for a desktop panel; on a
   narrow screen they must fit and scroll instead of running off the glass. */
html[data-bigrice-mobile] [role='menu'],
html[data-bigrice-mobile] [role='listbox'] {
  max-width: calc(100vw - 16px) !important;
  /* A menu is anchored to a zero-height wrapper, so a percentage cap resolves
     against that wrapper and collapses the menu to its border. Size against the
     viewport instead. */
  max-height: min(70vh, 520px) !important;
  overflow-y: auto !important;
}

/* ---- Settings: full screen, stacked cards -------------------------------- */
/* 0.2.2 has its own phone layout for this dialog (a single-column grid at
   ≤767px), so these overrides only apply to the desktop grid that still shows
   on a wide phone or a small tablet. Inside that range the panel is a grid
   (grid-template-areas: 'title header' / 'nav options') and its children
   are navTitle / nav / header / options, not the old wrapper. */
@media (min-width: 768px) {
html[data-bigrice-mobile] [role='dialog'][data-shortcut-modal='settings'] {
  position: fixed !important;
  inset: 0 !important;
  width: 100% !important;
  height: 100% !important;
  max-width: none !important;
  max-height: none !important;
  border-radius: 0 !important;
  display: flex !important;
  flex-direction: column !important;
  grid-template-columns: none !important;
  grid-template-rows: none !important;
  grid-template-areas: none !important;
  transform: none !important;
}

html[data-bigrice-mobile] [role='dialog'][data-shortcut-modal='settings'] > nav {
  display: block !important;
  width: auto !important;
  height: auto !important;
  max-height: none !important;
  flex: 0 0 auto !important;
  overflow: visible !important;
  padding: 8px 0 0 0 !important;
  border-right: 0 !important;
}

/* Close sits where a phone expects it; the desktop "open config file" action
   has no place on a touch surface. */
html[data-bigrice-mobile] [role='dialog'][data-shortcut-modal='settings'] > div[class*='header']:not([class*='navTitle']) {
  position: absolute !important;
  top: 8px;
  right: 8px;
  width: auto !important;
  height: 44px !important;
  display: flex !important;
  align-items: center !important;
  padding: 0 !important;
  z-index: 5;
}

html[data-bigrice-mobile] [role='dialog'][data-shortcut-modal='settings'] > div[class*='header']:not([class*='navTitle']) [class*='actions'] {
  display: none !important;
}

html[data-bigrice-mobile] [role='dialog'][data-shortcut-modal='settings'] > div[class*='header']:not([class*='navTitle']) button {
  width: 44px !important;
  height: 44px !important;
  border-radius: 12px !important;
}

html[data-bigrice-mobile] [role='dialog'][data-shortcut-modal='settings'] > div[class*='navTitle'] {
  font-size: 18px !important;
  font-weight: 500 !important;
  padding: 6px 56px 10px 16px !important;
}

/* The pills wrap instead of scrolling sideways: a scroll row looks broken on a
   phone because its left inset is visible while the right one is eaten by the
   clipped last pill. Wrapping keeps the same margin on both sides. */
html[data-bigrice-mobile] [role='dialog'][data-shortcut-modal='settings'] > nav > div[class*='navList'] {
  display: flex !important;
  flex-direction: row !important;
  flex-wrap: wrap !important;
  gap: 6px !important;
  justify-content: flex-start !important;
  overflow: visible !important;
  height: auto !important;
  max-height: none !important;
  margin: 0 16px 10px !important;
  padding: 0 !important;
}

html[data-bigrice-mobile] [role='dialog'][data-shortcut-modal='settings'] nav > div[class*='navList'] > button {
  flex: 0 0 auto !important;
  min-height: 36px !important;
  padding: 0 12px !important;
  font-size: 13px !important;
  border-radius: 999px !important;
  background: var(--dsw-alias-interactive-bg-hover) !important;
}

html[data-bigrice-mobile] [role='dialog'][data-shortcut-modal='settings'] nav button[aria-current],
html[data-bigrice-mobile] [role='dialog'][data-shortcut-modal='settings'] nav button[data-active] {
  background: var(--dsw-specific-sidebar-nav-item-active) !important;
}

html[data-bigrice-mobile] [role='dialog'][data-shortcut-modal='settings'] [class*='options'] {
  flex: 1 1 auto !important;
  overflow-y: auto !important;
  padding: 4px 16px calc(24px + env(safe-area-inset-bottom)) !important;
  min-width: 0 !important;
}

/* The install-source sheet is portalled to <body> with its own width, which is
   wider than the dialog it opens from. Match the dialog, and give it a solid
   panel so it does not melt into whatever is behind it. */
html[data-bigrice-mobile] [class*='registry'] {
  left: var(--bigrice-sheet-left, 16px) !important;
  right: var(--bigrice-sheet-right, 16px) !important;
  width: auto !important;
  max-width: calc(100vw - 16px) !important;
  background: var(--dsw-alias-bg-layer-2) !important;
  border: 1px solid var(--dsw-alias-border-l1) !important;
  border-radius: 16px !important;
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.5) !important;
  overflow: hidden !important;
}

html[data-bigrice-mobile] [role='dialog'][data-shortcut-modal='settings'] [class*='options'] [class*='_row']:not([class*='_rowText']),
html[data-bigrice-mobile] [role='dialog'][data-shortcut-modal='settings'] [class*='options'] [class*='_setting']:not([class*='_settingText']):not([class*='_settingTitle']) {
  background: var(--dsw-alias-bg-layer-1);
  border: 1px solid var(--dsw-alias-border-l1);
  border-radius: 14px;
  padding: 14px 16px !important;
  margin-bottom: 8px;
}

html[data-bigrice-mobile] [role='dialog'][data-shortcut-modal='settings'] [class*='options'] [class*='row'] [class*='rowText'] {
  padding-right: 8px !important;
  min-width: 0;
}

}

/* ---- Touch targets ------------------------------------------------------- */
html[data-bigrice-mobile] [role='menuitem'],
html[data-bigrice-mobile] [role='menuitemradio'],
html[data-bigrice-mobile] [role='menuitemcheckbox'] {
  min-height: 44px !important;
}

/* A phone held sideways has little height: reclaim the hero gap so the
   composer and the transcript both stay reachable. */
html[data-bigrice-short] [class*='scrollBody']:has([class*='hero']) {
  padding-top: 4vh;
}

html[data-bigrice-short] .bigrice-topbar button {
  width: 40px;
  height: 40px;
}

/* ---- bigrice settings tab ------------------------------------------------ */
.bigrice-settings {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-bottom: 8px;
}

.bigrice-card {
  border: 1px solid var(--dsw-alias-settings-card-stroke, var(--dsw-alias-border-l1));
  border-radius: var(--dsw-radius-lg);
  background: var(--dsw-alias-settings-card-fill, var(--dsw-alias-bg-layer-1));
  padding: 14px 16px;
}

.bigrice-cardHead {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  justify-content: space-between;
}

.bigrice-cardTitle {
  font-size: 14px;
  line-height: 22px;
  color: var(--dsw-alias-label-primary);
}

.bigrice-cardHint {
  margin-top: 2px;
  font-size: 12px;
  line-height: 18px;
  color: var(--dsw-alias-label-secondary);
}


.bigrice-cardActions {
  display: flex;
  flex: none;
  gap: 8px;
}

.bigrice-files {
  display: flex;
  flex-direction: column;
  margin-top: 10px;
  max-height: 38vh;
  overflow-y: auto;
}

.bigrice-file {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 34px;
  border-top: 1px solid var(--dsw-alias-border-l1);
  font-size: 13px;
}

.bigrice-fileName {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--dsw-alias-label-primary);
}

.bigrice-fileSize {
  flex: none;
  font-variant-numeric: tabular-nums;
  color: var(--dsw-alias-label-secondary);
}

.bigrice-fileDel {
  height: 26px;
  padding: 0 10px;
  font-size: 12px;
}

html[data-bigrice-mobile] .bigrice-fileDel {
  height: 30px;
  padding: 0 12px;
}

.bigrice-btn {
  flex: none;
  height: 32px;
  padding: 0 12px;
  border-radius: var(--dsw-radius-md);
  border: 1px solid var(--dsw-alias-border-l1);
  background: transparent;
  color: var(--dsw-alias-label-primary);
  font: inherit;
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
}

.bigrice-btn:hover {
  background: var(--dsw-alias-interactive-bg-hover);
}

.bigrice-btn:disabled {
  opacity: 0.45;
  cursor: default;
}

html[data-bigrice-mobile] .bigrice-btn {
  height: 36px;
  padding: 0 14px;
}

.bigrice-viewer {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
}

.bigrice-tabs {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.bigrice-tab {
  height: 30px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid var(--dsw-alias-border-l1);
  background: transparent;
  color: var(--dsw-alias-label-secondary);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.bigrice-tab[data-active] {
  background: var(--dsw-alias-bg-layer-2);
  color: var(--dsw-alias-label-primary);
  border-color: var(--dsw-alias-border-l2);
}

.bigrice-spacer {
  flex: 1 1 auto;
}

.bigrice-check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--dsw-alias-label-secondary);
  cursor: pointer;
}

.bigrice-meta {
  font-size: 12px;
  color: var(--dsw-alias-label-secondary);
}

.bigrice-error {
  font-size: 13px;
  color: var(--dsw-alias-state-error-primary);
}

.bigrice-log {
  margin: 0;
  padding: 10px 12px;
  max-height: 46vh;
  overflow: auto;
  border-radius: var(--dsw-radius-md);
  border: 1px solid var(--dsw-alias-border-l1);
  background: var(--dsw-alias-bg-base);
  color: var(--dsw-alias-label-primary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11.5px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
  -webkit-user-select: text;
  user-select: text;
}

.bigrice-browser {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.bigrice-path {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.bigrice-pathText {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  color: var(--dsw-alias-label-secondary);
  direction: rtl;
  text-align: left;
}

.bigrice-file[data-selected] {
  background: var(--dsw-alias-bg-layer-2);
  border-color: var(--dsw-alias-border-l2);
}

.bigrice-fileTick {
  flex: none;
  width: 18px;
  height: 18px;
  margin: 0;
  accent-color: var(--dsw-alias-brand-primary);
}

.bigrice-fileOpen {
  flex: 1;
  min-width: 0;
  padding: 0;
  border: 0;
  background: none;
  font: inherit;
  color: var(--dsw-alias-label-primary);
  text-align: left;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}

.bigrice-exportBar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.bigrice-btn.bigrice-primary,
.bigrice-btn.bigrice-primary:hover {
  background: var(--dsw-alias-button-primary-fill);
  border-color: var(--dsw-alias-button-primary-fill);
  color: var(--dsw-alias-label-primary-inverted);
}

.bigrice-preview {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.bigrice-previewImage {
  display: block;
  max-width: 100%;
  max-height: 60vh;
  margin: 0 auto;
  object-fit: contain;
  border-radius: var(--dsw-radius-md);
  border: 1px solid var(--dsw-alias-border-l1);
  background: var(--dsw-alias-bg-base);
}

.bigrice-console {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 10px;
}

.bigrice-consoleBar,
.bigrice-consoleForm {
  display: flex;
  align-items: center;
  gap: 8px;
}

.bigrice-consoleCwd,
.bigrice-consoleCmd {
  flex: 1 1 auto;
  min-width: 0;
}

.bigrice-consoleCmd {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.bigrice-consoleView {
  max-height: min(40vh, 360px);
  margin: 0;
}


.bigrice-browser {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 10px;
  padding: 10px;
  border-radius: var(--dsw-radius-lg);
  border: 1px solid var(--dsw-alias-border-l1);
  background: var(--dsw-alias-bg-layer-1);
}

.bigrice-files {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 0;
  max-height: min(46vh, 420px);
  overflow-y: auto;
  padding: 6px;
  border-radius: var(--dsw-radius-md);
  border: 1px solid var(--dsw-alias-border-l1);
  background: var(--dsw-alias-bg-layer-1);
}

.bigrice-file {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 38px;
  padding: 0 8px;
  border-radius: var(--dsw-radius-sm);
  border: 1px solid transparent;
}

.bigrice-fileSize {
  flex: none;
  font-size: 12px;
  color: var(--dsw-alias-label-tertiary);
}

.bigrice-fileDel {
  flex: none;
  height: 28px;
  padding: 0 10px;
  font-size: 12px;
}

.bigrice-browseEmpty {
  padding: 18px 10px;
  text-align: center;
}

.bigrice-naming {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.bigrice-input {
  flex: 1;
  min-width: 140px;
  height: 34px;
  padding: 0 10px;
  border-radius: var(--dsw-radius-md);
  border: 1px solid var(--dsw-alias-border-l2);
  background: var(--dsw-alias-bg-layer-1);
  color: var(--dsw-alias-label-primary);
  font: inherit;
  font-size: 13px;
}

.bigrice-previewHead {
  display: flex;
  align-items: center;
  gap: 8px;
}

.bigrice-previewName {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--dsw-alias-label-primary);
}

.bigrice-preview {
  min-height: 0;
  overflow: auto;
}
`

    function IconMenu() {
      return h('svg', { width: 22, height: 22, viewBox: '0 0 24 24', fill: 'none',
        stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round', 'aria-hidden': true },
      h('path', { d: 'M3.5 7h17' }), h('path', { d: 'M3.5 12h17' }), h('path', { d: 'M3.5 17h17' }))
    }

    /** A side panel drawn beside the conversation: the phone's preview entry. */

    /** True while the centre column shows a side panel instead of the chat. */
    let panelOpen = false

    /** Last click inside the app, so an auto-opened panel is never content the
     *  host restored at launch. */
    let lastUserClickAt = 0

    /** Automatic launch modals only exist while the page is young; anything the
     *  user opens afterwards is theirs to close. */
    let launchUntil = 0

    /** Collapses runs of whitespace so a translated label and its DOM text meet. */
    function normalize(value) {
      return String(value || '').replace(/\s+/g, ' ').trim()
    }

    /** Reads one dictionary entry at call time, so a language switch applies live.
     *  An unregistered namespace answers the key itself, which is not a label. */
    function translate(ctx, ns, key, fallback) {
      try {
        const bind = ctx.locale && typeof ctx.locale.bind === 'function' ? ctx.locale.bind(ns) : null
        const value = typeof bind === 'function' ? bind(key) : null
        if (typeof value === 'string' && value.length > 0 && value !== key) return value
      } catch (error) { /* the dictionary may not be loaded yet */ }
      return fallback || ''
    }

    /** Every string this plugin has to recognise, resolved for the current locale.
     *  A control is identified by structure, so only the two launch dialogues
     *  and their close affordance need words. */
    function labels(ctx) {
      const close = translate(ctx, 'settings.account', 'close', '') ||
        translate(ctx, 'common', 'close', '')
      return {
        close,
        loginTitle: translate(ctx, 'settings.account', 'loginTitle', ''),
        welcomeTitle: translate(ctx, 'settings.models', 'welcomeTitle', ''),
        welcomeContinue: translate(ctx, 'settings.models', 'welcomeContinue', ''),
      }
    }

    /** True when `text` is one of `values` (an entry may be empty). */
    function isOneOf(text, values) {
      const candidate = normalize(text)
      if (candidate === '') return false
      return values.some(value => value !== '' && normalize(value) === candidate)
    }

    const CLOSE_TEXTS = ['关闭', 'Close', 'Dismiss', '关闭对话框']

    function setFlag(root, name, on) {
      if (on && !root.hasAttribute(name)) root.setAttribute(name, '')
      if (!on && root.hasAttribute(name)) root.removeAttribute(name)
    }

    /** The host's own frame element, which carries the real column widths. A
     *  content frame (an image card, a diff card) also ends in `_frame`, so the
     *  lookup requires the centre column or, failing `:has`, an inline track. */
    function findFrame() {
      let byShape = null
      try {
        byShape = document.querySelector('[class*="_frame"]:has(> [class*="centerCol"])')
      } catch (error) { /* a selector engine without :has falls through */ }
      if (byShape instanceof HTMLElement) return byShape
      const candidates = document.querySelectorAll('[class*="_frame"], [class*="frame"]')
      for (const candidate of candidates) {
        if (candidate instanceof HTMLElement && candidate.style && candidate.style.gridTemplateColumns) return candidate
      }
      return null
    }

    /** True when the host renders the sidebar as the collapsed icon rail. */
    function railCollapsed() {
      const frame = findFrame()
      if (!frame) return true
      if (frame.hasAttribute('data-sidebar-collapsed')) return true
      const tracks = frame.style.gridTemplateColumns || ''
      if (tracks === '') return true
      return Number.parseFloat(tracks) < 100
    }

    function closeDrawer(ctx) {
      const root = document.documentElement
      if (!root.hasAttribute('data-bigrice-drawer')) return
      root.removeAttribute('data-bigrice-drawer')
      if (!railCollapsed()) ctx.layout?.toggleSidebar?.()
    }

    function openDrawer(ctx) {
      if (railCollapsed()) ctx.layout?.toggleSidebar?.()
      document.documentElement.setAttribute('data-bigrice-drawer', '')
    }

    /** Drops focus from the composer. The host focuses its editor on load and
     *  again around menu buttons, which pops the soft keyboard over whatever
     *  the user just opened. */
    /** Taps that open the command sheet must not leave the keyboard up. The
     *  composer is a contenteditable, so a script blur alone keeps the IME
     *  visible on Android; the host also drops it through its window token. */
    function blurComposer() {
      const seat = document.querySelector('[class*="composerSeat"]')
      const editor = seat ? seat.querySelector('[role="textbox"]') : null
      if (editor && document.activeElement === editor && typeof editor.blur === 'function') {
        editor.blur()
      }
      const host = bridge()
      if (host && typeof host.hideKeyboard === 'function') host.hideKeyboard()
    }

    /** The workspace row carries its own "new session" button next to "…".
     *  Its label is the localized `workspace.actions.newSession.aria`, which
     *  wraps the workspace name, so rebuild the expected label from the row's
     *  own title rather than hard-coding a phrase. */
    function isWorkspaceNewSession(ctx, button) {
      const label = button.getAttribute && button.getAttribute('aria-label')
      if (!label) return false
      const template = translate(ctx, 'workspace', 'actions.newSession.aria', '')
      if (!template || template.indexOf('{name}') < 0) return false
      const row = button.closest('[role="treeitem"]')
      const title = row ? row.querySelector('[class*="title"]') : null
      const name = title && typeof title.textContent === 'string' ? title.textContent.trim() : ''
      if (!name) return false
      return template.split('{name}').join(name) === label
    }

    /** A button that opens a popover keeps the drawer open: its menu is anchored
     *  to a button inside the drawer, so closing would take the menu with it. */
    function isMenuTrigger(button) {
      if (button.hasAttribute('aria-haspopup')) return true
      if (button.hasAttribute('aria-expanded')) return true
      return button.closest('[class*="moreAnchor"]') !== null
    }

    /** The sidebar panel carries the desktop track width (280px) inline, so on a
     *  wider drawer it stops short of the right edge and leaves an empty strip.
     *  The inline width is dropped while the phone shell is on and put back when
     *  a desktop window returns; the value is remembered per node. */
    function stretchSidebar(mobile) {
      const col = document.querySelector('[class*="sidebarCol"]')
      if (!col) return
      for (const node of [col, ...col.querySelectorAll('*')]) {
        if (!(node instanceof HTMLElement)) continue
        const saved = node.dataset.bigriceWidth
        const inline = node.style.width
        if (!mobile) {
          if (saved !== undefined) {
            node.style.width = saved
            delete node.dataset.bigriceWidth
          }
          continue
        }
        if (saved === undefined && /^\d+(\.\d+)?px$/.test(inline)) {
          node.dataset.bigriceWidth = inline
        }
        // A re-render may write the width back; clearing it again is free.
        if (inline !== '') node.style.width = ''
      }
    }

    /** The conversation header is a single row: a leading slot, the title and
     *  the two actions. While that row is on screen the menu button is placed
     *  against it and the phone bar stops reserving a band of its own, so the
     *  title and both drawers share one line. The row is measured instead of
     *  assumed: the header keeps a padding above it and its height follows the
     *  text size. */
    let headTop = -1
    let headHeight = -1
    function syncHeaderRow() {
      const root = document.documentElement
      const frame = root.hasAttribute('data-bigrice-mobile') ? findFrame() : null
      const row = frame ? frame.querySelector('[class*="centerCol"] [class*="header"] [class*="titleRow"]') : null
      if (!row) {
        setFlag(root, 'data-bigrice-head', false)
        headTop = -1
        headHeight = -1
        return
      }
      // Turning the flag on drops the bar, which moves the row up: read the
      // rect afterwards so the button lands on the row it was measured from.
      setFlag(root, 'data-bigrice-head', true)
      const rect = row.getBoundingClientRect()
      const top = Math.max(0, Math.round(rect.top - frame.getBoundingClientRect().top))
      const height = Math.max(28, Math.round(rect.height))
      if (top !== headTop) {
        headTop = top
        root.style.setProperty('--bigrice-head-top', `${top}px`)
      }
      if (height !== headHeight) {
        headHeight = height
        root.style.setProperty('--bigrice-head-height', `${height}px`)
      }
    }

    /* ---- bigrice settings tab ---------------------------------------------- */
    /** Settings-tab id and the dictionary namespace this plugin owns. */
    const BIGRICE_SECTION_ID = 'bigrice'
    const BIGRICE_NS = 'bigrice.ui'

    /** Per-file text window asked of the host bridge. */
    const LOG_WINDOW = 160000

    const BIGRICE_TEXT = {
      zh: {
        nav: 'bigrice',
        intro: '日志',
        logs: '查看日志',
        logsHint: 'dsh 服务端日志、本次启动日志',
        refresh: '刷新',
        copy: '复制',
        copied: '已复制到剪贴板',
        copyFailed: '复制失败，可以长按选择文字',
        auto: '自动刷新',
        hide: '收起',
        unavailable: '日志只能在 bigrice App 里读；在浏览器里打开时没有这条通道。',
        empty: '还没有内容。',
        missing: '没有这个文件',
        truncated: '只显示最后一段',
        readFailed: '读取失败，请重试。',
        chars: '字符',
        files: '文件',
        filesHint: '点“查看文件”可浏览、预览、导出和删除',
        filesEmpty: '这个文件夹还是空的。',
        hidden: '显示隐藏文件',
        shell: '命令台',
        shellHint: '在这台设备上执行 Linux 命令，输出显示在下面',
        shellEmpty: '还没有执行过命令。',
        shellRun: '输入命令，回车执行',
        workDir: '工作目录',
        run: '运行',
        running: '运行中…',
        clearOutput: '清空',
        stop: '停止',
        stopped: '已停止',
        shellFailed: '命令台只能在 App 内使用',
        exitCode: '退出码',
        timedOut: '超时',
        filesUnavailable: '文件管理只能在 bigrice App 里用。',
        change: '更改',
        open: '打开',
        browse: '查看文件',
        browseHint: '浏览 /root，选中文件或文件夹后打包导出',
        up: '上级',
        close: '关闭',
        loading: '正在读取…',
        browseEmpty: '这个文件夹是空的。',
        browseUnavailable: '文件浏览只能在 bigrice App 里用。',
        browseFailed: '读不到这个文件夹。',
        selectAll: '全选',
        clear: '清空',
        selected: '已选',
        export: '导出 ZIP',
        exporting: '正在打包…',
        exported: '已导出到',
        exportFailed: '导出失败',
        nothingSelected: '先选中文件或文件夹',
        saveTo: '保存到',
        previewFailed: '打不开这个文件',
        previewRemote: '这个文件不在手机里，无法预览',
        previewBinary: '二进制文件，不支持预览',
        previewUnsupported: '这个格式不支持预览',
        previewTooBig: '文件太大，不支持预览',
        filesReadFailed: '读不到这个文件夹。',
        delete: '删除',
        deleteConfirm: '确认删除',
        deleted: '已删除',
        deleteFailed: '删除失败',
        zipName: '文件名',
        exportNow: '导出',
        cancel: '取消',
        update: '更新',
        updateHint: 'dsh 跟 npm 最新版走，启动时不再自动检查；这里手动检查或安装',
        check: '检查更新',
        checking: '正在检查更新…',
        install: '安装最新版',
        installing: '正在安装，完成后会自动重启 dsh',
        current: '当前版本',
        updateAvailable: '有新版本可用',
        updateBusy: '已经有更新任务在跑，请稍等',
        updateFailed: '更新操作没成功',
        updateUnavailable: '更新只能在 bigrice App 里操作',
        updateUnknown: '未知',
        updateState: '状态',
        updating: '正在更新…',
        latest: '已是最新',
      },
      en: {
        nav: 'bigrice',
        intro: 'Logs',
        logs: 'View logs',
        logsHint: 'dsh server log and this boot log',
        refresh: 'Refresh',
        copy: 'Copy',
        copied: 'Copied to the clipboard',
        copyFailed: 'Copy failed — select the text instead',
        auto: 'Auto refresh',
        hide: 'Hide',
        unavailable: 'Logs are only readable inside the bigrice app; a plain browser has no channel to them.',
        empty: 'Nothing here yet.',
        missing: 'not there',
        truncated: 'showing the tail only',
        readFailed: 'Could not read the log. Try again.',
        chars: 'chars',
        files: 'Files',
        filesHint: 'Open the browser to preview, export or delete files',
        filesEmpty: 'This folder is empty.',
        hidden: 'Hidden files',
        shell: 'Console',
        shellHint: 'Run a Linux command on this device; the output stays below',
        shellEmpty: 'No command yet.',
        shellRun: 'Type a command and press enter',
        workDir: 'Working folder',
        run: 'Run',
        running: 'Running…',
        clearOutput: 'Clear',
        stop: 'Stop',
        stopped: 'Stopped',
        shellFailed: 'The console only works inside the app',
        exitCode: 'Exit',
        timedOut: 'Timed out',
        filesUnavailable: 'File management only works inside the bigrice app.',
        change: 'Change',
        open: 'Open',
        browse: 'Browse files',
        browseHint: 'Browse /root, pick files or folders, export a zip',
        up: 'Up',
        close: 'Close',
        loading: 'Reading…',
        browseEmpty: 'This folder is empty.',
        browseUnavailable: 'File browsing only works inside the bigrice app.',
        browseFailed: 'Could not read that folder.',
        selectAll: 'Select all',
        clear: 'Clear',
        selected: 'selected',
        export: 'Export zip',
        exporting: 'Zipping…',
        exported: 'Saved to',
        exportFailed: 'Export failed',
        nothingSelected: 'Pick something first',
        saveTo: 'Save to',
        previewFailed: 'Could not open that file',
        previewRemote: 'This file is not inside the phone, so it cannot be previewed',
        previewBinary: 'Binary file, no preview',
        previewUnsupported: 'No preview for this format',
        previewTooBig: 'File too large to preview',
        filesReadFailed: 'Could not read that folder.',
        delete: 'Delete',
        deleteConfirm: 'Confirm',
        deleted: 'Deleted',
        deleteFailed: 'Could not delete',
        zipName: 'File name',
        exportNow: 'Export',
        cancel: 'Cancel',
        update: 'Updates',
        updateHint: 'dsh follows the newest npm release; startup no longer checks — use this card',
        check: 'Check',
        checking: 'Checking for updates…',
        install: 'Install newest',
        installing: 'Installing; dsh restarts when it finishes',
        current: 'Running',
        updateAvailable: 'Newer version available',
        updateBusy: 'An update is already running',
        updateFailed: 'The update did not start',
        updateUnavailable: 'Updates only work inside the bigrice app',
        updateUnknown: 'unknown',
        updateState: 'Status',
        updating: 'Updating…',
        latest: 'Already newest',
      },
    }

    /** Listeners waiting for the export folder to change. */
    const exportDirListeners = new Set()

    /** Tells every mounted bigrice tab that the export folder changed. */
    function announceExportDir() {
      for (const listener of exportDirListeners) {
        try {
          listener()
        } catch (error) { /* one broken listener must not stop the rest */ }
      }
    }

    /** Listeners for dsh update frames coming from the app. */
    const updateListeners = new Set()

    /** Hands one update frame to every mounted update card. */
    function announceUpdate(payload) {
      const event = payload && typeof payload === 'object' ? payload : {}
      for (const listener of updateListeners) {
        try {
          listener(event)
        } catch (error) { /* one broken listener must not stop the rest */ }
      }
    }

    /** Console jobs that have not reported back yet, keyed by the app's id. */
    const shellPending = new Map()

    /** Hands one finished command to the line waiting for it. */
    function settleShell(id, payload) {
      const resolve = shellPending.get(id)
      if (!resolve) return
      shellPending.delete(id)
      resolve(payload && typeof payload === 'object' ? payload : null)
    }

    /** Consoles waiting for live output, one callback per mounted card. */
    const shellChunkListeners = new Set()

    /** Hands one slice of output to every console that is listening. */
    function emitShellChunk(id, text) {
      for (const listener of shellChunkListeners) {
        try {
          listener(id, text)
        } catch (error) { /* one broken listener must not stop the rest */ }
      }
    }

    /**
     * Starts a command and hands back its id plus a promise for the exit.
     *
     * <p>The id is needed before the command finishes: output arrives in
     * chunks and the Stop button targets that exact job.
     */
    function startShell(host, command, cwd) {
      let settle
      const done = new Promise((resolve) => { settle = resolve })
      let id = ''
      try {
        id = host.runShell(command, cwd)
      } catch (error) {
        settle(null)
        return { id: '', done }
      }
      if (!id) {
        settle(null)
        return { id: '', done }
      }
      shellPending.set(id, settle)
      return { id, done }
    }

    /** A compact byte count: `12 KB`, `3.4 MB`, `—` for a directory. */
    function sizeLabel(bytes) {
      if (typeof bytes !== 'number' || !(bytes > 0)) return ''
      if (bytes < 1024) return `${bytes} B`
      if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
      return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
    }

    /** The guest path of the workspace the main view is on, or null. */
    function currentWorkspacePath(ctx) {
      const workspace = ctx.uiWorkspace
      if (!workspace || !workspace.sessions || !workspace.workspaces) return null
      try {
        const selection = workspace.selection
          && typeof workspace.selection.getSnapshot === 'function'
          ? workspace.selection.getSnapshot()
          : null
        const sessions = workspace.sessions.list.getSnapshot()
        const summary = selection && selection.sessionId ? sessions.byId[selection.sessionId] : null
        if (summary && typeof summary.cwd === 'string' && summary.cwd !== '') return summary.cwd
        const items = workspace.workspaces.list.getSnapshot().items || []
        for (const item of items) {
          if (item && typeof item.path === 'string' && item.path !== '') return item.path
        }
      } catch (error) { /* the next tick retries */ }
      return null
    }

    /** The last import folder handed to the app, so a tick is a no-op. */
    let lastImportDir = ''

    /** Keeps the app's import folder on `<workspace>/download`.
     *  The host's file API is workspace-relative (`/root` is the workspace itself),
     *  so the folder goes in as a guest path. Passing it an absolute host path made
     *  the host create that whole path *inside* the workspace. */
    function followWorkspace(ctx) {
      const host = bridge()
      if (!host || typeof host.setImportDir !== 'function') return
      const target = 'download'
      if (target === lastImportDir) return
      try {
        const result = JSON.parse(host.setImportDir(target))
        if (result && result.ok) lastImportDir = target
      } catch (error) { /* the next tick retries */ }
    }

    /** The `BigriceHost` bridge, or null when the page runs in a plain browser. */
    function bridge() {
      const host = typeof window.BigriceHost === 'object' ? window.BigriceHost : null
      return host || null
    }

    /** A readable default for the zip name: the open folder plus a timestamp. */
    function suggestedZipName(guest) {
      const parts = String(guest || '').split('/').filter(Boolean)
      const base = parts.length > 0 ? parts[parts.length - 1] : 'files'
      const now = new Date()
      const pad = (value) => String(value).padStart(2, '0')
      return `${base}-${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
        + `-${pad(now.getHours())}${pad(now.getMinutes())}`
    }

    /** The dsh runtime updater: shows the version the app is running and asks
     *  the app to check npm or install the newest release. The app owns the
     *  work (it can download while this dialog is closed) and reports back
     *  through `window.__bigriceUpdate`. */
    function UpdateCard({ t }) {
      const [info, setInfo] = React.useState(null)
      const [notice, setNotice] = React.useState('')
      const [busy, setBusy] = React.useState(false)
      const [pct, setPct] = React.useState(-1)

      const refresh = React.useCallback(() => {
        const host = bridge()
        if (!host || typeof host.updateInfo !== 'function') return
        try {
          const next = JSON.parse(host.updateInfo())
          if (next && typeof next === 'object') {
            setInfo(next)
            if (typeof next.updating === 'boolean' && !next.updating) setBusy(false)
          }
        } catch (error) { /* keep the last snapshot */ }
      }, [])

      React.useEffect(() => {
        refresh()
        const timer = setInterval(refresh, 5000)
        updateListeners.add(refresh)
        return () => {
          clearInterval(timer)
          updateListeners.delete(refresh)
        }
      }, [refresh])

      React.useEffect(() => {
        const onEvent = (event) => {
          if (event.msg) setNotice(event.msg)
          if (typeof event.pct === 'number' && event.pct >= 0) setPct(event.pct)
          if (event.phase === 'done' || event.phase === 'failed' || event.phase === 'latest') {
            setBusy(false)
          } else if (event.updating === true) {
            setBusy(true)
          }
          refresh()
        }
        updateListeners.add(onEvent)
        return () => { updateListeners.delete(onEvent) }
      }, [refresh])

      const start = (name, started) => {
        const host = bridge()
        if (!host || typeof host[name] !== 'function') {
          setNotice(t('updateUnavailable'))
          return
        }
        try {
          if (host[name]()) {
            setBusy(true)
            setPct(-1)
            setNotice(started)
          } else {
            setNotice(t('updateBusy'))
          }
        } catch (error) {
          setNotice(t('updateFailed'))
        }
      }

      const version = info && info.version ? info.version : t('updateUnknown')
      const available = info && info.available ? info.available : ''
      const state = info && info.state ? info.state : ''
      const line = notice
        || (busy ? t('updating')
          : available ? `${t('updateAvailable')} ${available}`
            : state ? `${t('updateState')} ${state}` : '')
      const host = bridge()

      return h('div', { className: 'bigrice-card' },
        h('div', { className: 'bigrice-cardHead' },
          h('div', null,
            h('div', { className: 'bigrice-cardTitle' }, t('update')),
            h('div', { className: 'bigrice-cardHint' }, t('updateHint'))),
          h('div', { className: 'bigrice-cardActions' },
            h('button', {
              type: 'button',
              className: 'bigrice-btn',
              disabled: busy || !host,
              onClick: () => start('checkUpdate', t('checking')),
            }, t('check')),
            h('button', {
              type: 'button',
              className: 'bigrice-btn',
              disabled: busy || !host,
              onClick: () => start('applyUpdate', t('installing')),
            }, t('install')))),
        h('div', { className: 'bigrice-meta' }, `${t('current')} ${version}`),
        line === '' ? null : h('div', { className: 'bigrice-meta' },
          pct >= 0 && busy ? `${line} · ${pct}%` : line))
    }

    /** The bigrice settings tab: where imports land, the file browser and the
     *  log viewer. The browser opens inside its own bordered card so a long
     *  listing scrolls there instead of pushing the page around. */
    function BigriceSection({ t, ctx }) {
      const [open, setOpen] = React.useState(false)
      const [consoleOpen, setConsoleOpen] = React.useState(false)
      const [browsing, setBrowsing] = React.useState(false)
      const [snapshot, setSnapshot] = React.useState(null)
      const [failure, setFailure] = React.useState('')
      const [fileId, setFileId] = React.useState('dsh')
      const [auto, setAuto] = React.useState(false)
      const logRef = React.useRef(null)

      const load = React.useCallback(() => {
        const host = bridge()
        if (!host || typeof host.readLogs !== 'function') {
          setFailure(t('unavailable'))
          return
        }
        try {
          setSnapshot(JSON.parse(host.readLogs(LOG_WINDOW)))
          setFailure('')
        } catch (error) {
          setFailure(t('readFailed'))
        }
      }, [t])

      React.useEffect(() => {
        if (open) load()
      }, [open, load])

      React.useEffect(() => {
        if (!open || !auto) return undefined
        const timer = setInterval(load, 3000)
        return () => clearInterval(timer)
      }, [open, auto, load])

      const files = (snapshot && snapshot.files) || []
      const current = files.find(file => file.id === fileId) || files[0] || null
      const text = current ? current.text || '' : ''

      // A log reader is read from the bottom up; keep the newest lines in view
      // while auto refresh is on.
      React.useEffect(() => {
        const node = logRef.current
        if (!auto || !node) return
        node.scrollTop = node.scrollHeight
      }, [auto, text])

      const copy = () => {
        const host = bridge()
        const done = (ok) => {
          if (host && typeof host.toast === 'function') {
            host.toast(ok ? t('copied') : t('copyFailed'))
          }
        }
        const body = text
        try {
          const clip = typeof navigator !== 'undefined' ? navigator.clipboard : null
          if (clip && typeof clip.writeText === 'function') {
            clip.writeText(body).then(() => done(true), () => done(false))
            return
          }
        } catch (error) { /* fall through to the failure notice */ }
        done(false)
      }


      const filesCard = h('div', { className: 'bigrice-card' },
        h('div', { className: 'bigrice-cardHead' },
          h('div', null,
            h('div', { className: 'bigrice-cardTitle' }, t('files')),
            h('div', { className: 'bigrice-cardHint' }, t('filesHint'))),
          h('button', {
            type: 'button',
            className: 'bigrice-btn',
            onClick: () => setBrowsing(value => !value),
          }, browsing ? t('hide') : t('browse'))),
        browsing ? h(FileBrowser, { t }) : null)

      const logsCard = h('div', { className: 'bigrice-card' },
        h('div', { className: 'bigrice-cardHead' },
          h('div', null,
            h('div', { className: 'bigrice-cardTitle' }, t('intro')),
            h('div', { className: 'bigrice-cardHint' }, t('logsHint'))),
          h('button', {
            type: 'button',
            className: 'bigrice-btn',
            onClick: () => setOpen(value => !value),
          }, open ? t('hide') : t('logs'))),
        open ? h('div', { className: 'bigrice-viewer' },
          h('div', { className: 'bigrice-tabs' },
            files.map(file => h('button', {
              key: file.id,
              type: 'button',
              className: 'bigrice-tab',
              'data-active': current && file.id === current.id ? '' : undefined,
              onClick: () => setFileId(file.id),
            }, file.title)),
            h('span', { className: 'bigrice-spacer' }),
            h('label', { className: 'bigrice-check' },
              h('input', {
                type: 'checkbox',
                checked: auto,
                onChange: event => setAuto(Boolean(event.target.checked)),
              }),
              t('auto')),
            h('button', { type: 'button', className: 'bigrice-btn', onClick: load }, t('refresh')),
            h('button', {
              type: 'button',
              className: 'bigrice-btn',
              onClick: copy,
              disabled: text === '',
            }, t('copy'))),
          failure ? h('div', { className: 'bigrice-error' }, failure) : null,
          current ? h('div', { className: 'bigrice-meta' },
            `${current.chars} ${t('chars')}`,
            current.truncated ? ` · ${t('truncated')}` : '',
            current.missing ? ` · ${t('missing')}` : '') : null,
          current ? h('pre', { className: 'bigrice-log', ref: logRef },
            text === '' ? t('empty') : text) : null) : null)

      const consoleCard = h('div', { className: 'bigrice-card' },
        h('div', { className: 'bigrice-cardHead' },
          h('div', null,
            h('div', { className: 'bigrice-cardTitle' }, t('shell')),
            h('div', { className: 'bigrice-cardHint' }, t('shellHint'))),
          h('button', {
            type: 'button',
            className: 'bigrice-btn',
            onClick: () => setConsoleOpen(value => !value),
          }, consoleOpen ? t('hide') : t('shell'))),
        consoleOpen ? h(ConsoleCard, { t, ctx }) : null)

      const updateCard = h(UpdateCard, { t })

      return h('div', { className: 'bigrice-settings' }, updateCard, filesCard, consoleCard, logsCard)
    }

    /** The file browser: lists the guest's `/root` inside the bigrice tab,
     *  with a preview, a per-entry delete and a zipped export whose name is
     *  confirmed before it is written. */
    function FileBrowser({ t }) {
      const [path, setPath] = React.useState('')
      const [parent, setParent] = React.useState('')
      const [guest, setGuest] = React.useState('/root')
      const [listing, setListing] = React.useState([])
      const [failure, setFailure] = React.useState('')
      const [selected, setSelected] = React.useState([])
      const [preview, setPreview] = React.useState(null)
      const [exporting, setExporting] = React.useState(false)
      const [exportDir, setExportDir] = React.useState({ mode: 'download', label: 'Download' })
      const [naming, setNaming] = React.useState(false)
      const [zipName, setZipName] = React.useState('')
      const [pendingDelete, setPendingDelete] = React.useState('')
      const [hidden, setHidden] = React.useState(false)
      const hiddenRef = React.useRef(false)

      const toast = (text) => {
        const host = bridge()
        if (host && typeof host.toast === 'function') host.toast(text)
      }

      /** One directory listing; `rel` is relative to the guest's `/root`. */
      const load = React.useCallback((rel) => {
        const host = bridge()
        if (!host || typeof host.listDir !== 'function') {
          setFailure(t('browseUnavailable'))
          return
        }
        try {
          const found = JSON.parse(host.listDir(rel, hiddenRef.current))
          if (!found || found.ok === false) {
            setFailure(t('browseFailed'))
            return
          }
          setPath(typeof found.path === 'string' ? found.path : '')
          setParent(typeof found.parent === 'string' ? found.parent : '')
          setGuest(typeof found.guest === 'string' ? found.guest : '/root')
          setListing(Array.isArray(found.entries) ? found.entries : [])
          setFailure('')
          setPendingDelete('')
        } catch (error) {
          setFailure(t('browseFailed'))
        }
      }, [t])

      const loadExportDir = React.useCallback(() => {
        const host = bridge()
        if (!host || typeof host.getExportDir !== 'function') return
        try {
          const info = JSON.parse(host.getExportDir())
          if (info && typeof info.label === 'string') {
            setExportDir({ mode: info.mode || 'download', label: info.label })
          }
        } catch (error) { /* keep the default label */ }
      }, [])

      React.useEffect(() => {
        load('')
        loadExportDir()
      }, [load, loadExportDir])

      React.useEffect(() => {
        exportDirListeners.add(loadExportDir)
        return () => { exportDirListeners.delete(loadExportDir) }
      }, [loadExportDir])

      const selectedHas = (rel) => selected.indexOf(rel) >= 0

      const toggleSelected = (rel) => {
        setSelected((current) => (current.indexOf(rel) >= 0
          ? current.filter((item) => item !== rel)
          : current.concat(rel)))
      }

      const selectAll = () => setSelected(listing.map(entry => entry.rel))

      /** Opens a file in the preview panel: images as data URLs, text as text. */
      const openFile = (entry) => {
        const host = bridge()
        if (!host) return
        if (entry.dir) {
          setPreview(null)
          setSelected([])
          load(entry.rel)
          return
        }
        try {
          if (typeof host.readImage === 'function') {
            const image = JSON.parse(host.readImage(entry.rel))
            if (image && image.ok) {
              setPreview({ rel: entry.rel, name: entry.name, kind: 'image', src: image.data, note: sizeLabel(image.bytes) })
              return
            }
            if (image && image.error === 'too-big') {
              setPreview({ rel: entry.rel, name: entry.name, kind: 'notice', text: t('previewTooBig') })
              return
            }
            if (image && image.error && image.error !== 'unsupported') {
              setPreview({ rel: entry.rel, name: entry.name, kind: 'notice', text: t('previewFailed') })
              return
            }
          }
          if (typeof host.readText !== 'function') {
            setPreview({ rel: entry.rel, name: entry.name, kind: 'notice', text: t('previewUnsupported') })
            return
          }
          const body = JSON.parse(host.readText(entry.rel, 200000))
          if (body && body.ok) {
            setPreview({
              rel: entry.rel,
              name: entry.name,
              kind: 'text',
              text: body.text,
              note: body.truncated ? `${sizeLabel(body.bytes)} · ${t('truncated')}` : sizeLabel(body.bytes),
            })
            return
          }
          const reason = body && body.error
          setPreview({
            rel: entry.rel,
            name: entry.name,
            kind: 'notice',
            text: reason === 'binary' ? t('previewBinary')
              : reason === 'unsupported' ? t('previewUnsupported')
                : t('previewFailed'),
          })
        } catch (error) {
          setPreview({ rel: entry.rel, name: entry.name, kind: 'notice', text: t('previewFailed') })
        }
      }

      /** Deletes a file or a whole folder after a second, confirming tap. */
      const removeEntry = (entry) => {
        const host = bridge()
        if (!host || typeof host.deleteEntry !== 'function') return
        if (pendingDelete !== entry.rel) {
          setPendingDelete(entry.rel)
          return
        }
        setPendingDelete('')
        try {
          const result = JSON.parse(host.deleteEntry(entry.rel))
          if (result && result.ok) toast(t('deleted'))
          else toast(t('deleteFailed'))
        } catch (error) {
          toast(t('deleteFailed'))
        }
        if (preview && preview.rel === entry.rel) setPreview(null)
        setSelected((current) => current.filter((item) => item !== entry.rel))
        load(path)
      }

      /** Asks for the zip name first; the app sanitises and stamps it. */
      const startExport = () => {
        if (selected.length === 0) {
          toast(t('nothingSelected'))
          return
        }
        setZipName(suggestedZipName(guest))
        setNaming(true)
      }

      const confirmExport = () => {
        const host = bridge()
        if (!host || typeof host.exportZip !== 'function') return
        const name = zipName.trim() === '' ? suggestedZipName(guest) : zipName.trim()
        setNaming(false)
        setExporting(true)
        setTimeout(() => {
          try {
            const found = JSON.parse(host.exportZip(selected.join('\n'), name))
            if (found && found.ok) {
              toast(`${t('exported')} ${found.path} (${found.count})`)
              setSelected([])
            } else {
              toast(t('exportFailed'))
            }
          } catch (error) {
            toast(t('exportFailed'))
          }
          setExporting(false)
        }, 0)
      }

      const rows = listing.length === 0
        ? h('div', { className: 'bigrice-meta bigrice-browseEmpty' }, t('browseEmpty'))
        : h('div', { className: 'bigrice-files' },
          listing.map(entry => h('div', {
            key: entry.rel,
            className: 'bigrice-file',
            'data-selected': selectedHas(entry.rel) ? '' : undefined,
          },
            h('input', {
              type: 'checkbox',
              className: 'bigrice-fileTick',
              checked: selectedHas(entry.rel),
              onChange: () => toggleSelected(entry.rel),
            }),
            h('button', {
              type: 'button',
              className: 'bigrice-fileOpen',
              onClick: () => openFile(entry),
            }, entry.dir ? `📁 ${entry.name}` : entry.name),
            h('span', { className: 'bigrice-fileSize' },
              entry.dir ? '' : sizeLabel(entry.bytes)),
            h('button', {
              type: 'button',
              className: 'bigrice-btn bigrice-fileDel',
              onClick: () => removeEntry(entry),
            }, pendingDelete === entry.rel ? t('deleteConfirm') : t('delete')))))

      const actionBar = naming
        ? h('div', { className: 'bigrice-naming' },
          h('span', { className: 'bigrice-meta' }, t('zipName')),
          h('input', {
            type: 'text',
            className: 'bigrice-input',
            value: zipName,
            onInput: event => setZipName(event.target.value),
            onChange: event => setZipName(event.target.value),
          }),
          h('button', {
            type: 'button',
            className: 'bigrice-btn',
            onClick: () => setNaming(false),
          }, t('cancel')),
          h('button', {
            type: 'button',
            className: 'bigrice-btn bigrice-primary',
            onClick: confirmExport,
            disabled: exporting,
          }, exporting ? t('exporting') : t('exportNow')))
        : h('div', { className: 'bigrice-exportBar' },
          h('span', { className: 'bigrice-meta' }, `${t('selected')} ${selected.length}`),
          h('button', { type: 'button', className: 'bigrice-btn', onClick: selectAll }, t('selectAll')),
          h('button', {
            type: 'button',
            className: 'bigrice-btn',
            onClick: () => setSelected([]),
            disabled: selected.length === 0,
          }, t('clear')),
          h('span', { className: 'bigrice-spacer' }),
          h('button', {
            type: 'button',
            className: 'bigrice-btn bigrice-primary',
            onClick: startExport,
            disabled: exporting || selected.length === 0,
          }, t('export')))

      const previewPane = preview ? h('div', { className: 'bigrice-preview' },
        h('div', { className: 'bigrice-previewHead' },
          h('span', { className: 'bigrice-previewName' }, preview.name),
          h('span', { className: 'bigrice-meta' }, preview.note || ''),
          h('span', { className: 'bigrice-spacer' }),
          h('button', {
            type: 'button',
            className: 'bigrice-btn',
            onClick: () => setPreview(null),
          }, t('close'))),
        preview.kind === 'image'
          ? h('img', { className: 'bigrice-previewImage', src: preview.src, alt: preview.name })
          : preview.kind === 'text'
            ? h('pre', { className: 'bigrice-log' }, preview.text === '' ? t('empty') : preview.text)
            : h('div', { className: 'bigrice-meta' }, preview.text)) : null

      return h('div', { className: 'bigrice-browser' },
        h('div', { className: 'bigrice-path' },
            h('button', {
              type: 'button',
              className: 'bigrice-btn',
              onClick: () => {
                setPreview(null)
                setSelected([])
                load(parent)
              },
              disabled: path === '',
            }, t('up')),
            h('span', { className: 'bigrice-pathText' }, guest),
            h('span', { className: 'bigrice-spacer' }),
            h('label', { className: 'bigrice-check' },
              h('input', {
                type: 'checkbox',
                checked: hidden,
                onChange: (event) => {
                  const next = event && event.target
                    ? Boolean(event.target.checked)
                    : !hiddenRef.current
                  hiddenRef.current = next
                  setHidden(next)
                  load(path)
                },
              }),
              t('hidden'))),
          failure ? h('div', { className: 'bigrice-error' }, failure) : null,
          rows,
          actionBar,
          h('div', { className: 'bigrice-exportBar' },
            h('span', { className: 'bigrice-meta' }, `${t('saveTo')} ${exportDir.label}`),
            h('span', { className: 'bigrice-spacer' }),
            h('button', {
              type: 'button',
              className: 'bigrice-btn',
              onClick: () => {
                const host = bridge()
                if (host && typeof host.chooseExportDir === 'function') host.chooseExportDir()
              },
            }, t('change'))),
        previewPane)
    }

    /** One shell line and everything it printed, kept in the tab. */
    function ConsoleCard({ t, ctx }) {
      const [lines, setLines] = React.useState([])
      const [command, setCommand] = React.useState('')
      const [running, setRunning] = React.useState(false)
      const [cwd, setCwd] = React.useState(() => currentWorkspacePath(ctx) || '/root')
      const [jobId, setJobId] = React.useState('')
      const viewRef = React.useRef(null)
      const chunked = React.useRef(new Set())

      // Output is pushed while the command runs, so a long job shows progress
      // instead of a frozen card.
      React.useEffect(() => {
        const onChunk = (id, text) => {
          if (typeof text !== 'string' || text === '') return
          chunked.current.add(id)
          setLines((current) => {
            const next = current.slice()
            const last = next[next.length - 1]
            if (last && last.kind === 'out' && last.job === id) {
              next[next.length - 1] = { ...last, text: last.text + text }
            } else {
              next.push({ kind: 'out', job: id, text })
            }
            return next
          })
        }
        shellChunkListeners.add(onChunk)
        return () => { shellChunkListeners.delete(onChunk) }
      }, [])

      // The newest output is what the reader wants to see.
      React.useEffect(() => {
        const node = viewRef.current
        if (!node) return
        node.scrollTop = node.scrollHeight
      }, [lines])

      const submit = (event) => {
        if (event && typeof event.preventDefault === 'function') event.preventDefault()
        const line = command.trim()
        if (line === '' || running) return
        const host = bridge()
        if (!host || typeof host.runShell !== 'function') {
          setLines(current => current.concat({ kind: 'notice', text: t('shellFailed') }))
          return
        }
        const folder = cwd.trim() === '' ? '/root' : cwd.trim()
        setLines(current => current.concat({ kind: 'cmd', text: `${folder} $ ${line}` }))
        setCommand('')
        setRunning(true)
        const job = startShell(host, line, folder)
        setJobId(job.id)
        job.done.then((result) => {
          setRunning(false)
          setJobId('')
          if (!result) {
            setLines(current => current.concat({ kind: 'notice', text: t('shellFailed') }))
            return
          }
          // Normally every byte already arrived through the chunks; a result
          // carrying the whole output only matters when none did.
          const body = typeof result.output === 'string' ? result.output.replace(/\n$/, '') : ''
          const streamed = job.id !== '' && chunked.current.has(job.id)
          chunked.current.delete(job.id)
          const status = result.stopped
            ? t('stopped')
            : result.timedOut ? t('timedOut') : `${t('exitCode')} ${result.code}`
          const detail = `${status} · ${result.ms || 0} ms${result.truncated ? ` · ${t('truncated')}` : ''}`
          setLines(current => {
            const next = current.slice()
            if (!streamed && body !== '') next.push({ kind: 'out', job: job.id, text: body })
            next.push({ kind: 'meta', text: detail })
            return next
          })
        })
      }

      const body = lines.length === 0
        ? t('shellEmpty')
        : lines.map(line => line.text).join('\n')

      return h('div', { className: 'bigrice-console' },
        h('div', { className: 'bigrice-consoleBar' },
          h('span', { className: 'bigrice-meta' }, t('workDir')),
          h('input', {
            type: 'text',
            className: 'bigrice-input bigrice-consoleCwd',
            value: cwd,
            onInput: event => setCwd(event.target.value),
            onChange: event => setCwd(event.target.value),
          }),
          h('button', {
            type: 'button',
            className: 'bigrice-btn',
            onClick: () => setLines([]),
            disabled: lines.length === 0,
          }, t('clearOutput'))),
        h('pre', { className: 'bigrice-log bigrice-consoleView', ref: viewRef }, body),
        h('form', { className: 'bigrice-consoleForm', onSubmit: submit },
          h('span', { className: 'bigrice-meta' }, '$'),
          h('input', {
            type: 'text',
            className: 'bigrice-input bigrice-consoleCmd',
            value: command,
            placeholder: t('shellRun'),
            onInput: event => setCommand(event.target.value),
            onChange: event => setCommand(event.target.value),
          }),
          running
            ? h('button', {
              type: 'button',
              className: 'bigrice-btn',
              onClick: () => {
                const host = bridge()
                if (host && typeof host.killShell === 'function') host.killShell(jobId)
              },
            }, t('stop'))
            : h('button', {
              type: 'submit',
              className: 'bigrice-btn bigrice-primary',
              disabled: command.trim() === '',
            }, t('run'))))
    }

    function TopBar({ ctx }) {
      const onMenu = React.useCallback(() => {
        if (document.documentElement.hasAttribute('data-bigrice-drawer')) closeDrawer(ctx)
        else openDrawer(ctx)
      }, [])
      return h('div', { className: 'bigrice-topbar' },
        h('button', { type: 'button', 'aria-label': 'Menu', onClick: onMenu }, h(IconMenu)))
    }

    /** The plugin's own preview: path, name and what the bridge read back. */
    const previewListeners = new Set()
    let previewOpen = false
    let previewTarget = null

    function publishPreview() {
      for (const listener of previewListeners) listener(previewOpen, previewTarget)
    }

    /** The attachment name inside a tool row's text, if it names a file. */
    /** A display name for an image: the file name it was served from. */
    function imageLabel(img) {
      const src = String(img.currentSrc || img.src || '')
      let decoded = src
      try { decoded = decodeURIComponent(src) } catch (error) { decoded = src }
      const named = fileNameIn(decoded)
      return named || 'preview'
    }

    /** An image worth previewing: big enough, and not a decorative avatar. */
    function previewableImage(img) {
      if (!img || img.tagName !== 'IMG') return false
      const src = String(img.currentSrc || img.src || '')
      if (!src) return false
      if (src.indexOf('//static.deepseek.com/') >= 0) return false
      if (img.naturalWidth >= 64) return true
      // An image inside a collapsed tool row may not have decoded yet; its box
      // still tells us it is a picture and not an icon.
      const box = img.getBoundingClientRect()
      return box.width >= 64 || box.height >= 64
    }

    /** An <img> the click landed on, or one sitting in the same block. */
    function imageNear(target) {
      if (!target) return null
      if (previewableImage(target)) return target
      const holder = target.closest
        ? target.closest('[class*="_file"], [data-disclosure-row], [class*="message"], li, article, p')
        : null
      const scope = holder || (target.closest ? target.closest('div') : null)
      if (!scope || !scope.querySelectorAll) return null
      const images = scope.querySelectorAll('img[src]')
      for (let index = 0; index < images.length; index += 1) {
        if (previewableImage(images[index])) return images[index]
      }
      return null
    }

    /** True only for content rendered inside a conversation message: the
     *  preview is for what the model showed, not for chrome such as the
     *  sidebar avatar, the plugin cards or a settings preview. */
    function inConversation(node) {
      if (!node || !node.closest) return false
      const chrome = node.closest(
        '[class*="sidebarCol"], [class*="rightbarCol"], [class*="composerSeat"], '
        + '[class*="topbar"], [class*="topBar"], [role="dialog"], header, '
        + '.bigrice-topbar, .bigrice-lightbox, .bigrice-settings',
      )
      if (chrome) return false
      return Boolean(
        node.closest(
          '[class*="markdown"], [class*="message"], [class*="MessageBody"], '
          + '[class*="transcript"], [class*="thread"], [data-message-id]',
        ),
      )
    }

    /** A file name carried by the card the click landed on: the host renders
     *  edit summaries and attachment rows as buttons, and the phone has no
     *  sidebar to open, so those become previews. */
    function cardFileName(target) {
      if (!target || !target.closest) return ''
      const row = target.closest('button, [role="button"], [data-disclosure-row], '
        + '[class*="_card"], [class*="_file"], [class*="_editCard"]')
      if (!row) return ''
      const text = String(row.textContent || '')
      if (text.length > 240) return ''
      return fileNameIn(text)
    }

    /** The attachment name a click landed on, or '' when it is not a file. */
    function attachmentName(target) {
      if (!target || !target.closest) return ''
      const holder = target.closest('[class*="_file"], [data-disclosure-row]')
      const link = target.closest('button[class*="fileLink"], button[class*="cardPreview"]')
      const source = holder || link
      if (!source) return ''
      // A card's link button is icon-only, so its own text never names the file.
      return fileNameIn(String(source.textContent || ''))
    }

    function fileNameIn(text) {
      const match = String(text).match(/[^\s/\\]+\.(?:png|jpe?g|webp|gif|bmp|svg|txt|md|json|csv|log|zip|pdf|ts|tsx|js|jsx|py|sh|css|html|yml|yaml)\b/i)
      return match ? match[0] : ''
    }

    /** The host's right-panel service. It is not in the plugin's inject list
     *  because a build without it still has to run, so it is read on demand and
     *  a missing service simply means the panel never opens. */
    function rightbarApi(ctx) {
      try {
        const api = ctx && typeof ctx.get === 'function' ? ctx.get('sidebarRight') : null
        const usable = api && typeof api.isExpanded === 'function' &&
          typeof api.toggleExpanded === 'function'
        return usable ? api : null
      } catch (error) {
        return null
      }
    }

    function closeSidePanel(ctx) {
      const api = rightbarApi(ctx)
      if (!api || !api.isExpanded()) return false
      api.toggleExpanded()
      return true
    }

    /** The host tells the user files open "in the sidebar"; on a phone the
     *  plugin previews them instead, so that sentence is noise. It sits inside a
     *  paragraph that may also hold a line break, so walk text nodes and hide the
     *  smallest block around the sentence. */
    function hideDesktopNote() {
      if (typeof document.createTreeWalker !== 'function') return
      const filter = typeof NodeFilter === 'undefined' ? 4 : NodeFilter.SHOW_TEXT
      const walker = document.createTreeWalker(document.body, filter)
      while (walker.nextNode()) {
        const text = String(walker.currentNode.nodeValue || '')
        const chinese = text.indexOf('此主机没有可用的桌面') >= 0
        const english = text.toLowerCase().indexOf('no desktop') >= 0
        if (!chinese && !english) continue
        let host = walker.currentNode.parentElement
        for (let step = 0; step < 3 && host && host.parentElement; step += 1) {
          if (String(host.textContent || '').length <= 110) break
          host = host.parentElement
        }
        if (host && host !== document.body) host.style.display = 'none'
      }
    }

    let previewNode = null

    function previewLayer() {
      return (document.querySelector && document.querySelector('[class*="_frame"]')) || document.body
    }

    function closePreview() {
      const parent = previewNode && previewNode.parentNode
      if (parent) {
        if (typeof parent.removeChild === 'function') parent.removeChild(previewNode)
        else if (typeof previewNode.remove === 'function') previewNode.remove()
      }
      previewNode = null
      document.documentElement.removeAttribute('data-bigrice-preview')
    }

    function mountPreview(node) {
      closePreview()
      const close = document.createElement('button')
      close.type = 'button'
      close.className = 'bigrice-lightboxClose'
      close.setAttribute('aria-label', 'close')
      close.innerHTML = '<svg viewBox="0 0 44 44" width="32" height="32" aria-hidden="true">'
        + '<circle class="bigrice-lightboxCloseDisc" cx="22" cy="22" r="22"></circle>'
        + '<path class="bigrice-lightboxCloseGlyph" d="M15 15 L29 29 M29 15 L15 29"></path>'
        + '</svg>'
      close.addEventListener('click', closePreview)
      node.appendChild(close)
      previewLayer().appendChild(node)
      previewNode = node
      document.documentElement.setAttribute('data-bigrice-preview', '')
    }

    /** Pinch to zoom and drag to pan, the way a phone expects an image to
     *  behave. */
    function installZoom(stage, image) {
      let scale = 1
      let start = 0
      let base = 1
      let from = { x: 0, y: 0 }
      let offset = { x: 0, y: 0 }
      const distance = (touches) => {
        const dx = touches[0].clientX - touches[1].clientX
        const dy = touches[0].clientY - touches[1].clientY
        return Math.sqrt(dx * dx + dy * dy)
      }
      const apply = () => {
        image.style.transform = 'translate(' + offset.x + 'px, ' + offset.y + 'px) scale(' + scale + ')'
      }
      stage.addEventListener('touchstart', (event) => {
        const touches = event.touches || []
        if (touches.length === 2) {
          start = distance(touches)
          base = scale
          return
        }
        if (touches.length === 1) from = { x: touches[0].clientX, y: touches[0].clientY }
      }, { passive: true })
      stage.addEventListener('touchmove', (event) => {
        const touches = event.touches || []
        if (touches.length === 2 && start > 0) {
          scale = Math.min(6, Math.max(1, base * (distance(touches) / start)))
          apply()
          return
        }
        if (touches.length === 1 && scale > 1) {
          offset = {
            x: offset.x + (touches[0].clientX - from.x),
            y: offset.y + (touches[0].clientY - from.y),
          }
          from = { x: touches[0].clientX, y: touches[0].clientY }
          apply()
        }
      }, { passive: true })
      stage.addEventListener('touchend', () => {
        if (scale <= 1.02) {
          scale = 1
          offset = { x: 0, y: 0 }
          apply()
        }
      }, { passive: true })
    }

    function showImage(name, url) {
      const stage = document.createElement('div')
      stage.className = 'bigrice-lightbox'
      const image = document.createElement('img')
      image.className = 'bigrice-lightboxImage'
      image.alt = name || ''
      image.src = url
      stage.appendChild(image)
      installZoom(stage, image)
      mountPreview(stage)
    }

    function showText(text) {
      const stage = document.createElement('div')
      stage.className = 'bigrice-lightbox'
      stage.setAttribute('data-text', '')
      const body = document.createElement('pre')
      body.className = 'bigrice-lightboxText'
      body.textContent = text
      stage.appendChild(body)
      mountPreview(stage)
    }

    /** The dsh server serves guest files itself, which is how the conversation
     *  shows an image at all; reusing that route keeps the preview honest about
     *  what the session actually has. */
    function fileUrl(path) {
      const base = window.location && window.location.origin ? window.location.origin : ''
      if (!base) return ''
      return base + '/api/file?path=' + encodeURIComponent(path)
    }

    /** Extensions we show as text rather than trying to decode as an image. */
    const TEXTUAL_PATH = /\.(txt|md|json|csv|log|ts|tsx|js|jsx|py|sh|css|html|yml|yaml)$/i

    /** Opens whatever the tap landed on: an image the page already decoded, or
     *  the file behind a card, read through the server route the host uses. */
    function openPreview(target) {
      if (!target) return
      if (target.url) {
        showImage(target.name, target.url)
        return
      }
      const candidates = previewCandidates(window.__bigriceCtx, target.name)
      const imageFirst = candidates.filter((path) => !TEXTUAL_PATH.test(path))
      const textFirst = candidates.filter((path) => TEXTUAL_PATH.test(path))

      if (textFirst.length > 0 && typeof window.fetch === 'function') {
        let index = 0
        const tryText = () => {
          if (index >= textFirst.length) return false
          const url = fileUrl(textFirst[index])
          index += 1
          window.fetch(url)
            .then((response) => (response.ok ? response.text() : null))
            .then((text) => {
              if (text === null) {
                if (!tryText() && imageFirst.length > 0) showFirstImage(target.name, imageFirst)
              } else {
                showText(text.slice(0, 200000))
              }
            })
            .catch(() => {
              if (!tryText() && imageFirst.length > 0) showFirstImage(target.name, imageFirst)
            })
          return true
        }
        if (tryText()) return
      }
      showFirstImage(target.name, candidates)
    }

    /** Shows the first candidate that actually decodes; a broken one moves on. */
    function showFirstImage(name, candidates) {
      if (!candidates || candidates.length === 0) return
      const stage = document.createElement('div')
      stage.className = 'bigrice-lightbox'
      const image = document.createElement('img')
      image.className = 'bigrice-lightboxImage'
      image.alt = name || ''
      let index = 0
      image.onerror = () => {
        index += 1
        if (index >= candidates.length) {
          closePreview()
          return
        }
        image.src = fileUrl(candidates[index])
      }
      image.src = fileUrl(candidates[index])
      stage.appendChild(image)
      installZoom(stage, image)
      mountPreview(stage)
    }

    /** Resolves a card label against the workspace the session runs in. */
    function previewCandidates(ctx, name) {
      const base = currentWorkspacePath(ctx)
      const list = []
      if (base) list.push(base.replace(/\/+$/, '') + '/' + name)
      list.push('/root/' + name, '/root/A/' + name)
      return list.filter((path, index) => list.indexOf(path) === index)
    }

    /** Shows an image over the conversation, with pinch-to-zoom and drag, the
     *  way the desktop viewer behaves. */
    function Lightbox({ url, name, t }) {
      const [scale, setScale] = React.useState(1)
      const [offset, setOffset] = React.useState({ x: 0, y: 0 })
      const gesture = React.useRef({ start: 0, base: 1, x: 0, y: 0, ox: 0, oy: 0 })
      const distance = (touches) => {
        if (!touches || touches.length < 2) return 0
        const dx = touches[0].clientX - touches[1].clientX
        const dy = touches[0].clientY - touches[1].clientY
        return Math.sqrt(dx * dx + dy * dy)
      }
      const onTouchStart = (event) => {
        const touches = event.touches || []
        if (touches.length === 2) {
          gesture.current = { start: distance(touches), base: scale, x: 0, y: 0, ox: offset.x, oy: offset.y }
          return
        }
        if (touches.length === 1) {
          gesture.current = {
            start: 0,
            base: scale,
            x: touches[0].clientX,
            y: touches[0].clientY,
            ox: offset.x,
            oy: offset.y,
          }
        }
      }
      const onTouchMove = (event) => {
        const touches = event.touches || []
        if (touches.length === 2 && gesture.current.start > 0) {
          const ratio = distance(touches) / gesture.current.start
          const next = Math.min(6, Math.max(1, gesture.current.base * ratio))
          setScale(next)
          return
        }
        if (touches.length === 1 && scale > 1) {
          setOffset({
            x: gesture.current.ox + (touches[0].clientX - gesture.current.x),
            y: gesture.current.oy + (touches[0].clientY - gesture.current.y),
          })
        }
      }
      const onTouchEnd = () => {
        if (scale <= 1.01) {
          setScale(1)
          setOffset({ x: 0, y: 0 })
        }
      }
      return h('div', {
        className: 'bigrice-lightbox',
        role: 'dialog',
        'aria-label': name,
        onTouchStart,
        onTouchMove,
        onTouchEnd,
      },
      h('button', {
        type: 'button',
        className: 'bigrice-lightboxClose',
        'aria-label': t('close'),
        onClick: closePreview,
      }, '\u00d7'),
      h('img', {
        className: 'bigrice-lightboxImage',
        src: url,
        alt: name,
        style: `transform: translate(${offset.x}px, ${offset.y}px) scale(${scale})`,
        draggable: false,
      }))
    }

    function PreviewSheet({ ctx, t }) {
      const [name, setName] = React.useState(previewTarget ? previewTarget.name : '')
      const [state, setState] = React.useState({ kind: 'loading' })
      React.useEffect(() => {
        const listener = (open, target) => {
          if (open && target) setName(target.name)
        }
        previewListeners.add(listener)
        return () => { previewListeners.delete(listener) }
      }, [])
      React.useEffect(() => {
        const target = previewTarget
        if (!target) return
        if (target.url) {
          setState({ kind: 'image', url: target.url })
          return
        }
        const host = bridge()
        if (!host) {
          setState({ kind: 'error', text: t('browseUnavailable') })
          return
        }
        // An inline vector renders through the same <img> the conversation
        // already decoded, so no special case is needed here.
        const candidates = previewCandidates(ctx, target.name)
        let cancelled = false
        const tryAt = (index) => {
          if (cancelled) return
          if (index >= candidates.length) {
            setState({ kind: 'error', text: t('previewRemote') })
            return
          }
          const path = candidates[index]
          if (TEXTUAL_PATH.test(path) && typeof window.fetch === 'function') {
            window.fetch(fileUrl(path)).then((response) => (response.ok ? response.text() : null))
              .then((text) => {
                if (cancelled) return
                if (text === null) viaBridge(index, path)
                else setState({ kind: 'text', text: text.slice(0, 200000), path })
              })
              .catch(() => {
                if (!cancelled) viaBridge(index, path)
              })
            return
          }
          const direct = typeof window.Image === 'function' ? new window.Image() : null
          if (direct) {
            direct.onload = () => {
              if (cancelled) return
              setState({ kind: 'image', url: direct.src, path })
            }
            direct.onerror = () => {
              if (!cancelled) viaBridge(index, path)
            }
            direct.src = fileUrl(path)
            return
          }
          viaBridge(index, path)
        }
        const viaBridge = (index, path) => {
          if (cancelled) return
          const host = bridge()
          if (!host) {
            setState({ kind: 'error', text: t('browseUnavailable') })
            return
          }
          const image = JSON.parse(host.readImage(path) || '{}')
          if (image.ok) {
            setState({ kind: 'image', url: image.data, path })
            return
          }
          if (image.reason === 'too-big') {
            setState({ kind: 'error', text: t('previewTooBig') })
            return
          }
          const text = JSON.parse(host.readText(path, 200000) || '{}')
          if (text.ok) {
            setState({ kind: 'text', text: text.text, path })
            return
          }
          if (text.reason === 'binary') {
            setState({ kind: 'error', text: t('previewBinary') })
            return
          }
          tryAt(index + 1)
        }
        setState({ kind: 'loading' })
        tryAt(0)
        return () => { cancelled = true }
      }, [name, ctx, t])
      if (state.kind === 'image') {
        return h(Lightbox, { url: state.url, name, t })
      }
      const body = state.kind === 'text'
          ? h('pre', { className: 'bigrice-previewText' }, state.text)
          : state.kind === 'loading'
            ? h('div', { className: 'bigrice-meta' }, t('loading'))
            : h('div', { className: 'bigrice-meta' }, state.text)
      return h('div', { className: 'bigrice-previewSheet', role: 'dialog', 'aria-label': name },
        h('div', { className: 'bigrice-previewHead' },
          h('div', { className: 'bigrice-previewTitle' }, name),
          h('button', { type: 'button', className: 'bigrice-btn', onClick: closePreview }, t('close'))),
        h('div', { className: 'bigrice-previewBody' }, body))
    }

    function Mask({ ctx }) {
      return h('div', {
        className: 'bigrice-mask',
        onClick: () => { closeDrawer(ctx) },
      })
    }

    function Shell({ ctx, t }) {
      const [sheet, setSheet] = React.useState(previewOpen)
      React.useEffect(() => {
        const listener = (open) => setSheet(open)
        previewListeners.add(listener)
        listener(previewOpen)
        return () => { previewListeners.delete(listener) }
      }, [])
      return h(React.Fragment, null,
        h(Mask, { ctx }),
        h(TopBar, { ctx }),
        sheet ? h(PreviewSheet, { ctx, t }) : null)
    }

    /** On a phone the keyboard must only ever appear because the user touched
     *  the composer. The host focuses the draft whenever a navigation starts a
     *  new session (the toolbar "new session", the workspace row's ⊕, a freshly
     *  created workspace, a shortcut), which would otherwise raise the IME from
     *  a tap in the drawer. Blocking that programmatic call covers every such
     *  entry point at once; a real tap focuses the element natively and is
     *  untouched. */
    function installComposerFocusGuard() {
      const proto = typeof HTMLElement === 'undefined' ? null : HTMLElement.prototype
      if (!proto || typeof proto.focus !== 'function') return () => {}
      const original = proto.focus
      if (original && original.__bigriceGuard) return () => {}
      const guarded = function focus(...args) {
        try {
          const mobile = document.documentElement.hasAttribute('data-bigrice-mobile')
          if (mobile && this && typeof this.closest === 'function' && this.closest('[class*="composerSeat"]')) {
            return undefined
          }
        } catch (error) { /* fall through to the host behaviour */ }
        return original.apply(this, args)
      }
      guarded.__bigriceGuard = true
      proto.focus = guarded
      return () => {
        if (proto.focus === guarded) proto.focus = original
      }
    }

    return {
      inject: ['slots', 'layout', 'uiWorkspace', 'locale'],
      apply(ctx) {
        // The harness preopens `about:blank` and then swaps in the authorization
        // URL through `popup.location`. A transported WebView popup does not
        // follow that write, so this stub forwards the URL to the host, which
        // shows it in a closable window of its own.
        ctx.effect(() => {
          const open = window.open
          window.open = function (url, ...rest) {
            const bridge = typeof window.BigriceHost === 'object' ? window.BigriceHost : null
            if (!bridge) return open.call(window, url, ...rest)
            if (url && url !== 'about:blank') {
              bridge.openPopup(String(url))
              return null
            }
            const send = (target) => {
              if (!target) return
              bridge.openPopup(String(target))
            }
            return {
              closed: false,
              close() { this.closed = true },
              focus() {},
              postMessage() {},
              opener: null,
              location: {
                replace: send,
                assign: send,
                set href(target) { send(target) },
                get href() { return 'about:blank' },
              },
            }
          }
          return () => { window.open = open }
        }, 'bigrice-ui: popups open in the host window')

        ctx.effect(() => {
          const style = document.createElement('style')
          style.dataset.bigriceUi = 'mobile'
          style.textContent = STYLES
          document.head.append(style)
          return () => { style.remove() }
        }, 'bigrice-ui: mobile stylesheet')

        ctx.slots.inject('shell.overlay', () => ctx.slots.register({
          name: 'shell.overlay', id: 'bigrice-ui:shell', order: 100,
        }, () => h(Shell, { ctx, t: text })))

        // Imports land in `<workspace>/download`, so the app has to know which
        // workspace the page is on before the composer's picker opens.
        ctx.effect(() => {
          followWorkspace(ctx)
          const timer = setInterval(() => followWorkspace(ctx), 2000)
          return () => clearInterval(timer)
        }, 'bigrice-ui: import folder follows the workspace')

        ctx.effect(() => installComposerFocusGuard(), 'bigrice-ui: no spontaneous composer focus')

        // Launch modals are automatic only briefly; see launchUntil.
        launchUntil = Date.now() + 15000

        // A tab of its own in the settings dialog. The host builds the nav from
        // every registered `settings.section`, so this needs no host patch; the
        // order keeps it after the built-in pages.
        const text = ctx.locale.bind(BIGRICE_NS)
        ctx.effect(() => ctx.locale.register(BIGRICE_NS, BIGRICE_TEXT),
          'bigrice-ui: dictionaries')
        ctx.slots.inject('settings.section', () => ctx.slots.register({
          name: 'settings.section',
          id: BIGRICE_SECTION_ID,
          order: 90,
          label: () => text('nav'),
        }, () => h(BigriceSection, { t: text, ctx })))

        // Both folder choices happen in another window and report back through
        // a global: the popup cannot reach React, so this is the hand-off.
        ctx.effect(() => {
          window.__bigriceImportDir = () => {}
          window.__bigriceShellChunk = (id, text) => emitShellChunk(id, text)
          window.__bigriceShellResult = (id, payload) => settleShell(id, payload)
          window.__bigriceExportDir = (info) => {
            if (info && typeof info === 'object') announceExportDir()
          }
          window.__bigriceUpdate = (info) => announceUpdate(info)
          return () => {
            delete window.__bigriceImportDir
            delete window.__bigriceUpdate
            delete window.__bigriceExportDir
            delete window.__bigriceShellResult
            delete window.__bigriceShellChunk
          }
        }, 'bigrice-ui: folder callbacks')

        // A phone, a small tablet and a desktop window run the same bundle, so
        // the phone shell follows the measured frame instead of a media query.
        ctx.effect(() => {
          let wasMobile = false
          const sync = () => {
            const root = document.documentElement
            const frame = findFrame()
            // Before the frame mounts there is nothing to measure; guessing from
            // the window would put phone chrome on a desktop for one tick.
            if (!frame) return
            const width = frame.clientWidth
            const height = frame.clientHeight
            const mobile = width > 0 && width < MOBILE_MAX
            setFlag(root, 'data-bigrice-mobile', mobile)
            setFlag(root, 'data-bigrice-short', mobile && height > 0 && height < SHORT_MAX)
            // 0.2.2 mounts its own drawer below 768px: a leading hamburger in
            // `shell.leading`, a `.drawerMask` and a transform on the sidebar
            // column. Where that exists the host owns the state; the plugin
            // mirrors it (and its stylesheet drops the second bar and scrim) so
            // the two controls stop stacking on the same corner.
            const hostDrawer = document.querySelector('[data-shell-leading]') !== null
            setFlag(root, 'data-bigrice-hostdrawer', mobile && hostDrawer)
            if (mobile && hostDrawer) {
              setFlag(root, 'data-bigrice-drawer', !frame.hasAttribute('data-sidebar-collapsed'))
            }
            stretchSidebar(mobile && !hostDrawer)
            if (!hostDrawer) syncHeaderRow()
            // Leave the drawer behind only when a phone turned into a desktop;
            // collapsing on the first measurement would shut a desktop sidebar.
            if (!mobile && wasMobile) closeDrawer(ctx)
            wasMobile = mobile
          }
          sync()
          const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(sync) : null
          let watched = null
          const watch = () => {
            const frame = findFrame()
            if (!observer || !frame || frame === watched) return
            observer.observe(frame)
            observer.observe(document.body)
            watched = frame
          }
          watch()
          window.addEventListener('resize', sync)
          window.addEventListener('orientationchange', sync)
          // A page switch mounts or drops the header, and the measured flag has
          // to follow within a frame or two: a one-second tick would show the
          // empty bar for a beat every time a conversation comes back.
          let headTimer = 0
          const queueHeader = () => {
            if (headTimer) return
            headTimer = setTimeout(() => {
              headTimer = 0
              syncHeaderRow()
            }, 120)
          }
          const mutations = typeof MutationObserver === 'function' ? new MutationObserver(queueHeader) : null
          const watchHead = () => {
            const frame = findFrame()
            if (!mutations || !frame) return
            mutations.observe(frame, { childList: true, subtree: true })
          }
          watchHead()
          // The frame mounts after this effect on a cold start, and a rotation
          // does not always deliver a resize to the observed node.
          const timer = setInterval(() => { watch(); watchHead(); sync() }, 1000)
          return () => {
            observer?.disconnect()
            mutations?.disconnect()
            window.removeEventListener('resize', sync)
            window.removeEventListener('orientationchange', sync)
            clearInterval(timer)
            clearTimeout(headTimer)
          }
        }, 'bigrice-ui: follow the device width')

        // Keep the selected settings tab in sight. The pills wrap now, but a
        // short landscape window can still push the active row out of view.
        ctx.effect(() => {
          const reveal = () => {
            if (!document.documentElement.hasAttribute('data-bigrice-mobile')) return
            const active = document.querySelector(
              '[role="dialog"][data-shortcut-modal="settings"] nav [aria-current]')
            if (!(active instanceof HTMLElement) || !active.parentElement) return
            const box = active.parentElement.getBoundingClientRect()
            const pill = active.getBoundingClientRect()
            if (pill.top >= box.top && pill.bottom <= box.bottom) return
            active.scrollIntoView({ block: 'nearest', inline: 'nearest' })
          }
          const observer = new MutationObserver(reveal)
          observer.observe(document.body, {
            childList: true, subtree: true, attributes: true, attributeFilter: ['aria-current'],
          })
          return () => observer.disconnect()
        }, 'bigrice-ui: reveal the selected settings tab')

        // Choosing a destination inside the drawer should close it, the same way
        // a native drawer behaves. A menu trigger stays open, in any language.
        ctx.effect(() => {
          const onClick = (event) => {
            const target = event.target
            if (!(target instanceof Element)) return
            lastUserClickAt = Date.now()
            const text = labels(ctx)

            // A sheet that opens out of a dialog should line up with it rather
            // than stick out on both sides.
            const sheetOwner = target.closest('[role="dialog"]')
            if (sheetOwner) {
              const box = sheetOwner.getBoundingClientRect()
              const root = document.documentElement.style
              root.setProperty('--bigrice-sheet-left', `${Math.max(8, Math.round(box.left))}px`)
              root.setProperty('--bigrice-sheet-right',
                `${Math.max(8, Math.round(window.innerWidth - box.right))}px`)
            }

            // The account menu is portalled out of the sidebar, so its items are
            // recognised by role. Picking a destination there ends the drawer
            // errand; the settings dialog or the sign-in flow takes over.
            const menuItem = target.closest(
              '[role="menuitem"], [role="menuitemradio"], [role="menuitemcheckbox"]')
            if (menuItem) closeDrawer(ctx)

            // A button inside the composer opens a menu over the editor; the
            // host focuses the editor around that tap, so let the menu open and
            // then take the focus back.
            const composer = target.closest('[class*="composerSeat"]')
            if (composer) {
              if (target.closest('button[aria-haspopup], button[class*="_add"], '
                + 'button[class*="_trigger"], button[class*="_pill"]')) {
                setTimeout(blurComposer, 0)
              }
              return
            }

            // The png/svg rows a session leaves behind are the only way a phone
            // user meets an attachment, so opening one is done here rather than
            // through the host's right column.
            // The image sits inside a host button, so a tap can land on the
            // wrapper rather than the <img> itself.
            const picture = inConversation(target) ? imageNear(target) : null
            if (picture) {
              openPreview({
                name: imageLabel(picture),
                url: picture.currentSrc || picture.src,
              })
              return
            }
            const picked = inConversation(target)
              ? attachmentName(target) || cardFileName(target)
              : ''
            if (picked) {
              openPreview({ name: picked })
              return
            }
            if (!target.closest('[class*="sidebarCol"]')) return

            // A row action ("…", archive, pin) opens a popover anchored inside
            // the drawer and sits inside the row it belongs to, so it must not
            // count as picking that row.
            const iconButton = target.closest('button[class*="iconButton"]')
            if (iconButton) {
              // The row's own "new session" is an icon button too, but it opens a
              // draft and focuses the composer, so it ends the errand.
              if (!isWorkspaceNewSession(ctx, iconButton)) return
              closeDrawer(ctx)
              for (const delay of [0, 120, 400]) setTimeout(blurComposer, delay)
              return
            }

            // "New session" (and the brand pill above it) starts a draft and
            // then focuses the composer. The drawer still has to close, and the
            // keyboard must not follow the new draft, so drop it again once the
            // host has had its turn at focusing.
            const startSession = target.closest('button[class*="_newSession"], button[class*="_brand"]')
            if (startSession) {
              closeDrawer(ctx)
              for (const delay of [0, 120, 400]) setTimeout(blurComposer, delay)
              return
            }

            const row = target.closest('[role="treeitem"]')
            if (row) {
              // A workspace row only expands; picking a session ends the errand.
              if (row.hasAttribute('aria-expanded')) return
              closeDrawer(ctx)
              setTimeout(blurComposer, 0)
              return
            }

            // Only a rail destination (插件, 自动化任务) ends the drawer errand.
            // Anything else in there — the search box, its clear button, row
            // actions, the account trigger — is a control inside the drawer.
            const button = target.closest('button')
            if (!button) return
            const panelRow = button.closest('[class*="_panelRow"]')
            if (!panelRow || isMenuTrigger(button)) return
            // 插件 and 自动化任务 swap the centre column for a panel; remembering
            // that is what lets Back know there is a level to return from.
            panelOpen = true
            closeDrawer(ctx)
          }
          window.__bigriceCtx = ctx
          document.addEventListener('click', onClick, true)
          // The host's file card handles the pointer itself and never emits a
          // click we could see, so look the card up from the touch point.
          // A swipe that scrolls the conversation also ends with a touchend
          // over whatever is under the finger, so only a short, still press
          // counts as a tap on a picture.
          let press = null
          const onPointerDown = (event) => {
            const point = event.changedTouches ? event.changedTouches[0] : event
            if (!point || typeof point.clientX !== 'number') {
              press = null
              return
            }
            press = { x: point.clientX, y: point.clientY, at: Date.now() }
          }
          const onPointerUp = (event) => {
            const start = press
            press = null
            if (!start) return
            if (!document.documentElement.hasAttribute('data-bigrice-mobile')) return
            const point = event.changedTouches ? event.changedTouches[0] : event
            if (!point || typeof point.clientX !== 'number') return
            const dx = Math.abs(point.clientX - start.x)
            const dy = Math.abs(point.clientY - start.y)
            if (dx > 12 || dy > 12 || Date.now() - start.at > 900) return
            const node = document.elementFromPoint(point.clientX, point.clientY)
            if (!node || !node.closest) return
            const picture = inConversation(node) ? imageNear(node) : null
            if (picture) {
              openPreview({
                name: imageLabel(picture),
                url: picture.currentSrc || picture.src,
              })
              return
            }
            const name = inConversation(node)
              ? attachmentName(node) || cardFileName(node)
              : ''
            if (name) {
              openPreview({ name })
              return
            }
          }
          document.addEventListener('pointerdown', onPointerDown, true)
          document.addEventListener('touchstart', onPointerDown, true)
          document.addEventListener('pointerup', onPointerUp, true)
          document.addEventListener('touchend', onPointerUp, true)
          document.addEventListener('mouseup', onPointerUp, true)
          return () => {
            document.removeEventListener('click', onClick, true)
            document.removeEventListener('pointerdown', onPointerDown, true)
            document.removeEventListener('touchstart', onPointerDown, true)
            document.removeEventListener('pointerup', onPointerUp, true)
            document.removeEventListener('touchend', onPointerUp, true)
            document.removeEventListener('mouseup', onPointerUp, true)
          }
        }, 'bigrice-ui: close the drawer after a pick')

        // Hardware Back walks one level up: dialog, then drawer, then side panel,
        // and only an exhausted page lets the Android side leave the app.
        ctx.effect(() => {
          const closeButton = (dialog, text) => {
            const own = dialog.querySelector('button[class*="_close"]')
            if (own instanceof HTMLElement) return own
            return [...dialog.querySelectorAll('button')].find(button =>
              isOneOf(button.getAttribute('aria-label'), [text.close, ...CLOSE_TEXTS])) || null
          }
          const back = () => {
            if (previewNode) {
              closePreview()
              return true
            }
            const text = labels(ctx)
            const settings = document.querySelector(
              '[role="dialog"][data-shortcut-modal="settings"]')
            if (settings) {
              const close = closeButton(settings, text)
              if (close) { close.click(); return true }
            }
            const dialogs = [...document.querySelectorAll('[role="dialog"]')]
            for (const dialog of dialogs.reverse()) {
              const close = closeButton(dialog, text)
              if (close) { close.click(); return true }
            }
            if (previewOpen) { closePreview(); return true }
            if (document.documentElement.hasAttribute('data-bigrice-drawer')) {
              closeDrawer(ctx)
              return true
            }
            // The right panel is a full sheet on a phone, so Back has to leave it
            // before it reaches the conversation behind.
            if (closeSidePanel(ctx)) return true
            const info = ctx.layout?.panelInfo
            let active = panelOpen
            try {
              if (!active && typeof info?.getSnapshot === 'function') {
                active = Boolean(info.getSnapshot()?.activePanelId)
              }
            } catch (e) { /* fall through to the tracked flag */ }
            if (active) {
              ctx.layout.selectPanel(null)
              panelOpen = false
              return true
            }
            return false
          }
          window.__bigriceBack = back
          return () => { delete window.__bigriceBack }
        }, 'bigrice-ui: back walks one level up')

        // Two desktop modals block the phone at launch and both read as a hung
        // app: the preview notice (escape: its primary button) and the automatic
        // sign-in prompt (escape: the close button). Both are launch-only, so
        // they are cleared once and only while the page is still untouched —
        // a sign-in prompt the user opened by hand has to stay up.
        ctx.effect(() => {
          let dismissedPreview = false
          let dismissedLogin = false
          const dismiss = () => {
            const text = labels(ctx)
            const root = document.documentElement
            const dialogs = [...document.querySelectorAll('[role="dialog"]')]
            for (const dialog of dialogs.reverse()) {
              const title = normalize(dialog.getAttribute('aria-label')) ||
                normalize(dialog.querySelector('h2')?.textContent)
              if (!dismissedPreview &&
                isOneOf(title, [text.welcomeTitle, '预览版说明', 'Preview Notice'])) {
                const button = [...dialog.querySelectorAll('button')].find(node =>
                  isOneOf(node.textContent, [text.welcomeContinue, '继续', 'Continue']))
                if (!button) continue
                dismissedPreview = true
                button.click()
                root.dataset.bigriceLastAction = 'dismiss.previewNotice'
                return
              }
              if (!dismissedLogin && Date.now() < launchUntil &&
                isOneOf(title, [text.loginTitle, '开始使用', 'Get started'])) {
                const close = [...dialog.querySelectorAll('button')].find(node =>
                  isOneOf(node.getAttribute('aria-label'), [text.close, ...CLOSE_TEXTS]))
                if (!close) continue
                dismissedLogin = true
                close.click()
                root.dataset.bigriceLastAction = 'dismiss.loginPrompt'
                return
              }
            }
          }
          const observer = new MutationObserver(dismiss)
          observer.observe(document.body, { childList: true, subtree: true })
          const timer = setInterval(dismiss, 500)
          return () => { observer.disconnect(); clearInterval(timer) }
        }, 'bigrice-ui: dismiss the launch modals')
      },
    }
  },
})
