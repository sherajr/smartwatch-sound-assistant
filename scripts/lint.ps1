# Runs Android Lint on the debug variant.
# Usage: scripts\lint.ps1 [-Module all|watch|phone]    (default: all)
param(
    [ValidateSet("all", "watch", "phone")][string]$Module = "all"
)
. "$PSScriptRoot\common.ps1"

$tasks = switch ($Module) {
    "watch" { @(":app:lintDebug") }
    "phone" { @(":phone:lintDebug") }
    default { @(":app:lintDebug", ":phone:lintDebug") }
}
Invoke-StageScopeGradle @tasks

$root = Split-Path -Parent $PSScriptRoot
if ($Module -ne "phone") { Write-Output "Watch report: $root\app\build\reports\lint-results-debug.html" }
if ($Module -ne "watch") { Write-Output "Phone report: $root\phone\build\reports\lint-results-debug.html" }
