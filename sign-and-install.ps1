# Sign and install the locally built release APK.
#
# Signing credentials are NEVER stored in this file. They are read from
# environment variables, or from an untracked signing.properties file in the
# project root (gitignored). Example signing.properties:
#
#   keystore=C:\path\to\your\release.jks
#   alias=your_alias
#   storePass=...
#   keyPass=...
#
# Environment variables take precedence:
#   FB_KEYSTORE, FB_KEY_ALIAS, FB_STORE_PASS, FB_KEY_PASS

$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

$root = "C:\Users\Admin\Documents\Default Project\FlashBridge"
# Product flavors: full (all advanced operations) or play (standard build).
$flavor = if ($env:FB_FLAVOR) { $env:FB_FLAVOR } else { "full" }
$apk = "$root\app\build\outputs\apk\$flavor\release\app-$flavor-release-unsigned.apk"
$apksigner = "C:\AndroidSdk\build-tools\35.0.0\apksigner.bat"
$zipalign = "C:\AndroidSdk\build-tools\35.0.0\zipalign.exe"
$aligned = "$root\flashbridge-aligned.apk"
$final = "C:\Users\Admin\Documents\Default Project\flashbridge.apk"

# ---- Load signing configuration (env > local properties file) --------------
$cfg = @{}
$props = Join-Path $root "signing.properties"
if (Test-Path $props) {
    foreach ($line in Get-Content $props) {
        if ($line -match '^\s*([^#=]+?)\s*=\s*(.*?)\s*$') {
            $cfg[$Matches[1].Trim()] = $Matches[2].Trim()
        }
    }
}

$keystore = if ($env:FB_KEYSTORE) { $env:FB_KEYSTORE } else { $cfg["keystore"] }
$alias = if ($env:FB_KEY_ALIAS) { $env:FB_KEY_ALIAS } else { $cfg["alias"] }
$storePass = if ($env:FB_STORE_PASS) { $env:FB_STORE_PASS } else { $cfg["storePass"] }
$keyPass = if ($env:FB_KEY_PASS) { $env:FB_KEY_PASS } else { $cfg["keyPass"] }

$missing = @()
if (-not $keystore) { $missing += "FB_KEYSTORE / keystore" }
if (-not $alias) { $missing += "FB_KEY_ALIAS / alias" }
if (-not $storePass) { $missing += "FB_STORE_PASS / storePass" }
if (-not $keyPass) { $missing += "FB_KEY_PASS / keyPass" }
if ($missing.Count -gt 0) {
    throw "Signing is not configured. Set these values in environment variables or signing.properties: $($missing -join ', ')"
}
if (-not (Test-Path $keystore)) { throw "Keystore not found: $keystore" }
if (-not (Test-Path $apk)) { throw "Unsigned APK not found: $apk (run Gradle assembleRelease first)" }

# ---- Sign ------------------------------------------------------------------
& $zipalign -f 4 $apk $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

& $apksigner sign --ks $keystore --ks-key-alias $alias `
    --ks-pass pass:$storePass --key-pass pass:$keyPass --out $final $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Output "Signed APK: $final"
Write-Output "SHA256: $((Get-FileHash $final -Algorithm SHA256).Hash)"

# ---- Verify ----------------------------------------------------------------
# Capture first: piping directly to Select-Object -First can break apksigner's
# stdout and produce a false non-zero exit code.
$verifyOutput = & $apksigner verify --verbose $final 2>&1
$verifyOutput | Select-Object -First 8
if ($LASTEXITCODE -ne 0) { throw "APK verification failed" }

# ---- Install on the connected device ---------------------------------------
$adbCmd = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adbCmd -and (Test-Path "C:\AndroidSdk\platform-tools\adb.exe")) {
    $adbCmd = "C:\AndroidSdk\platform-tools\adb.exe"
}
if ($adbCmd) {
    $devices = & $adbCmd devices | Select-String "device$"
    if ($devices) {
        & $adbCmd install -r $final
        Write-Output "Installed on connected device."
    } else {
        Write-Output "No ADB device connected; skipped install."
    }
} else {
    Write-Output "adb not found; skipped install."
}
