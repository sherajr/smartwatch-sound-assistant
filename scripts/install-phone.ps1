# Installs the PHONE companion app on a connected Android phone and launches it.
# Usage: scripts\install-phone.ps1 [-Serial <adb-serial>]
# It refuses to install on a watch (the watch app has its own script: install-launch.ps1).
param(
    [string]$Serial = ""
)
. "$PSScriptRoot\common.ps1"

if (-not (Test-Path $script:PhoneApk)) {
    Write-Output "No phone APK found, building first..."
    Invoke-StageScopeGradle ":phone:assembleDebug"
}

$device = Select-StageScopeDevice -Role phone -Serial $Serial
Write-Output "Target phone: $($device.Serial) ($($device.Model))"
Install-StageScopeApk -Serial $device.Serial -Apk $script:PhoneApk -Activity $script:PhoneActivity -Label "phone"
