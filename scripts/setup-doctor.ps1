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
        Write-Output "Connected devices (the watch app and the phone app are different APKs; install scripts check the kind):"
        $devices = @(Get-StageScopeDevices)
        if ($devices.Count -eq 0) { Write-Output "  none" }
        $listed = @{}
        foreach ($d in $devices) {
            $kind = if ($d.State -ne "device") { $d.State } elseif ($d.IsWatch) { "WATCH  -> scripts\install-launch.ps1" } else { "PHONE  -> scripts\install-phone.ps1" }
            # Wi-Fi debugging often lists one device under two names; say so instead of looking like two watches.
            if ($d.State -eq "device" -and $listed.ContainsKey($d.HardwareId)) { $kind = "same device as $($listed[$d.HardwareId])" }
            elseif ($d.State -eq "device") { $listed[$d.HardwareId] = $d.Serial }
            Write-Output ("  {0,-24} {1,-18} {2}" -f $d.Serial, $d.Model, $kind)
        }
    } catch {
        Write-Check "adb" $false
    }
}

$apksPresent = (Test-Path $script:WatchApk) -and (Test-Path $script:PhoneApk)
Write-Check "Watch + phone APKs built" $apksPresent $(if ($apksPresent) { "run scripts\check-signing.ps1 to confirm they share one signing key" } else { "run scripts\build.ps1" })

if ($javaOk -and $sdkOk) {
    Write-Output ""
    Write-Output "Toolchain looks complete. Try scripts\build.ps1 next."
} else {
    Write-Output ""
    Write-Output "Toolchain is incomplete. See docs\STATUS.md / README.md for how this was originally set up."
}
