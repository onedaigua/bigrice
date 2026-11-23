/* Logic test for bigrice-ui/client.js: runs the module in a tiny DOM stub and
   drives the real event handlers. Covers the language-independent paths that a
   browser could not be launched for here. */
import { readFileSync } from 'node:fs'
import vm from 'node:vm'

/* ---------------------------------------------------------------- mini DOM */
class Node {
  constructor(tag) {
    this.tagName = String(tag).toUpperCase()
    this.children = []
    this.parentNode = null
    this.attrs = new Map()
    this.dataset = {}
    this.textContent = ''
    this.listeners = new Map()
    this._class = ''
    this.style = {
      items: new Map(),
      gridTemplateColumns: '',
      width: '',
      setProperty(k, v) { this.items.set(k, v) },
      getPropertyValue(k) { return this.items.get(k) || '' },
    }
    this.clientWidth = 0
    this.clientHeight = 0
  }
  get className() { return this._class }
  set className(v) { this._class = String(v) }
  get classList() { return this._class.split(/\s+/).filter(Boolean) }
  setAttribute(k, v) {
    if (k === 'class') { this._class = String(v); return }
    this.attrs.set(k, String(v))
  }
  getAttribute(k) { if (k === 'class') return this._class || null; return this.attrs.has(k) ? this.attrs.get(k) : null }
  hasAttribute(k) { if (k === 'class') return Boolean(this._class); return this.attrs.has(k) }
  removeAttribute(k) { this.attrs.delete(k) }
  appendChild(child) { child.parentNode = this; this.children.push(child); return child }
  append(...kids) { kids.forEach((k) => k && this.appendChild(k)) }
  remove() { const p = this.parentNode; if (p) p.children = p.children.filter((c) => c !== this) }
  addEventListener(type, fn) { if (!this.listeners.has(type)) this.listeners.set(type, new Set()); this.listeners.get(type).add(fn) }
  removeEventListener(type, fn) { this.listeners.get(type)?.delete(fn) }
  click() { this.dispatch('click', { target: this }) }
  dispatch(type, event) {
    for (const fn of this.listeners.get(type) || []) fn(event)
  }
  getBoundingClientRect() { return { x: 0, y: 0, left: 0, top: 0, right: 360, bottom: 640, width: 360, height: 640 } }
  scrollIntoView() {}
  querySelectorAll(sel) { return queryAll(this, sel) }
  querySelector(sel) { return queryAll(this, sel)[0] || null }
  closest(sel) { let node = this; while (node) { if (matches(node, sel)) return node; node = node.parentNode } return null }
  focus() { documentStub.activeElement = this }
  blur() { if (documentStub.activeElement === this) documentStub.activeElement = null }
  matches(sel) { return matches(this, sel) }
}
class Element extends Node {}
class HTMLElement extends Element {}

function parseCompound(text) {
  const out = { tag: '', attrs: [], classes: [] }
  const tagMatch = /^[a-zA-Z*][\w-]*/.exec(text)
  if (tagMatch) { out.tag = tagMatch[0].toUpperCase(); text = text.slice(tagMatch[0].length) }
  const re = /\[([\w-]+)(?:([*^$]?=)"([^"]*)")?\]|\.([\w-]+)/g
  let m
  while ((m = re.exec(text))) {
    if (m[1]) out.attrs.push({ name: m[1], op: m[2] || '', value: m[3] })
    else if (m[4]) out.classes.push(m[4])
  }
  return out
}

function matchCompound(node, compound) {
  if (compound.tag && compound.tag !== '*') {
    const tag = node.tagName
    const want = compound.tag
    const normalized = want === 'H2' || want.length <= 2 ? want : want
    if (tag !== normalized) return false
  }
  for (const cls of compound.classes) if (!node.classList.includes(cls)) return false
  for (const { name, op, value } of compound.attrs) {
    if (!node.hasAttribute(name)) return false
    const actual = node.getAttribute(name) || ''
    if (op === '=' && actual !== value) return false
    if (op === '*=' && !actual.includes(value)) return false
    if (op === '^=' && !actual.startsWith(value)) return false
    if (op === '$=' && !actual.endsWith(value)) return false
  }
  return true
}

function splitSelector(sel) {
  // supports "a b", "a > b" with simple compounds only
  const parts = []
  let buffer = ''
  const tokens = []
  for (const ch of sel.trim()) {
    if (/\s/.test(ch)) { if (buffer) { tokens.push(buffer); buffer = '' } tokens.push(' ') }
    else if (ch === '>') { if (buffer) { tokens.push(buffer); buffer = '' } tokens.push('>') }
    else buffer += ch
  }
  if (buffer) tokens.push(buffer)
  const steps = []
  let combinator = ' '
  for (const tok of tokens) {
    if (tok === ' ' || tok === '>') { combinator = tok; continue }
    steps.push({ combinator, compound: parseCompound(tok) })
    combinator = ' '
  }
  return { steps, parts }
}

function matches(node, sel) {
  // The stub engine has no :has(), like an older WebView: such a selector is
  // dropped wholesale instead of being matched by its leading compound.
  if (sel.includes(':has(')) return false
  if (sel.includes(',')) return sel.split(',').some((part) => matches(node, part.trim()))
  const { steps } = splitSelector(sel)
  if (!steps.length) return false
  const last = steps[steps.length - 1]
  if (!matchCompound(node, last.compound)) return false
  let current = node
  for (let i = steps.length - 2; i >= 0; i -= 1) {
    const step = steps[i + 1]
    if (step.combinator === '>') {
      current = current.parentNode
      if (!current || !(current instanceof Node) || !matchCompound(current, steps[i].compound)) return false
    } else {
      let ancestor = current.parentNode
      let found = null
      while (ancestor) {
        if (ancestor instanceof Node && matchCompound(ancestor, steps[i].compound)) { found = ancestor; break }
        ancestor = ancestor.parentNode
      }
      if (!found) return false
      current = found
    }
  }
  return true
}

function descendants(root, out = []) {
  for (const child of root.children) { out.push(child); descendants(child, out) }
  return out
}

function queryAll(root, sel) {
  return descendants(root).filter((node) => matches(node, sel))
}

/* ------------------------------------------------------------------ scene */
const documentElement = new HTMLTag('html')
const body = new HTMLTag('body')
const head = new HTMLTag('head')
documentElement.appendChild(body)
documentElement.appendChild(head)

