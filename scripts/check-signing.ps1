# Verifies that the watch APK and the phone APK are signed with the SAME certificate, and prints the
# fingerprints you need for Google Cloud (docs\AI_SETUP.md).
#
# Why it matters: the Wear Data Layer only connects a watch app and a phone app that have the same package name AND
# the same signing certificate. If they differ, the two apps silently never find each other.
# Usage: scripts\check-signing.ps1 [-WatchApk <path>] [-PhoneApk <path>]
param(
    [string]$WatchApk = "",
    [string]$PhoneApk = ""
)
. "$PSScriptRoot\common.ps1"

if (-not $WatchApk) { $WatchApk = $script:WatchApk }
if (-not $PhoneApk) { $PhoneApk = $script:PhoneApk }
foreach ($apk in @($WatchApk, $PhoneApk)) {
    if (-not (Test-Path $apk)) { throw "APK not found: $apk. Run scripts\build.ps1 first." }
}

Initialize-StageScopeEnvironment | Out-Null
$sdk = Resolve-StageScopeSdkRoot
$apksigner = Get-ChildItem (Join-Path $sdk "build-tools") -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    ForEach-Object { Join-Path $_.FullName "apksigner.bat" } |
    Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $apksigner) { throw "apksigner.bat not found under $sdk\build-tools. Install the Android SDK build-tools." }

function Get-Cert($apk) {
    $text = & $apksigner verify --print-certs $apk 2>&1 | Out-String
    $sha256 = ([regex]::Match($text, "certificate SHA-256 digest:\s*([0-9a-fA-F]+)")).Groups[1].Value
    $sha1 = ([regex]::Match($text, "certificate SHA-1 digest:\s*([0-9a-fA-F]+)")).Groups[1].Value
    $dn = ([regex]::Match($text, "certificate DN:\s*(.+)")).Groups[1].Value.Trim()
    if (-not $sha256) { throw "Could not read the signing certificate of $apk`n$text" }
    return [pscustomobject]@{ Sha256 = $sha256.ToLower(); Sha1 = $sha1.ToLower(); Subject = $dn }
}

function Format-Fingerprint($hex) { ($hex -split "(?<=\G..)(?=.)" | ForEach-Object { $_.ToUpper() }) -join ":" }

$watch = Get-Cert $WatchApk
$phone = Get-Cert $PhoneApk

Write-Output "Watch APK : $WatchApk"
Write-Output "Phone APK : $PhoneApk"
Write-Output "Certificate subject : $($phone.Subject)"
Write-Output "SHA-1   (for the Google Cloud Android OAuth client) : $(Format-Fingerprint $phone.Sha1)"
Write-Output "SHA-256 : $(Format-Fingerprint $phone.Sha256)"
Write-Output ""
if ($watch.Sha256 -eq $phone.Sha256) {
    Write-Output "[OK] Both APKs are signed with the same certificate. The Wear Data Layer will connect them."
} else {
    Write-Output "[MISMATCH] The APKs use DIFFERENT signing certificates:"
    Write-Output "  watch SHA-256: $(Format-Fingerprint $watch.Sha256)"
    Write-Output "  phone SHA-256: $(Format-Fingerprint $phone.Sha256)"
    Write-Output "The watch and phone apps will not find each other until both are signed with one key (docs\AI_SETUP.md -> Signing)."
    exit 1
}
