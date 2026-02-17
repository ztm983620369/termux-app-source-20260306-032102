# Termux-App 项目导航（全量分析版）

> 生成时间：2026-02-07  
> 目标：后续在新会话中，直接通过本 `md` 快速定位到**文件级别**。

---

## 1. 使用方式（给未来会话）

1. 先看「模块总表」判断问题属于哪个模块。  
2. 再看「功能 -> 文件定位」直接跳转到具体文件。  
3. 若是三方大模块（`sora` / `fossify`），先看「大模块扫描结果」里的入口与包分布。  
4. 最后用「定位命令模板」做二次精准搜索。

---

## 2. 根目录地图（按职责）

### 2.1 核心业务模块
- `app/`：Termux 主应用（Activity/Service/Manifest/资源）。
- `termux-shared/`：跨模块共享能力（常量、shell、配置、通知、插件协议等）。
- `terminal-emulator/`：终端内核（PTY、ANSI、Buffer、Session）。
- `terminal-view/`：终端 View 层（渲染、手势、文本选择）。
- `terminal-tabs/`：终端 Tab 条 UI 组件。
- `file-bridge/`：文件打开桥接协议（`EDIT_FILE` + 内存桥）。
- `ui-shell/`：Compose UI 外壳（文件页、面板、IDE 项目模板、导航桥）。

### 2.2 集成三方模块（已纳入 Gradle 多模块）
- `sora-editor-0.24.3/`：代码编辑器生态（editor + language + lsp + native）。
- `File-Manager-1.5.0/File-Manager-1.5.0/app/`：Fossify File Manager。
- `fossify-commons/`：File Manager 依赖的通用库。

### 2.3 构建/发布/自动化
- `build.gradle`：根构建脚本。
- `settings.gradle`：多模块注册与目录映射。
- `gradle.properties`：`minSdk/targetSdk/compileSdk` 与 Gradle 参数。
- `.github/workflows/`：CI（构建/测试/校验）。
- `fastlane/`：商店元数据。

### 2.4 当前仓库中的“环境与缓存目录”（阅读代码可忽略）
- `.android/`、`.gradle/`、`.gradle-user-home/`、`.kotlin/`、`.java-user-home/`、`build/` 等。

---

## 3. 模块总表（全量扫描）

| Gradle 模块 | 实际目录 | src 文件数 | 代码文件数 |
|---|---|---:|---:|
| `:app` | `app` | 95 | 37 |
| `:termux-shared` | `termux-shared` | 148 | 122 |
| `:terminal-emulator` | `terminal-emulator` | 36 | 34 |
| `:terminal-view` | `terminal-view` | 12 | 8 |
| `:terminal-tabs` | `terminal-tabs` | 3 | 1 |
| `:file-bridge` | `file-bridge` | 3 | 2 |
| `:ui-shell` | `ui-shell` | 25 | 23 |
| `:file-manager` | `File-Manager-1.5.0/File-Manager-1.5.0/app` | 174 | 39 |
| `:fossify-commons` | `fossify-commons` | 684 | 252 |
| `:sora-editor` | `sora-editor-0.24.3/editor` | 311 | 282 |
| `:sora-demo` | `sora-editor-0.24.3/app` | 100 | 20 |
| `:sora-language-java` | `sora-editor-0.24.3/language-java` | 9 | 8 |
| `:sora-language-textmate` | `sora-editor-0.24.3/language-textmate` | 146 | 145 |
| `:sora-language-monarch` | `sora-editor-0.24.3/language-monarch` | 51 | 48 |
| `:sora-language-treesitter` | `sora-editor-0.24.3/language-treesitter` | 27 | 26 |
| `:sora-editor-lsp` | `sora-editor-0.24.3/editor-lsp` | 76 | 67 |
| `:sora-oniguruma-native` | `sora-editor-0.24.3/oniguruma-native` | 185 | 98 |

---

## 4. 依赖关系（核心）

- `:app` 依赖：`:terminal-view`、`:termux-shared`、`:terminal-tabs`、`:ui-shell`、`:sora-demo`、`:file-bridge`、`:file-manager`、`:fossify-commons`  
  文件：`app/build.gradle`
- `:terminal-view` 依赖：`:terminal-emulator`  
  文件：`terminal-view/build.gradle`
