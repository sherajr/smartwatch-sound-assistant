# Self-test for the device-selection helpers in common.ps1. Needs no adb, no device and no Gradle: a fake adb answers
# for a scripted set of "connected" devices. Exit code 1 if any check fails.
# Usage: scripts\test-device-selection.ps1      (also run by scripts\test.ps1 -Module scripts|all)
#
# Why this exists: the install scripts once refused with "More than one watch is connected" when Wi-Fi debugging listed a
# SINGLE Pixel Watch twice (an ip:port entry and an mDNS "adb-<serial>-xxxx._adb-tls-connect._tcp" entry). Emulators never
# show that, so it was missed. The cases below are that situation, plus the ones that must still refuse.
. "$PSScriptRoot\common.ps1"
$ErrorActionPreference = "Stop"

$script:Fake = @()

# Replaces the real lookup: returns a script block that `& $adb <args>` runs in place of adb.exe.
function Get-StageScopeAdbPath {
    return {
        $a = @($args)
        if ($a[0] -eq "devices") {
            "List of devices attached"
            foreach ($d in $script:Fake) { "$($d.Serial)`t$(if ($d.State) { $d.State } else { 'device' })" }
            return
        }
        if ($a[0] -eq "-s") {
            $d = $script:Fake | Where-Object { $_.Serial -eq $a[1] } | Select-Object -First 1
            $cmd = ($a[2..($a.Count - 1)]) -join " "
            switch -Regex ($cmd) {
                "has-feature android.hardware.type.watch" { if ($d.Watch) { "true" } else { "false" } }
                "ro.build.characteristics"                 { if ($d.Watch) { "nosdcard,watch" } else { "phone" } }
                "ro.product.model"                         { $d.Model }
                "ro.serialno"                              { $d.Hw }
            }
        }
    }
}

function Phone([string]$Serial = "FAKEPHONE0001") { @{ Serial = $Serial; Watch = $false; Model = "Pixel 10"; Hw = "FAKEPHONE0001" } }
function Watch([string]$Serial, [string]$Hw = "FAKEWATCH0001", [string]$Model = "Pixel Watch 5") { @{ Serial = $Serial; Watch = $true; Model = $Model; Hw = $Hw } }

$IP = "192.0.2.10:40000"
$MDNS = "adb-FAKEWATCH0001-abc123._adb-tls-connect._tcp"

$script:failures = 0
function Check([string]$Name, [scriptblock]$Test) {
    try { & $Test; Write-Output "[PASS] $Name" }
    catch { $script:failures++; Write-Output "[FAIL] $Name -- $($_.Exception.Message)" }
}
function Expect($Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Refuses([scriptblock]$Call, [string]$Pattern) {
    try { & $Call | Out-Null } catch { Expect ($_.Exception.Message -match $Pattern) "refused, but with: $($_.Exception.Message)"; return }
    throw "expected a refusal matching '$Pattern' but the call succeeded"
}

Check "one watch listed twice (ip:port and mDNS) is one watch, as the user's setup is" {
    $script:Fake = @((Phone), (Watch $IP), (Watch $MDNS))
    $w = Select-StageScopeDevice -Role watch 6>$null
    Expect ($w -isnot [array]) "returned more than one object (the note leaked into the return value)"
    Expect ($w.Serial -eq $IP) "chose '$($w.Serial)', wanted the explicit ip:port entry"
    $p = Select-StageScopeDevice -Role phone 6>$null
    Expect ($p.Serial -eq "FAKEPHONE0001") "phone: chose '$($p.Serial)'"
}

Check "the choice does not depend on the order adb lists the entries in" {
    $script:Fake = @((Watch $MDNS), (Phone), (Watch $IP))
    Expect ((Select-StageScopeDevice -Role watch 6>$null).Serial -eq $IP) "order changed the choice"
}

Check "a cable beats Wi-Fi when the same watch is reachable both ways" {
    $script:Fake = @((Watch $IP), (Watch "FAKEWATCH0001"), (Watch $MDNS))
    Expect ((Select-StageScopeDevice -Role watch 6>$null).Serial -eq "FAKEWATCH0001") "did not prefer the USB entry"
}

Check "two DIFFERENT watches are still refused, listed once each" {
    $script:Fake = @((Watch $IP), (Watch $MDNS), (Watch "192.0.2.11:5555" "FAKEWATCH0002" "Pixel Watch 4"))
    Refuses { Select-StageScopeDevice -Role watch 6>$null } "More than one watch"
    try { Select-StageScopeDevice -Role watch 6>$null } catch {
        Expect ($_.Exception.Message -match "Pixel Watch 5" -and $_.Exception.Message -match "Pixel Watch 4") "both watches should be named"
        Expect (([regex]::Matches($_.Exception.Message, "Pixel Watch 5")).Count -eq 1) "the duplicated watch was listed more than once"
    }
}

Check "two emulators are never merged (every emulator of one build reports the same ro.serialno)" {
    $script:Fake = @((Watch "emulator-5554" "EMULATOR37X2X12X0" "sdk_gwear"), (Watch "emulator-5556" "EMULATOR37X2X12X0" "sdk_gwear"))
    Refuses { Select-StageScopeDevice -Role watch 6>$null } "More than one watch"
}

Check "a watch emulator and a phone emulator with the same ro.serialno stay separate roles" {
    $script:Fake = @((Watch "emulator-5570" "EMULATOR37X2X12X0" "sdk_gwear"), @{ Serial = "emulator-5572"; Watch = $false; Model = "sdk_gphone"; Hw = "EMULATOR37X2X12X0" })
    Expect ((Select-StageScopeDevice -Role watch 6>$null).Serial -eq "emulator-5570") "watch"
    Expect ((Select-StageScopeDevice -Role phone 6>$null).Serial -eq "emulator-5572") "phone"
}

Check "an explicit -Serial naming either entry of the watch is accepted" {
    $script:Fake = @((Phone), (Watch $IP), (Watch $MDNS))
    Expect ((Select-StageScopeDevice -Role watch -Serial $MDNS 6>$null).Serial -eq $MDNS) "mDNS serial"
    Expect ((Select-StageScopeDevice -Role watch -Serial $IP 6>$null).Serial -eq $IP) "ip serial"
}

Check "the wrong kind of device is refused, in both directions, with or without -Serial" {
    $script:Fake = @((Phone), (Watch $IP))
    Refuses { Select-StageScopeDevice -Role phone -Serial $IP 6>$null } "is a watch, but this command installs the PHONE app"
    Refuses { Select-StageScopeDevice -Role watch -Serial "FAKEPHONE0001" 6>$null } "is not a watch, but this command installs the WATCH app"
    $script:Fake = @((Phone))
    Refuses { Select-StageScopeDevice -Role watch 6>$null } "No watch is connected"
    $script:Fake = @((Watch $IP))
    Refuses { Select-StageScopeDevice -Role phone 6>$null } "No phone is connected"
}

Check "a device that is offline is not a candidate" {
    $script:Fake = @((Phone), (Watch $IP), @{ Serial = $MDNS; Watch = $true; Model = "Pixel Watch 5"; Hw = "FAKEWATCH0001"; State = "offline" })
    Expect ((Select-StageScopeDevice -Role watch 6>$null).Serial -eq $IP) "offline entry interfered"
}

if ($script:failures -gt 0) { Write-Output "$($script:failures) check(s) FAILED"; exit 1 }
Write-Output "All device-selection checks passed."
exit 0
