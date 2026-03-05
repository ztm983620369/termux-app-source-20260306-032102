# Artifact Calculator Kit

This folder provides a practical, buildable three-artifact template:

- `calculator-m3.apk`: standalone Compose Material 3 calculator app.
- `calculator-m3.zip`: zip package containing the same calculator APK.
- `calculator-m3.dex`: Dex entry compatible with Termux `ShadowDexRunner` (`com.termux.shadowtemplate.DemoEntry#run(Context)`).

## Why this is structured this way

- APK and ZIP can carry a full Android UI app.
- Dex in the current Termux chain is reflection-based code execution, not a full manifest/resource app container.
- So the Dex artifact launches the installed calculator app and writes a run report.

## Build

From repository root:

```powershell
powershell -ExecutionPolicy Bypass -File .\artifact-calculator-kit\build-all.ps1
```

Generated artifacts are in `artifact-calculator-kit\out`.

## Device deploy and run

```powershell
powershell -ExecutionPolicy Bypass -File .\artifact-calculator-kit\adb-deploy-run.ps1
```

This script:

- installs and launches `calculator-m3.apk`,
- pushes `calculator-m3.dex` and `calculator-m3.zip` to `/sdcard/Download/termux-artifacts`,
- executes Dex `main()` once via `dalvikvm` for a live report.

## Notes

- `adb-deploy-run.ps1` uses `artifact-calculator-kit\tools\adb_bridge.py` (direct ADB protocol over localhost `5037`), so it does not require `adb.exe` in PATH.
- If your local adb server is not running, start it on your host first.