- `:termux-shared` 依赖：`:terminal-view`  
  文件：`termux-shared/build.gradle`
- `:ui-shell` 依赖：`:termux-shared`、`:terminal-view`、`:file-bridge`  
  文件：`ui-shell/build.gradle`
- `:file-manager` 依赖：`:fossify-commons`、`:file-bridge`  
  文件：`File-Manager-1.5.0/File-Manager-1.5.0/app/build.gradle.kts`
- `:sora-demo` 依赖：`:sora-editor`、`:sora-language-*`、`:sora-editor-lsp`、`:sora-oniguruma-native`，并额外依赖 `:file-bridge`、`:termux-shared`  
  文件：`sora-editor-0.24.3/app/build.gradle.kts`

---

## 5. 启动链路（从点击图标到终端）

1. `Application` 启动：`app/src/main/java/com/termux/app/TermuxApplication.java`
   - 初始化 crash/logger/theme/shell env。
   - 检查 Termux files 目录、初始化 `TermuxAmSocketServer`。

2. 主页 Activity：`app/src/main/java/com/termux/app/TermuxActivity.java`
   - 主入口 UI（终端 + 编辑器页 + 文件页 + 底部导航）。
   - 绑定 `TermuxService`。
   - 接入 `EditorController`（Sora）与 `FileManagerController`（Fossify）。

3. 前台服务：`app/src/main/java/com/termux/app/TermuxService.java`
   - 维护 Termux sessions。
   - 处理执行命令 Intent（含插件 API 流程）。
   - 维护常驻通知与生命周期。

4. Session 客户端（Activity 侧）：`app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java`
   - 新建/切换/重命名 session。
   - 会话和 UI 同步、铃声、光标、字体色彩。
   - 内置 `proot` 默认会话逻辑。

5. 终端引擎链路
   - Session：`terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java`
   - Emulator：`terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java`
   - View：`terminal-view/src/main/java/com/termux/view/TerminalView.java`

### 5.1 关键方法锚点（精确到行号）
- 应用初始化：`app/src/main/java/com/termux/app/TermuxApplication.java:24`
- 主页面创建：`app/src/main/java/com/termux/app/TermuxActivity.java:245`
- 底部导航初始化：`app/src/main/java/com/termux/app/TermuxActivity.java:676`
- Tab 切换核心：`app/src/main/java/com/termux/app/TermuxActivity.java:738`
- 文件页跳转：`app/src/main/java/com/termux/app/TermuxActivity.java:582`
- 编辑器意图处理：`app/src/main/java/com/termux/app/TermuxActivity.java:865`
- 编辑器初始化：`app/src/main/java/com/termux/app/TermuxActivity.java:820`
- 文件管理初始化：`app/src/main/java/com/termux/app/TermuxActivity.java:884`
- 文件打开桥回调：`app/src/main/java/com/termux/app/TermuxActivity.java:931`
- Termux 服务启动：`app/src/main/java/com/termux/app/TermuxService.java:127`
- Session 创建：`app/src/main/java/com/termux/app/TermuxService.java:575`
- RUN_COMMAND 入口：`app/src/main/java/com/termux/app/RunCommandService.java:58`
- Bootstrap 安装：`app/src/main/java/com/termux/app/TermuxInstaller.java:69`
- 文件接收入口：`app/src/main/java/com/termux/app/api/file/FileReceiverActivity.java:63`
- 终端会话新增：`app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java:366`
- 终端 Session 初始化：`terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java:78`
- 终端 View 绑定 Session：`terminal-view/src/main/java/com/termux/view/TerminalView.java:290`
- 文件桥协议：`file-bridge/src/main/java/com/termux/bridge/FileEditorContract.kt:15`
- 文件桥分发：`file-bridge/src/main/java/com/termux/bridge/FileOpenBridge.kt:11`
- Compose 外壳入口：`ui-shell/src/main/java/com/termux/ui/TermuxUiActivity.kt:7`
- Compose 文件页入口：`ui-shell/src/main/java/com/termux/ui/files/FilesBrowserPage.kt:108`
- UI 导航桥：`ui-shell/src/main/java/com/termux/ui/nav/UiShellNavBridge.kt:6`
- FileManager 容器控制器：`File-Manager-1.5.0/File-Manager-1.5.0/app/src/main/kotlin/org/fossify/filemanager/controllers/FileManagerController.kt:80`
- Sora 编辑器控制器：`sora-editor-0.24.3/app/src/main/java/io/github/rosemoe/sora/app/EditorController.kt:112`

