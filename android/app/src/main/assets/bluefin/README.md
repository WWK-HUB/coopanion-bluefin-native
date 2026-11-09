# 蓝鳍 Android WebView 资源包

这是蓝鳍的**路由 B**资源包：`index.html`、互动引擎和透明 PNG 分开存放，给 App 里的 WebView 用。它不是把全部帧嵌成 base64 的 `bluefin-preview.html`。

- 形象包 ID：`bluefin-native`
- 版本：`1.0.0`（与 `figure.json` 一致）
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