function HTMLTag(tag) {
  const node = new HTMLElement(tag)
  return node
}

const root = new HTMLTag('div')
const frame = new HTMLTag('div')
frame.className = 'mP3x-q_frame'
frame.style.gridTemplateColumns = '0px minmax(0, 1fr) 0px'
frame.clientWidth = 411
frame.clientHeight = 915
const sidebarCol = new HTMLTag('div')
sidebarCol.className = 'mP3x-q_sidebarCol'
const sidebarRoot = new HTMLTag('div')
sidebarRoot.className = 'wtg9ga_root wtg9ga_quietBars'
const toggle = new HTMLTag('button')
toggle.className = 'wtg9ga_iconButton wtg9ga_toggle'
toggle.setAttribute('aria-label', 'Collapse sidebar')
const moreButton = new HTMLTag('button')
moreButton.className = 'wtg9ga_panelRow'
moreButton.setAttribute('aria-haspopup', 'menu')
moreButton.setAttribute('aria-expanded', 'false')
moreButton.textContent = 'More'
const moreAnchor = new HTMLTag('div')
moreAnchor.className = 'wtg9ga_moreAnchor'
const accountButton = new HTMLTag('button')
accountButton.className = 'Acc_trigger'
accountButton.setAttribute('aria-label', 'Account menu')
accountButton.setAttribute('aria-haspopup', 'menu')
accountButton.textContent = 'More'
const panelRow = new HTMLTag('button')
panelRow.className = 'wtg9ga_panelRow'
panelRow.textContent = 'Plugins'
const centerCol = new HTMLTag('div')
centerCol.className = 'mP3x-q_centerCol'
const overlay = new HTMLTag('div')
overlay.className = 'mP3x-q_overlayLayer'

/* A content frame also ends in `_frame` (the image card class is
   `n-Yh7q_frame`). It sits earlier in document order and is wide, so a lookup
   that trusts the suffix alone would measure a desktop layout on a phone. */
const imageFrame = new HTMLTag('div')
imageFrame.className = 'n-Yh7q_frame'
imageFrame.clientWidth = 1280
imageFrame.clientHeight = 240
const beforeFrame = new HTMLTag('div')
beforeFrame.appendChild(imageFrame)
root.children.unshift(beforeFrame)

moreAnchor.appendChild(moreButton)
sidebarRoot.append(toggle, moreAnchor, panelRow, accountButton)
/* A workspace row only expands, a session row navigates, and the row action
   ("…") opens a portalled menu; only the session pick may close the drawer. */
const workspaceRow = new HTMLTag('div')
workspaceRow.setAttribute('role', 'treeitem')
workspaceRow.setAttribute('aria-expanded', 'false')
workspaceRow.textContent = 'B'
const workspaceTitle = new HTMLTag('span')
workspaceTitle.className = 'wnFoaW_title'
workspaceTitle.textContent = 'B'
const workspaceActions = new HTMLTag('span')
workspaceActions.className = 'wnFoaW_rowActions'
const workspaceNewSession = new HTMLTag('button')
workspaceNewSession.className = 'wnFoaW_iconButton'
workspaceNewSession.setAttribute('aria-label', 'New session in B')
workspaceActions.appendChild(workspaceNewSession)
workspaceRow.append(workspaceTitle, workspaceActions)
const sessionRow = new HTMLTag('div')
sessionRow.setAttribute('role', 'treeitem')
sessionRow.setAttribute('aria-selected', 'false')
sessionRow.textContent = 'Session'
const rowMenu = new HTMLTag('button')
rowMenu.className = 'YDXeBa_iconButton'
rowMenu.setAttribute('aria-label', 'Row actions')
sessionRow.appendChild(rowMenu)
const newSessionButton = new HTMLTag('button')
newSessionButton.className = 'hHd-Xa_newSession'
newSessionButton.textContent = 'New session'
const clearButton = new HTMLTag('button')
clearButton.className = 'bhn1Oq_clearButton'
clearButton.setAttribute('aria-label', 'Clear search')
sidebarRoot.append(workspaceRow, sessionRow, newSessionButton, clearButton)
sidebarCol.appendChild(sidebarRoot)

/* The composer sits in the centre column: tapping its "+" must not keep the
   keyboard up, and it must never be mistaken for a drawer pick. */
const composerSeat = new HTMLTag('div')
composerSeat.className = 'uV2eYG_composerSeat'
const composerInput = new HTMLTag('div')
composerInput.setAttribute('role', 'textbox')
composerInput.className = 'uV2eYG_input'
const composerAdd = new HTMLTag('button')
composerAdd.className = 'uV2eYG_add'
composerAdd.setAttribute('aria-label', 'Add files or commands')
composerSeat.append(composerInput, composerAdd)
centerCol.appendChild(composerSeat)
frame.append(sidebarCol, centerCol, overlay)
root.appendChild(frame)
body.appendChild(root)

/* portalled menu item, outside the sidebar */
const menuLayer = new HTMLTag('div')
const menuItem = new HTMLTag('button')
menuItem.setAttribute('role', 'menuitem')
menuItem.textContent = 'Sign in'
menuLayer.appendChild(menuItem)
body.appendChild(menuLayer)

class ImageStub {
  constructor() { this.onload = null; this.onerror = null; this._src = '' }
  get src() { return this._src }
  set src(value) {
    this._src = value
    const fire = () => {
      if (value.indexOf('chino.png') !== -1) { if (this.onload) this.onload() }
      else if (this.onerror) this.onerror()
    }
    fire()
  }
}

const documentStub = {
  activeElement: null,
  documentElement,
  body,
  head,
  createElement(tag) { return new HTMLElement(tag) },
  querySelector(sel) { return queryAll(documentElement, sel)[0] || null },
  querySelectorAll(sel) { return queryAll(documentElement, sel) },
  getElementById() { return null },
  addEventListener(type, fn) { if (!this._listeners) this._listeners = new Map(); if (!this._listeners.has(type)) this._listeners.set(type, new Set()); this._listeners.get(type).add(fn) },
  removeEventListener(type, fn) { this._listeners?.get(type)?.delete(fn) },
  dispatch(type, event) { for (const fn of this._listeners?.get(type) || []) fn(event) },
}

/* ------------------------------------------------------------------ timers */
const intervals = []
const timeouts = []
function setIntervalStub(fn) { intervals.push(fn); return intervals.length }
function clearIntervalStub() {}
function setTimeoutStub(fn) { timeouts.push(fn); return timeouts.length }