---

## 6. Android 组件定位（Manifest）

主清单：`app/src/main/AndroidManifest.xml`

### 6.1 Activity
- `com.termux.ui.TermuxUiActivity`（Compose UI 外壳入口）
- `com.termux.app.TermuxActivity`（主启动 Activity）
- `com.termux.app.activities.HelpActivity`
- `com.termux.app.activities.SettingsActivity`
- `com.termux.shared.activities.ReportActivity`
- `com.termux.app.api.file.FileReceiverActivity`

### 6.2 Activity Alias
- `com.termux.HomeActivity`
- `com.termux.app.api.file.FileShareReceiverActivity`
- `com.termux.app.api.file.FileViewReceiverActivity`

### 6.3 Service
- `com.termux.app.TermuxService`
- `com.termux.app.RunCommandService`

### 6.4 Receiver
- `com.termux.app.TermuxOpenReceiver`
- `com.termux.app.event.SystemEventReceiver`
- `com.termux.shared.activities.ReportActivity$ReportActivityBroadcastReceiver`

### 6.5 Provider
- `com.termux.filepicker.TermuxDocumentsProvider`
- `com.termux.app.TermuxOpenReceiver$ContentProvider`

---

## 7. 功能 -> 文件定位（高频导航）

### 7.1 底部导航（终端/文件/编辑器）
- `app/src/main/java/com/termux/app/TermuxActivity.java`
  - `setupBottomNavigation(...)`
  - `setBottomNavTab(...)`
  - `switchBottomTabByName(...)`
- 布局：`app/src/main/res/layout/activity_termux.xml`

### 7.2 编辑器（Sora）接入
- `app/src/main/java/com/termux/app/TermuxActivity.java`
  - `ensureEditorInitialized()`
  - `handleEditorIntentIfNeeded(...)`
- 协议：`file-bridge/src/main/java/com/termux/bridge/FileEditorContract.kt`
- 广播/桥：`file-bridge/src/main/java/com/termux/bridge/FileOpenBridge.kt`

### 7.3 文件管理（Fossify）接入
- `app/src/main/java/com/termux/app/TermuxActivity.java`
  - `ensureFileManagerInitialized()`
  - `openFilesAtPath(...)`
- 控制器：`File-Manager-1.5.0/File-Manager-1.5.0/app/src/main/kotlin/org/fossify/filemanager/controllers/FileManagerController.kt`
- Host 接口：`File-Manager-1.5.0/File-Manager-1.5.0/app/src/main/kotlin/org/fossify/filemanager/interfaces/FileManagerHost.kt`

### 7.4 外部调用命令（RUN_COMMAND API）
- `app/src/main/java/com/termux/app/RunCommandService.java`
- `app/src/main/java/com/termux/app/TermuxService.java`
- 常量定义：`termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java`

### 7.5 文件分享/打开/接收
- `app/src/main/java/com/termux/app/TermuxOpenReceiver.java`
- `app/src/main/java/com/termux/app/api/file/FileReceiverActivity.java`
- `app/src/main/java/com/termux/filepicker/TermuxDocumentsProvider.java`

### 7.6 Bootstrap/安装/存储链接
- `app/src/main/java/com/termux/app/TermuxInstaller.java`
- `app/src/main/cpp/termux-bootstrap.c`
- `app/src/main/cpp/termux-bootstrap-zip.S`
- `app/src/main/cpp/Android.mk`

### 7.7 应用设置页面
- Activity：`app/src/main/java/com/termux/app/activities/SettingsActivity.java`
- Root Preferences：`app/src/main/res/xml/root_preferences.xml`
- 各偏好页：`app/src/main/res/xml/termux_*.xml`
- 对应 Fragment：`app/src/main/java/com/termux/app/fragments/settings/**`

### 7.8 终端 Tab 条
- `terminal-tabs/src/main/java/com/termux/terminaltabs/TerminalTabsBar.java`
- `terminal-tabs/src/main/res/layout/view_terminal_tabs_bar.xml`

