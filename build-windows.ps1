param([string]$Version)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString() -ne 'X64') {
    throw 'This script packages the Windows x64 build.'
}
if (!$Version) {
    [xml]$manifest = Get-Content AndroidManifest.xml -Raw
    $Version = $manifest.DocumentElement.GetAttribute('versionName','http://schemas.android.com/apk/res/android')
}

function Invoke-Checked {
    param([string]$Program,[string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program exited with code $LASTEXITCODE" }
}

$build = Join-Path $PSScriptRoot 'build'
$dist = Join-Path $PSScriptRoot 'dist'
New-Item -ItemType Directory -Force $build,$dist | Out-Null
$stage = Join-Path $build ("windows-"+[guid]::NewGuid().ToString('N'))
$classes = Join-Path $stage 'classes'
$inputDir = Join-Path $stage 'input'
$outputDir = Join-Path $stage 'output'
New-Item -ItemType Directory -Force $classes,$inputDir,$outputDir | Out-Null
try {
    $sources = @('src/snow/BoardVision.java','src/snow/Recognizer.java','src/snow/Solver.java',
        'desktop/src/snow/WindowsApp.java')
    Invoke-Checked javac (@('--release','17','-encoding','UTF-8','-d',$classes) + $sources)
    Copy-Item -LiteralPath 'assets/shapes.txt','res/drawable-nodpi/app_icon.png' -Destination $classes
    Invoke-Checked jar @('--create','--file',"$inputDir\SnowRebuild.jar",'-C',$classes,'.')
    $icon = Join-Path $stage 'SnowRebuild.ico'
    Invoke-Checked python @('-c','from PIL import Image; import sys; Image.open(sys.argv[1]).save(sys.argv[2],sizes=[(16,16),(32,32),(48,48),(128,128),(256,256)])',
        'res/drawable-nodpi/app_icon.png',$icon)
    Invoke-Checked jpackage @('--type','app-image','--name','SnowRebuild','--app-version',$Version,
        '--input',$inputDir,'--dest',$outputDir,'--main-jar','SnowRebuild.jar','--main-class','snow.rebuild.WindowsApp',
        '--icon',$icon,'--add-modules','java.desktop','--jlink-options','--strip-debug --no-man-pages --no-header-files')
    $archive = Join-Path $dist "SnowRebuild-$Version-windows-x64.zip"
    @'
from pathlib import Path
import sys
from zipfile import ZipFile, ZIP_DEFLATED

output = Path(sys.argv[1])
application = output / "SnowRebuild"
assert (application / "SnowRebuild.exe").is_file()
with ZipFile(sys.argv[2], "w", ZIP_DEFLATED, compresslevel=6) as package:
    for path in sorted(application.rglob("*")):
        if path.is_file():
            package.write(path, path.relative_to(output))
with ZipFile(sys.argv[2]) as package:
    assert package.testzip() is None
'@ | python - $outputDir $archive
    if ($LASTEXITCODE -ne 0) { throw 'Windows ZIP verification failed.' }
    Write-Output $archive
} finally {
    $root = (Resolve-Path -LiteralPath $build).Path
    $target = (Resolve-Path -LiteralPath $stage).Path
    if ([IO.Path]::GetDirectoryName($target) -eq $root -and
        [IO.Path]::GetFileName($target) -match '^windows-[a-f0-9]{32}$') {
        Remove-Item -LiteralPath $target -Recurse -Force
    }
}
