#!/usr/bin/env python3
"""Regenerate android-pack/ for the Bluefin Android WebView.

The pack is a 1:1 copy source for android/app/src/main/assets/bluefin/.
It does not modify bluefin-native painting or selectFrame, and it does not
touch the desktop ZIP / base64 preview built by build_release.py.

PNG recompression, when oxipng is on PATH, is lossless (-o 4, no --alpha,
no rescale) and is applied only to copies inside the pack.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'android-pack'
PACK = ROOT / 'bluefin-native'
KIT = ROOT / 'preview' / 'kit'

INDEX_HTML = r"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="color-scheme" content="light">
<title>蓝鳍</title>
<style>
html{color-scheme:light}
*{box-sizing:border-box}
html,body{height:100%;margin:0}
body{background:#edf1f8;color:#203354;font-family:"PingFang SC","Noto Sans SC","Microsoft YaHei",sans-serif;overflow:hidden;overscroll-behavior:none}
main{height:100%;height:100dvh;display:flex;flex-direction:column;padding:env(safe-area-inset-top) env(safe-area-inset-right) env(safe-area-inset-bottom) env(safe-area-inset-left)}
#wrap{display:flex;flex-direction:column;flex:1 1 auto;min-width:0;min-height:0}
header{flex:none;padding:8px 12px 4px}
h1{font-size:17px;line-height:1.3;margin:0;font-weight:650}
.sub{margin-top:2px;color:#647895;font-size:12px;line-height:1.45;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
#ver{margin-right:6px;color:#3d5c86}
#stage{position:relative;flex:1 1 auto;min-height:0;overflow:hidden;background:linear-gradient(#dbe8fb,#f8fbff 75%);touch-action:none;user-select:none;-webkit-user-select:none;-webkit-touch-callout:none}
#stage,#stage *{touch-action:none}
#stage:after{content:"";position:absolute;left:0;right:0;bottom:40px;border-top:1px solid #b7cce7;pointer-events:none}
#hint{position:absolute;z-index:2;top:10px;left:12px;right:12px;font-size:12px;line-height:1.45;color:#6681a4;pointer-events:none}
#root{position:absolute;inset:0}
#panel{flex:0 1 auto;max-height:34dvh;overflow:auto;-webkit-overflow-scrolling:touch;touch-action:pan-y;padding:8px 12px 12px;background:#fff}
#status{font-size:13px;min-height:1.4em;margin:0 0 8px}
.tools{display:flex;gap:6px}
.tools button{flex:1}
h2{font-size:13px;margin:10px 0 6px;color:#3d5c86}
.buttons{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:6px}
button{border:1px solid #cfdced;border-radius:9px;background:#f7faff;padding:8px 4px;min-height:40px;color:#24436b;font:inherit;font-size:13px;touch-action:manipulation}
button.active,button:hover{background:#dbeaff;border-color:#6b9fe0}
.notes{font-size:12px;line-height:1.6;color:#69809d;margin:8px 0 0}
#events{margin-top:6px;font:11px/1.45 ui-monospace,Consolas,monospace;color:#54708e;white-space:pre-wrap;max-height:3.2em;overflow:hidden}
canvas{image-rendering:auto}
@media(min-width:720px){.buttons{grid-template-columns:repeat(6,minmax(0,1fr))}#panel{max-height:32dvh}}
@media(orientation:landscape) and (max-height:520px){main{flex-direction:row}#panel{width:min(400px,48vw);max-height:none;height:100%}}
</style>
</head>
<body>
<main>
  <div id="wrap">
    <header>
      <h1 id="title">蓝鳍</h1>
      <div class="sub"><span id="ver"></span><span id="sub">正在载入原画…</span></div>
    </header>
    <div id="stage">
      <div id="hint">轻点是戳一戳，按住拖动可提起，快速甩开会抛出。下方可选挥手、行走、跑步、跳舞和睡觉。</div>
      <div id="root"></div>
    </div>
  </div>
  <section id="panel">
    <div id="status">正在载入原画…</div>
    <div class="tools">
      <button type="button" id="reset">恢复站立</button>
      <button type="button" id="demo">连续演示</button>
      <button type="button" id="pause">暂停</button>
      <button type="button" id="size">切换大小</button>
    </div>
    <h2>表情</h2>
    <div id="expressions" class="buttons"></div>
    <h2>动作</h2>
    <div id="motions" class="buttons"></div>
    <p class="notes">轻点角色：戳一戳。未按住时在身上来回移动：摸头。按住拖动：提起挣动。快速甩开：抛出，重重落地会头晕。睡觉时轻点：戳醒。</p>
    <div id="events"></div>
  </section>
</main>
<script type="module">
const stage = document.querySelector('#stage');
const root = document.querySelector('#root');
const events = [];
let body = null;
let paused = false;
let compact = false;
let manual = false;
let scale = 1;
let demoTimer = 0;

function preferredScale() {
  const h = stage.clientHeight;
  const w = stage.clientWidth;
  if (h < 40 || w < 40) return 1;
  const s = Math.min((h - 28) / 262, (w - 12) / 190);
  return Math.max(0.48, Math.min(1.35, Math.round(s * 100) / 100));
}
function smallerScale() {
  return Math.max(0.42, Math.round(preferredScale() * 0.67 * 100) / 100);
}
function bounds() {
  return { W: stage.clientWidth, H: stage.clientHeight, floorY: stage.clientHeight - 40, S: scale };
}
const imgs = new Map();
function loadImage(u) {
  if (!imgs.has(u)) {
    imgs.set(u, new Promise((ok, bad) => {
      const image = new Image();
      image.onload = () => ok(image);
      image.onerror = () => bad(new Error('无法加载 ' + u));
      image.src = u;
    }));
  }
  return imgs.get(u);
}
const asset = (p) => new URL(p, import.meta.url).href;

function point(e) {
  const rect = stage.getBoundingClientRect();
  return { x: e.clientX - rect.left, y: e.clientY - rect.top, t: e.timeStamp };
}
stage.addEventListener('pointerdown', (e) => {
  if (!body) return;
  if (e.cancelable) e.preventDefault();
  try { stage.setPointerCapture(e.pointerId); } catch (err) {}
  body.pointer('down', point(e));
}, { passive: false });
stage.addEventListener('pointermove', (e) => {
  if (!body) return;
  if (e.cancelable) e.preventDefault();
  body.pointer('move', point(e));
}, { passive: false });
stage.addEventListener('pointerup', (e) => {
  if (!body) return;
  body.pointer('up', point(e));
}, { passive: false });
stage.addEventListener('pointercancel', (e) => {
  if (!body) return;
  body.pointer('cancel', point(e));
}, { passive: false });
stage.addEventListener('pointerleave', (e) => {
  if (!body) return;
  body.pointer('leave', point(e));
}, { passive: false });
stage.addEventListener('contextmenu', (e) => e.preventDefault());

function mark(id) {
  document.querySelectorAll('button[data-word]').forEach((el) => {
    el.classList.toggle('active', el.dataset.word === id);
  });
}
function showStatus() {
  const canvas = root.querySelector('canvas');
  const pose = canvas && canvas.dataset.pose || '';
  const mode = body ? body.layout().mode : '…';
  document.querySelector('#status').textContent = body ? ('当前状态：' + mode + '　原画帧：' + pose) : '正在载入原画…';
  document.querySelector('#events').textContent = events.slice(-3).map((e) => JSON.stringify(e)).join('\n');
}

async function main() {
  let kit, mod, meta;
  try {
    [kit, mod] = await Promise.all([
      import('./kit/body.js'),
      import('./figure.js'),
    ]);
    const res = await fetch(new URL('./figure.json', import.meta.url));
    if (!res.ok) throw new Error('figure.json HTTP ' + res.status);
    meta = await res.json();
  } catch (err) {
    document.querySelector('#status').textContent = '载入失败：' + (err && err.message ? err.message : err);
    console.error(err);
    return;
  }
  document.title = (meta.name && meta.name.zh) || '蓝鳍';
  document.querySelector('#title').textContent = document.title;
  document.querySelector('#ver').textContent = meta.version ? ('v' + meta.version) : '';
  document.querySelector('#sub').textContent = (meta.about && meta.about.zh) || '';
  for (const word of meta.vocab || []) {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = (word.names && word.names.zh && word.names.zh[0]) || word.id;
    button.dataset.word = word.id;
    button.addEventListener('click', () => {
      if (!body) return;
      clearInterval(demoTimer);
      demoTimer = 0;
      mark(word.id);
      body.do(word.id);
    });
    const slot = word.kind === 'expression' ? '#expressions' : '#motions';
    document.querySelector(slot).appendChild(button);
  }

  async function reset() {
    clearInterval(demoTimer);
    demoTimer = 0;
    const previous = body;
    body = null;
    previous?.dispose();
    events.length = 0;
    scale = compact ? smallerScale() : preferredScale();
    body = await mod.createBluefin(new URL('./', import.meta.url), {
      kit,
      host: {
        root,
        bounds,
        emit(kind, detail) {
          events.push({ kind, ...detail });
          if (events.length > 120) events.shift();
        },
        sound() {},
        start: { x: stage.clientWidth / 2, facing: 1 },
      },
      loadImage,
      asset,
    });
    body.set({ roam: 'off' });
    body.step(1 / 60);
    showStatus();
    return body;
  }

  window.qa = {
    events,
    get body() { return body; },
    get meta() { return meta; },
    manual(v) { manual = v; },
    advance(n = 1) { for (let i = 0; i < n; i++) body.step(1 / 60); },
    snapshot() {
      return {
        layout: body.layout(),
        pose: (root.querySelector('canvas') || {}).dataset?.pose || '',
        mode: body.layout().mode,
        events: events.slice(),
      };
    },
    reset,
    ready: false,
  };

  document.querySelector('#reset').addEventListener('click', () => {
    mark('');
    reset().catch((err) => {
      document.querySelector('#status').textContent = '载入失败：' + (err && err.message ? err.message : err);
      console.error(err);
    });
  });
  document.querySelector('#pause').addEventListener('click', (e) => {
    paused = !paused;
    e.currentTarget.textContent = paused ? '继续' : '暂停';
  });
  document.querySelector('#size').addEventListener('click', () => {
    compact = !compact;
    scale = compact ? smallerScale() : preferredScale();
    if (body) body.resize();
  });
  document.querySelector('#demo').addEventListener('click', async () => {
    if (!mod) return;
    await reset();
    let i = 0;
    const seq = ['wave', 'walk', 'run', 'jump', 'nod', 'shake', 'bow', 'dance', 'sit', 'sleep', 'stand', 'dizzy', 'love', 'hug'];
    const play = () => {
      const id = seq[i % seq.length];
      i += 1;
      mark(id);
      body.do(id);
    };
    play();
    demoTimer = setInterval(play, 3800);
  });
  addEventListener('resize', () => {
    scale = compact ? smallerScale() : preferredScale();
    if (body) body.resize();
  });
  let prev = performance.now();
  function tick(t) {
    if (!paused && !manual && body) body.step(Math.min(0.04, (t - prev) / 1000));
    prev = t;
    if (body) showStatus();
    requestAnimationFrame(tick);
  }
  requestAnimationFrame(tick);
  try {
    await reset();
  } catch (err) {
    document.querySelector('#status').textContent = '载入失败：' + (err && err.message ? err.message : err);
    console.error(err);
    return;
  }
  window.qa.ready = true;
}
main();
</script>
</body>
</html>
"""

