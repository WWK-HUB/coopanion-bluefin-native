# 蓝鳍 Android 可玩预览

竖屏、离线、单 Activity 的 WebView 壳。安装包走侧载，不经过应用商店。默认打开拆分资源（Route B）：

`android/app/src/main/assets/bluefin/index.html`

页面通过 `WebViewAssetLoader` 映射到：

`https://appassets.androidplatform.net/assets/bluefin/index.html`

这样同目录下的 ES module（`figure.js`、`kit/body.js`、`kit/rig.js`）可以正常 `import`。

## 环境

- JDK 17 或更高（本机用 JDK 21 验证过构建）
- Android SDK Platform 35、Build-Tools 35.0.0
- 设置 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`），或在 `android/local.properties` 写入：

```properties
sdk.dir=/你的/Android/Sdk
```

`local.properties` 不要提交。

## 构建

在仓库根目录：

```bash
cd android
chmod +x ./gradlew
./gradlew assembleDebug -PrequireRouteB=true
```

`-PrequireRouteB=true` 会在缺少 `assets/bluefin/index.html` 时直接失败，避免打出空壳包。

调试 APK：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

签名是本机 Android debug 证书，只适合侧载。换电脑重新打包后如果覆盖安装失败，先卸载再装。

如果仓库根目录有 `android-pack/`，`assembleDebug` 会在打包前把它拷进 `app/src/main/assets/bluefin/`，并保持相对路径。下面三种摆放都可以识别：

```text
android-pack/index.html
android-pack/bluefin/index.html
android-pack/assets/bluefin/index.html
```

## 安装（允许未知来源）

把 `app-debug.apk` 拷到手机后安装。系统会要求允许「安装未知应用」。

1. 用数据线、网盘或聊天文件把 APK 放到手机。
2. 打开设置，允许来自当前安装入口（文件管理器、浏览器或聊天应用）的未知应用：
   - 原生 / Pixel：设置 → 应用 → 特殊应用权限 → 安装未知应用 → 选中正在用来打开 APK 的应用 → 允许
   - 小米：设置 → 隐私保护 → 特殊权限 → 安装未知应用
   - 华为：设置 → 安全 → 更多安全设置 → 外部来源应用下载
   - 若点开 APK 时系统弹出拦截，按提示进入该应用的「允许来自此来源」并打开
3. 回到文件管理器，点开 APK，确认应用名为「蓝鳍」，继续安装。
4. 也可以在电脑上执行：

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

打开「蓝鳍」。首次会看到三步提示（戳、摸头/提起、抛出）。提示可跳过，状态记在本机。完全离线可用，应用不申请网络权限。竖屏锁定。

卸载：在系统应用列表中卸载「蓝鳍」。

## 预览里可以做的事

- 轻点角色：戳。睡觉时轻点可以戳醒。
- 在角色身上小范围来回滑动：摸头。引擎只在「指针移动、但还没形成按下拖拽」时摸头；手指一下就按下，所以外壳会把原地来回滑动交给这条路径。滑出约 28 像素则变成提起。
- 按住并向一侧拖动：提起。
- 提起后快速甩动再松手：抛出，重重落地会头晕。
- 右下角「动作」：挥手、行走、跑步、跳舞、睡觉。抽屉调用页面已有的动作接口（`bluefin.do`、带 `data-word` 的按钮，或 `qa.body.do`），不改互动引擎。
- 右上角「设置」：音效、重置引导与角色、关于。

音效开关会静音页面里的 `<audio>` / `<video>`，并调用页面上的 `setSound`（如果有）。页面若在宿主回调里调用 `BluefinNative.play(name, kind)`，且开关为开，外壳会播放短提示音。离线预览模板里的 `sound(){}` 本身是空的，这种情况下开关只记住偏好并静音 WebView 媒体。

返回键：页面没有历史时退出。

## Route B 目录

交付包使用下面这棵树（相对路径保持不变）：

```text
android/app/src/main/assets/bluefin/
├── index.html
├── figure.js
├── figure.json
├── kit/
│   ├── body.js
│   └── rig.js
└── assets/
    └── *.png
```

`kit/body.js` 通过 `export { createRig } from './rig.js'` 引用同目录的 `rig.js`。`figure.js` 的 `createBluefin` 用调用方传入的 `loadImage` / `asset` 读取 `assets/<帧>.png`。

入口常量在 `app/src/main/java/com/wwkhub/bluefin/PreviewEntry.kt` 的 `ENTRY_ASSET`。保持 `null` 时，包内有 `bluefin/index.html` 就用 Route B。

## 本机自测整页预览（Route A）

只在本机临时查看 Releases 里的单文件预览时使用，不作为对外安装包。

```bash
mkdir -p android/app/src/main/assets/www
curl -L -o android/app/src/main/assets/www/bluefin-preview.html \
  https://github.com/WWK-HUB/coopanion-bluefin-native/releases/latest/download/bluefin-preview.html
```

该 html 已在 `.gitignore` 中，约 15MB，不要提交。没有 Route B 时，应用会退到这个文件，并在画面上标出「本地自测」。要强制走某一条，把 `ENTRY_ASSET` 改成 `ROUTE_A` 或 `ROUTE_B`。

自测构建可以去掉 `-PrequireRouteB=true`。

## 说明

- 包名：`com.wwkhub.bluefin`。应用名：蓝鳍。
- 单 Activity，Kotlin，没有 Capacitor / Flutter。
- 不含悬浮窗、AI 对话、多角色。
- 启动图标是简单占位图。
- 调试包打开了 WebView 远程调试，便于用电脑 Chrome 检查 `chrome://inspect`。