class MutationObserverStub {
  constructor(cb) { this.cb = cb }
  observe() {}
  disconnect() {}
}
class ResizeObserverStub {
  constructor(cb) { this.cb = cb; observers.push(this) }
  observe() {}
  disconnect() {}
}
const observers = []

const windowStub = {
  Image: ImageStub,
  innerWidth: 411,
  innerHeight: 915,
  addEventListener(type, fn) { (this._l || (this._l = new Map())).set(type, fn) },
  removeEventListener() {},
  dispatch(type) { this._l?.get(type)?.() },
  open: undefined,
  /* The app bridge the bigrice settings tab reads its logs through. */
  BigriceHost: {
    toasts: [],
    toast(message) { this.toasts.push(message) },
    keyboardHides: 0,
    hideKeyboard() { this.keyboardHides += 1 },
    readLogs() {
      return JSON.stringify({
        app: { versionName: '0.0.31', versionCode: 31, package: 'com.bigrice' },
        files: [
          { id: 'run', title: '本次启动', chars: 5, missing: false, truncated: false, text: 'boot!' },
          { id: 'dsh', title: 'dsh.log', chars: 9, missing: false, truncated: true, text: 'hello log' },
        ],
      })
    },
    /* The import folder the settings tab mirrors. */
    listCalls: 0,
    getDirCalls: 0,
    setDirs: [],
    deleted: [],
    picked: 0,
    getImportDir() {
      this.getDirCalls += 1
      const guest = this.setDirs.length > 0 ? this.setDirs[this.setDirs.length - 1] : '/root/A/download'
      return JSON.stringify({ path: guest.replace('/root/', ''), guest })
    },
    setImportDir(dir) {
      this.setDirs.push(dir)
      return JSON.stringify({ ok: true, path: String(dir).replace('/root/', ''), guest: dir })
    },
    deleteEntry(rel) {
      this.deleted.push(rel)
      return JSON.stringify({ ok: true, rel })
    },
    /* The /root browser the file card reads through. */
    dirCalls: 0,
    dirHidden: [],
    listDir(rel, showHidden) {
      this.dirCalls += 1
      this.dirHidden.push(Boolean(showHidden))
      if (showHidden) {
        return JSON.stringify({
          ok: true,
          path: rel,
          parent: '',
          guest: rel === '' ? '/root' : `/root/${rel}`,
          entries: [
            { name: '.cache', rel: rel === '' ? '.cache' : `${rel}/.cache`, dir: true, bytes: 0 },
            { name: 'notes.txt', rel: 'notes.txt', dir: false, bytes: 12 },
          ],
        })
      }
      if (rel === 'A') {
        return JSON.stringify({
          ok: true,
          path: 'A',
          parent: '',
          guest: '/root/A',
          entries: [{ name: 'headshot.png', rel: 'A/headshot.png', dir: false, bytes: 913035 }],
        })
      }
      return JSON.stringify({
        ok: true,
        path: '',
        parent: '',
        guest: '/root',
        entries: [
          { name: 'A', rel: 'A', dir: true, bytes: 0 },
          { name: 'notes.txt', rel: 'notes.txt', dir: false, bytes: 12 },
        ],
      })
    },
    readText(rel) {
      if (rel === 'notes.txt') {
        return JSON.stringify({ ok: true, bytes: 11, truncated: false, text: 'hello notes' })
      }
      return JSON.stringify({ ok: false, error: 'binary', bytes: 12 })
    },
    readImage(rel) {
      if (rel === 'A/headshot.png') {
        return JSON.stringify({ ok: true, mime: 'image/png', bytes: 9, data: 'data:image/png;base64,AAAA' })
      }
      return JSON.stringify({ ok: false, error: 'unsupported' })
    },
    exports: [],
    exportZip(rels, name) {
      this.exports.push({ rels, name })
      return JSON.stringify({ ok: true, path: 'Download/bigrice-1.zip', count: 1, bytes: 128 })
    },
    shellCalls: [],
    runShell(command, cwd) {
      this.shellCalls.push({ command, cwd })
      return `sh${this.shellCalls.length}`
    },
    exportPicks: 0,
    getExportDir() { return JSON.stringify({ mode: 'download', label: 'Download' }) },
    chooseExportDir() { this.exportPicks += 1 },
  },
}

const context = vm.createContext({
  window: windowStub,
  document: documentStub,
  HTMLElement,
  Element,
  Node,
  MutationObserver: MutationObserverStub,
  ResizeObserver: ResizeObserverStub,
  setInterval: setIntervalStub,
  clearInterval: clearIntervalStub,
  setTimeout: setTimeoutStub,
  clearTimeout: () => {},
  requestAnimationFrame: (fn) => fn(),
  console,
  Date,
  Math,
  Number,
  String,
  Boolean,
  Array,
  Object,
  JSON,
  Promise,
  Symbol,
  Map,
  Set,
})
context.globalThis = context

/* ------------------------------------------------------------------ loader */
const DICT = {
  'settings.account': { menu: 'Account menu', signIn: 'Sign in', close: 'Close', loginTitle: 'Get started' },
  'settings.models': { welcomeTitle: 'Preview Notice', welcomeContinue: 'Continue' },
  common: { close: 'Close' },
  sidebar: { 'panels.more': 'More' },
  workspace: { 'actions.newSession.aria': 'New session in {name}' },
}
const effects = []
const registered = []
let sidebarCollapsed = true
/* The host's right-panel service is read without an inject, so the stub only
   has to answer while the panel is open. */
let panelExpanded = false
const sidebarRight = {
  isExpanded() { return panelExpanded },
  toggleExpanded() { panelExpanded = !panelExpanded },
}
const ctx = {
  get(name) {
    return name === 'sidebarRight' ? sidebarRight : undefined
  },
  effect(fn, label) {
    const dispose = fn()
    if (typeof dispose === 'function') effects.push({ label, dispose })
  },
  slots: {
    inject(_name, fn) { fn() },
    register(options, component) { registered.push({ options, component }); return () => {} },
  },
  layout: {
    toggleSidebar() { sidebarCollapsed = !sidebarCollapsed },
    selectPanel() {},
    panelInfo: { get() { return { activePanelId: null } } },
  },
  uiWorkspace: {
    selection: { getSnapshot() { return { sessionId: 's1' } } },
    sessions: {
      list: {
        getSnapshot() {
          return { byId: { s1: { cwd: '/root/B' } } }
        },
      },
    },
    workspaces: {
      list: { getSnapshot() { return { items: [{ workspaceId: 'w1', path: '/root/B' }] } } },
    },
  },
  locale: {
    bind: (ns) => (key) => (DICT[ns] || {})[key] || key,
    register(ns, dict) {
      DICT[ns] = { ...(DICT[ns] || {}), ...(dict.en || {}) }
      return () => {}
    },
  },
}

