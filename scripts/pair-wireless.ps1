# Pairs and connects to a Wear OS watch over Wi-Fi debugging.
# Get these values from the watch: Settings > Developer options > Wireless debugging.
# Usage:
#   scripts\pair-wireless.ps1 -PairIp 192.168.1.23 -PairPort 41235 -PairCode 123456 -ConnectPort 41234
param(
    [Parameter(Mandatory = $true)][string]$PairIp,
    [Parameter(Mandatory = $true)][int]$PairPort,
    [Parameter(Mandatory = $true)][string]$PairCode,
    [Parameter(Mandatory = $true)][int]$ConnectPort
)
. "$PSScriptRoot\common.ps1"

$adb = Get-StageScopeAdbPath

Write-Output "Pairing with $PairIp`:$PairPort ..."
& $adb pair "$PairIp`:$PairPort" $PairCode
if ($LASTEXITCODE -ne 0) { throw "adb pair failed" }

Write-Output "Connecting on $PairIp`:$ConnectPort ..."
& $adb connect "$PairIp`:$ConnectPort"
if ($LASTEXITCODE -ne 0) { throw "adb connect failed" }

& $adb devices -l
