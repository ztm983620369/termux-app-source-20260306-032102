param(
    [string]$SdkRoot = "C:\sdk"
)

$ErrorActionPreference = "Stop"

$KitDir = $PSScriptRoot
$RepoRoot = Split-Path -Parent $KitDir
$AppProject = Join-Path $KitDir "calculator-app"
$DexSrcDir = Join-Path $KitDir "dex-src"
$OutDir = Join-Path $KitDir "out"
$WorkDir = Join-Path $KitDir ".build-work"
$GradleWrapper = Join-Path $RepoRoot "gradlew.bat"

if (!(Test-Path $GradleWrapper)) {
    throw "gradlew.bat not found: $GradleWrapper"
}
if (!(Test-Path $AppProject)) {
    throw "calculator app project not found: $AppProject"
}
if (!(Test-Path $DexSrcDir)) {
    throw "dex source dir not found: $DexSrcDir"
}

New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
New-Item -ItemType Directory -Path $WorkDir -Force | Out-Null

$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:JAVA_TOOL_OPTIONS = "-Duser.home=$RepoRoot"
$env:GRADLE_USER_HOME = Join-Path $RepoRoot ".gradle-user-home-local"
New-Item -ItemType Directory -Path $env:GRADLE_USER_HOME -Force | Out-Null

Write-Host "[1/4] Building Compose Material 3 calculator APK..."
& $GradleWrapper -p $AppProject :app:assembleDebug --console=plain --no-daemon
if ($LASTEXITCODE -ne 0) {
    throw "Gradle assembleDebug failed with exit code $LASTEXITCODE"
}

$BuiltApk = Join-Path $AppProject "app\build\outputs\apk\debug\app-debug.apk"
if (!(Test-Path $BuiltApk)) {
    throw "Built APK not found: $BuiltApk"
}

$ApkOut = Join-Path $OutDir "calculator-m3.apk"
Copy-Item $BuiltApk $ApkOut -Force

Write-Host "[2/4] Building ZIP artifact..."
$ZipOut = Join-Path $OutDir "calculator-m3.zip"
if (Test-Path $ZipOut) {
    Remove-Item $ZipOut -Force
}
Compress-Archive -Path $ApkOut -DestinationPath $ZipOut -Force

Write-Host "[3/4] Building Dex artifact..."
$DexClassesDir = Join-Path $WorkDir "dex-classes"
$DexOutDir = Join-Path $WorkDir "dex-out"
if (Test-Path $DexClassesDir) { Remove-Item -Recurse -Force $DexClassesDir }
if (Test-Path $DexOutDir) { Remove-Item -Recurse -Force $DexOutDir }
New-Item -ItemType Directory -Path $DexClassesDir -Force | Out-Null
New-Item -ItemType Directory -Path $DexOutDir -Force | Out-Null

$PlatformJar = Get-ChildItem -Path (Join-Path $SdkRoot "platforms") -Directory |
    Sort-Object Name -Descending |
    ForEach-Object { Join-Path $_.FullName "android.jar" } |
    Where-Object { Test-Path $_ } |
    Select-Object -First 1
if (!$PlatformJar) {
    throw "android.jar not found under $SdkRoot\platforms"
}

$D8 = Get-ChildItem -Path (Join-Path $SdkRoot "build-tools") -Directory |
    Sort-Object Name -Descending |
    ForEach-Object { Join-Path $_.FullName "d8.bat" } |
    Where-Object { Test-Path $_ } |
    Select-Object -First 1
if (!$D8) {
    throw "d8.bat not found under $SdkRoot\build-tools"
}

$JavaSources = Get-ChildItem -Path $DexSrcDir -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName
if (!$JavaSources -or $JavaSources.Count -eq 0) {
    throw "No java sources found in $DexSrcDir"
}

javac -encoding UTF-8 -source 1.8 -target 1.8 -classpath $PlatformJar -d $DexClassesDir $JavaSources
if ($LASTEXITCODE -ne 0) {
    throw "javac failed compiling dex source"
}

$ClassFiles = Get-ChildItem -Path $DexClassesDir -Recurse -Filter "*.class" | Select-Object -ExpandProperty FullName
& $D8 --min-api 24 --output $DexOutDir $ClassFiles
if ($LASTEXITCODE -ne 0) {
    throw "d8 failed building dex"
}

$BuiltDex = Join-Path $DexOutDir "classes.dex"
if (!(Test-Path $BuiltDex)) {
    throw "classes.dex not generated"
}

$DexOut = Join-Path $OutDir "calculator-m3.dex"
Copy-Item $BuiltDex $DexOut -Force

Write-Host "[4/4] Writing build report..."
$Artifacts = @($ApkOut, $ZipOut, $DexOut) | ForEach-Object {
    $item = Get-Item $_
    $hash = Get-FileHash -Algorithm SHA256 -Path $_
    [PSCustomObject]@{
        name = $item.Name
        path = $item.FullName
        sizeBytes = $item.Length
        sha256 = $hash.Hash
    }
}

$Report = [PSCustomObject]@{
    generatedAt = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
    packageName = "com.termux.artifactcalc"
    artifacts = $Artifacts
}

$ReportPath = Join-Path $OutDir "build-report.json"
$Report | ConvertTo-Json -Depth 8 | Set-Content -Path $ReportPath -Encoding UTF8

Write-Host ""
Write-Host "Artifacts generated:"
Write-Host "  $ApkOut"
Write-Host "  $ZipOut"
Write-Host "  $DexOut"
Write-Host "Report:"
Write-Host "  $ReportPath"