README_TEMPLATE = r"""# 蓝鳍 Android WebView 资源包

这是蓝鳍的**路由 B**资源包：`index.html`、互动引擎和透明 PNG 分开存放，给 App 里的 WebView 用。它不是把全部帧嵌成 base64 的 `bluefin-preview.html`。

- 形象包 ID：`__ID__`
- 版本：`__VERSION__`（与 `figure.json` 一致）
- 绘画和 `selectFrame` 是 `bluefin-native/figure.js` 的原样副本，互动引擎是 `preview/kit/body.js` 与 `preview/kit/rig.js` 的原样副本。本包只改页面如何用相对路径加载它们。

## 复制到 App

把**本目录中的全部内容**复制到：

`android/app/src/main/assets/bluefin/`

复制的是 `android-pack/` **里面**的文件和子目录，不要多套一层名为 `android-pack` 的文件夹。

复制完成后，入口文件必须在：

`android/app/src/main/assets/bluefin/index.html`

英文说明（与 `pack-manifest.json` 的 `copyInstruction` 相同）：

copy contents of android-pack/ into assets/bluefin/

目标路径：`assets/bluefin/`。

## WebView 怎么打开

使用 AndroidX `WebViewAssetLoader`，把 URL 路径 `/assets/` 映射到 App 的 `assets/` 目录，然后加载：

`https://appassets.androidplatform.net/assets/bluefin/index.html`

`index.html` 里用到的地址都以 **`index.html` 所在目录**为基准：

| 相对路径 | 作用 |
| --- | --- |
| `./figure.js` | `createBluefin` / `selectFrame` |
| `./figure.json` | 版本、名称、表情和动作词汇 |
| `./kit/body.js` | 互动与物理引擎 |
| `assets/<姿势>.png` | `createBluefin` 调用 `asset("assets/<姿势>.png")` 后相对 `index.html` 解析 |

`kit/body.js` 里有 `export { createRig } from './rig.js'`。这行按 ES 模块规则相对 **`kit/body.js` 自己**解析，磁盘上的文件是 `kit/rig.js`。从 `index.html` 看，它仍然是相对路径 `kit/rig.js`。不要改成站点根路径，也不要再包回 base64。

请用上面的 `https://appassets.androidplatform.net/...` 地址打开。不要用 `file://`，否则模块脚本通常会被浏览器拦住。

### 建议的 WebView 设置

依赖：`androidx.webkit:webkit`。

- `javaScriptEnabled = true`
- `setSupportZoom(false)`，避免双指缩放抢走舞台
- `overScrollMode = NEVER`，避免下拉回弹和提起拖拽抢手势
- `.js` 的响应类型必须是 `text/javascript`（或 `application/javascript`）。部分系统的 `MimeTypeMap` 对 `.js` 返回空或 `text/plain`，ES 模块就不会执行

舞台 CSS 已写 `touch-action:none`。指针原样交给引擎，判定没有改：

| 浏览器事件 | `body.pointer` |
| --- | --- |
| pointerdown | down |
| pointermove | move |
| pointerup | up |
| pointercancel | cancel |
| pointerleave | leave |

```kotlin
val assetLoader = WebViewAssetLoader.Builder()
    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
    .build()

webView.settings.javaScriptEnabled = true
webView.settings.setSupportZoom(false)
webView.isVerticalScrollBarEnabled = false
webView.overScrollMode = View.OVER_SCROLL_NEVER

webView.webViewClient = object : WebViewClient() {
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val res = assetLoader.shouldInterceptRequest(request.url) ?: return null
        val path = request.url.path ?: return res
        val mime = when {
            path.endsWith(".js") -> "text/javascript"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".html") -> "text/html"
            path.endsWith(".png") -> "image/png"
            else -> res.mimeType
        }
        if (mime == null || mime == res.mimeType) return res
        return WebResourceResponse(mime, res.encoding ?: "utf-8", res.data)
    }
}

webView.loadUrl("https://appassets.androidplatform.net/assets/bluefin/index.html")
```

需要支持 ES Modules 和 Pointer Events 的 Android System WebView（Chrome 80 及以上更稳妥）。

## 互动（P0，与桌面预览同一套引擎）

| 操作 | 表现 | 帧 |
| --- | --- | --- |
| 轻点 | 戳一戳；随机表情或蹦一下 | 当前表情帧，没有单独的「戳」帧 |
| 未按住时在身上来回移动 | 摸头（喜欢或害羞） | `love`、`shy` |
| 按住并拖动 | 提起，四帧挣动 | `lift_0` … `lift_3` |
| 快速甩开后松手，重重落地 | 抛出，然后头晕、起身 | `fall`、`dizzy_0`…`dizzy_3`，以及 `sit`、`shake_0`、`shake_2`、`crouch`、`neutral` |
| 动作按钮：挥手 / 行走 / 跑步 / 跳舞 | 对应动作 | `wave_0`…`wave_3`、`walk_0`…`walk_7`、`run_0`…`run_3`、`dance_0`…`dance_3` |
| 睡觉 | 坐下 → 趴下 → 睡着 | `sit`、`prone`、`sleep` |
| 睡觉时轻点 | 戳醒（引擎里已有） | `sleep`、`prone`、`sit`、`stretch`、`jump`、`crouch`、`neutral` |

触摸屏没有鼠标悬停。手指按下后再移动，超过引擎大约 6 像素会变成「提起」。这是 `body.js` 里的原有规则，本包不改 `body.js`。摸头仍然走「指针在移动、但没有形成按下拖拽」那条路径。

下方按钮列出 `figure.json` 里的全部表情和动作，用来直接看挥手、行走、跑步、跳舞、睡觉等。自由游荡默认关闭（`roam: off`），和桌面预览页一样，避免一加载就自己走开。

`frames-index.json` 里有每个姿势的文件名、字节数、序列分组、词汇，以及上面这些 P0 姿势。`pack-manifest.json` 里有入口、必需文件、总字节数和复制说明。

## 目录里有什么

```text
index.html
figure.js
figure.json
kit/body.js
kit/rig.js
assets/*.png
frames-index.json
pack-manifest.json
README.md
```

`assets/` 包含 `figure.js` 的 `ASSETS` 全部姿势，以及 `figure.json` 指向的缩略图 `thumb.png`。不含 `source/`、文档配图、验证用 GIF/JPG，也不含 base64 单文件预览。

## 重新生成

在仓库根目录执行：

```text
python3 build_android_pack.py
```

脚本会：

1. 用 Node 读取 `bluefin-native/figure.js` 的 `ASSETS`，并调用 `selectFrame`，确认每个被选中的姿势都有 PNG。
2. 原样复制 `figure.js`、`figure.json`、`preview/kit/body.js`、`preview/kit/rig.js`。
3. 复制 `bluefin-native/assets` 下的全部 PNG。若本机有 `oxipng`，只对**本包里的副本**做无损压缩（`-o 4`，不使用会改写透明像素的 `--alpha`，不缩放）。设 `BLUEFIN_OXIPNG=0` 则按原字节复制。
4. 重写 `frames-index.json` 和 `pack-manifest.json`。

桌面角色 ZIP 和单文件预览仍然只用 `python3 build_release.py`，两条路径互不修改。

需要 Node.js 来核对 `figure.js`。没有 `oxipng` 时帧图与源文件字节相同。
"""

