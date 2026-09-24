# Builds the debug APK.
. "$PSScriptRoot\common.ps1"
Invoke-StageScopeGradle assembleDebug
Write-Output "APK: $(Split-Path -Parent $PSScriptRoot)\app\build\outputs\apk\debug\app-debug.apk"