### 7.9 Compose 文件页（ui-shell）
- `ui-shell/src/main/java/com/termux/ui/files/FilesBrowserPage.kt`
- `ui-shell/src/main/java/com/termux/ui/files/FileActionsDialogHost.kt`
- 导航桥：`ui-shell/src/main/java/com/termux/ui/nav/UiShellNavBridge.kt`

### 7.10 PRoot / 环境面板
- `ui-shell/src/main/java/com/termux/ui/panel/EnvironmentManager.kt`
- `ui-shell/src/main/java/com/termux/ui/panel/TermuxCommandRunner.kt`
- `ui-shell/src/main/java/com/termux/ui/panel/SystemDashboardActivity.kt`
- Session 默认 proot 逻辑：`app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java`

- 安装逻辑：`app/src/main/java/com/termux/app/TermuxInstaller.java`

### 7.11 底部面板小键盘（Extra Keys）核心实现
- 布局入口：
  - `app/src/main/res/layout/activity_termux.xml:130`（`terminal_toolbar_view_pager`，位于 `bottom_navigation` 上方）
  - `app/src/main/res/layout/view_terminal_toolbar_extra_keys.xml:2`（`ExtraKeysView` 实体）
- Activity 接入与显示：
  - `app/src/main/java/com/termux/app/TermuxActivity.java:1227`（`setTerminalToolbarView(...)` 初始化小键盘与 ViewPager）
  - `app/src/main/java/com/termux/app/TermuxActivity.java:1247`（`setTerminalToolbarHeight()` 按矩阵行数计算高度）
  - `app/src/main/java/com/termux/app/TermuxActivity.java:1258`（`toggleTerminalToolbar()` 显示/隐藏小键盘栏）
  - `app/src/main/java/com/termux/app/TermuxActivity.java:1302`（`setToggleKeyboardView()` 左侧抽屉“键盘”按钮点击/长按）
- ViewPager 页面装配：
  - `app/src/main/java/com/termux/app/terminal/io/TerminalToolbarViewPager.java:41`（`instantiateItem(...)` 在 position=0 加载小键盘页）
  - `app/src/main/java/com/termux/app/terminal/io/TerminalToolbarViewPager.java:47`（绑定 `TermuxTerminalExtraKeys` 到 `ExtraKeysView`）
- 按键定义与事件分发：
  - `app/src/main/java/com/termux/app/terminal/io/TermuxTerminalExtraKeys.java:49`（`setExtraKeys()` 读取 `extra-keys` / `extra-keys-style`）
  - `app/src/main/java/com/termux/app/terminal/io/TermuxTerminalExtraKeys.java:86`（`onTerminalExtraKeyButtonClick(...)` 处理 `KEYBOARD`/`DRAWER`/`PASTE`/`SCROLL`）
  - `termux-shared/src/main/java/com/termux/shared/termux/terminal/io/TerminalExtraKeys.java:28`（宏键拆分与派发）
  - `termux-shared/src/main/java/com/termux/shared/termux/terminal/io/TerminalExtraKeys.java:54`（按键最终写入 `TerminalView`/`TerminalSession`）
  - `termux-shared/src/main/java/com/termux/shared/termux/extrakeys/ExtraKeysView.java:386`（`reload(...)` 动态构建按键网格）
  - `termux-shared/src/main/java/com/termux/shared/termux/extrakeys/ExtraKeysView.java:643`（`readSpecialButton(...)` 读取 `CTRL/ALT/SHIFT/FN` 状态）
- 软键盘切换执行点：
  - `app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java:548`（`onToggleSoftKeyboardRequest()`）
- 配置键与默认值：
  - `termux-shared/src/main/java/com/termux/shared/termux/settings/properties/TermuxPropertyConstants.java:327`（`KEY_EXTRA_KEYS`）
  - `termux-shared/src/main/java/com/termux/shared/termux/settings/properties/TermuxPropertyConstants.java:332`（`KEY_EXTRA_KEYS_STYLE`）

---

## 8. 核心模块文件级清单

> 注：这里列“核心开发高频模块”的关键文件清单；大型三方模块在下一节给出全量包级扫描与入口。

