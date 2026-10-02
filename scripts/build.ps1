# Builds the debug APKs.
# Usage: scripts\build.ps1 [-Target all|watch|phone]    (default: all)
#   watch -> app\build\outputs\apk\debug\app-debug.apk       (Wear OS app: the instruments + the assistant page)
#   phone -> phone\build\outputs\apk\debug\phone-debug.apk   (the AI companion app)
param(
    [ValidateSet("all", "watch", "phone")][string]$Target = "all"
)
. "$PSScriptRoot\common.ps1"

switch ($Target) {
    "watch" { Invoke-StageScopeGradle ":app:assembleDebug" }
    "phone" { Invoke-StageScopeGradle ":phone:assembleDebug" }
    default { Invoke-StageScopeGradle ":app:assembleDebug" ":phone:assembleDebug" }
}

if ($Target -ne "phone") { Write-Output "Watch APK: $script:WatchApk" }
if ($Target -ne "watch") { Write-Output "Phone APK: $script:PhoneApk" }