CHECK_JS = r"""
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const root = process.argv[2];
const mode = process.argv[3];
const packDir = process.argv[4];

if (mode === 'pack') {
  const kit = await import(pathToFileURL(path.join(packDir, 'kit/body.js')).href);
  const fig = await import(pathToFileURL(path.join(packDir, 'figure.js')).href);
  if (typeof kit.createBody !== 'function') throw new Error('createBody missing');
  if (typeof kit.createRig !== 'function') throw new Error('createRig missing (rig.js did not load)');
  if (typeof fig.createBluefin !== 'function') throw new Error('createBluefin missing');
  if (typeof fig.selectFrame !== 'function') throw new Error('selectFrame missing');
  if (!Array.isArray(fig.ASSETS) || !fig.ASSETS.length) throw new Error('ASSETS empty');
  const body = fs.readFileSync(path.join(packDir, 'kit/body.js'), 'utf8');
  if (!body.includes("from './rig.js'")) throw new Error("kit/body.js must keep import ./rig.js");
  process.stdout.write(JSON.stringify({ ok: true, assets: fig.ASSETS.length }));
  process.exit(0);
}

const fig = await import(pathToFileURL(path.join(root, 'bluefin-native/figure.js')).href);
const { ASSETS, selectFrame } = fig;
if (!Array.isArray(ASSETS) || !ASSETS.length) throw new Error('ASSETS empty');
if (new Set(ASSETS).size !== ASSETS.length) throw new Error('duplicate ASSETS id');

const src = fs.readFileSync(path.join(root, 'bluefin-native/figure.js'), 'utf8');
const faceBlock = src.match(/const facePose=\{([^}]+)\}/);
if (!faceBlock) throw new Error('facePose block not found');
const facePose = {};
for (const pair of faceBlock[1].matchAll(/(\w+):'([^']+)'/g)) facePose[pair[1]] = pair[2];

const assetDir = path.join(root, 'bluefin-native/assets');
const pngs = fs.readdirSync(assetDir).filter((name) => name.endsWith('.png')).sort();
const missingFiles = ASSETS.filter((id) => !pngs.includes(id + '.png'));
const faceMissing = Object.entries(facePose)
  .filter(([, pose]) => !ASSETS.includes(pose))
  .map(([face, pose]) => face + '->' + pose);

function frame(partial) {
  return selectFrame({ t: 0, modeT: 0, face: 'neutral', facing: 1, talk: 0, blink: 0, ...partial }).pose;
}
function uniq(list) {
  return [...new Set(list)];
}
function expect(name, got, ids) {
  const have = new Set(got);
  const miss = ids.filter((id) => !have.has(id));
  if (miss.length) throw new Error(name + ' missing poses: ' + miss.join(', ') + ' ; got ' + uniq(got).join(', '));
}
function sampleGesture(kind, n) {
  const out = [];
  for (let i = 0; i < n; i++) {
    const k = (i + 0.01) / n;
    out.push(frame({ mode: 'idle', gesture: { kind, k }, t: 0.4 }));
  }
  return out;
}

const lift = [];
for (let i = 0; i < 4; i++) {
  const mt = (i + 0.1) / 7;
  lift.push(frame({ mode: 'drag', modeT: mt, t: mt }));
}
const dizzy = [frame({ mode: 'dizzy', modeT: 0.05, t: 0.05 })];
for (let i = 0; i < 4; i++) {
  const mt = i === 0 ? 0.13 : (i + 0.05) / 7;
  dizzy.push(frame({ mode: 'dizzy', modeT: mt, t: mt }));
}
const recovery = [];
for (let i = 0; i < 5; i++) {
  const k = (i + 0.01) / 5;
  const mt = 2.4 + k * 0.6;
  recovery.push(frame({ mode: 'dizzy', modeT: mt, t: mt }));
}
const wave = sampleGesture('wave', 9);
const bow = sampleGesture('bow', 7);
const nod = sampleGesture('nod', 8);
const shake = sampleGesture('shake', 8);
const walk = [];
for (const facing of [1, -1]) {
  for (let i = 0; i < 4; i++) {
    const mt = (i + 0.1) / 9;
    walk.push(frame({ mode: 'walk', modeT: mt, t: mt, facing }));
  }
}
const run = [];
const dance = [];
for (let i = 0; i < 4; i++) {
  run.push(frame({ mode: 'run', modeT: (i + 0.1) / 13, t: (i + 0.1) / 13 }));
  dance.push(frame({ mode: 'dance', modeT: (i + 0.1) / 5, t: (i + 0.1) / 5 }));
}
const sleep = [0.05, 0.25, 0.5].map((mt) => frame({ mode: 'sleep', modeT: mt, t: mt }));
const wake = [];
for (let i = 0; i < 7; i++) {
  const mt = ((i + 0.01) / 7) * 1.8;
  wake.push(frame({ mode: 'wake', modeT: mt, t: mt, face: 'neutral' }));
}
for (let i = 0; i < 5; i++) {
  const mt = ((i + 0.01) / 5) * 0.9;
  wake.push(frame({ mode: 'wake', modeT: mt, t: mt, face: 'surprised' }));
}
const airFall = frame({ mode: 'air', thrown: true });
const poke = ['happy', 'wink', 'surprised', 'love', 'angry'].map((face) => frame({
  mode: 'idle',
  face,
  t: face === 'happy' ? 0.4 : 0,
}));
const headPat = ['love', 'shy'].map((face) => frame({ mode: 'idle', face, t: 0.4 }));

expect('lift', lift, ['lift_0', 'lift_1', 'lift_2', 'lift_3']);
expect('dizzy', dizzy.concat(recovery), ['fall', 'dizzy_0', 'dizzy_1', 'dizzy_2', 'dizzy_3', 'sit', 'shake_0', 'shake_2', 'crouch', 'neutral']);
expect('wave', wave, ['wave_0', 'wave_1', 'wave_2', 'wave_3']);
expect('walk', walk, ['walk_0', 'walk_1', 'walk_2', 'walk_3', 'walk_4', 'walk_5', 'walk_6', 'walk_7']);
expect('run', run, ['run_0', 'run_1', 'run_2', 'run_3']);
expect('dance', dance, ['dance_0', 'dance_1', 'dance_2', 'dance_3']);
expect('sleep', sleep, ['sit', 'prone', 'sleep']);
expect('wake', wake, ['sleep', 'prone', 'sit', 'stretch', 'jump', 'crouch', 'neutral']);
expect('bow', bow, ['bow_0', 'bow_1', 'bow_2', 'bow_3']);
expect('nod', nod, ['nod_0', 'nod_1', 'nod_2', 'nod_3']);
expect('shake', shake, ['shake_0', 'shake_1', 'shake_2', 'shake_3']);
expect('poke', poke, ['happy', 'talk', 'jump', 'love', 'angry']);
expect('headPat', headPat, ['love', 'shy']);
if (airFall !== 'fall') throw new Error('thrown air pose should be fall, got ' + airFall);

const swept = new Set();
const faces = Object.keys(facePose);
const modes = ['idle', 'drag', 'dizzy', 'air', 'crouch', 'land', 'walk', 'run', 'dance', 'sleep', 'wake', 'sit', 'look'];
const kinds = [null, 'spin', 'nod', 'shake', 'bow', 'wave', 'hug', 'prone', 'stretch', 'shiver', 'flap'];
const facings = [-1, 0, 1];
const mts = [0, 0.05, 0.11, 0.13, 0.2, 0.3, 0.5, 0.9, 1.2, 1.7, 2.2, 2.45, 2.7, 3.1];
for (const modeName of modes) {
  for (const face of faces) {
    for (const kind of kinds) {
      for (const facing of facings) {
        for (const mt of mts) {
          for (const talk of [0, 0.5]) {
            for (const blink of [0, 0.9]) {
              for (const thrown of [false, true]) {
                const gesture = kind ? { kind, k: mt % 1 } : null;
                swept.add(selectFrame({
                  mode: modeName, modeT: mt, t: mt, face, facing, talk, blink, thrown, gesture,
                }).pose);
              }
            }
          }
        }
      }
    }
  }
}
for (const kind of ['spin', 'nod', 'shake', 'bow', 'wave', 'hug', 'prone', 'stretch', 'shiver', 'flap']) {
  for (let step = 0; step <= 20; step++) {
    const k = step / 20;
    swept.add(selectFrame({
      mode: 'idle', modeT: 0, t: 0.4, face: 'neutral', facing: 1, talk: 0, blink: 0, gesture: { kind, k: Math.min(k, 0.99) },
    }).pose);
  }
}
for (const pose of [lift, dizzy, recovery, wave, bow, nod, shake, walk, run, dance, sleep, wake, poke, headPat, [airFall]]) {
  for (const id of pose) swept.add(id);
}

const notInAssets = [...swept].filter((pose) => !ASSETS.includes(pose)).sort();
if (missingFiles.length) throw new Error('ASSETS png missing: ' + missingFiles.join(', '));
if (faceMissing.length) throw new Error('facePose target not in ASSETS: ' + faceMissing.join(', '));
if (notInAssets.length) throw new Error('selectFrame pose missing from ASSETS: ' + notInAssets.join(', '));
const faceUnhit = [...new Set(Object.values(facePose))].filter((pose) => !swept.has(pose));
if (faceUnhit.length) throw new Error('facePose value never selected: ' + faceUnhit.join(', '));

process.stdout.write(JSON.stringify({
  assets: ASSETS,
  pngs,
  extras: pngs.filter((name) => !ASSETS.includes(name.slice(0, -4))),
  facePose,
  swept: [...swept].sort(),
  p0: {
    poke: uniq(poke),
    headPat: uniq(headPat),
    lift: uniq(lift),
    throwDizzy: uniq(dizzy.concat(recovery, [airFall])),
    wave: uniq(wave),
    walk: uniq(walk),
    run: uniq(run),
    dance: uniq(dance),
    sleep: uniq(sleep),
    wake: uniq(wake),
  },
  gestures: {
    bow: uniq(bow),
    nod: uniq(nod),
    shake: uniq(shake),
  },
}));
"""

