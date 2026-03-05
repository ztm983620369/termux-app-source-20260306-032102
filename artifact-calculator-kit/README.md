# Artifact Calculator Kit（中文说明）

这个目录提供一套可直接构建与验证的三件套产物：

- `calculator-m3.apk`：独立的 Compose Material 3 计算器应用。
- `calculator-m3.zip`：包含同一 APK 的压缩包。
- `calculator-m3.dex`：兼容 Termux `ShadowDexRunner` 的 Dex 入口（`com.termux.shadowtemplate.DemoEntry#run(Context)`）。

## 结构设计原因

- APK 和 ZIP 可以承载完整的 Android UI 应用。
- 当前 Termux 链路中的 Dex 主要是反射执行代码，不是完整的清单/资源容器。
- 因此 Dex 产物的职责是：拉起已安装的计算器应用，并输出一次运行报告。

## 构建方法

在仓库根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\artifact-calculator-kit\build-all.ps1
```

构建产物输出到 `artifact-calculator-kit\out`。

## 设备部署与运行

```powershell
powershell -ExecutionPolicy Bypass -File .\artifact-calculator-kit\adb-deploy-run.ps1
```

脚本会执行以下操作：

- 安装并启动 `calculator-m3.apk`；
- 将 `calculator-m3.dex` 与 `calculator-m3.zip` 推送到 `/sdcard/Download/termux-artifacts`；
- 通过 `dalvikvm` 执行一次 Dex `main()` 并输出实时报告。

## 备注

- `adb-deploy-run.ps1` 依赖 `artifact-calculator-kit\tools\adb_bridge.py`（通过 localhost `5037` 直连 ADB 协议），不要求 `adb.exe` 在 `PATH` 中。
- 如果本机 adb server 未启动，请先在宿主机启动。
