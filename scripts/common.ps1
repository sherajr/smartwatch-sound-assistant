# Shared environment resolution for StageScope's build scripts.
# Dot-sourced by the other scripts in this folder; not meant to be run directly.

$ErrorActionPreference = "Stop"
$script:ProjectRoot = Split-Path -Parent $PSScriptRoot

if ($env:WSL_DISTRO_NAME) {
    Write-Warning "Running inside WSL. These scripts expect the Windows-side JDK/SDK installed under `$env:LOCALAPPDATA\Android. Run them via 'powershell.exe' (not a separate Linux toolchain) to keep paths consistent."
}

function Resolve-StageScopeJavaHome {
    if ($env:JAVA_HOME -and (Test-Path $env:JAVA_HOME)) { return $env:JAVA_HOME }
    $fallback = "$env:LOCALAPPDATA\Android\jdk-17"
    if (Test-Path $fallback) { return $fallback }
    throw "JDK 17 not found. Set JAVA_HOME, or run scripts\setup-doctor.ps1 first."
}

function Resolve-StageScopeSdkRoot {
    if ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) { return $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) { return $env:ANDROID_SDK_ROOT }
    $fallback = "$env:LOCALAPPDATA\Android\Sdk"
    if (Test-Path $fallback) { return $fallback }
    throw "Android SDK not found. Set ANDROID_HOME, or run scripts\setup-doctor.ps1 first."
}

function Initialize-StageScopeEnvironment {
    $env:JAVA_HOME = Resolve-StageScopeJavaHome
    $sdkRoot = Resolve-StageScopeSdkRoot
    $env:ANDROID_HOME = $sdkRoot
    $env:ANDROID_SDK_ROOT = $sdkRoot

    $localProps = Join-Path $script:ProjectRoot "local.properties"
    if (-not (Test-Path $localProps)) {
        $escaped = $sdkRoot -replace '\\', '\\\\'
        $escaped = $escaped -replace ':', '\:'
        Set-Content -Path $localProps -Value "sdk.dir=$escaped" -Encoding ascii
    }
    return $sdkRoot
}

function Get-StageScopeAdbPath {
    $sdkRoot = Resolve-StageScopeSdkRoot
    $adb = Join-Path $sdkRoot "platform-tools\adb.exe"
    if (-not (Test-Path $adb)) { throw "adb.exe not found at $adb" }
    return $adb
}

function Invoke-StageScopeGradle {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArgs)
    Initialize-StageScopeEnvironment | Out-Null
    $gradlew = Join-Path $script:ProjectRoot "gradlew.bat"
    if (-not (Test-Path $gradlew)) { throw "gradlew.bat not found at $gradlew" }
    & $gradlew -p $script:ProjectRoot @GradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
}

# The WATCH app and the PHONE app deliberately share one applicationId (the Wear Data Layer only connects two
# apps with the same package name AND the same signing certificate). They are different APKs for different
# devices, so every install script below checks what kind of device it is talking to first.
$script:PackageId = "com.peaceantz.stagescope"
$script:WatchApk = Join-Path $script:ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
$script:PhoneApk = Join-Path $script:ProjectRoot "phone\build\outputs\apk\debug\phone-debug.apk"
$script:WatchActivity = "com.peaceantz.stagescope/.MainActivity"
$script:PhoneActivity = "com.peaceantz.stagescope/com.peaceantz.stagescope.phone.MainActivity"

# Every adb device with its state, model and whether it is a watch (Wear OS) or not.
function Get-StageScopeDevices {
    $adb = Get-StageScopeAdbPath
    $lines = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_.Trim() -ne "" -and $_ -notmatch "^\* " })
    $result = @()
    foreach ($line in $lines) {
        $parts = $line -split "\s+"
        $serial = $parts[0]
        $state = if ($parts.Count -gt 1) { $parts[1] } else { "unknown" }
        $isWatch = $false
        $model = ""
        # Which physical device this entry is. Wi-Fi debugging routinely lists ONE watch twice -- once as "ip:port"
        # (from `adb connect`) and once as an mDNS name ("adb-<serial>-xxxx._adb-tls-connect._tcp") -- and the hardware
        # serial is what tells that apart from two different watches. Emulators are never merged: every emulator from
        # one emulator build reports the same ro.serialno, so for them the adb serial itself is the identity.
        $hardwareId = $serial
        if ($state -eq "device") {
            $feature = (& $adb -s $serial shell pm has-feature android.hardware.type.watch 2>$null | Out-String).Trim()
            $characteristics = (& $adb -s $serial shell getprop ro.build.characteristics 2>$null | Out-String).Trim()
            $isWatch = ($feature -eq "true") -or ($characteristics -match "watch")
            $model = (& $adb -s $serial shell getprop ro.product.model 2>$null | Out-String).Trim()
            if ($serial -notmatch "^emulator-") {
                $hw = (& $adb -s $serial shell getprop ro.serialno 2>$null | Out-String).Trim()
                if ($hw -and $hw -ne "unknown") { $hardwareId = $hw }
            }
        }
        $result += [pscustomobject]@{ Serial = $serial; State = $state; IsWatch = $isWatch; Model = $model; HardwareId = $hardwareId }
    }
    # Callers wrap the call in @(...) so zero or one device still behaves as a list.
    return $result
}