P0_NOTES = {
    'poke': '轻点：pointerdown 后在约 400ms 内 pointerup，且没有拖过提起阈值。引擎随机表情或蹦跳；睡觉或坐下时改为戳醒。这些是表情帧，没有单独的戳帧。',
    'headPat': '摸头：未按住时 pointermove 在角色身上累计滑动，引擎在 love 与 shy 之间选择。触摸屏按下后移动超过约 6px 会变成提起，这是引擎原有规则。',
    'lift': '按住拖动。selectFrame 在 mode=drag 时循环 lift_0..lift_3。',
    'throwDizzy': '从提起状态松手进入抛出（air + thrown → fall）。重落地后 mode=dizzy：先 fall，再 dizzy_0..3，然后 sit、shake_0、shake_2、crouch、neutral。',
    'wave': '词汇 wave。selectFrame 使用 wave_0..wave_3。',
    'walk': '词汇 walk。面向右为 walk_0..walk_3，面向左为 walk_4..walk_7。',
    'run': '词汇 run。selectFrame 循环 run_0..run_3。',
    'dance': '词汇 dance。selectFrame 循环 dance_0..dance_3。',
    'sleep': '词汇 sleep。modeT 先 sit，再 prone，然后 sleep。',
    'wake': '睡觉时轻点进入 wake（引擎已有）。普通起身与受惊（face=surprised）两条 selectFrame 路径都覆盖。',
}

