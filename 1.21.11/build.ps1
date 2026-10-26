# LeafViewSender build script
# Usage: powershell -ExecutionPolicy Bypass -File build.ps1
#
# Requires: JDK 21 (C:\Program Files\Java\jdk-21 by default, override with LEAFVIEWSENDER_JDK)
# Compile classpath: lib\compile\*.jar (Bukkit/Paper API, Adventure) + lib\server\*.jar (Paper server jar, compile only)
# Nothing from lib is bundled into the output jar.

$ErrorActionPreference = 'Stop'

$Root    = Split-Path -Parent $MyInvocation.MyCommand.Path
$Version = '1.1.0'
$MC      = '1.21.11'
$Name    = "LeafViewSender-$Version-$MC.jar"

$JdkDir = $env:LEAFVIEWSENDER_JDK
if ([string]::IsNullOrWhiteSpace($JdkDir)) { $JdkDir = 'C:\Program Files\Java\jdk-21' }
$Javac = Join-Path $JdkDir 'bin\javac.exe'
$JarTool = Join-Path $JdkDir 'bin\jar.exe'

if (-not (Test-Path $Javac)) { throw "javac not found: $Javac (set LEAFVIEWSENDER_JDK)" }

$Src = Join-Path $Root 'src'
$Res = Join-Path $Root 'resources'
$Out = Join-Path $Root 'build'
$Dist = Join-Path $Root 'dist'

$libJars = @()
foreach ($dir in @((Join-Path $Root 'lib\compile'), (Join-Path $Root 'lib\server'))) {
    if (Test-Path $dir) { $libJars += Get-ChildItem $dir -Filter *.jar | ForEach-Object { $_.FullName } }
}
if ($libJars.Count -eq 0) { throw 'No jars found in lib\compile or lib\server' }
$Classpath = $libJars -join ';'

if (Test-Path $Out) { Remove-Item $Out -Recurse -Force }
New-Item -ItemType Directory -Path $Out | Out-Null
if (-not (Test-Path $Dist)) { New-Item -ItemType Directory -Path $Dist | Out-Null }

$sources = Get-ChildItem $Src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
if ($sources.Count -eq 0) { throw 'No java sources found' }

$argsList = @('-J-Duser.language=en', '-J-Duser.country=US', '-encoding', 'UTF-8', '--release', '21', '-nowarn', '-cp', $Classpath, '-d', $Out) + $sources
& $Javac @argsList
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

Copy-Item (Join-Path $Res '*') $Out -Recurse -Force

$outJar = Join-Path $Dist $Name
if (Test-Path $outJar) { Remove-Item $outJar -Force }
& $JarTool --create --file $outJar -C $Out .
if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }

$classes = (Get-ChildItem $Out -Recurse -Filter *.class | Measure-Object).Count
$size = [math]::Round((Get-Item $outJar).Length / 1KB, 1)
Write-Host ""
Write-Host "OK  $outJar"
Write-Host "    classes: $classes    size: ${size} KB"
Write-Host "    JDK:     $JdkDir"