### 8.1 `app`（Java 源码全清单）
- `app/src/main/java/com/termux/app/activities/HelpActivity.java`
- `app/src/main/java/com/termux/app/activities/SettingsActivity.java`
- `app/src/main/java/com/termux/app/api/file/FileReceiverActivity.java`
- `app/src/main/java/com/termux/app/event/SystemEventReceiver.java`
- `app/src/main/java/com/termux/app/fragments/settings/termux/DebuggingPreferencesFragment.java`
- `app/src/main/java/com/termux/app/fragments/settings/termux/TerminalIOPreferencesFragment.java`
- `app/src/main/java/com/termux/app/fragments/settings/termux/TerminalViewPreferencesFragment.java`
- `app/src/main/java/com/termux/app/fragments/settings/termux_api/DebuggingPreferencesFragment.java`
- `app/src/main/java/com/termux/app/fragments/settings/termux_float/DebuggingPreferencesFragment.java`
- `app/src/main/java/com/termux/app/fragments/settings/termux_tasker/DebuggingPreferencesFragment.java`
- `app/src/main/java/com/termux/app/fragments/settings/termux_widget/DebuggingPreferencesFragment.java`
- `app/src/main/java/com/termux/app/fragments/settings/TermuxAPIPreferencesFragment.java`
- `app/src/main/java/com/termux/app/fragments/settings/TermuxFloatPreferencesFragment.java`
- `app/src/main/java/com/termux/app/fragments/settings/TermuxPreferencesFragment.java`
- `app/src/main/java/com/termux/app/fragments/settings/TermuxTaskerPreferencesFragment.java`
- `app/src/main/java/com/termux/app/fragments/settings/TermuxWidgetPreferencesFragment.java`
- `app/src/main/java/com/termux/app/models/UserAction.java`
- `app/src/main/java/com/termux/app/RunCommandService.java`
- `app/src/main/java/com/termux/app/terminal/io/FullScreenWorkAround.java`
- `app/src/main/java/com/termux/app/terminal/io/KeyboardShortcut.java`
- `app/src/main/java/com/termux/app/terminal/io/TerminalToolbarViewPager.java`
- `app/src/main/java/com/termux/app/terminal/io/TermuxTerminalExtraKeys.java`
- `app/src/main/java/com/termux/app/terminal/TermuxActivityRootView.java`
- `app/src/main/java/com/termux/app/terminal/TermuxSessionsListViewController.java`
- `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java`
- `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionServiceClient.java`
- `app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java`
- `app/src/main/java/com/termux/app/TermuxActivity.java`
- `app/src/main/java/com/termux/app/TermuxActivityUiReceiver.java`
- `app/src/main/java/com/termux/app/TermuxApplication.java`
- `app/src/main/java/com/termux/app/TermuxInstaller.java`
- `app/src/main/java/com/termux/app/TermuxOpenReceiver.java`
- `app/src/main/java/com/termux/app/TermuxService.java`
- `app/src/main/java/com/termux/filepicker/TermuxDocumentsProvider.java`

### 8.2 `ui-shell`（Kotlin 源码全清单）
- `ui-shell/src/main/java/com/termux/ui/files/CanvasDemoActivity.kt`
- `ui-shell/src/main/java/com/termux/ui/files/CanvasPlaceholder.kt`
- `ui-shell/src/main/java/com/termux/ui/files/DirectoryObserver.kt`
- `ui-shell/src/main/java/com/termux/ui/files/FileActionsDialogHost.kt`
- `ui-shell/src/main/java/com/termux/ui/files/FileEntry.kt`
- `ui-shell/src/main/java/com/termux/ui/files/FilesBrowserPage.kt`
- `ui-shell/src/main/java/com/termux/ui/files/FilesRepository.kt`
- `ui-shell/src/main/java/com/termux/ui/files/FilesSelfTest.kt`
- `ui-shell/src/main/java/com/termux/ui/files/FileTypeHighlight.kt`
- `ui-shell/src/main/java/com/termux/ui/files/VsCodeFileIconTheme.kt`
- `ui-shell/src/main/java/com/termux/ui/ide/IdeProjectModels.kt`
- `ui-shell/src/main/java/com/termux/ui/ide/IdeProjectPreflight.kt`
- `ui-shell/src/main/java/com/termux/ui/ide/IdeProjectStore.kt`
- `ui-shell/src/main/java/com/termux/ui/ide/IdeProjectTemplates.kt`
- `ui-shell/src/main/java/com/termux/ui/nav/UiShellNavBridge.kt`
- `ui-shell/src/main/java/com/termux/ui/panel/EnvironmentManager.kt`
- `ui-shell/src/main/java/com/termux/ui/panel/PanelSelfTest.kt`
- `ui-shell/src/main/java/com/termux/ui/panel/SystemDashboardActivity.kt`
- `ui-shell/src/main/java/com/termux/ui/panel/TermuxCommandRunner.kt`
- `ui-shell/src/main/java/com/termux/ui/panel/TermuxTerminalLauncher.kt`
- `ui-shell/src/main/java/com/termux/ui/selftest/FullChainSelfTest.kt`
- `ui-shell/src/main/java/com/termux/ui/TermuxUiActivity.kt`
- `ui-shell/src/main/java/com/termux/ui/TermuxUiApp.kt`

