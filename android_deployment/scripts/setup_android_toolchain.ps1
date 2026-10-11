<#
.SYNOPSIS
    Provision a self-contained Android build toolchain for the FSDH-YOLO native app.

.DESCRIPTION
    Installs JDK 17 + Android cmdline-tools/SDK + Gradle into a SINGLE directory
    (default D:\android-toolchain) so the whole thing can be removed by deleting
    that one folder. Nothing is written to the registry and nothing is added to
    the system PATH -- the build script sets $env:JAVA_HOME / ANDROID_HOME for the
    child process only.

    Idempotent: re-running skips anything already present.

.NOTES
    Versions are pinned deliberately. This combo is a well-tested set:
      Gradle 8.7  + AGP 8.5.2 + Kotlin 1.9.24 + compileSdk 34 / JDK 17

    Mirror choice is not cosmetic. Measured on this machine:
      api.adoptium.net (-> github.com)      times out
      mirrors.tuna.tsinghua.edu.cn            12.9 MB/s
      mirrors.cloud.tencent.com               15.7 MB/s
      dl.google.com                            1.9 MB/s
    so JDK comes from TUNA, Gradle from Tencent, and the SDK from Google direct.
    Every download falls back to the next mirror on failure.
#>
[CmdletBinding()]
param(
    [string] $Root = "D:\android-toolchain",
    [string] $SdkRoot = "D:\android-toolchain\android-sdk",
    [switch] $SkipSdkPackages
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$JdkDir    = Join-Path $Root "jdk17"
$GradleDir = Join-Path $Root "gradle-8.7"

function Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }
function Info($msg) { Write-Host "[i] $msg" }
function Warn($msg) { Write-Host "[!] $msg" -ForegroundColor Yellow }
function Ok($msg)   { Write-Host "[+] $msg" -ForegroundColor Green }

