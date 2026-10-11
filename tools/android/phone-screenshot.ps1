param([string]$OutputPath='captures/phone/current.png')
$ErrorActionPreference='Stop'
$jpqRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$jpqTarget=[IO.Path]::GetFullPath((Join-Path $jpqRoot $OutputPath))
if(-not $jpqTarget.StartsWith($jpqRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Screenshot must stay in JPQ workspace.' }
New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($jpqTarget)) -Force | Out-Null
$jpqInfo=[Diagnostics.ProcessStartInfo]::new()
$jpqInfo.FileName=Join-Path $jpqRoot '.android-tools/sdk/platform-tools/adb.exe'
$jpqInfo.ArgumentList.Add('exec-out'); $jpqInfo.ArgumentList.Add('screencap'); $jpqInfo.ArgumentList.Add('-p')
$jpqInfo.UseShellExecute=$false; $jpqInfo.CreateNoWindow=$true; $jpqInfo.RedirectStandardOutput=$true
$jpqProcess=[Diagnostics.Process]::Start($jpqInfo)
$jpqStream=[IO.File]::Create($jpqTarget)
try { $jpqProcess.StandardOutput.BaseStream.CopyTo($jpqStream) } finally { $jpqStream.Dispose() }
$jpqProcess.WaitForExit()
if($jpqProcess.ExitCode -ne 0) { throw 'Screenshot failed' }
# Some multi-display Android builds prepend a display warning to exec-out bytes.
$jpqBytes=[IO.File]::ReadAllBytes($jpqTarget)
$jpqSignature=[byte[]](137,80,78,71,13,10,26,10)
$jpqOffset=-1
for($jpqIndex=0;$jpqIndex -lt [Math]::Min(4096,$jpqBytes.Length-8);$jpqIndex++) {
    $jpqMatches=$true
    for($jpqByte=0;$jpqByte -lt 8;$jpqByte++) { if($jpqBytes[$jpqIndex+$jpqByte] -ne $jpqSignature[$jpqByte]) { $jpqMatches=$false; break } }
    if($jpqMatches) { $jpqOffset=$jpqIndex; break }
}
if($jpqOffset -lt 0) { throw 'No PNG screenshot received' }
if($jpqOffset -gt 0) { [IO.File]::WriteAllBytes($jpqTarget,$jpqBytes[$jpqOffset..($jpqBytes.Length-1)]) }
Write-Output $jpqTarget
