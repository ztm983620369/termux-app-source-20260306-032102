param(
    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"

$KitDir = $PSScriptRoot
$OutDir = Join-Path $KitDir "out"
$Bridge = Join-Path $KitDir "tools\adb_bridge.py"

$Apk = Join-Path $OutDir "calculator-m3.apk"
$Zip = Join-Path $OutDir "calculator-m3.zip"
$Dex = Join-Path $OutDir "calculator-m3.dex"

if (!(Test-Path $Bridge)) { throw "adb bridge script missing: $Bridge" }
if (!(Test-Path $Apk)) { throw "artifact missing: $Apk (run build-all.ps1 first)" }
if (!(Test-Path $Zip)) { throw "artifact missing: $Zip (run build-all.ps1 first)" }
if (!(Test-Path $Dex)) { throw "artifact missing: $Dex (run build-all.ps1 first)" }

function Invoke-AdbBridge {
    param(
        [string[]]$CommandArgs
    )
    $cmd = @($Bridge)
    if ($Serial -ne "") {
        $cmd += @("--serial", $Serial)
    }
    $cmd += $CommandArgs
    $output = & python $cmd
    if ($LASTEXITCODE -ne 0) {
        throw "adb_bridge failed: $($CommandArgs -join ' ')"
    }
    return $output
}

Write-Host "[1/6] Checking devices..."
$devicesOutput = Invoke-AdbBridge -CommandArgs @("devices")
if ([string]::IsNullOrWhiteSpace($devicesOutput)) {
    throw "No online adb device found. Please reconnect USB, enable USB debugging, and keep phone unlocked."
}
Write-Host $devicesOutput

Write-Host "[2/6] Installing APK..."
Invoke-AdbBridge -CommandArgs @("install", $Apk)

Write-Host "[3/6] Launching calculator activity..."
Invoke-AdbBridge -CommandArgs @("shell", "am start -n com.termux.artifactcalc/.MainActivity")

Write-Host "[4/6] Pushing artifacts for Termux runtime..."
Invoke-AdbBridge -CommandArgs @("shell", "mkdir -p /sdcard/Download/termux-artifacts")
Invoke-AdbBridge -CommandArgs @("push", $Dex, "/sdcard/Download/termux-artifacts/calculator-m3.dex")
Invoke-AdbBridge -CommandArgs @("push", $Zip, "/sdcard/Download/termux-artifacts/calculator-m3.zip")
Invoke-AdbBridge -CommandArgs @("push", $Apk, "/sdcard/Download/termux-artifacts/calculator-m3.apk")

Write-Host "[5/6] Running dex main() once on device (report output)..."
Invoke-AdbBridge -CommandArgs @("shell", "dalvikvm -cp /sdcard/Download/termux-artifacts/calculator-m3.dex com.termux.shadowtemplate.DemoEntry")

Write-Host "[6/6] Listing pushed files..."
Invoke-AdbBridge -CommandArgs @("shell", "ls -l /sdcard/Download/termux-artifacts")

Write-Host ""
Write-Host "Done. You can now open Termux file manager and run these files directly:"
Write-Host "  /sdcard/Download/termux-artifacts/calculator-m3.dex"
Write-Host "  /sdcard/Download/termux-artifacts/calculator-m3.apk"
Write-Host "  /sdcard/Download/termux-artifacts/calculator-m3.zip"
