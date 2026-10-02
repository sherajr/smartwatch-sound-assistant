# Streams logcat filtered to StageScope's own process/package.
# Usage: scripts\logs.ps1 [-Serial <adb-serial>] [-Role watch|phone]
#   With -Role, the one connected watch (or phone) is chosen for you. Without either, the only
#   connected device is used. Logs never contain API keys, tokens or message text (the app doesn't write them).
param(
    [string]$Serial = "",
    [ValidateSet("", "watch", "phone")][string]$Role = ""
)
. "$PSScriptRoot\common.ps1"

$adb = Get-StageScopeAdbPath

if (-not $Serial) {
    if ($Role) {
        $Serial = (Select-StageScopeDevice -Role $Role).Serial
    } else {
        $ready = @(Get-StageScopeDevices | Where-Object { $_.State -eq "device" })
        # One physical device may appear under several adb serials (see Get-StageScopeDevices); that is still "the only device".
        $physical = @($ready | Group-Object HardwareId)
        if ($physical.Count -eq 1) {
            $Serial = ($physical[0].Group | Sort-Object @{ Expression = { Get-StageScopeTransportRank $_.Serial } }, Serial | Select-Object -First 1).Serial
        }
        elseif ($physical.Count -gt 1) { throw "More than one device is connected; choose with -Serial or -Role watch|phone." }
    }
}

$adbArgs = @()
if ($Serial) { $adbArgs += @("-s", $Serial) }

Write-Output "Streaming logs for $($script:PackageId) $(if ($Serial) { "on $Serial " })(Ctrl+C to stop)..."
& $adb @adbArgs logcat -v time | Where-Object { $_ -match [regex]::Escape($script:PackageId) -or $_ -match "AndroidRuntime" -or $_ -match "StageScope" }
