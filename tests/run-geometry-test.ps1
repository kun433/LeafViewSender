# Offline geometry assertions for LeafViewSender.
# Verifies the yaw -> allowed-chunk mapping (front/back/left/right), near buffer,
# 360-degree off switch, angle wrapping and chunk key packing.
#
# Usage: powershell -ExecutionPolicy Bypass -File tests\run-geometry-test.ps1
# Requires: JDK 21 and a built plugin (run 1.21.11\build.ps1 first).

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$JdkDir = $env:LEAFVIEWSENDER_JDK
if ([string]::IsNullOrWhiteSpace($JdkDir)) { $JdkDir = 'C:\Program Files\Java\jdk-21' }
$Javac = Join-Path $JdkDir 'bin\javac.exe'
$Java = Join-Path $JdkDir 'bin\java.exe'

$buildDir = Join-Path $Root '1.21.11\build'
if (-not (Test-Path $buildDir)) { throw "Plugin not built yet: $buildDir (run 1.21.11\build.ps1)" }

$jars = Get-ChildItem (Join-Path $Root '1.21.11\lib\compile') -Filter *.jar | ForEach-Object { $_.FullName }
if ($jars.Count -eq 0) { throw 'No jars found in 1.21.11\lib\compile' }

$cp = (@($buildDir) + $jars) -join ';'
$out = Join-Path $Root 'tests\out'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path $out | Out-Null

& $Javac -encoding UTF-8 --release 21 -nowarn -cp $cp -d $out (Join-Path $Root 'tests\GeometryTest.java')
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

& $Java "-Dfile.encoding=UTF-8" -cp ($out + ';' + $cp) GeometryTest
exit $LASTEXITCODE