SEQUENCE_ORDER = ['lift', 'walk', 'dizzy', 'run', 'dance', 'bow', 'wave', 'nod', 'shake']
COPY_INSTRUCTION = 'copy contents of android-pack/ into assets/bluefin/'
WEBVIEW_ENTRY = 'https://appassets.androidplatform.net/assets/bluefin/index.html'
TARGET_APP_PATH = 'assets/bluefin/'


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def png_ihdr(data: bytes) -> tuple[int, int]:
    if data[:8] != b'\x89PNG\r\n\x1a\n' or data[12:16] != b'IHDR':
        raise SystemExit('not a PNG')
    width = int.from_bytes(data[16:20], 'big')
    height = int.from_bytes(data[20:24], 'big')
    return width, height


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8', newline='\n')


def dump_json(obj: object) -> str:
    return json.dumps(obj, ensure_ascii=False, indent=2) + '\n'


def run_node(check_path: Path, mode: str, pack: Path | None = None) -> dict:
    cmd = ['node', str(check_path), str(ROOT), mode]
    if pack is not None:
        cmd.append(str(pack))
    proc = subprocess.run(cmd, cwd=str(ROOT), capture_output=True, text=True)
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr)
        sys.stderr.write(proc.stdout)
        raise SystemExit(f'node check failed ({mode})')
    return json.loads(proc.stdout)


