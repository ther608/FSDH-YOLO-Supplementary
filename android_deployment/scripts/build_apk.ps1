<#
.SYNOPSIS
    Build the FSDH-YOLO Android APK against the self-contained toolchain.

.DESCRIPTION
    Wraps `gradle assembleDebug|assembleRelease`. JAVA_HOME / ANDROID_SDK_ROOT
    are set for this process only -- the system environment is never modified,
    so the build is reproducible on a machine that has no Android tooling at all
    beyond what setup_android_toolchain.ps1 installs.

.EXAMPLE
    powershell -File build_apk.ps1
    powershell -File build_apk.ps1 -Flavor slim
    powershell -File build_apk.ps1 -Release
#>
[CmdletBinding()]
param(
    [string] $ToolchainRoot = "D:\android-toolchain",
    [switch] $Release,
    [switch] $Clean,
    # full = both runtimes + both models (the A/B build, and the default)
    # slim = ONNX Runtime + the single final model (the deployment build)
    # all  = both APKs in one run
    [ValidateSet("full", "slim", "all")]
    [string] $Flavor = "full"
)

$ErrorActionPreference = "Stop"

$JdkDir    = Join-Path $ToolchainRoot "jdk17"
$SdkRoot   = Join-Path $ToolchainRoot "android-sdk"
$Gradle    = Join-Path $ToolchainRoot "gradle-8.7\bin\gradle.bat"
$AppDir    = (Resolve-Path (Join-Path $PSScriptRoot "..\android")).Path

foreach ($p in @($JdkDir, $SdkRoot, $Gradle)) {
    if (-not (Test-Path $p)) {
        throw "missing $p -- run setup_android_toolchain.ps1 first"
    }
}

$env:JAVA_HOME = $JdkDir
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:PATH = "$JdkDir\bin;$env:PATH"

# local.properties is machine-specific and deliberately not committed; forward
# slashes are valid here and avoid backslash-escaping bugs in .properties
"sdk.dir=$($SdkRoot -replace '\\','/')" |
    Set-Content -Path (Join-Path $AppDir "local.properties") -Encoding utf8

$variant = if ($Release) { "Release" } else { "Debug" }
$task = switch ($Flavor) {
    "full" { "assembleFull$variant" }
    "slim" { "assembleSlim$variant" }
    "all"  { "assemble$variant" }
}
$args = @("-p", $AppDir, $task, "--console=plain")
if ($Clean) { $args += "clean" }

Write-Host "[i] JAVA_HOME    = $JdkDir"
Write-Host "[i] ANDROID_HOME = $SdkRoot"
Write-Host "[i] flavor       = $Flavor"
Write-Host "[i] task         = $task"
Write-Host ""

& $Gradle @args
$code = $LASTEXITCODE

if ($code -eq 0) {
    Write-Host "`n[+] build succeeded" -ForegroundColor Green
    Get-ChildItem -Path (Join-Path $AppDir "app\build\outputs\apk") -Recurse -Filter "*.apk" |
        Where-Object { $_.Name -notlike "*-unsigned*" } |
        Sort-Object Name |
        ForEach-Object {
            Write-Host ("    {0}  ({1:N1} MB)" -f $_.FullName, ($_.Length / 1MB))
        }
} else {
    Write-Host "`n[!] build failed with exit code $code" -ForegroundColor Red
}

exit $code
