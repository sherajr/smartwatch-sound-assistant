# Runs the JVM unit tests.
# Usage: scripts\test.ps1 [-Module all|watch|phone|shared|scripts]    (default: all)
#   watch   -> DSP, ring tracker, calibration, audio coordinator, measurement evidence, assistant delivery rules
#   phone   -> provider adapters (fixture streams), orchestrator, tools, Gmail/Calendar clients, message handler
#   shared  -> the wire protocol, issue sync (CRDT), action state machine, time resolution, reports
#   scripts -> the install scripts' device selection (a fake adb; no device needed)
param(
    [ValidateSet("all", "watch", "phone", "shared", "scripts")][string]$Module = "all"
)
. "$PSScriptRoot\common.ps1"

if ($Module -in @("all", "scripts")) {
    $global:LASTEXITCODE = 0
    & "$PSScriptRoot\test-device-selection.ps1"
    if ($LASTEXITCODE -ne 0) { throw "The device-selection self-test failed." }
}

$tasks = switch ($Module) {
    "scripts" { @() }
    "watch"   { @(":app:testDebugUnitTest") }
    "phone"   { @(":phone:testDebugUnitTest") }
    "shared"  { @(":shared:test") }
    default   { @(":shared:test", ":phone:testDebugUnitTest", ":app:testDebugUnitTest") }
}
if ($tasks.Count -gt 0) { Invoke-StageScopeGradle @tasks }

$root = Split-Path -Parent $PSScriptRoot
if ($Module -in @("all", "shared")) { Write-Output "Shared report: $root\shared\build\reports\tests\test\index.html" }
if ($Module -in @("all", "phone")) { Write-Output "Phone report:  $root\phone\build\reports\tests\testDebugUnitTest\index.html" }
if ($Module -in @("all", "watch")) { Write-Output "Watch report:  $root\app\build\reports\tests\testDebugUnitTest\index.html" }