### 8.3 终端核心模块（全清单）
- `terminal-emulator/src/main/java/com/termux/terminal/ByteQueue.java`
- `terminal-emulator/src/main/java/com/termux/terminal/JNI.java`
- `terminal-emulator/src/main/java/com/termux/terminal/KeyHandler.java`
- `terminal-emulator/src/main/java/com/termux/terminal/Logger.java`
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalBuffer.java`
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalColors.java`
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalColorScheme.java`
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java`
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalOutput.java`
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalRow.java`
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java`
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalSessionClient.java`
- `terminal-emulator/src/main/java/com/termux/terminal/TextStyle.java`
- `terminal-emulator/src/main/java/com/termux/terminal/WcWidth.java`
- `terminal-emulator/src/main/jni/termux.c`
- `terminal-view/src/main/java/com/termux/view/TerminalView.java`
- `terminal-view/src/main/java/com/termux/view/TerminalRenderer.java`
- `terminal-view/src/main/java/com/termux/view/TerminalViewClient.java`
- `terminal-view/src/main/java/com/termux/view/GestureAndScaleRecognizer.java`
- `terminal-view/src/main/java/com/termux/view/textselection/CursorController.java`
- `terminal-view/src/main/java/com/termux/view/textselection/TextSelectionCursorController.java`
- `terminal-view/src/main/java/com/termux/view/textselection/TextSelectionHandleView.java`

### 8.4 桥接与标签模块
- `file-bridge/src/main/java/com/termux/bridge/FileEditorContract.kt`
- `file-bridge/src/main/java/com/termux/bridge/FileOpenBridge.kt`
- `terminal-tabs/src/main/java/com/termux/terminaltabs/TerminalTabsBar.java`

### 8.5 `termux-shared`（包级定位）
- `activities`：`ReportActivity.java`、`TextIOActivity.java`
- `android`：权限/包管理/系统特性（`PermissionUtils.java`、`PackageUtils.java`、`FeatureFlagUtils.java`）
- `data`：`IntentUtils.java`、`DataUtils.java`
- `errors`：`Error.java`、`Errno.java`
- `file`：`FileUtils.java` + `filesystem/*`
- `logger`：`Logger.java`
- `net`：`uri/*`、`socket/local/*`
- `settings`：`SharedProperties.java`、`AppSharedPreferences.java`
- `shell`：`ExecutionCommand.java`、`ShellCommandConstants.java`、`runner/app/AppShell.java`
- `termux`
  - `TermuxConstants.java`、`TermuxBootstrap.java`、`TermuxUtils.java`
  - `termux-shared/src/main/java/com/termux/shared/termux/settings/preferences/`、`termux-shared/src/main/java/com/termux/shared/termux/settings/properties/`
  - `termux-shared/src/main/java/com/termux/shared/termux/shell/TermuxShellManager.java`
  - `termux-shared/src/main/java/com/termux/shared/termux/shell/command/environment/`
  - `termux-shared/src/main/java/com/termux/shared/termux/terminal/TermuxTerminalSessionClientBase.java`
  - `termux-shared/src/main/java/com/termux/shared/termux/terminal/TermuxTerminalViewClientBase.java`
  - `termux-shared/src/main/java/com/termux/shared/termux/plugins/TermuxPluginUtils.java`

---

## 9. 三方大模块扫描结果（全量包级）