/* A hooks runtime small enough to render one component: state changes re-render,
   effects honour their dependency list (the component's own auto-refresh loop
   would otherwise re-run forever), and callbacks stay stable across renders. */
let hooks = []
let hookIndex = 0
let pendingRender = null
let deferred = []
let lastTree = null

function runRender() {
  hookIndex = 0
  deferred = []
  const vnode = pendingRender()
  lastTree = vnode
  const jobs = deferred
  deferred = []
  for (const job of jobs) job()
  return vnode
}

const sameDeps = (a, b) => Boolean(a && b) && a.length === b.length && a.every((v, i) => Object.is(v, b[i]))

const reactStub = {
  createElement: (type, props, ...children) => ({ type, props, children }),
  Fragment: Symbol('fragment'),
  useCallback(fn, deps) {
    const i = hookIndex++
    const prev = hooks[i]
    if (prev && sameDeps(prev.deps, deps)) return prev.value
    hooks[i] = { value: fn, deps }
    return fn
  },
  useRef(initial) {
    const i = hookIndex++
    if (!hooks[i]) hooks[i] = { current: initial }
    return hooks[i]
  },
  useEffect(fn, deps) {
    const i = hookIndex++
    const prev = hooks[i]
    const first = !prev
    hooks[i] = { deps: deps ? deps.slice() : null }
    if (first || !deps || !sameDeps(prev.deps, deps)) deferred.push(fn)
  },
  useState(initial) {
    const i = hookIndex++
    if (!hooks[i]) hooks[i] = { value: typeof initial === 'function' ? initial() : initial }
    const set = (next) => {
      hooks[i].value = typeof next === 'function' ? next(hooks[i].value) : next
      runRender()
    }
    return [hooks[i].value, set]
  },
}

windowStub.__ModuleLoader__ = {
  load(spec) {
    const mod = spec.factory(() => reactStub)
    mod.apply(ctx)
  },
}

const source = readFileSync(new URL('../bigrice-ui/client.js', import.meta.url), 'utf8')
vm.runInContext(source, context, { filename: 'client.js' })

/* ------------------------------------------------------------------ checks */
const failures = []
const results = []
function check(name, condition, detail) {
  results.push({ name, ok: Boolean(condition), detail })
  if (!condition) failures.push(name + (detail ? ': ' + detail : ''))
}

/** All text below a rendered vnode, including text inside fragment arrays. */
function textOf(node) {
  if (node === null || node === undefined || node === false) return ''
  if (typeof node === 'string' || typeof node === 'number') return String(node)
  if (Array.isArray(node)) return node.map(textOf).join('')
  if (typeof node === 'object') return textOf(node.children)
  return ''
}

/** Replaces function components by what they return, so a rendered tree holds
 *  host elements only. */
function expandVNode(node) {
  if (Array.isArray(node)) return node.map(expandVNode)
  if (node === null || typeof node !== 'object') return node
  if (typeof node.type === 'function') return expandVNode(node.type(node.props || {}))
  return { ...node, children: expandVNode(node.children) }
}

/** Every vnode matching `predicate` in the rendered tree. */
function domNodes(predicate) {
  const found = []
  const walk = (node) => {
    if (!node || typeof node !== 'object') return
    if (predicate(node)) found.push(node)
    for (const child of node.children || []) walk(child)
  }
  walk(documentStub.documentElement)
  return found
}

function findNodes(node, predicate, found = []) {
  if (node === null || node === undefined || typeof node !== 'object') return found
  if (Array.isArray(node)) {
    for (const child of node) findNodes(child, predicate, found)
    return found
  }
  if (node.type && predicate(node)) found.push(node)
  findNodes(node.children, predicate, found)
  return found
}

const style = queryAll(head, 'style')[0]
const shell = registered.find((entry) => entry.options.id === 'bigrice-ui:shell')
check('injects one stylesheet', Boolean(style) && style.dataset.bigriceUi === 'mobile')
check('stylesheet is non-trivial', style && style.textContent.length > 2000)
check('registers the phone shell', Boolean(shell))
check('shell component renders', Boolean(shell && shell.component()))
check('mobile flag from 411px frame', documentElement.hasAttribute('data-bigrice-mobile'))
check('short flag off at 915px', !documentElement.hasAttribute('data-bigrice-short'))

/* the frame rule must not reach content frames: a zero-width grid track there
   would collapse the image card's picture */