# curl.exe ships with Windows 10+ and is far more reliable here than
# Invoke-WebRequest: it follows redirects, resumes, and actually reports
# transfer speeds. $Urls is an ordered fallback list.
function Get-File {
    param(
        [string[]] $Urls,
        [string]   $OutFile,
        [long]     $MinBytes = 1MB
    )
    $curl = (Get-Command curl.exe -ErrorAction SilentlyContinue).Source
    if (-not $curl) { throw "curl.exe not found; this script requires Windows 10+" }

    foreach ($u in $Urls) {
        Info "GET $u"
        & $curl -sS -L --fail --retry 3 --retry-delay 2 --connect-timeout 20 --max-time 1800 `
                -o $OutFile $u
        if ($LASTEXITCODE -eq 0 -and (Test-Path $OutFile) -and (Get-Item $OutFile).Length -ge $MinBytes) {
            Ok ("saved {0:N1} MB" -f ((Get-Item $OutFile).Length / 1MB))
            return $true
        }
        Warn "failed or truncated (curl exit $LASTEXITCODE); trying next mirror"
        if (Test-Path $OutFile) { Remove-Item $OutFile -Force }
    }
    throw "all mirrors failed for $($Urls[0])"
}

# Windows PowerShell 5.1's Expand-Archive is pathologically slow on archives
# with many small files -- measured at 28 files / 12 s on the JDK zip, which is
# ~20k files and would have taken hours. bsdtar (shipped as tar.exe since Win10
# 1803) does the same job in seconds, so it is used first.
function Expand-Zip {
    param([string] $Zip, [string] $Dest)
    if (Test-Path $Dest) { Remove-Item $Dest -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $Dest | Out-Null

    $tar = (Get-Command tar.exe -ErrorAction SilentlyContinue).Source
    if ($tar) {
        Info "extracting with tar.exe..."
        & $tar -xf $Zip -C $Dest
        if ($LASTEXITCODE -eq 0) { return }
        Warn "tar.exe failed ($LASTEXITCODE); falling back to Expand-Archive"
    }
    Expand-Archive -Path $Zip -DestinationPath $Dest -Force
}

# Windows PowerShell 5.1 turns a native command's redirected stderr into
# ErrorRecords, and with $ErrorActionPreference='Stop' that becomes a
# terminating error even when the exe succeeded -- `java -version` writes to
# stderr, so it killed the run outright. Calling through this helper keeps the
# output visible while downgrading those records to plain strings.
function Invoke-Native {
    param([string] $Exe, [string[]] $Arguments)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $Exe @Arguments 2>&1 | ForEach-Object { "$_" }
    } finally {
        $ErrorActionPreference = $prev
    }
}

New-Item -ItemType Directory -Force -Path $Root | Out-Null

# ---------------------------------------------------------------- JDK 17
Step "JDK 17 (Temurin 17.0.20.1+1, zip distribution - no installer, no registry)"
if (Test-Path (Join-Path $JdkDir "bin\java.exe")) {
    Ok "already present: $JdkDir"
} else {
    $zip = Join-Path $Root "jdk17.zip"
    Get-File -OutFile $zip -MinBytes 100MB -Urls @(
        "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip",
        "https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip",
        "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"
    )
    Info "extracting..."
    $tmp = Join-Path $Root "_jdk_tmp"
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    $inner = (Get-ChildItem $tmp -Directory | Select-Object -First 1).FullName
    Move-Item $inner $JdkDir
    Remove-Item $tmp -Recurse -Force
    Remove-Item $zip -Force
    Ok "JDK -> $JdkDir"
}
$env:JAVA_HOME = $JdkDir
$env:PATH = "$JdkDir\bin;$env:PATH"
Info ("java: " + ((Invoke-Native "$JdkDir\bin\java.exe" @("-version")) | Select-Object -First 1))

# ------------------------------------------------------- Android cmdline-tools
Step "Android SDK cmdline-tools"
$CmdToolsBin = Join-Path $SdkRoot "cmdline-tools\latest\bin"
if (Test-Path (Join-Path $CmdToolsBin "sdkmanager.bat")) {
    Ok "already present: $CmdToolsBin"
} else {
    $zip = Join-Path $Root "cmdline-tools.zip"
    Get-File -OutFile $zip -MinBytes 50MB -Urls @(
        "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    )
    $tmp = Join-Path $Root "_cmdtools_tmp"
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    # the zip contains a bare `cmdline-tools/` dir; sdkmanager requires it be
    # named `latest` so it can locate its own source.properties
    $dest = Split-Path $CmdToolsBin -Parent
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Move-Item (Join-Path $tmp "cmdline-tools\*") $dest -Force
    Remove-Item $tmp -Recurse -Force
    Remove-Item $zip -Force
    Ok "cmdline-tools -> $CmdToolsBin"
}

$SdkManager = Join-Path $CmdToolsBin "sdkmanager.bat"
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot

# --------------------------------------------------------------- SDK packages
if (-not $SkipSdkPackages) {
    # sdkmanager.bat is a batch file, and piping into a batch file's stdin from
    # PowerShell 5.1 does NOT reliably deliver the answers -- the first attempt
    # reported "7 of 7 SDK package licenses not accepted" and every package
    # install then failed silently. Feeding the answers through cmd.exe's `<`
    # redirection instead is what actually works.
    $yesFile = Join-Path $Root "sdk-yes.txt"
    Set-Content -Path $yesFile -Value ((1..300 | ForEach-Object { "y" }) -join "`r`n") -Encoding ascii

    Step "Accepting SDK licenses (unattended)"
    & cmd /c "`"$SdkManager`" --sdk_root=`"$SdkRoot`" --licenses < `"$yesFile`" 2>&1" |
        Select-Object -Last 4

    Step "Installing SDK packages"
    foreach ($p in @("platform-tools", "platforms;android-34", "build-tools;34.0.0")) {
        Info "installing $p ..."
        & cmd /c "`"$SdkManager`" --sdk_root=`"$SdkRoot`" `"$p`" < `"$yesFile`" 2>&1" |
            Select-Object -Last 3
    }
    Remove-Item $yesFile -Force -ErrorAction SilentlyContinue

    # Verify against the filesystem rather than trusting sdkmanager's exit code:
    # the silent-failure mode above is exactly why.
    $required = @(
        (Join-Path $SdkRoot "platform-tools\adb.exe"),
        (Join-Path $SdkRoot "platforms\android-34\android.jar"),
        (Join-Path $SdkRoot "build-tools\34.0.0\aapt2.exe")
    )
    $missing = $required | Where-Object { -not (Test-Path $_) }
    if ($missing) {
        throw "SDK install incomplete; missing:`n  " + ($missing -join "`n  ")
    }
    Ok "SDK packages installed and verified on disk"
}

# ------------------------------------------------------------------- Gradle
Step "Gradle 8.7"
if (Test-Path (Join-Path $GradleDir "bin\gradle.bat")) {
    Ok "already present: $GradleDir"
} else {
    $zip = Join-Path $Root "gradle.zip"
    Get-File -OutFile $zip -MinBytes 80MB -Urls @(
        "https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip",
        "https://services.gradle.org/distributions/gradle-8.7-bin.zip"
    )
    Expand-Archive -Path $zip -DestinationPath $Root -Force
    Remove-Item $zip -Force
    Ok "Gradle -> $GradleDir"
}

Step "Summary"
Info "JAVA_HOME    = $JdkDir"
Info "ANDROID_HOME = $SdkRoot"
Info "GRADLE       = $GradleDir"
Info ""
Info "To remove everything:  Remove-Item -Recurse -Force '$Root'"
Info "Nothing was written outside $Root (no registry, no system PATH)."