# How an adb serial reaches the device, best first: a cable or emulator (0), an explicit `adb connect ip:port` (1),
# or an mDNS auto-connect entry (2). Used only to pick ONE entry when adb lists the same device several ways.
function Get-StageScopeTransportRank {
    param([string]$Serial)
    if ($Serial -match "^adb-.+\._adb-tls-(connect|pairing)\._tcp") { return 2 }
    if ($Serial -match "^\d{1,3}(\.\d{1,3}){3}:\d+$") { return 1 }
    return 0
}

# Picks the device to install on. Refuses a device of the wrong kind (never installs the watch APK on a phone or the
# phone APK on a watch) and refuses to guess when several DIFFERENT devices of that kind exist. Several adb entries
# for the SAME physical device (see Get-StageScopeDevices) are one candidate, not several.
function Select-StageScopeDevice {
    param(
        [Parameter(Mandatory = $true)][ValidateSet("watch", "phone")][string]$Role,
        [string]$Serial = ""
    )
    $all = @(Get-StageScopeDevices)
    $ready = @($all | Where-Object { $_.State -eq "device" })
    $wantWatch = ($Role -eq "watch")

    if ($Serial) {
        $d = $ready | Where-Object { $_.Serial -eq $Serial } | Select-Object -First 1
        if (-not $d) {
            $state = ($all | Where-Object { $_.Serial -eq $Serial } | Select-Object -First 1).State
            throw "Device '$Serial' is not ready (state: $(if ($state) { $state } else { 'not connected' })). Check the cable / Wi-Fi debugging and accept the debugging prompt on the device."
        }
        if ($d.IsWatch -ne $wantWatch) {
            $kind = if ($d.IsWatch) { "a watch" } else { "not a watch" }
            throw "Refusing: '$Serial' ($($d.Model)) is $kind, but this command installs the $($Role.ToUpper()) app. The watch and phone apps share one package name, so installing the wrong one would replace the right one."
        }
        return $d
    }

    $matching = @($ready | Where-Object { $_.IsWatch -eq $wantWatch })
    if ($matching.Count -eq 0) {
        $seen = if ($all.Count -eq 0) { "nothing" } else { ($all | ForEach-Object { "$($_.Serial) [$($_.State)$(if ($_.State -eq 'device') { if ($_.IsWatch) { ', watch' } else { ', phone' } })]" }) -join "; " }
        $how = if ($wantWatch) { "Turn on Wi-Fi debugging on the watch and run scripts\pair-wireless.ps1 (or use USB)" } else { "Enable USB debugging on the phone and plug it in (accept the prompt on the phone)" }
        throw "No $Role is connected (adb sees: $seen). $how, then retry."
    }
    # One entry per physical device, preferring the most direct way adb reaches it.
    $physical = @($matching | Group-Object HardwareId | ForEach-Object {
            $_.Group | Sort-Object @{ Expression = { Get-StageScopeTransportRank $_.Serial } }, Serial | Select-Object -First 1
        })
    if ($physical.Count -gt 1) {
        throw "More than one $Role is connected ($(($physical | ForEach-Object { "$($_.Serial) $($_.Model)" }) -join '; ')). Choose one with -Serial <serial>."
    }
    $chosen = $physical[0]
    $aliases = @($matching | Where-Object { $_.HardwareId -eq $chosen.HardwareId -and $_.Serial -ne $chosen.Serial } | ForEach-Object { $_.Serial })
    # Write-Host, not Write-Output: this function's output is its return value.
    if ($aliases.Count -gt 0) {
        Write-Host "adb lists this $Role more than once ($($chosen.Serial), $($aliases -join ', ')); using $($chosen.Serial)."
    }
    return $chosen
}

# Installs an APK with `adb install -r` and explains a signature mismatch WITHOUT uninstalling anything.
function Install-StageScopeApk {
    param([string]$Serial, [string]$Apk, [string]$Activity, [string]$Label)
    $adb = Get-StageScopeAdbPath
    if (-not (Test-Path $Apk)) { throw "$Label APK not found at $Apk. Run scripts\build.ps1 first." }
    Write-Output "Installing the $Label app on $Serial ..."
    $out = & $adb -s $Serial install -r $Apk 2>&1 | Out-String
    Write-Output $out.Trim()
    if ($out -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match") {
        throw "The $Label app on $Serial is signed with a different key than this build. NOT uninstalling it automatically (that would delete its data, e.g. saved keys and logs). Build with the same signing key as the installed app, or uninstall it yourself if you are sure. See docs\AI_SETUP.md -> Signing."
    }
    if ($LASTEXITCODE -ne 0 -or $out -notmatch "Success") { throw "adb install failed for the $Label app." }
    & $adb -s $Serial shell am start -n $Activity | Out-Null
    Write-Output "Installed and launched the $Label app on $Serial."
}
