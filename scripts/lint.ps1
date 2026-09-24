# Runs Android Lint on the debug variant.
. "$PSScriptRoot\common.ps1"
Invoke-StageScopeGradle lintDebug
Write-Output "Report: $(Split-Path -Parent $PSScriptRoot)\app\build\reports\lint-results-debug.html"