def find_oxipng() -> str | None:
    if os.environ.get('BLUEFIN_OXIPNG', '1') == '0':
        return None
    env = os.environ.get('OXIPNG')
    if env:
        return env
    return shutil.which('oxipng')


def check_html(html: str) -> None:
    required = [
        "import('./kit/body.js')",
        "import('./figure.js')",
        "new URL('./figure.json', import.meta.url)",
        "new URL(p, import.meta.url)",
        'touch-action:none',
        "body.pointer('down'",
        "body.pointer('move'",
        "body.pointer('up'",
        "body.pointer('cancel'",
        "body.pointer('leave'",
        'pointerdown',
        'pointermove',
        'pointerup',
        'pointercancel',
        'pointerleave',
    ]
    for needle in required:
        if needle not in html:
            raise SystemExit(f'index.html missing {needle}')
    forbidden = ['__PAYLOAD__', 'data:image', 'base64', 'file:///bluefin', 'http://', 'https://']
    for needle in forbidden:
        if needle in html:
            raise SystemExit(f'index.html must not contain {needle}')
    start = html.find('<script type="module">')
    end = html.find('</script>', start)
    if start < 0 or end < 0:
        raise SystemExit('index.html module script not found')
    script = html[start + len('<script type="module">'):end]
    module_path = Path(tempfile.gettempdir()) / 'bluefin-index-module.mjs'
    module_path.write_text(script, encoding='utf-8')
    proc = subprocess.run(['node', '--check', str(module_path)], capture_output=True, text=True)
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr)
        raise SystemExit('index.html module failed node --check')


