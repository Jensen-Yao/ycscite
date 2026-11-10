param([string]$Jdk,[switch]$SkipBuild)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=New-Object System.Text.UTF8Encoding($false)
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $projectRoot
try {
    $metadata=cargo metadata --manifest-path native/Cargo.toml --format-version 1 --locked | ConvertFrom-Json
    if($LASTEXITCODE -ne 0){throw 'Cargo metadata failed'}
    $version=($metadata.packages | Where-Object name -eq 'ycscite-client').version
    $packageName="Ycscite-$version-windows-x64"
    $packageRoot=Join-Path $projectRoot "dist\$packageName"
    $candidates=@($Jdk,$env:YCSCITE_JDK,$env:JAVA_HOME,'D:\JDK\jdk-21-temurin')
    foreach($candidate in $candidates){
        if(-not $candidate){continue}
        if((Test-Path -LiteralPath (Join-Path $candidate 'jmods\java.base.jmod')) -and (Test-Path -LiteralPath (Join-Path $candidate 'bin\jlink.exe'))){$Jdk=$candidate;break}
    }
    if(-not $Jdk){throw 'A full JDK21+ is required. Pass -Jdk or set YCSCITE_JDK.'}
    $javaPath=Join-Path $Jdk 'bin\java.exe'
    $jarPath=Join-Path $Jdk 'bin\jar.exe'
    $jlinkPath=Join-Path $Jdk 'bin\jlink.exe'
    if(-not $SkipBuild){
        & (Join-Path $projectRoot 'tools\build-java.ps1') -Jdk $Jdk
        cargo test --manifest-path native/Cargo.toml --locked
        if($LASTEXITCODE -ne 0){throw 'Rust tests failed'}
        cargo build --release --manifest-path native/Cargo.toml --locked
        if($LASTEXITCODE -ne 0){throw 'Rust release build failed'}
    }
    New-Item -ItemType Directory -Path (Join-Path $packageRoot 'engine') -Force | Out-Null
    # A fresh class directory prevents stale or test-only classes entering the release.
    $stageRoot=Join-Path ([IO.Path]::GetTempPath()) ('ycscite-package-'+[Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $stageRoot | Out-Null
    $sourceArgs=Join-Path $stageRoot 'sources.txt'
    $sources=Get-ChildItem -LiteralPath src -Filter '*.java' -Recurse -File | Sort-Object FullName | ForEach-Object {'"'+$_.FullName.Replace('\','/')+'"'}
    [IO.File]::WriteAllLines($sourceArgs,[string[]]$sources,(New-Object System.Text.UTF8Encoding($false)))
    $ErrorActionPreference='Continue'
    & (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 --release 21 -d $stageRoot ('@'+$sourceArgs)
    $compilerExit=$LASTEXITCODE
    $ErrorActionPreference='Stop'
    if($compilerExit -ne 0){throw 'Engine compilation failed'}
    Copy-Item -LiteralPath resources\ycscite-logo.png -Destination (Join-Path $stageRoot 'ycscite-logo.png')
    $engineJar=Join-Path $packageRoot 'engine\ycscite-engine.jar'
    & $jarPath --create --file $engineJar -C $stageRoot ycscite -C $stageRoot ycscite-logo.png
    if($LASTEXITCODE -ne 0){throw 'JAR packaging failed'}
    $runtimeRoot=Join-Path $packageRoot 'runtime'
    if(-not(Test-Path -LiteralPath $runtimeRoot)){
        $ErrorActionPreference='Continue'
        & $jlinkPath --add-modules java.base,java.desktop,java.net.http,java.sql,java.xml,java.logging,jdk.crypto.ec,jdk.charsets,jdk.localedata --strip-debug --no-header-files --no-man-pages --compress=2 --output $runtimeRoot
        $runtimeExit=$LASTEXITCODE
        $ErrorActionPreference='Stop'
        if($runtimeExit -ne 0){throw 'Runtime packaging failed'}
    }
    Copy-Item -LiteralPath native\target\release\ycscite.exe -Destination (Join-Path $packageRoot 'ycscite-cli.exe') -Force
    Copy-Item -LiteralPath native\target\release\ycscite-desktop.exe -Destination (Join-Path $packageRoot 'Ycscite.exe') -Force
    Copy-Item -LiteralPath native\assets\ycscite.ico -Destination (Join-Path $packageRoot 'Ycscite.ico') -Force
    Copy-Item -LiteralPath native\assets\logo-512.png -Destination (Join-Path $packageRoot 'Ycscite-logo.png') -Force
    Get-ChildItem -LiteralPath native\target\release -Filter '*.dll' -File | ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination $packageRoot -Force}
    Copy-Item -LiteralPath native\PORTABLE-README.md -Destination (Join-Path $packageRoot '使用说明.md') -Force
    foreach($directory in @('docs','examples')){
        New-Item -ItemType Directory -Path (Join-Path $packageRoot $directory) -Force | Out-Null
        Get-ChildItem -LiteralPath $directory -File | ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $packageRoot $directory) -Force}
    }
    Set-Content -LiteralPath (Join-Path $packageRoot '打开Web工作台.cmd') -Encoding ascii -Value '@echo off','cd /d "%~dp0"','ycscite-cli.exe serve'
    $licensesRoot=Join-Path $packageRoot 'licenses'
    New-Item -ItemType Directory -Path $licensesRoot -Force | Out-Null
    Get-ChildItem -LiteralPath native\web\fonts -Filter '*-OFL.txt' -File | ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination $licensesRoot -Force}
    Copy-Item -LiteralPath THIRD-PARTY-NOTICES.md -Destination $packageRoot -Force
    $notice=@('Ycscite Rust dependency inventory. Runtime license files are in runtime/legal/.','')
    foreach($package in ($metadata.packages | Sort-Object name,version)){
        $notice+=('{0} {1}: {2}' -f $package.name,$package.version,$package.license)
        $source=Split-Path $package.manifest_path -Parent
        $licenses=Get-ChildItem -LiteralPath $source -File | Where-Object Name -Match '^(LICENSE|LICENCE|COPYING|NOTICE)'
        if($licenses){
            $destination=Join-Path $licensesRoot ($package.name+'-'+$package.version)
            New-Item -ItemType Directory -Path $destination -Force | Out-Null
            $licenses | ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination $destination -Force}
        }
    }
    Set-Content -LiteralPath (Join-Path $packageRoot 'THIRD-PARTY-NOTICES.txt') -Encoding utf8 -Value $notice
    Copy-Item -LiteralPath LICENSE -Destination $packageRoot -Force
    & (Join-Path $packageRoot 'ycscite-cli.exe') --json doctor
    if($LASTEXITCODE -ne 0){throw 'Portable runtime self-check failed'}
    $zipPath=Join-Path $projectRoot "dist\$packageName.zip"
    # Some upstream source/license files carry Unix epoch timestamps, outside ZIP's DOS date range.
    Get-ChildItem -LiteralPath $packageRoot -Recurse -Force | ForEach-Object {
        if($_.LastWriteTime.Year -lt 1980 -or $_.LastWriteTime.Year -gt 2107){$_.LastWriteTime=[datetime]'1980-01-01'}
    }
    Compress-Archive -LiteralPath $packageRoot -DestinationPath $zipPath -CompressionLevel Optimal -Force
    $hash=(Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath ($zipPath+'.sha256') -Encoding ascii -Value "$hash  $packageName.zip"
    Write-Output "Portable app: $packageRoot"
    Write-Output "ZIP: $zipPath"
    Write-Output "SHA256: $hash"
} finally {Pop-Location}