### 9.1 `file-manager`（Fossify File Manager）
- 代码目录：`File-Manager-1.5.0/File-Manager-1.5.0/app/src/main/kotlin/org/fossify/filemanager`
- 包分布：
  - `activities` 9
  - `adapters` 4
  - `controllers` 1
  - `dialogs` 7
  - `extensions` 5
  - `fragments` 4
  - `helpers` 3
  - `interfaces` 2
  - `models` 1
  - `views` 2
- 集成入口：
  - `File-Manager-1.5.0/File-Manager-1.5.0/app/src/main/kotlin/org/fossify/filemanager/controllers/FileManagerController.kt`
  - `File-Manager-1.5.0/File-Manager-1.5.0/app/src/main/kotlin/org/fossify/filemanager/activities/SimpleActivity.kt`
  - `File-Manager-1.5.0/File-Manager-1.5.0/app/src/main/kotlin/org/fossify/filemanager/interfaces/FileManagerHost.kt`

### 9.2 `fossify-commons`
- 代码目录：`fossify-commons/src/main/kotlin/org/fossify/commons`
- 包分布：
  - `compose` 52
  - `views` 38
  - `dialogs` 35
  - `extensions` 41
  - `helpers` 18
  - 其余为 activities、adapters、models、interfaces 等目录
- 结论：这是 File Manager 的基础 UI/工具库，修改文件页行为通常优先看 `file-manager` 而不是直接改这里。

### 9.3 `sora-editor` 生态
- `sora-editor-0.24.3/editor/src/main/java/io/github/rosemoe/sora`
  - `lang` 96
  - `widget` 75
  - `text` 37
  - `event` 22
  - `util` 29
- `sora-editor-0.24.3/app/src/main/java/io/github/rosemoe/sora/app`（demo/app 层入口）
- `sora-editor-0.24.3/editor-lsp/src/main/java/io/github/rosemoe/sora/lsp`
  - `editor` 29
  - `events` 19
  - `client` 15
- `sora-editor-0.24.3/language-*`
  - `language-java`：`langs/java/*`
  - `language-textmate`：`langs/textmate/*`
  - `language-monarch`：`langs/monarch/*`（47）
  - `language-treesitter`：`editor/ts/*`
- 在本项目中的主要接入点是 `EditorController`，入口在：
  - `app/src/main/java/com/termux/app/TermuxActivity.java`
  - `sora-editor-0.24.3/app/src/main/java/io/github/rosemoe/sora/app/MainActivity.kt`

---

## 10. 测试与 CI 导航

### 10.1 关键测试
- `app/src/test/java/com/termux/app/TermuxActivityTest.java`
- `app/src/test/java/com/termux/app/api/file/FileReceiverActivityTest.java`
- `terminal-emulator/src/test/java/com/termux/terminal/*`（终端核心测试集中区）

### 10.2 CI 文件
- `.github/workflows/run_tests.yml`：执行 `./gradlew test`
- `.github/workflows/debug_build.yml`：多 `package_variant` 组装调试 APK

---

## 11. 快速定位命令模板（未来会话直接复用）

### 11.1 按类名找文件
```powershell
rg --files | rg "TermuxActivity\\.java|TermuxService\\.java|FileManagerController\\.kt"
```

### 11.2 按功能关键词找实现
```powershell
rg -n "setupBottomNavigation|setBottomNavTab|openFilesAtPath|ACTION_RUN_COMMAND" app ui-shell file-bridge termux-shared
```

### 11.3 按 Manifest 组件找入口
```powershell
rg -n "activity|service|receiver|provider" app/src/main/AndroidManifest.xml
```

### 11.4 按模块列源码
```powershell
rg --files app/src/main
rg --files ui-shell/src/main
rg --files terminal-emulator/src/main
```

### 11.5 定位三方编辑器/文件管理
```powershell
rg -n "EditorController|FileManagerController|FileOpenBridge|FileEditorContract" app ui-shell file-bridge File-Manager-1.5.0 fossify-commons sora-editor-0.24.3
```

---

## 12. 本仓库当前状态备注（给后续会话）

- 当前工作树不是干净状态（已有较多本地修改与未跟踪目录）。
- 导航时请优先以 `settings.gradle` 中声明模块为准；三方目录里有大量上游文件与资产，不要默认都参与当前 APK 构建。
