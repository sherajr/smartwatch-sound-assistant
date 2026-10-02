# Installs the WATCH app on a connected Wear OS watch and launches it.
# Usage: scripts\install-launch.ps1 [-Serial <adb-serial>]
# It refuses to install on anything that isn't a watch (the phone app has its own script: install-phone.ps1).
param(
    [string]$Serial = ""
)
. "$PSScriptRoot\common.ps1"

if (-not (Test-Path $script:WatchApk)) {
    Write-Output "No watch APK found, building first..."
    Invoke-StageScopeGradle ":app:assembleDebug"
}

$device = Select-StageScopeDevice -Role watch -Serial $Serial
Write-Output "Target watch: $($device.Serial) ($($device.Model))"
Install-StageScopeApk -Serial $device.Serial -Apk $script:WatchApk -Activity $script:WatchActivity -Label "watch"
