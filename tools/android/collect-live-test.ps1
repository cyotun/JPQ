#requires -Version 7.0
param(
    [string]$OutputDirectory = ('captures/phone/live-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [int]$DurationSeconds = 1800,
    [string]$DeviceSerial = '',
    [int]$PollMilliseconds = 500
)
$ErrorActionPreference='Stop'
$jpqRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$jpqOutput=[IO.Path]::GetFullPath((Join-Path $jpqRoot $OutputDirectory))
$jpqAllowed=[IO.Path]::GetFullPath((Join-Path $jpqRoot 'captures/phone'))+[IO.Path]::DirectorySeparatorChar
if(-not $jpqOutput.StartsWith($jpqAllowed,[StringComparison]::OrdinalIgnoreCase)) { throw 'Evidence must stay in captures/phone.' }
if($DurationSeconds -lt 5 -or $DurationSeconds -gt 7200) { throw 'Duration must be 5..7200 seconds.' }
if($PollMilliseconds -lt 250 -or $PollMilliseconds -gt 1000) { throw 'Poll interval must be 250..1000 milliseconds.' }
if(Test-Path -LiteralPath $jpqOutput) { throw 'Use a new evidence directory.' }
$jpqAdb=Join-Path $jpqRoot '.android-tools/sdk/platform-tools/adb.exe'
$jpqScrcpy=Join-Path $jpqRoot '.android-tools/scrcpy-5.0.1/scrcpy-win64-v5.0.1/scrcpy.exe'
if(-not (Test-Path -LiteralPath $jpqScrcpy)) { throw 'Prepare the verified official scrcpy 5.0.1 locally first.' }
. (Join-Path $PSScriptRoot 'live-test-support.ps1')
$jpqDevices=Invoke-JpqReadCommand $jpqAdb @('devices')
if(-not $jpqDevices.Ok) { throw 'Cannot read the ADB device list.' }
$jpqConnected=@([regex]::Matches($jpqDevices.Text,'(?m)^([^\s]+)\s+device\s*$') | ForEach-Object { $_.Groups[1].Value })
if($DeviceSerial) {
    if($jpqConnected -notcontains $DeviceSerial) { throw 'The selected device is not connected and authorized.' }
} elseif($jpqConnected.Count -eq 1) { $DeviceSerial=$jpqConnected[0] }
else { throw 'Select exactly one connected device with -DeviceSerial.' }
New-Item -ItemType Directory -Path $jpqOutput | Out-Null

function Start-JpqChild([string]$Executable,[string[]]$Arguments,[string]$Name) {
    # Direct inherited file handles avoid pipe backpressure and unfinished
    # PowerShell CopyToAsync tasks; the dedicated console remains hidden.
    [pscustomobject]@{ Process=[JpqEvidence.NativeConsole]::Start($Executable,$Arguments,$jpqRoot,
        (Join-Path $jpqOutput "$Name.stdout.log"),(Join-Path $jpqOutput "$Name.stderr.log"),$jpqAdb) }
}
function Close-JpqChild($Child) {
    if($null -eq $Child) { return }
    Stop-JpqOwnedConsole $Child.Process
}
function Write-JpqMarker([string]$Event,[hashtable]$Values=@{}) {
    $record=@{event=$Event; hostTime=(Get-Date).ToUniversalTime().ToString('o'); hostElapsedSeconds=$jpqTimer.Elapsed.TotalSeconds}
    foreach($key in $Values.Keys) { $record[$key]=$Values[$key] }
    Add-Content -LiteralPath (Join-Path $jpqOutput 'markers.jsonl') -Value ($record|ConvertTo-Json -Compress) -Encoding utf8
}
function Invoke-JpqAdb([string[]]$Arguments) {
    Invoke-JpqReadCommand $jpqAdb (@('-s',$DeviceSerial)+$Arguments) 2000
}
function Write-JpqClockSample {
    $before=[DateTimeOffset]::UtcNow
    $reading=Invoke-JpqAdb @('shell','cat','/proc/uptime')
    $after=[DateTimeOffset]::UtcNow; $uptime=$null
    if($reading.Ok -and $reading.Text -match '^\s*(\d+(?:\.\d+)?)\s') {
        $uptime=[double]::Parse($Matches[1],[Globalization.CultureInfo]::InvariantCulture)
    }
    Write-JpqMarker 'clock_sample' @{hostBefore=$before.ToString('o'); hostAfter=$after.ToString('o');
        deviceUptimeSeconds=$uptime; uncertaintyMs=($after-$before).TotalMilliseconds/2+10}
    $script:jpqLastClock=$jpqTimer.Elapsed.TotalSeconds
}
$jpqApk=Join-Path $jpqRoot 'app/build/outputs/apk/release/app-release.apk'
$jpqMetadata=@{schemaVersion=2; collectorStarted=(Get-Date).ToUniversalTime().ToString('o');
    commit=(& git -C $jpqRoot rev-parse HEAD).Trim(); dirty=[bool](& git -C $jpqRoot status --porcelain);
    localApkSha256=if(Test-Path -LiteralPath $jpqApk) { (Get-FileHash -LiteralPath $jpqApk -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null };
    apkHashScope='Local artifact only; installed version comes from JPQTrace session records.';
    recorder='scrcpy 5.0.1'; recorderMode='no-control/no-audio/no-window/display-0'; maxSize=1920; maxFps=15;
    durationSeconds=$DurationSeconds; pollMilliseconds=$PollMilliseconds; readTimeoutMs=2000;
    privacy='Fail closed on non-game, unknown, asleep or showing keyguard. Foreground polling has finite delay; review and remove non-game tail before viewing/exporting.';
    timeline='Clock samples bound host UTC to device elapsedRealtime. MKV starts at its first encoded frame, not process launch; align a visible event before measuring event delays.';
    controlBoundary='User enters and plays; collector never clicks, installs, stops capture or changes game state.'}
$jpqMetadata|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $jpqOutput 'metadata.json') -Encoding utf8
$jpqLogs=$null; $jpqVideo=$null; $jpqSegment=0; $jpqRetryAfter=0.0; $jpqLastClock=-60.0; $jpqLastGuard=-10.0
$jpqFinishReason='duration_limit'
$jpqTimer=[Diagnostics.Stopwatch]::StartNew()
try {
    # Do not clear logcat or collect all-app AndroidRuntime crash messages.
    $jpqLogs=Start-JpqChild $jpqAdb @('-s',$DeviceSerial,'logcat','-T','1','-v','epoch','JPQCapture:I','JPQRound:I','JPQVision:I','JPQTrace:I','*:S') 'logcat'
    Write-JpqMarker 'armed'
    Write-JpqClockSample
    while($jpqTimer.Elapsed.TotalSeconds -lt $DurationSeconds -and -not (Test-Path -LiteralPath (Join-Path $jpqOutput 'stop.signal'))) {
        # Raw dumpsys stays in memory; unrelated app names are never written.
        # The read-only guard has finite delay, so a switched/locked segment's
        # tail must be trimmed before review or any evidence export.
        if($jpqLogs.Process.HasExited) { throw 'Evidence log stream exited; see the local logcat logs.' }
        $guardStart=$jpqTimer.Elapsed.TotalSeconds
        $activity=Invoke-JpqAdb @('shell','dumpsys','activity','activities')
        $policy=Invoke-JpqAdb @('shell','dumpsys','window','policy')
        $power=Invoke-JpqAdb @('shell','dumpsys','power')
        $guard=Get-JpqForegroundState $activity.Text $policy.Text $power.Text
        $inGame=$activity.Ok -and $policy.Ok -and $power.Ok -and $guard.SafeToRecord
        if($jpqTimer.Elapsed.TotalSeconds-$jpqLastGuard -ge 5 -or -not $inGame) {
            Write-JpqMarker 'foreground_guard' @{gameOnly=$guard.GameOnly; screenOn=$guard.ScreenOn; unlocked=$guard.Unlocked;
                safe=$inGame; queryMs=($jpqTimer.Elapsed.TotalSeconds-$guardStart)*1000}
            $jpqLastGuard=$jpqTimer.Elapsed.TotalSeconds
        }
        if($null -ne $jpqVideo -and (-not $inGame -or $jpqVideo.Process.HasExited)) {
            $reason=if(-not $inGame) {'foreground_or_lock_guard'} else {'recorder_exited'}
            Write-JpqMarker 'video_stop_requested' @{segment=$jpqSegment; reason=$reason}
            $closed=Close-JpqChild $jpqVideo; $jpqVideo=$null
            Write-JpqMarker 'video_stopped' @{segment=$jpqSegment; reason=$reason; exitCode=$closed.ExitCode;
                forced=$closed.Forced; signalError=$closed.SignalError; reviewTail=(-not $inGame); elapsedSeconds=$jpqTimer.Elapsed.TotalSeconds;
                fileBytes=if(Test-Path -LiteralPath $file) {(Get-Item -LiteralPath $file).Length} else {0}}
            $jpqRetryAfter=$jpqTimer.Elapsed.TotalSeconds+3
        }
        if($inGame -and $null -eq $jpqVideo -and $jpqTimer.Elapsed.TotalSeconds -ge $jpqRetryAfter) {
            $jpqSegment++
            $name='game-{0:D3}' -f $jpqSegment
            $file=Join-Path $jpqOutput "$name.mkv"
            $remaining=[Math]::Max(1,[Math]::Ceiling($DurationSeconds-$jpqTimer.Elapsed.TotalSeconds))
            Write-JpqMarker 'video_launch_requested' @{segment=$jpqSegment; file="$name.mkv"}
            $jpqVideo=Start-JpqChild $jpqScrcpy @('--serial',$DeviceSerial,'--no-control','--no-clipboard-autosync','--no-audio','--no-window','--no-playback','--no-power-on','--no-terminal-title','--display-id=0','--max-size=1920','--max-fps=15','--video-bit-rate=3M',"--time-limit=$remaining","--record=$file") $name
            Write-JpqMarker 'video_process_started' @{segment=$jpqSegment; elapsedSeconds=$jpqTimer.Elapsed.TotalSeconds}
            Write-JpqClockSample
        }
        if($jpqTimer.Elapsed.TotalSeconds-$jpqLastClock -ge 60) { Write-JpqClockSample }
        Start-Sleep -Milliseconds $PollMilliseconds
    }
    if(Test-Path -LiteralPath (Join-Path $jpqOutput 'stop.signal')) { $jpqFinishReason='stop_signal' }
} catch {
    $jpqFinishReason='collector_error'
    Write-JpqMarker 'collector_error' @{reason='Collector failed; this evidence cannot establish a passed game.'}
    throw
} finally {
    # Cleanup only the owned recorder/logcat processes, even if one fails.
    try {
        $videoClosed=Close-JpqChild $jpqVideo
        if($null -ne $videoClosed) { Write-JpqMarker 'video_stopped' @{segment=$jpqSegment; reason=$jpqFinishReason; exitCode=$videoClosed.ExitCode; forced=$videoClosed.Forced;
            fileBytes=if(Test-Path -LiteralPath $file) {(Get-Item -LiteralPath $file).Length} else {0}} }
    } finally {
        $logsClosed=Close-JpqChild $jpqLogs
        Write-JpqMarker 'collector_stopped' @{elapsedSeconds=$jpqTimer.Elapsed.TotalSeconds;
            reason=$jpqFinishReason;
            logcatForced=if($null -ne $logsClosed) {$logsClosed.Forced} else {$null}}
    }
}
Write-Output $jpqOutput
