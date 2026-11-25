param(
    [string]$Sdk = "$env:LOCALAPPDATA\SnowRebuildSdk"
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$tools = Join-Path $Sdk 'build-tools\35.0.0'
$android = Join-Path $Sdk 'platforms\android-35\android.jar'
if (!(Test-Path $android) -or !(Test-Path "$tools\aapt2.exe")) {
    throw 'Install Android SDK platform 35 and Build Tools 35.0.0 first.'
}

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program exited with code $LASTEXITCODE" }
}

$build = Join-Path $PSScriptRoot 'build'
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
$dist = Join-Path $PSScriptRoot 'dist'
New-Item -ItemType Directory -Force $classes, $dex, $dist | Out-Null
$deps = Join-Path $build 'deps'
New-Item -ItemType Directory -Force $deps | Out-Null
$opencv = Join-Path $deps 'opencv-4.12.0.aar'
if (!(Test-Path $opencv)) {
    Invoke-Checked curl.exe @('-fLsS','--retry','2','https://repo.maven.apache.org/maven2/org/opencv/opencv/4.12.0/opencv-4.12.0.aar','-o',"$opencv.download")
    Move-Item -LiteralPath "$opencv.download" -Destination $opencv
}
if ((Get-FileHash -LiteralPath $opencv -Algorithm SHA256).Hash -ne 'F71846A313388D9DA667A59BE1E921669FCF792D1BA264B3898253CA789BC3F0') {
    throw 'OpenCV AAR checksum mismatch.'
}
$cvJar = Join-Path $deps 'opencv.jar'
@'
import sys, zipfile
from pathlib import Path
with zipfile.ZipFile(sys.argv[1]) as aar:
    Path(sys.argv[2]).write_bytes(aar.read("classes.jar"))
'@ | python - $opencv $cvJar
if ($LASTEXITCODE -ne 0) { throw 'Could not extract OpenCV classes.' }
[string[]]$files = Get-ChildItem src -Recurse -Filter *.java | ForEach-Object FullName
Invoke-Checked javac (@('--release','17','-encoding','UTF-8','-classpath',"$android;$cvJar",'-d',$classes) + $files)
Invoke-Checked jar @('--create','--file',"$build\classes.jar",'-C',$classes,'.')
Invoke-Checked "$tools\d8.bat" @('--min-api','26','--lib',$android,'--output',$dex,"$build\classes.jar",$cvJar)
Invoke-Checked "$tools\aapt2.exe" @('compile','--dir','res','-o',"$build\resources.zip")
Invoke-Checked "$tools\aapt2.exe" @('link','-o',"$build\base.apk",'-I',$android,'--manifest','AndroidManifest.xml','-A','assets',"$build\resources.zip")
@'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "a", zipfile.ZIP_DEFLATED) as apk:
    apk.write(sys.argv[2], "classes.dex")
    with zipfile.ZipFile(sys.argv[3]) as aar:
        for name in aar.namelist():
            if name.startswith("jni/") and name.endswith(".so"):
                apk.writestr("lib/" + name[4:], aar.read(name))
'@ | python - "$build\base.apk" "$dex\classes.dex" $opencv
if ($LASTEXITCODE -ne 0) { throw 'Could not add classes.dex to the APK.' }
Invoke-Checked "$tools\zipalign.exe" @('-f','4',"$build\base.apk","$build\aligned.apk")
$key = Join-Path $build 'development.p12'
if (!(Test-Path $key)) {
    Invoke-Checked keytool @('-genkeypair','-storetype','PKCS12','-keystore',$key,'-storepass','android',
        '-alias','rebuild-local','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Snow Rebuild Local')
}
$apk = Join-Path $dist 'SnowRebuild.apk'
Invoke-Checked "$tools\apksigner.bat" @('sign','--ks',$key,'--ks-pass','pass:android','--out',$apk,"$build\aligned.apk")
Invoke-Checked "$tools\apksigner.bat" @('verify','--verbose',$apk)
Write-Output $apk