def build(dest: Path, report: dict) -> dict:
    meta = json.loads((PACK / 'figure.json').read_text(encoding='utf-8'))
    assets: list[str] = report['assets']
    if list(meta.get('vocab') or []) == []:
        raise SystemExit('figure.json has no vocab')

    figure_js = (PACK / 'figure.js').read_bytes()
    figure_json = (PACK / 'figure.json').read_bytes()
    body_js = (KIT / 'body.js').read_bytes()
    rig_js = (KIT / 'rig.js').read_bytes()
    (dest / 'figure.js').write_bytes(figure_js)
    (dest / 'figure.json').write_bytes(figure_json)
    (dest / 'kit').mkdir(parents=True)
    (dest / 'kit' / 'body.js').write_bytes(body_js)
    (dest / 'kit' / 'rig.js').write_bytes(rig_js)

    asset_dir = dest / 'assets'
    asset_dir.mkdir()
    source_pngs = sorted((PACK / 'assets').glob('*.png'))
    source_names = [p.name for p in source_pngs]
    missing = [f'{name}.png' for name in assets if f'{name}.png' not in source_names]
    if missing:
        raise SystemExit('missing asset files: ' + ', '.join(missing))
    dims: dict[str, tuple[int, int]] = {}
    source_sizes: dict[str, int] = {}
    for src in source_pngs:
        data = src.read_bytes()
        dims[src.name] = png_ihdr(data)
        source_sizes[src.name] = len(data)
        (asset_dir / src.name).write_bytes(data)

    ox = find_oxipng()
    ox_version = None
    if ox:
        version = subprocess.run([ox, '--version'], capture_output=True, text=True, check=True)
        ox_version = version.stdout.strip() or version.stderr.strip()
        subprocess.run([ox, '-o', '4', '-q', *[str(p) for p in sorted(asset_dir.glob('*.png'))]], check=True)
        for name, (width, height) in dims.items():
            packed = (asset_dir / name).read_bytes()
            got = png_ihdr(packed)
            if got != (width, height):
                raise SystemExit(f'{name} dimensions changed from {(width, height)} to {got}')
            if len(packed) > source_sizes[name]:
                raise SystemExit(f'{name} grew during oxipng')
    else:
        for name, size in source_sizes.items():
            if (asset_dir / name).stat().st_size != size:
                raise SystemExit(f'{name} was not copied byte-for-byte')

    check_html(INDEX_HTML)
    write_text(dest / 'index.html', INDEX_HTML)
    readme = README_TEMPLATE.replace('__ID__', meta['id']).replace('__VERSION__', meta['version'])
    write_text(dest / 'README.md', readme)

    def pose_info(pose_id: str) -> dict:
        filename = f'{pose_id}.png'
        return {
            'file': f'assets/{filename}',
            'bytes': (asset_dir / filename).stat().st_size,
            'sourceBytes': source_sizes[filename],
            'width': dims[filename][0],
            'height': dims[filename][1],
        }

    groups: dict[str, list[tuple[int, str]]] = {}
    stills: list[str] = []
    for pose_id in assets:
        match = re.fullmatch(r'([A-Za-z]+)_(\d+)', pose_id)
        if match:
            groups.setdefault(match.group(1), []).append((int(match.group(2)), pose_id))
        else:
            stills.append(pose_id)
    sequence_keys = [key for key in SEQUENCE_ORDER if key in groups]
    sequence_keys += sorted(key for key in groups if key not in sequence_keys)
    sequences = {}
    for key in sequence_keys:
        ids = [pose_id for _, pose_id in sorted(groups[key])]
        sequences[key] = {
            'ids': ids,
            'files': [f'assets/{pose_id}.png' for pose_id in ids],
            'bytes': sum((asset_dir / f'{pose_id}.png').stat().st_size for pose_id in ids),
        }

    def noted(group: dict[str, list[str]], notes: dict[str, str]) -> dict:
        out = {}
        for key, ids in group.items():
            item = {'poses': ids, 'files': [f'assets/{pose_id}.png' for pose_id in ids]}
            if key in notes:
                item['note'] = notes[key]
            out[key] = item
        return out

    extras = []
    for name in report['extras']:
        extras.append({
            'file': f'assets/{name}',
            'bytes': (asset_dir / name).stat().st_size,
            'sourceBytes': source_sizes[name],
            'width': dims[name][0],
            'height': dims[name][1],
            'note': '不在 figure.js ASSETS 中。thumb.png 是 figure.json 的缩略图。',
        })

    frames_index = {
        'id': meta['id'],
        'version': meta['version'],
        'manifest': meta['manifest'],
        'api': meta['api'],
        'name': meta['name'],
        'poseCount': len(assets),
        'assets': assets,
        'poses': {pose_id: pose_info(pose_id) for pose_id in assets},
        'sequences': sequences,
        'stills': stills,
        'extras': extras,
        'vocab': [
            {'id': item['id'], 'kind': item['kind'], 'names': item['names'], **({'lasting': True} if item.get('lasting') else {})}
            for item in meta['vocab']
        ],
        'p0': noted(report['p0'], P0_NOTES),
        'gestures': noted(report['gestures'], {}),
        'sweptPoseCount': len(report['swept']),
    }
    for pose_id in report['swept']:
        if pose_id not in frames_index['poses']:
            raise SystemExit(f'swept pose {pose_id} has no packed PNG')
    write_text(dest / 'frames-index.json', dump_json(frames_index))

    roles = {
        'index.html': 'entry',
        'figure.js': 'module',
        'figure.json': 'metadata',
        'kit/body.js': 'module',
        'kit/rig.js': 'module',
        'frames-index.json': 'index',
        'README.md': 'doc',
    }
    ordered = ['index.html', 'figure.js', 'figure.json', 'kit/body.js', 'kit/rig.js']
    for name in source_names:
        ordered.append(f'assets/{name}')
        roles[f'assets/{name}'] = 'thumb' if name == 'thumb.png' else 'frame'
    ordered += ['frames-index.json', 'README.md']

    other_files = []
    for rel in ordered:
        other_files.append({'path': rel, 'bytes': (dest / rel).stat().st_size, 'role': roles[rel]})
    other_sum = sum(item['bytes'] for item in other_files)

    def render_manifest(manifest_bytes: int, pad: str) -> str:
        files = other_files + [{'path': 'pack-manifest.json', 'bytes': manifest_bytes, 'role': 'manifest'}]
        obj = {
            'format': 'bluefin-android-webview-pack',
            'formatVersion': 1,
            'id': meta['id'],
            'version': meta['version'],
            'name': meta['name'],
            'entry': 'index.html',
            'pathsRelativeTo': 'index.html',
            'relativePaths': 'All resource URLs used by index.html are relative to index.html. kit/body.js imports ./rig.js relative to itself; that file is kit/rig.js beside index.html.',
            'pathNote': 'index.html 引用的模块、figure.json 和图片都以 index.html 所在目录为基准（./figure.js、./kit/body.js、./figure.json、assets/<姿势>.png）。kit/body.js 再按 ES 模块规则相对自身导入 ./rig.js，对应文件是 kit/rig.js。不要改成绝对 URL 或 base64。',
            'targetAppPath': TARGET_APP_PATH,
            'webviewEntry': WEBVIEW_ENTRY,
            'copyInstruction': COPY_INSTRUCTION,
            'copy': {
                'from': 'android-pack/',
                'to': 'android/app/src/main/assets/bluefin/',
                'instruction': COPY_INSTRUCTION,
            },
            'png': {
                'mode': 'lossless-oxipng' if ox else 'byte-copy',
                'tool': ox_version,
                'args': ['-o', '4'] if ox else [],
                'alphaRewrite': False,
                'rescale': False,
                'source': 'bluefin-native/assets',
            },
            'byteCopies': {
                'figure.js': sha256(figure_js),
                'figure.json': sha256(figure_json),
                'kit/body.js': sha256(body_js),
                'kit/rig.js': sha256(rig_js),
            },
            'poseCount': len(assets),
            'totalBytes': other_sum + manifest_bytes,
            'fileCount': len(files),
            'requiredFiles': [item['path'] for item in files],
            'files': files,
        }
        if pad:
            obj['pad'] = pad
        return dump_json(obj)

    manifest_bytes = 0
    pad = ''
    text = ''
    stable = False
    for attempt in range(40):
        text = render_manifest(manifest_bytes, pad)
        size = len(text.encode('utf-8'))
        if size == manifest_bytes:
            stable = True
            break
        if attempt > 12:
            pad += ' '
        manifest_bytes = size
    if not stable:
        raise SystemExit('pack-manifest.json size did not converge')
    write_text(dest / 'pack-manifest.json', text)
    manifest = json.loads(text)
    verify_disk(dest, manifest, assets, report)
    return manifest


