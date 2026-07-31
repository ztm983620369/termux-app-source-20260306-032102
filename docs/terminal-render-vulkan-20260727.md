# Termux 独立 Vulkan 终端渲染器（2026-07-27）

## 结论边界

本阶段已经完成独立 Android Vulkan renderer 的第一版源码落地，并通过本地编译、单元测试、四 ABI 原生构建和 Android instrumentation APK 构建。2026-07-27 已在 HONOR AGI-AN00（Android 16 / API 36）覆盖安装，并通过终端状态、真实 present 和 GPU 像素方向正确性门禁。Ghostty/libghostty-vt 仍是唯一的终端解析与 render-state 权威；Vulkan 只消费其完整、不可变的渲染批次，不重复解析 VT，也不逐 cell 访问 JNI。

本文记录的是实现事实和验证入口。当前设备功能正确性门禁通过，但极限负载帧指标尚未达到工业性能目标，因此不能把“Vulkan 已工作”等同于“性能已经达标”。

## 运行链路

```text
PTY bytes
  -> libghostty-vt / GhosttyTerminalBackend（唯一解析权威）
  -> GhosttyRenderDelta（批量 native transport）
  -> GhosttyRenderNodeRenderer（保留行、damage、viewport）
  -> TerminalGpuFrame（不可变 full/delta 批次）
  -> TerminalVulkanView 独立 render thread
  -> Android text shaping + glyph atlas
  -> 单批 vertex buffer
  -> Vulkan swapchain / present
```

每个可见或 ViewPager 保留的终端页拥有独立 `TextureView` surface 和 render thread。终端 session、Ghostty 状态和 retained rows 不因横向切换而重建。第一帧完成之前继续显示 Canvas；只有 Vulkan 已经 present 一个完整 frame 后，Canvas 才变为透明覆盖层。这条原子提交约束用于阻止黑屏、半屏和旧 tab 内容串入新 tab。

选择 `TextureView` 是为了保持当前页面层级、横向滑动变换和 tab 生命周期的一致性。API 33 以下不实例化 Vulkan view，直接走既有 Canvas 正确性链路；API 33 以上还会检查系统 Vulkan feature 和 native library，任何初始化、surface 或 render 致命错误都会永久回退到 Canvas。

## 实现范围

- `TerminalGpuFrame`：完整帧或 damage 帧的不可变快照，包含背景矩形、文本 run、行 generation 和 viewport 元数据；构造时防御复制。
- `GhosttyRenderNodeRenderer`：从 retained Ghostty rows 导出批量 frame；修正滚动边界 overscan，禁止在 scrollback 顶部或底部导出不存在的像素偏移。
- `TerminalRenderer` / `TerminalView`：统一预热、full/delta 提交、generation/revision 消费和首帧回退状态；内容更新不再无条件退化为 full frame。
- `TerminalVulkanRenderer`：后台 shaping、普通字形 R8 atlas、彩色字形 RGBA atlas、dirty-region 上传与单批顶点生成。
- `TerminalVulkanView`：surface 所有权、pending-frame 合并、独立 HandlerThread、一次 full retry、首帧回调、销毁与诊断计数。
- `terminal_vulkan_renderer.c`：Android surface、物理设备/queue 选择、swapchain、render pass、pipeline、descriptor、双帧同步、staging/vertex buffer、atlas 增量上传、out-of-date 重建和 JNI 批量入口。
- `layout-v33`：Vulkan view 位于 Canvas `TerminalView` 后方；基础 layout 不引用新类，保护低版本加载。
- `TerminalIndustrialInstrumentation`：设备门禁新增 `expected/ready/failed/presented_frames/diagnostics` Vulkan 证据。

## 正确性合同

1. Ghostty 是字符、颜色、样式、宽度、光标和排版状态的唯一来源。
2. Java 到 native 每帧只有批量 render 调用，不存在逐 cell JNI。
3. frame 在进入 render thread 前不可变；不完整 frame 不允许 present。
4. delta 只在已经提交过同尺寸基帧后使用；尺寸、字体、surface 或 generation 基线变化强制 full frame。
5. swapchain `OUT_OF_DATE` / `SUBOPTIMAL` 触发重建；首个增量无法应用时只允许一次 full retry。
6. Vulkan 首帧提交前 Canvas 始终可见；Vulkan 失败后 Canvas 立即恢复并保留诊断日志。
7. tab 切换不销毁 PTY、Ghostty backend 或 retained state；surface 重建不能改变 session 内容。

## 本地验证

2026-07-27 01:39 完成：

```text
:terminal-view:testDebugUnitTest                 PASS
:terminal-session-surface:testDebugUnitTest     PASS
:app:assembleDebug                              PASS
:app:assembleDebugAndroidTest                   PASS
Vulkan native: arm64-v8a/armeabi-v7a/x86/x86_64 PASS
SPIR-V validation                               PASS
JNI export / APK native library inspection      PASS
git diff --check                                PASS
```

