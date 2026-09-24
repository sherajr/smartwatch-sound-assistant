# Installs the debug APK on a specific device/emulator and launches it.
# Usage: scripts\install-launch.ps1 [-Serial <adb-serial>]
param(
    [string]$Serial = ""
)
. "$PSScriptRoot\common.ps1"

$adb = Get-StageScopeAdbPath
$projectRoot = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"

if (-not (Test-Path $apk)) {
    Write-Output "No APK found, building first..."
    Invoke-StageScopeGradle assembleDebug
}

$deviceLines = & $adb devices | Select-Object -Skip 1 | Where-Object { $_.Trim() -ne "" -and $_ -notmatch "^\* " }
$deviceSerials = $deviceLines | ForEach-Object { ($_ -split "\s+")[0] }

if ($deviceSerials.Count -eq 0) {
    throw "No adb devices found. Connect the watch (USB or wireless debugging) and retry, or run scripts\pair-wireless.ps1."
}

if (-not $Serial) {
    if ($deviceSerials.Count -gt 1) {
        Write-Output "Multiple devices found; specify one with -Serial:"
        $deviceSerials | ForEach-Object { Write-Output "  $_" }
        throw "Ambiguous target device."
    }
    $Serial = $deviceSerials[0]
}

Write-Output "Target device: $Serial"
& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed" }

& $adb -s $Serial shell am start -n "com.peaceantz.stagescope/.MainActivity"
if ($LASTEXITCODE -ne 0) { throw "Failed to launch MainActivity" }

Write-Output "Installed and launched on $Serial."