def verify_disk(dest: Path, manifest: dict, assets: list[str], report: dict) -> None:
    disk_files = sorted(p.relative_to(dest).as_posix() for p in dest.rglob('*') if p.is_file())
    listed = [item['path'] for item in manifest['files']]
    if set(disk_files) != set(listed):
        extra = sorted(set(disk_files) - set(listed))
        missing = sorted(set(listed) - set(disk_files))
        raise SystemExit(f'manifest file set mismatch extra={extra} missing={missing}')
    if listed != manifest['requiredFiles']:
        raise SystemExit('requiredFiles drifted from files')
    total = 0
    for item in manifest['files']:
        size = (dest / item['path']).stat().st_size
        if size != item['bytes']:
            raise SystemExit(f"size mismatch {item['path']}: manifest {item['bytes']} disk {size}")
        total += size
    if total != manifest['totalBytes'] or manifest['fileCount'] != len(listed):
        raise SystemExit('totalBytes or fileCount mismatch')
    if manifest['entry'] != 'index.html' or manifest['pathsRelativeTo'] != 'index.html':
        raise SystemExit('entry or path root mismatch')
    if manifest['targetAppPath'] != TARGET_APP_PATH or manifest['copyInstruction'] != COPY_INSTRUCTION:
        raise SystemExit('copy instruction mismatch')
    if manifest['webviewEntry'] != WEBVIEW_ENTRY:
        raise SystemExit('webview entry mismatch')
    for rel in ('figure.js', 'figure.json', 'kit/body.js', 'kit/rig.js'):
        digest = sha256((dest / rel).read_bytes())
        if digest != manifest['byteCopies'][rel]:
            raise SystemExit(f'{rel} is not a byte copy')
    for pose_id in assets:
        if not (dest / 'assets' / f'{pose_id}.png').is_file():
            raise SystemExit(f'missing packed pose {pose_id}')
    for group in report['p0'].values():
        for pose_id in group:
            if pose_id not in assets:
                raise SystemExit(f'P0 pose {pose_id} not in ASSETS')
    html = (dest / 'index.html').read_text(encoding='utf-8')
    for bad in ('*.jpg', '.gif'):
        pass
    if '.jpg' in html or '.gif' in html:
        raise SystemExit('index.html references a preview image')
    stray = [p.name for p in dest.rglob('*') if p.suffix.lower() in {'.jpg', '.jpeg', '.gif', '.webp'}]
    if stray:
        raise SystemExit('pack contains preview images: ' + ', '.join(stray))
    if '__PAYLOAD__' in html or 'base64' in html:
        raise SystemExit('index.html is not a split pack')


def main() -> None:
    check_path = Path(tempfile.gettempdir()) / 'bluefin-android-check.mjs'
    check_path.write_text(CHECK_JS, encoding='utf-8')
    report = run_node(check_path, 'source')
    tmp = ROOT / '.android-pack.tmp'
    if tmp.exists():
        shutil.rmtree(tmp)
    tmp.mkdir()
    try:
        manifest = build(tmp, report)
        run_node(check_path, 'pack', tmp)
        if OUT.exists():
            shutil.rmtree(OUT)
        tmp.rename(OUT)
    except Exception:
        if tmp.exists():
            shutil.rmtree(tmp, ignore_errors=True)
        raise
    png_bytes = sum(item['bytes'] for item in manifest['files'] if item['path'].endswith('.png'))
    print(f"android-pack: {manifest['fileCount']} files, {manifest['totalBytes']} bytes ({png_bytes} in png)")
    print(f"poses: {manifest['poseCount']}  id: {manifest['id']}  version: {manifest['version']}")
    print(f"png: {manifest['png']['mode']} {manifest['png'].get('tool') or ''}")
    print(f"entry: {manifest['entry']}  ->  {manifest['webviewEntry']}")


if __name__ == '__main__':
    main()
