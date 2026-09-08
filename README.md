# 本地电子书阅读器（Hybrid · MVP）

一个**零权限、全程离线**的 Android 本地电子书阅读器。原生层只是一个薄壳：用 `WebView` 承载全部 UI（HTML/CSS/JS），通过 `JSBridge` 让 Web 层调用原生能力（书架、导入、进度）。没有任何 `uses-permission`，导入走系统 `SAF`，文件读写只落在 App 专属目录。

## 架构

```
┌─────────────────────────────────────────┐
│  Web 层 (assets/web/*)                   │  UI 全在这里
│  index.html / reader.html / app.js / ... │  书架 · 分页 · 翻页 · 字号 · 夜间 · 进度
└───────────────┬─────────────────────────┘
                │ JSBridge.invoke(method, params, cbId)
┌───────────────▼─────────────────────────┐
│  原生壳 MainActivity.java (WebView)      │  薄壳，只做三件事：
│  · shouldInterceptRequest 虚拟域名路由    │   ① 路由 assets / books
│  · JSBridge：books.* / progress.*        │   ② 暴露 JSBridge
│  · SAF 导入（GBK/UTF-8 自动探测）        │   ③ SAF 导入
└───────────────────────────────────────────┘
```

- **虚拟域名**：所有页面请求 `https://appassets.local/...`，在 `shouldInterceptRequest` 里被截住，没有任何真实 HTTP 服务器。
- **零权限**：`AndroidManifest.xml` 不含任何 `<uses-permission>`。导入文件用 `Intent.ACTION_OPEN_DOCUMENT`（SAF），读取的内容不经过 Web 层，直接由原生落盘为 UTF-8。
- **编码探测**：导入 txt 时先严格试 UTF-8，失败回退 GB18030（GBK 超集），自动去 BOM，统一转 UTF-8 存储，避免中文乱码。

## 目录结构

```
reader/
├── AndroidManifest.xml          # 零权限清单（package + uses-sdk）
├── build_apk.sh                # 七步手工构建脚本（CI 与本机通用）
├── java/com/example/reader/
│   └── MainActivity.java       # 原生薄壳
├── assets/web/
│   ├── index.html              # 书架
│   ├── reader.html             # 阅读页
│   ├── app.js                  # 全部交互逻辑
│   ├── style.css
│   └── seed/                   # 首启播种的公版种子书（UTF-8 无 BOM）
│       ├── daodejing.txt       # 道德经（全文）
│       ├── lunyu.txt           # 论语（核心篇章）
│       └── zhuangzi.txt        # 庄子（逍遥游/齐物论/养生主…）
└── .github/workflows/build.yml # GitHub Actions 云端构建
```

## 功能（MVP）

- **书架**：列出 App 专属目录下的 `.txt`，含标题、大小、已读进度；支持删除、空状态提示。
- **阅读**：CSS 多列 + `scrollLeft` 实现翻页（非整页滚动），字号 3 档、夜间模式。
- **进度**：阅读百分比持久化（原生 `SharedPreferences`），重开自动回到上次位置。
- **导入**：系统文件选择器选 `.txt`，原生层做编码探测与落盘，Web 层只收到结果。
- **种子书**：首启自动播种 3 本公版书，离线即可体验。

## 构建

### 方式一：GitHub Actions（推荐，无需本机环境）

推送即触发：在云端自动装 JDK 17 + Android SDK（platform-34 / build-tools;34.0.0），跑 `build_apk.sh`，产物 `app-debug.apk` 作为 artifact 提供下载。

```bash
git push origin main      # 自动构建，到 Actions 页下载 app-debug.apk
```

### 方式二：本机手工构建

需 JDK 17 与 Android SDK（platform-34 / build-tools;34.0.0 已安装）：

```bash
export ANDROID_SDK_ROOT=/path/to/Android/sdk
bash build_apk.sh        # 产出 build/app-debug.apk
```

七步：`aapt2 link` → `javac` → `d8` → 拼装(classes.dex+assets) → `zipalign` → `keytool` 自签 → `apksigner` 签名。

## 安装到手机

```bash
adb install build/app-debug.apk
# 或把 app-debug.apk 传到手机，用文件管理器点击安装
```

## 许可

- 应用代码：MIT。
- 种子书文本（道德经 / 论语 / 庄子）为公有领域公版文献。
