# Runs the JVM unit tests (DSP correctness, calibration fingerprinting, etc).
. "$PSScriptRoot\common.ps1"
Invoke-StageScopeGradle testDebugUnitTest
Write-Output "Report: $(Split-Path -Parent $PSScriptRoot)\app\build\reports\tests\testDebugUnitTest\index.html"