check('frame rule is scoped to the app frame', style.textContent.includes("[class*='_frame']:has(> [class*='centerCol'])"))
check('no rule targets every _frame element', !/\[class\*='_frame'\]\s*\{/.test(style.textContent))
check('an image card is not measured as the frame', documentElement.hasAttribute('data-bigrice-mobile'))
check('the composer dock is left alone', !/\[data-composer-dock\]/.test(style.textContent))
check('the console has its own styles', /\.bigrice-consoleView\s*\{[^}]*max-height/.test(style.textContent))
check('secondary buttons are outlined, not filled',
  /\.bigrice-btn\s*\{[^}]*background:\s*transparent/.test(style.textContent))
check('only the primary action is filled',
  /\.bigrice-btn\.bigrice-primary[^{]*\{[^}]*background:\s*var\(--dsw-alias-button-primary-fill\)/.test(style.textContent))
/* 0.2.2 anchors its popovers with CSS anchor positioning, so the old
   `style*='left'` centring never matched. The generic dialog width now has to
   step aside for them instead, or an anchored popover is stretched to 520px. */
check('an anchored popover keeps its own width',
  /\[role='dialog'\]:not\(\[data-shortcut-modal='settings'\]\):not\(\[style\*='position-anchor'\]\)/.test(style.textContent) &&
    !/\[role='dialog'\]\[style\*='left'\][^{]*\{[^}]*translateX\(-50%\)/.test(style.textContent))

/* a menu hangs off a zero-height anchor: a percentage cap collapses it */
check('a menu is capped by the viewport, not by its anchor',
  /\[role='menu'\][\s\S]{0,700}max-height:\s*min\(70vh/.test(style.textContent) &&
    !/\[role='menu'\][\s\S]{0,700}max-height:\s*calc\(100%/.test(style.textContent))

const htmlStyle = documentElement.style
const setSize = (w, h) => { frame.clientWidth = w; frame.clientHeight = h; windowStub.innerWidth = w; windowStub.innerHeight = h; windowStub.dispatch('resize') }
/* The host gives the right column no grid track at this width, but its own seat
   already reports the panel fullscreen and 100vw wide; the plugin only has to
   stage it as a screen of its own instead of hiding it. */
check('the right panel is staged as a screen',
  /\[class\*='rightbarCol'\]\s*\{[\s\S]{0,260}position:\s*absolute\s*!important/.test(style.textContent) &&
    /\[class\*='rightbarCol'\]\s*\{[\s\S]{0,260}inset:\s*0\s*!important/.test(style.textContent))
check('the plugin bar yields to the open panel',
  /:has\(\[data-sidebar-right-open\]\)\s*\.bigrice-topbar\s*\{\s*display:\s*none/.test(style.textContent))
check('a closed right panel takes no taps',
  /\[class\*='rightbarCol'\]\s*\{[\s\S]{0,320}pointer-events:\s*none\s*!important/.test(style.textContent) &&
    /\[class\*='rightbarCol'\]\:has\(\[data-sidebar-right-open\]\)\s*\{\s*pointer-events:\s*auto/.test(style.textContent))
check('the header expand control stays reachable',
  !/\[data-sidebar-right-expand\][^{]*\{[^}]*display:\s*none/.test(style.textContent))
/* A conversation header already holds the title and both actions, so the menu
   button rides that row and the bar stops reserving a band above it. */
check('the bar gives its band to a conversation header',
  /\[data-bigrice-mobile\]\[data-bigrice-head\]\s*\{\s*--bigrice-bar:\s*0px/.test(style.textContent))
check('the menu button rides on the header row',
  /\[data-bigrice-head\]\s+\.bigrice-topbar\s*\{[\s\S]{0,220}?top:\s*var\(--bigrice-head-top/.test(style.textContent))
/* The indent only exists when the plugin owns the leading corner: with the
   host drawer the host's own --dsh-frame-leading-clearance does that job. */
check('the header keeps its title clear of the button',
  /\[data-bigrice-head\]:not\(\[data-bigrice-hostdrawer\]\)[^{]*?titleRow'\][^{]*\{\s*padding-left:\s*56px/.test(style.textContent))
check('a document body reads at phone size',
  /pre\[data-textpreview-page\]\s*\{[\s\S]{0,90}?font-size:\s*15px/.test(style.textContent) &&
    /pre\.shiki\s*\{[\s\S]{0,90}?font-size:\s*14px/.test(style.textContent))
check('the preview sheet rides over the conversation',
  /\.bigrice-previewSheet\s*\{[\s\S]{0,240}position:\s*absolute/.test(style.textContent))

/* 0.1.31: below 768px the host mounts its own drawer (a leading hamburger, a
   scrim and a transformed sidebar column). Where that exists the plugin must
   stand down instead of stacking a second bar and a second mask on it. */
check('the plugin bar stands down for the host drawer',
  /\[data-bigrice-hostdrawer\]\s+\.bigrice-topbar[\s\S]{0,120}display:\s*none\s*!important/.test(style.textContent) &&
    /\[data-bigrice-hostdrawer\]\s+\.bigrice-mask/.test(style.textContent))
check('the host drawer brings its own leading band',
  /\[data-bigrice-mobile\]\[data-bigrice-hostdrawer\]\s*\{\s*--bigrice-bar:\s*0px/.test(style.textContent))
check('a phone settings page is edge to edge',
  /\[role='presentation'\]:has\(> \[role='dialog'\]\[data-shortcut-modal='settings'\]\)\s*\{\s*padding:\s*0/.test(style.textContent))
check('the host drawer probe reads the leading slot', source.includes("querySelector('[data-shell-leading]')"))
check('the drawer state mirrors the host frame', source.includes("setFlag(root, 'data-bigrice-drawer', !frame.hasAttribute('data-sidebar-collapsed'))"))
check('the phone styles use the 0.2.2 label token',
  style.textContent.includes('--dsw-alias-label-primary') && !style.textContent.includes('--dsw-alias-text-primary'))
check('the phone reads the new layout API', source.includes('getSnapshot()') && !source.includes('panelInfo.get()'))

/* a file card click opens it and the bridge supplies the pixels */
const reader = { readImage: windowStub.BigriceHost.readImage, readText: windowStub.BigriceHost.readText }
let askedImages = 0
windowStub.BigriceHost.readImage = (path) => {
  askedImages += 1
  return JSON.stringify({ ok: true, data: 'data:image/png;base64,AAAA', bytes: 4, path })
}
windowStub.BigriceHost.readText = () => JSON.stringify({ ok: false, reason: 'binary' })
/* The real card lives inside a rendered message; the preview only applies
   there, so the fixture is wrapped the same way. */
const messageBody = new Element('div')
messageBody.className = 'hWmORq_body'
const messageMarkdown = new Element('div')
messageMarkdown.className = '_markdown_1ypvv_5'
messageMarkdown.appendChild(messageBody)
const fileLink = new Element('button')
fileLink.className = 'o3BgMG_fileLink'
fileLink.textContent = 'chino.png'
messageBody.appendChild(fileLink)
documentElement.children.push(messageMarkdown)
documentStub.dispatch('click', { target: fileLink })
pendingRender = () => expandVNode(shell.component())
runRender()
for (const run of deferred.splice(0)) run()
/* A png/svg row opens in the plugin's own viewer; the host's right column is
   never involved. */
check('a file card opens the lightbox',
  documentElement.hasAttribute('data-bigrice-preview'))
check('back closes the lightbox',
  windowStub.__bigriceBack() === true && !documentElement.hasAttribute('data-bigrice-preview'))
windowStub.BigriceHost.readImage = reader.readImage
windowStub.BigriceHost.readText = reader.readText
documentElement.children = documentElement.children.filter((child) => child !== messageMarkdown)

/* Chrome around the conversation must stay inert: a file name in the sidebar
   or a settings preview is not something to open. */
const chromeLink = new Element('button')
chromeLink.className = 'YDXeBa_sessionRow o3BgMG_fileLink'
chromeLink.textContent = 'chino.txt'
const chromeCol = new Element('div')
chromeCol.className = 'pI_x6G_sidebarCol'
chromeCol.appendChild(chromeLink)
documentElement.children.push(chromeCol)
const beforeChrome = documentElement.hasAttribute('data-bigrice-preview')
documentStub.dispatch('click', { target: chromeLink })
check('a file name in the sidebar is left alone',
  !documentElement.hasAttribute('data-bigrice-preview') && beforeChrome === false)
documentElement.children = documentElement.children.filter((child) => child !== chromeCol)



setSize(411, 400)
check('short flag on at 400px', documentElement.hasAttribute('data-bigrice-short'))
setSize(411, 915)
check('short flag clears again', !documentElement.hasAttribute('data-bigrice-short'))

setSize(1280, 900)
check('desktop width leaves mobile mode', !documentElement.hasAttribute('data-bigrice-mobile'))
setSize(411, 915)
check('phone width re-enters mobile mode', documentElement.hasAttribute('data-bigrice-mobile'))

/* the host panel keeps a desktop track width inline; a drawer must not */
sidebarRoot.style.width = '280px'
setSize(411, 915)
check('drawer drops the desktop panel width', sidebarRoot.style.width === '', 'width=' + sidebarRoot.style.width)
setSize(1280, 900)
check('desktop restores the panel width', sidebarRoot.style.width === '280px', 'width=' + sidebarRoot.style.width)
setSize(411, 915)

/* launch modals are dismissed by title, whatever the language */
function makeDialog(label, buttons, extra = {}) {
  const node = new HTMLTag('div')
  node.setAttribute('role', 'dialog')
  node.setAttribute('aria-label', label)
  for (const b of buttons) {
    const button = new HTMLTag('button')
    button.textContent = b.text
    if (b.ariaLabel) button.setAttribute('aria-label', b.ariaLabel)
    button.addEventListener('click', () => { node.removed = true; node.dispatch('click', { target: button }) })
    button._wired = true
    node.appendChild(button)
  }
  Object.assign(node, extra)
  body.appendChild(node)
  return node
}

const welcome = makeDialog('Preview Notice', [{ text: 'Continue' }])
for (const fn of intervals) fn()
check('preview notice is dismissed in English', welcome.removed === true)
body.children = body.children.filter((c) => c !== welcome)

const signin = makeDialog('Get started', [{ text: '✕', ariaLabel: 'Close' }])
for (const fn of intervals) fn()
check('sign-in prompt is dismissed in English', signin.removed === true)
body.children = body.children.filter((c) => c !== signin)

const fresh = makeDialog('Get started', [{ text: '✕', ariaLabel: 'Close' }])
documentStub.dispatch('click', { target: panelRow })
for (const fn of intervals) fn()
check('a just-opened sign-in prompt survives', fresh.removed !== true)


documentElement.setAttribute('data-bigrice-drawer', '')
documentStub.dispatch('click', { target: moreButton })
check('More (en) keeps the drawer open', documentElement.hasAttribute('data-bigrice-drawer'))

documentStub.dispatch('click', { target: accountButton })
check('account trigger keeps the drawer open', documentElement.hasAttribute('data-bigrice-drawer'))

documentStub.dispatch('click', { target: toggle })
check('host toggle keeps the drawer open', documentElement.hasAttribute('data-bigrice-drawer'))

documentStub.dispatch('click', { target: menuItem })
check('a portalled menu pick closes the drawer', !documentElement.hasAttribute('data-bigrice-drawer'))

documentElement.setAttribute('data-bigrice-drawer', '')
documentStub.dispatch('click', { target: panelRow })
check('a panel row closes the drawer', !documentElement.hasAttribute('data-bigrice-drawer'))

documentElement.setAttribute('data-bigrice-drawer', '')
documentStub.dispatch('click', { target: rowMenu })
check('a row action menu keeps the drawer open', documentElement.hasAttribute('data-bigrice-drawer'))

documentStub.dispatch('click', { target: workspaceRow })
check('a workspace row only expands', documentElement.hasAttribute('data-bigrice-drawer'))

documentStub.dispatch('click', { target: sessionRow })
check('picking a session closes the drawer', !documentElement.hasAttribute('data-bigrice-drawer'))

documentStub.activeElement = null
composerInput.focus()
check('a scripted focus cannot raise the keyboard', documentStub.activeElement === null,
  'active=' + (documentStub.activeElement && documentStub.activeElement.className))

documentElement.setAttribute('data-bigrice-drawer', '')
const hidesBeforeRow = windowStub.BigriceHost.keyboardHides
documentStub.activeElement = composerInput
documentStub.dispatch('click', { target: workspaceNewSession })
for (const job of timeouts.splice(0)) job()
check('workspace new session closes the drawer', !documentElement.hasAttribute('data-bigrice-drawer'))
check('workspace new session drops the keyboard',
  documentStub.activeElement === null && windowStub.BigriceHost.keyboardHides > hidesBeforeRow,
  'active=' + (documentStub.activeElement && documentStub.activeElement.className))

documentElement.setAttribute('data-bigrice-drawer', '')
const hidesBefore = windowStub.BigriceHost.keyboardHides
documentStub.activeElement = composerInput
documentStub.dispatch('click', { target: newSessionButton })
for (const job of timeouts.splice(0)) job()
check('new session closes the drawer', !documentElement.hasAttribute('data-bigrice-drawer'))
check('new session drops the keyboard', documentStub.activeElement === null && windowStub.BigriceHost.keyboardHides > hidesBefore,
  'active=' + (documentStub.activeElement && documentStub.activeElement.className) + ' hides=' + windowStub.BigriceHost.keyboardHides)

documentElement.setAttribute('data-bigrice-drawer', '')
documentStub.dispatch('click', { target: clearButton })
check('clearing the search keeps the drawer open', documentElement.hasAttribute('data-bigrice-drawer'))

documentStub.activeElement = composerInput
documentStub.dispatch('click', { target: composerAdd })
for (const job of timeouts.splice(0)) job()
check('tapping + drops the keyboard', documentStub.activeElement === null, 'activeElement=' + (documentStub.activeElement && documentStub.activeElement.className))
check('tapping + asks the host to hide the IME', windowStub.BigriceHost.keyboardHides > 0, 'hides=' + windowStub.BigriceHost.keyboardHides)

// dialogs sit above the drawer, so clear the launch-modals first
body.children = body.children.filter((c) => c.getAttribute('role') !== 'dialog')
documentElement.setAttribute('data-bigrice-drawer', '')
const backFn = windowStub.__bigriceBack || context.__bigriceBack
const backResult = typeof backFn === 'function' ? backFn() : 'missing:' + typeof backFn
check('back closes the drawer first', backResult === true && !documentElement.hasAttribute('data-bigrice-drawer'), 'returned ' + backResult)
check('back closes the side panel next', backFn() === true)
check('back reports nothing left', backFn() === false)

/* The right panel covers the conversation, so Back has to leave it first. */
panelExpanded = true
check('back closes the right panel', backFn() === true && panelExpanded === false, 'expanded=' + panelExpanded)

/* the sheet alignment vars come from the enclosing dialog */
const dialog = new HTMLTag('div')
dialog.setAttribute('role', 'dialog')
dialog.setAttribute('data-shortcut-modal', 'settings')
const inner = new HTMLTag('button')
dialog.appendChild(inner)
body.appendChild(dialog)
documentStub.dispatch('click', { target: inner })
check('dialog sets the sheet left edge', htmlStyle.getPropertyValue('--bigrice-sheet-left') === '8px')
check('dialog sets the sheet right edge', htmlStyle.getPropertyValue('--bigrice-sheet-right') === '51px')

/* ---- the bigrice tab in the settings dialog ----------------------------- */
const sectionEntry = registered.find((entry) => entry.options.name === 'settings.section')
check('registers a bigrice settings tab', Boolean(sectionEntry) && sectionEntry.options.id === 'bigrice')
check('the tab label does not depend on the locale', Boolean(sectionEntry) && sectionEntry.options.label() === 'bigrice')
check('the tab sorts after the built-in pages', Boolean(sectionEntry) && sectionEntry.options.order >= 50)

hooks = []
pendingRender = () => expandVNode(sectionEntry.component())
let tree = runRender()
check('the tab is titled Logs', textOf(tree).includes('Logs'))
check('the tab hints at what it reads', textOf(tree).includes('dsh server log'))
check('the tab offers a log viewer', textOf(tree).includes('View logs'))
check('logs stay hidden until asked for', !textOf(tree).includes('hello log'))

const toggleLogs = findNodes(tree, (node) => node.type === 'button' && textOf(node) === 'View logs')[0]
check('the viewer button is wired', Boolean(toggleLogs && toggleLogs.props && typeof toggleLogs.props.onClick === 'function'))
toggleLogs.props.onClick()
tree = lastTree
check('the viewer reads the dsh log', textOf(tree).includes('hello log'))
check('the hint drops the app version', !textOf(tree).includes('0.0.31'))
check('the viewer marks a truncated read', textOf(tree).includes('showing the tail only'))

const bootTab = findNodes(tree, (node) => node.type === 'button' && textOf(node) === '本次启动')[0]
check('the viewer lists the boot log as a second file', Boolean(bootTab))
bootTab.props.onClick()
tree = lastTree
check('picking another file shows its text', textOf(tree).includes('boot!'))

windowStub.BigriceHost.toasts.length = 0
const copyButton = findNodes(tree, (node) => node.type === 'button' && textOf(node) === 'Copy')[0]
copyButton.props.onClick()
check('a copy without a clipboard reports the fallback',
  windowStub.BigriceHost.toasts.includes('Copy failed — select the text instead'))

/* ---- the file card under the same tab ----------------------------------- */
check('the import folder is a workspace-relative path',
  windowStub.BigriceHost.setDirs.indexOf('download') >= 0,
  JSON.stringify(windowStub.BigriceHost.setDirs))
check('the import folder is never an absolute host path',
  windowStub.BigriceHost.setDirs.every((dir) => !dir.startsWith('/')),
  JSON.stringify(windowStub.BigriceHost.setDirs))
check('the file card points at the browser', textOf(tree).includes('Browse files'))
check('the file card no longer lists the imports', !textOf(tree).includes('headshot.png'))
const browseButton = findNodes(tree, (node) => node.type === 'button' && textOf(node) === 'Browse files')[0]
check('the file card offers the browser', Boolean(browseButton))
browseButton.props.onClick()
tree = lastTree
check('the browser opens inside the card',
  textOf(tree).includes('/root') && textOf(tree).includes('Save to Download'))
check('the browser keeps the settings tab around', textOf(tree).includes('Files'))
check('the browser can be folded away again', textOf(tree).includes('Hide'))
check('the browser lists files', textOf(tree).includes('notes.txt'))
check('the browser marks folders', textOf(tree).includes('A'))
check('the browser sizes a file', textOf(tree).includes('12 B'))

const folderRow = findNodes(tree, (node) => node.type === 'button' && textOf(node).trim().endsWith('A'))[0]
check('a folder row is clickable', Boolean(folderRow))
folderRow.props.onClick()
tree = lastTree
check('opening a folder moves the path', textOf(tree).includes('/root/A'))

const imageRow = findNodes(tree, (node) => node.type === 'button' && textOf(node) === 'headshot.png')[0]
check('a file row is clickable', Boolean(imageRow))
imageRow.props.onClick()
tree = lastTree
const previewImage = findNodes(tree, (node) => node.type === 'img')[0]
check('an image previews from a data URL',
  Boolean(previewImage) && previewImage.props.src.indexOf('data:image/png;base64,') === 0)

const upButton = findNodes(tree, (node) => node.type === 'button' && textOf(node) === 'Up')[0]
check('the browser offers Up', Boolean(upButton))
upButton.props.onClick()
tree = lastTree
check('Up returns to /root', textOf(tree).includes('notes.txt'))

const hiddenLabel = findNodes(tree, (node) => node.type === 'label' && textOf(node).trim() === 'Hidden files')[0]
check('the browser offers hidden files', Boolean(hiddenLabel))
hiddenLabel.children[0].props.onChange({ target: { checked: true } })
tree = lastTree
check('the listing asks for hidden entries',
  windowStub.BigriceHost.dirHidden[windowStub.BigriceHost.dirHidden.length - 1] === true,
  JSON.stringify(windowStub.BigriceHost.dirHidden))
check('a hidden entry shows up once asked for', textOf(tree).includes('.cache'))
hiddenLabel.children[0].props.onChange({ target: { checked: false } })
tree = lastTree
check('the flag goes back off',
  windowStub.BigriceHost.dirHidden[windowStub.BigriceHost.dirHidden.length - 1] === false
    && !textOf(tree).includes('.cache'))

const textRow = findNodes(tree, (node) => node.type === 'button' && textOf(node) === 'notes.txt')[0]
textRow.props.onClick()
tree = lastTree
check('a text file previews inline', textOf(tree).includes('hello notes'))

const firstDelete = findNodes(tree, (node) => node.type === 'button' && textOf(node) === 'Delete')[0]
check('delete is offered for an entry', Boolean(firstDelete))
firstDelete.props.onClick()
tree = lastTree
check('one tap only asks for confirmation',
  windowStub.BigriceHost.deleted.length === 0 && textOf(tree).includes('Confirm'))
const confirmButton = findNodes(tree, (node) => node.type === 'button' && textOf(node) === 'Confirm')[0]
check('the confirmation button is wired', Boolean(confirmButton))
confirmButton.props.onClick()
check('the confirmed tap deletes the entry', windowStub.BigriceHost.deleted[0] === 'A')

tree = lastTree
const rowTicks = () => findNodes(tree, (node) => node.type === 'input'
  && node.props.type === 'checkbox' && node.props.className === 'bigrice-fileTick')
let ticks = rowTicks()
check('every row can be ticked', ticks.length >= 2, String(ticks.length))
ticks[0].props.onChange()
tree = lastTree
ticks = rowTicks()
ticks[1].props.onChange()
tree = lastTree
check('a tick is counted', textOf(tree).includes('selected 2'))

const exportButton = findNodes(tree, (node) => node.type === 'button' && textOf(node) === 'Export zip')[0]
check('the export button is wired', Boolean(exportButton))
exportButton.props.onClick()
tree = lastTree
const nameInput = findNodes(tree, (node) => node.type === 'input' && node.props.type === 'text')[0]
check('export asks for a file name first', Boolean(nameInput))
check('the suggested name is not just bigrice',
  Boolean(nameInput) && /^root-\d{8}-\d{4}$/.test(nameInput.props.value), nameInput && nameInput.props.value)
nameInput.props.onChange({ target: { value: 'my-notes' } })
tree = lastTree
const confirmExport = findNodes(tree, (node) => node.type === 'button' && textOf(node) === 'Export')[0]
check('the export can be confirmed', Boolean(confirmExport))
confirmExport.props.onClick()
for (const job of timeouts.splice(0)) job()
check('export hands the ticked paths and the typed name to the bridge',
  windowStub.BigriceHost.exports.length === 1
  && windowStub.BigriceHost.exports[0].rels === ['A', 'notes.txt'].join(String.fromCharCode(10))
  && windowStub.BigriceHost.exports[0].name === 'my-notes',
  JSON.stringify(windowStub.BigriceHost.exports))
check('a finished export is reported',
  windowStub.BigriceHost.toasts.some((text) => text.indexOf('Saved to Download/bigrice-1.zip') === 0))

tree = lastTree
const pickFolder = findNodes(tree, (node) => node.type === 'button' && textOf(node) === 'Change')[0]
check('the export location can be changed', Boolean(pickFolder))
pickFolder.props.onClick()
check('changing the export location opens the picker', windowStub.BigriceHost.exportPicks === 1)

/* the popup hand-off still has to exist, and must not throw when no card is up */
windowStub.__bigriceImportDir('/root/C/download')
check('the import callback is callable', typeof windowStub.__bigriceImportDir === 'function')

/* the browser sits in the tab, so Back has no window to close: it walks the
   drawer and the side panel instead, leaving the browser as it was */
documentElement.setAttribute('data-bigrice-drawer', '')
check('back clears the drawer before the panel', windowStub.__bigriceBack() === true
  && !documentElement.hasAttribute('data-bigrice-drawer'))
check('back leaves the browser open', textOf(lastTree).includes('Save to'))

/* the console: one Linux command at a time, with its output kept in the card */
const consoleButton = findNodes(tree, (node) => node.type === 'button' && textOf(node) === 'Console')[0]
check('the tab offers a console', Boolean(consoleButton))
consoleButton.props.onClick()
tree = lastTree
check('the console opens inside its card', textOf(tree).includes('No command yet.'))
const cmdInput = findNodes(tree, (node) => node.type === 'input'
  && node.props.placeholder === 'Type a command and press enter')[0]
check('the console has a command line', Boolean(cmdInput))
cmdInput.props.onInput({ target: { value: 'echo hi' } })
tree = lastTree
const cmdForm = findNodes(tree, (node) => node.type === 'form')[0]
check('the console submits a form', Boolean(cmdForm))
cmdForm.props.onSubmit({ preventDefault() {} })
check('the command reaches the app',
  windowStub.BigriceHost.shellCalls.length === 1
    && windowStub.BigriceHost.shellCalls[0].command === 'echo hi',
  JSON.stringify(windowStub.BigriceHost.shellCalls))
windowStub.__bigriceShellChunk('sh1', 'hi\n')
await Promise.resolve()
await Promise.resolve()
tree = lastTree
check('live output shows while the command runs', textOf(tree).includes('hi'))
check('a running command can be stopped',
  Boolean(findNodes(tree, (node) => node.type === 'button' && textOf(node) === 'Stop')[0]))
windowStub.__bigriceShellResult('sh1', {
  ok: true, code: 0, ms: 12, truncated: false, timedOut: false, output: 'hi\n',
})
await Promise.resolve()
await Promise.resolve()
tree = lastTree
check('the output lands in the console', textOf(tree).includes('hi'))
const outputLines = textOf(tree).split(String.fromCharCode(10))
  .filter((line) => line.trim() === 'hi')
check('the streamed output is not repeated', outputLines.length === 1, String(outputLines.length))
check('the console reports the exit code', textOf(tree).includes('Exit 0'))

/* the same tab without the app bridge explains itself instead of going blank */
pendingRender = () => expandVNode(sectionEntry.component())
hooks = []
const savedHost = windowStub.BigriceHost
delete windowStub.BigriceHost
tree = runRender()
const noBridgeToggle = findNodes(tree, (node) => node.type === 'button' && textOf(node) === 'View logs')[0]
noBridgeToggle.props.onClick()
check('a plain browser is told the log channel is missing',
  textOf(lastTree).includes('a plain browser has no channel to them'))
windowStub.BigriceHost = savedHost


/* cleanup runs without throwing */
for (const effect of effects) if (effect.dispose) effect.dispose()

console.log(JSON.stringify({ results, failures }, null, 2))
process.exitCode = failures.length ? 1 : 0
