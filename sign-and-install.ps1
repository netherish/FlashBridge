$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$apk = "C:\Users\Admin\Documents\Default Project\FlashBridge\app\build\outputs\apk\release\app-release-unsigned.apk"
$keytool = "$env:JAVA_HOME\bin\keytool.exe"
$apksigner = "C:\AndroidSdk\build-tools\35.0.0\apksigner.bat"
$zipalign = "C:\AndroidSdk\build-tools\35.0.0\zipalign.exe"
$ks = "C:\Users\Admin\Documents\Default Project\FlashBridge\flashbridge.jks"
$aligned = "C:\Users\Admin\Documents\Default Project\FlashBridge\flashbridge-aligned.apk"
$final = "C:\Users\Admin\Documents\Default Project\flashbridge.apk"

if (-not (Test-Path $ks)) {
    & $keytool -genkeypair -v -keystore $ks -alias flashbridge -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass flashbridge123 -keypass flashbridge123 `
        -dname "CN=netherish, OU=FlashBridge, O=netherish, L=NA, ST=NA, C=US" | Out-Null
    Write-Output "Keystore created."
}

& $zipalign -f 4 $apk $aligned
& $apksigner sign --ks $ks --ks-key-alias flashbridge --ks-pass pass:flashbridge123 --key-pass pass:flashbridge123 --out $final $aligned
Write-Output "Signed APK: $final"
Write-Output "SHA256: $((Get-FileHash $final -Algorithm SHA256).Hash)"

# verify
& $apksigner verify --verbose $final 2>&1 | Select-Object -First 8
