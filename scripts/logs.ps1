# Streams logcat filtered to StageScope's own process/package.
# Usage: scripts\logs.ps1 [-Serial <adb-serial>]
param(
    [string]$Serial = ""
)
. "$PSScriptRoot\common.ps1"

$adb = Get-StageScopeAdbPath

if (-not $Serial) {
    $deviceLines = & $adb devices | Select-Object -Skip 1 | Where-Object { $_.Trim() -ne "" -and $_ -notmatch "^\* " }
    $deviceSerials = $deviceLines | ForEach-Object { ($_ -split "\s+")[0] }
    if ($deviceSerials.Count -eq 1) { $Serial = $deviceSerials[0] }
}

$adbArgs = @()
if ($Serial) { $adbArgs += @("-s", $Serial) }

Write-Output "Streaming logs for $($script:PackageId) (Ctrl+C to stop)..."
& $adb @adbArgs logcat -v time | Where-Object { $_ -match [regex]::Escape($script:PackageId) -or $_ -match "AndroidRuntime" }
