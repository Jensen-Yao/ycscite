param([string]$Jdk,[switch]$SkipTests)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=New-Object System.Text.UTF8Encoding($false)
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$candidates=New-Object System.Collections.Generic.List[string]
if($Jdk){$candidates.Add($Jdk)}
if($env:YCSCITE_JDK){$candidates.Add($env:YCSCITE_JDK)}
if($env:JAVA_HOME){$candidates.Add($env:JAVA_HOME)}
$candidates.Add('D:\JDK\jdk-21-temurin')
$pathCompiler=Get-Command javac.exe -ErrorAction SilentlyContinue
if($pathCompiler){$candidates.Add((Split-Path (Split-Path $pathCompiler.Source -Parent) -Parent))}
$selectedJdk=$null
foreach($candidate in $candidates){
    $compiler=Join-Path $candidate 'bin\javac.exe'
    if(-not(Test-Path -LiteralPath $compiler)){continue}
    $probeInfo=New-Object System.Diagnostics.ProcessStartInfo
    $probeInfo.FileName=$compiler
    $probeInfo.Arguments='-version'
    $probeInfo.UseShellExecute=$false
    $probeInfo.CreateNoWindow=$true
    $probeInfo.RedirectStandardOutput=$true
    $probeInfo.RedirectStandardError=$true
    $probe=New-Object System.Diagnostics.Process
    $probe.StartInfo=$probeInfo
    [void]$probe.Start()
    $versionText=$probe.StandardOutput.ReadToEnd()+$probe.StandardError.ReadToEnd()
    $probe.WaitForExit()
    if($versionText -match 'javac\s+(\d+)' -and [int]$Matches[1] -ge 21){$selectedJdk=$candidate;break}
}
if(-not $selectedJdk){throw 'Java 21+ JDK is required. Set YCSCITE_JDK or JAVA_HOME, or pass -Jdk to tools/build-java.ps1.'}
$argFile=[IO.Path]::GetTempFileName()
Push-Location $projectRoot
try{
    New-Item -ItemType Directory -Path out -Force | Out-Null
    $sources=Get-ChildItem -LiteralPath src,test -Filter '*.java' -Recurse -File | Sort-Object FullName | ForEach-Object {'"'+$_.FullName.Replace('\','/')+'"'}
    [IO.File]::WriteAllLines($argFile,[string[]]$sources,(New-Object System.Text.UTF8Encoding($false)))
    $ErrorActionPreference='Continue'
    & (Join-Path $selectedJdk 'bin\javac.exe') -encoding UTF-8 --release 21 -d out ('@'+$argFile)
    $compilerExit=$LASTEXITCODE
    $ErrorActionPreference='Stop'
    if($compilerExit -ne 0){throw 'Java compilation failed'}
    Copy-Item -LiteralPath resources\ycscite-logo.png -Destination out\ycscite-logo.png -Force
    Write-Output 'BUILD OK'
    if(-not $SkipTests){
        $ErrorActionPreference='Continue'
        & (Join-Path $selectedJdk 'bin\java.exe') '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp out ycscite.TestAll
        $testExit=$LASTEXITCODE
        $ErrorActionPreference='Stop'
        if($testExit -ne 0){throw 'Java regression tests failed'}
    }
}finally{
    Remove-Item -LiteralPath $argFile -Force -ErrorAction SilentlyContinue
    Pop-Location
}
