# Shared environment resolution for StageScope's build scripts.
# Dot-sourced by the other scripts in this folder; not meant to be run directly.

$ErrorActionPreference = "Stop"
$script:ProjectRoot = Split-Path -Parent $PSScriptRoot

if ($env:WSL_DISTRO_NAME) {
    Write-Warning "Running inside WSL. These scripts expect the Windows-side JDK/SDK installed under `$env:LOCALAPPDATA\Android. Run them via 'powershell.exe' (not a separate Linux toolchain) to keep paths consistent."
}

function Resolve-StageScopeJavaHome {
    if ($env:JAVA_HOME -and (Test-Path $env:JAVA_HOME)) { return $env:JAVA_HOME }
    $fallback = "$env:LOCALAPPDATA\Android\jdk-17"
    if (Test-Path $fallback) { return $fallback }
    throw "JDK 17 not found. Set JAVA_HOME, or run scripts\setup-doctor.ps1 first."
}

function Resolve-StageScopeSdkRoot {
    if ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) { return $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) { return $env:ANDROID_SDK_ROOT }
    $fallback = "$env:LOCALAPPDATA\Android\Sdk"
    if (Test-Path $fallback) { return $fallback }
    throw "Android SDK not found. Set ANDROID_HOME, or run scripts\setup-doctor.ps1 first."
}

function Initialize-StageScopeEnvironment {
    $env:JAVA_HOME = Resolve-StageScopeJavaHome
    $sdkRoot = Resolve-StageScopeSdkRoot
    $env:ANDROID_HOME = $sdkRoot
    $env:ANDROID_SDK_ROOT = $sdkRoot

    $localProps = Join-Path $script:ProjectRoot "local.properties"
    if (-not (Test-Path $localProps)) {
        $escaped = $sdkRoot -replace '\\', '\\\\'
        $escaped = $escaped -replace ':', '\:'
        Set-Content -Path $localProps -Value "sdk.dir=$escaped" -Encoding ascii
    }
    return $sdkRoot
}

function Get-StageScopeAdbPath {
    $sdkRoot = Resolve-StageScopeSdkRoot
    $adb = Join-Path $sdkRoot "platform-tools\adb.exe"
    if (-not (Test-Path $adb)) { throw "adb.exe not found at $adb" }
    return $adb
}

function Invoke-StageScopeGradle {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArgs)
    Initialize-StageScopeEnvironment | Out-Null
    $gradlew = Join-Path $script:ProjectRoot "gradlew.bat"
    if (-not (Test-Path $gradlew)) { throw "gradlew.bat not found at $gradlew" }
    & $gradlew -p $script:ProjectRoot @GradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
}

$script:PackageId = "com.peaceantz.stagescope"
