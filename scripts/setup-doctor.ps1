# Verifies the local toolchain and reports what's missing. Safe to run repeatedly.
. "$PSScriptRoot\common.ps1"

function Write-Check($label, $ok, $detail = "") {
    $mark = if ($ok) { "[OK]" } else { "[MISSING]" }
    Write-Output ("{0,-10} {1}  {2}" -f $mark, $label, $detail)
}

Write-Output "StageScope environment check"
Write-Output "-----------------------------"

$javaOk = $false
try {
    $javaHome = Resolve-StageScopeJavaHome
    $javaOk = $true
    Write-Check "JDK 17" $true $javaHome
} catch {
    Write-Check "JDK 17" $false "not found under `$env:LOCALAPPDATA\Android\jdk-17 and JAVA_HOME is unset"
}

$sdkOk = $false
try {
    $sdkRoot = Resolve-StageScopeSdkRoot
    $sdkOk = $true
    Write-Check "Android SDK" $true $sdkRoot
    Write-Check "  platform-tools" (Test-Path "$sdkRoot\platform-tools\adb.exe")
    Write-Check "  platform android-36" (Test-Path "$sdkRoot\platforms\android-36")
    Write-Check "  build-tools 36.0.0" (Test-Path "$sdkRoot\build-tools\36.0.0")
} catch {
    Write-Check "Android SDK" $false "not found under `$env:LOCALAPPDATA\Android\Sdk and ANDROID_HOME is unset"
}

$gradlewPath = Join-Path (Split-Path -Parent $PSScriptRoot) "gradlew.bat"
Write-Check "Gradle wrapper" (Test-Path $gradlewPath) $gradlewPath

if ($sdkOk) {
    try {
        $adb = Get-StageScopeAdbPath
        Write-Check "adb" $true $adb
        Write-Output ""
        Write-Output "Connected devices:"
        & $adb devices -l | Select-Object -Skip 1 | Where-Object { $_.Trim() -ne "" } | ForEach-Object { Write-Output "  $_" }
    } catch {
        Write-Check "adb" $false
    }
}

if ($javaOk -and $sdkOk) {
    Write-Output ""
    Write-Output "Toolchain looks complete. Try scripts\build.ps1 next."
} else {
    Write-Output ""
    Write-Output "Toolchain is incomplete. See docs\STATUS.md / README.md for how this was originally set up."
}
