# Camera Capsule Surface

Decoupled floating capsule module for Termux that anchors a visual overlay near the display cutout / front-camera area and keeps a `system-status-surface` notification as the system fallback.

## What it does

- Cross-app overlay via `TYPE_APPLICATION_OVERLAY`
- Cutout-first anchoring with top-center fallback
- Foreground service orchestration for process stability
- Broadcast-driven control so it stays decoupled from app business logic
- Notification fallback through `system-status-surface`
- Unicode-safe text payloads, including Chinese

## Broadcast actions

- `com.termux.cameracapsulesurface.action.PUBLISH`
- `com.termux.cameracapsulesurface.action.CANCEL`
- `com.termux.cameracapsulesurface.action.CLEAR_ALL`
- `com.termux.cameracapsulesurface.action.RESTORE`
- `com.termux.cameracapsulesurface.action.OPEN_OVERLAY_SETTINGS`
- `com.termux.cameracapsulesurface.action.SHOW_DEMO`
- `com.termux.cameracapsulesurface.action.REQUEST_SHIZUKU_PERMISSION`
- `com.termux.cameracapsulesurface.action.RESTORE_STATUS_BAR`

## Main extras

- `surface_id`
- `title`
- `text`
- `status`
- `short_text`
- `progress`
- `progress_max`
- `progress_indeterminate`
- `priority`
- `color`
- `expanded`
- `touchable`
- `draggable`
- `control_status_bar`
- `hide_status_bar_system_icons`
- `hide_status_bar_clock`
- `hide_status_bar_notification_icons`
- `status_bar_privilege_mode` = `auto` | `shizuku` | `root` | `direct` | `none`
- `anchor_mode` = `cutout` | `top_center` | `manual`
- `offset_x_dp`
- `offset_y_dp`
- `width_dp`
- `height_dp`

Unknown extras are stored into the generic payload bundle for future integration.

## Privileged status bar backend

When the overlay is visible, the module can try to hide the system status-bar row with:

```bash
cmd statusbar send-disable-flag system-icons clock notification-icons
```

When the overlay is cleared, it restores with:

```bash
cmd statusbar send-disable-flag none
```

Backend order in `auto` mode:

1. `shizuku`
2. `root`
3. `direct`

Notes:

- `shizuku` gives adb-shell-like or root-like privileges through the Shizuku service
- `root` uses `su -c`
- `direct` works only when the app already has privileged execution ability, such as a system-signed / privileged install
- plain third-party app context cannot launch real `adb shell` by itself; Shizuku is the supported adb-shell-equivalent path inside the app

## ADB demo

Grant overlay permission:

```bash
adb shell appops set com.termux SYSTEM_ALERT_WINDOW allow
```

Show the demo capsule:

```bash
adb shell am broadcast \
  -n com.termux/com.termux.cameracapsulesurface.CameraCapsuleSurfaceReceiver \
  -a com.termux.cameracapsulesurface.action.SHOW_DEMO
```

If the OEM blocks cold-start background broadcasts, use the no-UI command activity:

```bash
adb shell am start \
  -n com.termux/com.termux.cameracapsulesurface.CameraCapsuleSurfaceCommandActivity \
  -a com.termux.cameracapsulesurface.action.SHOW_DEMO
```

Show a custom progress capsule:

```bash
adb shell am broadcast \
  -n com.termux/com.termux.cameracapsulesurface.CameraCapsuleSurfaceReceiver \
  -a com.termux.cameracapsulesurface.action.PUBLISH \
  --es surface_id sync_job \
  --es title 服务端同步 \
  --es text 后台任务继续运行 \
  --es status 68% \
  --es short_text 68% \
  --ei progress 68 \
  --ei progress_max 100 \
  --ez expanded true \
  --ez control_status_bar true \
  --ez hide_status_bar_system_icons true \
  --ez hide_status_bar_clock true \
  --ez hide_status_bar_notification_icons true \
  --es status_bar_privilege_mode auto \
  --es priority high \
  --es color '#4CAF50'
```

Open overlay settings:

```bash
adb shell am broadcast \
  -n com.termux/com.termux.cameracapsulesurface.CameraCapsuleSurfaceReceiver \
  -a com.termux.cameracapsulesurface.action.OPEN_OVERLAY_SETTINGS
```

Request Shizuku permission:

```bash
adb shell am start \
  -n com.termux/com.termux.cameracapsulesurface.CameraCapsuleSurfacePermissionActivity \
  -a com.termux.cameracapsulesurface.action.REQUEST_SHIZUKU_PERMISSION
```

Force restore the system status bar:

```bash
adb shell am broadcast \
  -n com.termux/com.termux.cameracapsulesurface.CameraCapsuleSurfaceReceiver \
  -a com.termux.cameracapsulesurface.action.RESTORE_STATUS_BAR
```