最终 arm64 APK：`app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk`

测试 APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

## 设备验证入口

覆盖安装：

```bash
adb install -r -d app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk
adb install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

完整真实终端门禁：

```bash
adb shell am instrument -w -r \
  -e action terminal_industrial \
  -e run_id vulkan-20260727 \
  -e stress 8 \
  -e frames 900 \
  -e burst_lines 50000 \
  com.termux.test/com.termux.shadow.ShadowProbeInstrumentation
```

诊断日志：

```bash
adb logcat -v threadtime -s TermuxVulkanNative TermuxVulkanView TermuxTerminalView TermuxIndustrialProbe
```

门禁要求：`vulkan_renderer.expected=true`、`ready=true`、`failed=false`、`presented_frames>0`；同时检查 frame metrics、tab switch sample、pinch sample、scroll/viewport 样本、后台 session 持续进度和报告中的 `errors=[]`。任何一项失败都不能把本阶段标为设备达标。

## 设备实测与已修缺陷

第一次设备启动揭示 Honor `TextureView` 不允许背景 drawable：构造函数里的 `setBackgroundColor()` 抛出 `UnsupportedOperationException`，页面在 inflate 阶段崩溃。该无效调用已删除，修复后应用和门禁均可稳定启动。

第二次设备测试揭示 Vulkan 顶点 shader 错误地按 OpenGL 方向换算 Y 坐标，导致终端内容整体上下倒置，而 tab 和底部 Android UI 保持正常。当前使用 Vulkan 正高度 viewport 的正确映射：终端顶端 `y=0` 映射到 NDC `y=-1`。同时新增 debug 设备像素探针：独立创建 96x96 Vulkan surface，渲染左上红、右上绿、左下蓝、右下黄，再从真实 `TextureView` bitmap 读取四象限。该门禁会同时捕获 X/Y 翻转，不能再由状态日志误判。

最终设备运行 `vulkan-20260727-orientation`：

```text
status                                      PASS (correctness)
duration                                    12,933 ms
terminal selections                         21 complete / 0 incomplete
Ghostty parser bytes off main               80,835,322
Ghostty parser calls on main                 0
Vulkan expected/ready/failed                 true / true / false
Vulkan presented/incomplete/retry/failed     133 / 0 / 0 / 0
GPU orientation sampled                      ffff2020,ff20ff20,ff2020ff,ffffff20
GPU orientation result                       PASS
lab summary                                  3 passed / 0 failed / 0 warned
report errors/warnings                       0 / 0
```

设备报告：`/data/data/com.termux/files/home/termux-tui-lab/reports/termux-industrial-vulkan-20260727-orientation.json.app.json`

同一轮性能证据：90 Hz 屏幕、176 个 FrameMetrics 样本、132 个超过当前预算的 janky frame（75%）；total p50 37.37 ms、p95 245.83 ms，draw p50 0.31 ms、p95 18.17 ms。最终极小字号 169x159 网格的 Vulkan 批次为 60,996 vertices，最近一次 render 27.74 ms。该极端测试证明 UI draw 主路径已经很轻，但高频全屏组装、提交和合成仍有明显长尾；性能状态明确为 **未达标**，后续应针对 retained row vertex cache、提交节流与 GPU timestamp 分解继续优化，而不是再加延迟或动画掩盖。

## 回滚

Vulkan 前源码快照：

```text
/root/termux-render-checkpoints/termux-before-vulkan-renderer-20260727.tar.gz
SHA-256 b7c61640d3af1249a07eafd792259c9bd0698a3968019382f6450edd7dd7fe98
```

instrumentation 前补充快照：

```text
/root/termux-render-checkpoints/termux-before-vulkan-instrumentation-20260727.tar.gz
SHA-256 4bbeda04db0d8f14c877a45dca613d716da86f7146b3d8ee479c3a175b828c4e
```

一键恢复并构建安装：

```bash
./rollback-terminal-render-vulkan.sh
```

只恢复源码：

```bash
./rollback-terminal-render-vulkan.sh --source-only
```

恢复本次优化备份：

```bash
./rollback-terminal-render-vulkan.sh --undo
```

回滚脚本会先备份当前优化源码，再校验并恢复两个快照，删除 Vulkan 专属新增文件；不会使用 `git reset` 或覆盖仓库内其他无关改动。

## 尚未宣称的事项

- 本机驱动已经通过首轮 present、tab 切换、pinch、scroll、三 session 后台推进和四象限方向验证；其他 GPU/Android 厂商仍需独立设备矩阵。
- 当前每个活跃 `TextureView` 创建独立 Vulkan device/context，这是第一版的隔离策略，不应描述为共享 GPU device。
- 字体 shaping 仍使用 Android `Paint`/`TextRunShaper` 栅格化后上传 atlas，并非 Ghostty 自带字体系统或完整 Ghostty GPU renderer。
- 正确性设备报告无错误并不代表性能达标；当前极限负载 jank 仍需下一阶段解决。
