# System Status Surface

独立的系统状态表面模块。

目标：

- 不绑定 `app` 业务状态机
- 统一接收外部状态输入
- 统一渲染到系统通知表面
- 支持手动广播控制
- 支持 Android 16 `Live Update` / `ProgressStyle`

## 当前能力

- 发布状态
- 覆盖更新同一个 `surface_id`
- 取消单个状态
- 清空全部状态
- 任务进度展示
- 状态快照持久化
- Android 16 promoted ongoing 请求
- 手动打开 promoted notification 设置

## 广播入口

接收器：

- `com.termux.systemstatussurface.SystemStatusSurfaceReceiver`

动作：

- `com.termux.systemstatussurface.action.PUBLISH`
- `com.termux.systemstatussurface.action.CANCEL`
- `com.termux.systemstatussurface.action.CLEAR_ALL`
- `com.termux.systemstatussurface.action.OPEN_PROMOTION_SETTINGS`

## ADB 示例

发布：

```bash
adb shell am broadcast \
  -n com.termux/com.termux.systemstatussurface.SystemStatusSurfaceReceiver \
  -a com.termux.systemstatussurface.action.PUBLISH \
  --es surface_id demo_progress \
  --es channel_id demo_status \
  --es channel_name DemoStatus \
  --es channel_description DemoStatusSurface \
  --es title DemoSurface \
  --es text ProgressRunning \
  --es status 35/100 \
  --es short_critical_text 35% \
  --ei progress 35 \
  --ei progress_max 100 \
  --ez ongoing true \
  --ez promoted true \
  --es priority high
```

取消：

```bash
adb shell am broadcast \
  -n com.termux/com.termux.systemstatussurface.SystemStatusSurfaceReceiver \
  -a com.termux.systemstatussurface.action.CANCEL \
  --es surface_id demo_progress
```

清空：

```bash
adb shell am broadcast \
  -n com.termux/com.termux.systemstatussurface.SystemStatusSurfaceReceiver \
  -a com.termux.systemstatussurface.action.CLEAR_ALL
```

打开 promoted settings：

```bash
adb shell am broadcast \
  -n com.termux/com.termux.systemstatussurface.SystemStatusSurfaceReceiver \
  -a com.termux.systemstatussurface.action.OPEN_PROMOTION_SETTINGS
```
