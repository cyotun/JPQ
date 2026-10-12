#requires -Version 7.0
$ErrorActionPreference='Stop'
$jpqRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'live-test-support.ps1')
$jpqTestFolder=Join-Path $jpqRoot ('captures/phone/collector-selftest-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $jpqTestFolder | Out-Null
$jpqChecks=0
function Assert-Jpq([bool]$Condition,[string]$Description) {
    if(-not $Condition) { throw "Self-test failed: $Description" }
    $script:jpqChecks++
}
# No ADB/device operation occurs in these tests.
$game='      topResumedActivity=ActivityRecord{abcdef u0 com.tencent.guandansy/.MainActivity t42}'
$other='      topResumedActivity=ActivityRecord{abcdef u0 example.other/.Activity t42}'
$awake="  mWakefulness=Awake`n  mWakefulness=1`n  mWakefulness=0"
$unlocked="  KeyguardServiceDelegate`n    showing=false`n    occluded=false`n  mScreenOn = true`n  mScreenOn = false"
Assert-Jpq (Get-JpqForegroundState $game $unlocked $awake).SafeToRecord 'game + unlocked + named awake'
Assert-Jpq (-not (Get-JpqForegroundState $other $unlocked $awake).SafeToRecord) 'unrelated app is rejected'
Assert-Jpq (-not (Get-JpqForegroundState $game 'showing=true' $awake).SafeToRecord) 'keyguard is rejected'
Assert-Jpq (-not (Get-JpqForegroundState $game $unlocked 'mWakefulness=Asleep').SafeToRecord) 'sleep is rejected'
Assert-Jpq (-not (Get-JpqForegroundState $game '' $awake).SafeToRecord) 'unknown lock state is rejected'
Assert-Jpq (-not (Get-JpqForegroundState ($game+"`n"+$other) $unlocked $awake).SafeToRecord) 'multiple resumed apps are rejected'
Assert-Jpq (-not (Get-JpqForegroundState '' $unlocked $awake).SafeToRecord) 'missing activity is rejected'
Assert-Jpq (-not (Get-JpqForegroundState $game $unlocked 'mWakefulness=1').SafeToRecord) 'numeric display-only wakefulness is rejected'

$jpqPwsh=(Get-Process -Id $PID).Path
$jpqPipes=Join-Path $jpqTestFolder 'pipes.ps1'
@'
[Console]::Out.Write(('x'*524288))
[Console]::Error.Write(('y'*524288))
'@ | Set-Content -LiteralPath $jpqPipes -Encoding utf8
$jpqRead=Invoke-JpqReadCommand $jpqPwsh @('-NoProfile','-NonInteractive','-File',$jpqPipes) 8000
Assert-Jpq ($jpqRead.Ok -and $jpqRead.Text.Length -eq 524288 -and $jpqRead.Error.Length -eq 524288) 'both redirected pipes drain beyond OS pipe capacity'
$jpqSleep=Join-Path $jpqTestFolder 'sleep.ps1'
'Start-Sleep -Seconds 10' | Set-Content -LiteralPath $jpqSleep -Encoding utf8
$jpqTimeout=Invoke-JpqReadCommand $jpqPwsh @('-NoProfile','-NonInteractive','-File',$jpqSleep) 250
Assert-Jpq (-not $jpqTimeout.Ok -and $jpqTimeout.Error -eq 'read_timeout') 'hung read is bounded'

$jpqMock=Join-Path $jpqTestFolder 'mock console with spaces.ps1'
@'
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Threading;
public static class MockEvidenceConsole {
    delegate bool Handler(uint value);
    static readonly ManualResetEvent stop=new ManualResetEvent(false);
    static readonly Handler handler=(value) => { stop.Set(); return true; };
    [DllImport("kernel32.dll")] static extern bool SetConsoleCtrlHandler(Handler callback, bool add);
    public static void Run() {
        if (!SetConsoleCtrlHandler(handler,true)) throw new Exception("handler failed");
        Console.Out.WriteLine("ready"); Console.Out.Flush();
        if (!stop.WaitOne(15000)) throw new Exception("signal never arrived");
        Console.Out.WriteLine("graceful_trailer_written"); Console.Out.Flush();
    }
}
"@
[Console]::Error.Write(('e'*262144))
[Console]::Error.Flush()
[MockEvidenceConsole]::Run()
'@ | Set-Content -LiteralPath $jpqMock -Encoding utf8
$jpqOut=Join-Path $jpqTestFolder 'console.stdout.log'; $jpqErr=Join-Path $jpqTestFolder 'console.stderr.log'
$jpqChild=$null
try {
    $jpqChild=[JpqEvidence.NativeConsole]::Start($jpqPwsh,@('-NoProfile','-NonInteractive','-File',$jpqMock),$jpqRoot,$jpqOut,$jpqErr,'')
    $jpqReadyTimer=[Diagnostics.Stopwatch]::StartNew(); $jpqReady=$false
    while($jpqReadyTimer.Elapsed.TotalSeconds -lt 8 -and -not $jpqChild.HasExited) {
        if((Get-Content -LiteralPath $jpqOut -Raw) -match 'ready') { $jpqReady=$true; break }
        Start-Sleep -Milliseconds 50
    }
    Assert-Jpq $jpqReady 'hidden, isolated console is running with redirected file output'
    $jpqClosed=Stop-JpqOwnedConsole $jpqChild 3000; $jpqChild=$null
    $jpqClosed|ConvertTo-Json -Compress|Set-Content -LiteralPath (Join-Path $jpqTestFolder 'shutdown.json') -Encoding utf8
    Assert-Jpq ($jpqClosed.Signaled -and -not $jpqClosed.Forced -and $jpqClosed.ExitCode -eq 0) 'Ctrl+C reaches only the owned console and closes gracefully'
    Assert-Jpq ((Get-Content -LiteralPath $jpqOut -Raw) -match 'graceful_trailer_written') 'graceful completion marker survives shutdown'
    Assert-Jpq ((Get-Item -LiteralPath $jpqErr).Length -eq 262144) 'large stderr was written without pipe backpressure'
} finally {
    if($null -ne $jpqChild) { [void](Stop-JpqOwnedConsole $jpqChild) }
}

$jpqParseErrors=$null; $jpqTokens=$null
[void][Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'collect-live-test.ps1'),[ref]$jpqTokens,[ref]$jpqParseErrors)
Assert-Jpq ($jpqParseErrors.Count -eq 0) 'collector PowerShell syntax is valid'
Write-Output "$jpqChecks local collector checks passed; no phone accessed. Evidence: $jpqTestFolder"
