#requires -Version 7.0
param(
    [string]$OutputDirectory = ('captures/phone/live-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [int]$DurationSeconds = 1800,
    [string]$DeviceSerial = '',
    [int]$PollMilliseconds = 500,
    [int]$FrameIntervalMilliseconds = 1000,
    [switch]$LogsOnly,
    [switch]$SyntheticProbe
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
if($FrameIntervalMilliseconds -lt 1000 -or $FrameIntervalMilliseconds -gt 10000) { throw 'Frame interval must be 1000..10000 milliseconds.' }
. (Join-Path $PSScriptRoot 'live-test-support.ps1')
$jpqDevices=Invoke-JpqReadCommand $jpqAdb @('devices')
if(-not $jpqDevices.Ok) { throw 'Cannot read the ADB device list.' }
$jpqConnected=@([regex]::Matches($jpqDevices.Text,'(?m)^([^\s]+)\s+device\s*$') | ForEach-Object { $_.Groups[1].Value })
if($DeviceSerial) {
    if($jpqConnected -notcontains $DeviceSerial) { throw 'The selected device is not connected and authorized.' }
} elseif($jpqConnected.Count -eq 1) { $DeviceSerial=$jpqConnected[0] }
else { throw 'Select exactly one connected device with -DeviceSerial.' }
$jpqPhysical=$null
if(-not $LogsOnly) {
    $jpqPhysicalRead=Invoke-JpqReadCommand $jpqAdb @('-s',$DeviceSerial,'shell','dumpsys','SurfaceFlinger','--display-id')
    if($jpqPhysicalRead.Ok) { $jpqPhysical=Get-JpqPrimaryPhysicalDisplayId $jpqPhysicalRead.Text }
    if(-not $jpqPhysical) { throw 'Cannot identify the primary physical panel; use -LogsOnly rather than guessing a display.' }
}
New-Item -ItemType Directory -Path $jpqOutput | Out-Null
if(-not $LogsOnly) { New-Item -ItemType Directory -Path (Join-Path $jpqOutput 'frames') | Out-Null }

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
$jpqMetadata=@{schemaVersion=3; collectorStarted=(Get-Date).ToUniversalTime().ToString('o');
    commit=(& git -C $jpqRoot rev-parse HEAD).Trim(); dirty=[bool](& git -C $jpqRoot status --porcelain);
    localApkSha256=if(Test-Path -LiteralPath $jpqApk) { (Get-FileHash -LiteralPath $jpqApk -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null };
    apkHashScope='Local artifact only; installed version comes from JPQTrace session records.';
    recorder='Stateless screencap PNG sequence'; recorderMode=if($LogsOnly) {'logs_only'} else {'primary_physical_display_png_sequence'};
    physicalDisplayId=$jpqPhysical; requestedFrameIntervalMs=$FrameIntervalMilliseconds;
    acceptedSurface=if($SyntheticProbe) {'dedicated_phone_frame_probe_only'} else {'tencent_guandan_only'};
    frameRateNote='Each screenshot has start/end timestamps. Guard and capture overhead reduce actual cadence; this is not 15 fps video.';
    captureIsolation='No scrcpy, screenrecord, MediaProjection or second mirrored virtual display is created by this collector.';
    durationSeconds=$DurationSeconds; pollMilliseconds=$PollMilliseconds; readTimeoutMs=2000;
    privacy='Require target app, awake and unlocked before and after each screenshot. Finite guard/capture intervals still require privacy review before viewing/exporting.';
    timeline='Clock samples bound host UTC to device elapsedRealtime. frames.jsonl bounds each screenshot by capture start/end; no invented fixed frame rate.';
    controlBoundary='User enters and plays; collector never clicks, installs, stops capture or changes game state.'}
$jpqMetadata|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $jpqOutput 'metadata.json') -Encoding utf8
$jpqLogs=$null; $jpqFrame=0; $jpqNextFrame=0.0; $jpqLastClock=-60.0; $jpqLastGuard=-10.0; $jpqFrameBytes=0L
$jpqFinishReason='duration_limit'
$jpqTimer=[Diagnostics.Stopwatch]::StartNew()
try {
    # Do not clear logcat or collect all-app AndroidRuntime crash messages.
    $jpqLogs=Start-JpqChild $jpqAdb @('-s',$DeviceSerial,'logcat','-T','1','-v','epoch','JPQCapture:I','JPQRound:I','JPQVision:I','JPQTrace:I','*:S') 'logcat'
    Write-JpqMarker 'armed'
    Write-JpqClockSample
    while($jpqTimer.Elapsed.TotalSeconds -lt $DurationSeconds -and -not (Test-Path -LiteralPath (Join-Path $jpqOutput 'stop.signal'))) {
        # Raw dumpsys stays in memory; unrelated app names are never written.
        # The guard has finite timing uncertainty; post-capture rejection and
        # privacy review are still required before any evidence export.
        if($jpqLogs.Process.HasExited) { throw 'Evidence log stream exited; see the local logcat logs.' }
        $guardStart=$jpqTimer.Elapsed.TotalSeconds
        $activity=Invoke-JpqAdb @('shell','dumpsys','activity','activities')
        $policy=Invoke-JpqAdb @('shell','dumpsys','window','policy')
        $power=Invoke-JpqAdb @('shell','dumpsys','power')
        $guard=Get-JpqForegroundState $activity.Text $policy.Text $power.Text -SyntheticProbe:$SyntheticProbe
        $inGame=$activity.Ok -and $policy.Ok -and $power.Ok -and $guard.SafeToRecord
        if($jpqTimer.Elapsed.TotalSeconds-$jpqLastGuard -ge 5 -or -not $inGame) {
            Write-JpqMarker 'foreground_guard' @{gameOnly=$guard.GameOnly; screenOn=$guard.ScreenOn; unlocked=$guard.Unlocked;
                safe=$inGame; queryMs=($jpqTimer.Elapsed.TotalSeconds-$guardStart)*1000}
            $jpqLastGuard=$jpqTimer.Elapsed.TotalSeconds
        }
        if($inGame -and -not $LogsOnly -and $jpqTimer.Elapsed.TotalSeconds -ge $jpqNextFrame) {
            $before=[DateTimeOffset]::UtcNow
            $frame=Invoke-JpqReadPng $jpqAdb @('-s',$DeviceSerial,'exec-out','screencap','-p','-d',$jpqPhysical)
            $after=[DateTimeOffset]::UtcNow
            # Recheck after capture: discard switch/lock transitions instead of
            # persisting another app. No screenshot or raw dumpsys is logged on rejection.
            $postActivity=Invoke-JpqAdb @('shell','dumpsys','activity','activities')
            $postPolicy=Invoke-JpqAdb @('shell','dumpsys','window','policy')
            $postPower=Invoke-JpqAdb @('shell','dumpsys','power')
            $postGuard=Get-JpqForegroundState $postActivity.Text $postPolicy.Text $postPower.Text -SyntheticProbe:$SyntheticProbe
            $safe=$frame.Ok -and $postActivity.Ok -and $postPolicy.Ok -and $postPower.Ok -and $postGuard.SafeToRecord
            if($safe) {
                # Bound local storage; metadata/logs continue if the frame budget is full.
                if($jpqFrameBytes+$frame.Bytes.Length -le 512L*1024*1024) {
                    $jpqFrame++
                    $relative='frames/frame-{0:D6}.png' -f $jpqFrame
                    [IO.File]::WriteAllBytes((Join-Path $jpqOutput $relative),$frame.Bytes)
                    $jpqFrameBytes+=$frame.Bytes.Length
                    @{frame=$jpqFrame;file=$relative;captureBefore=$before.ToString('o');captureAfter=$after.ToString('o');
                        acceptedAt=[DateTimeOffset]::UtcNow.ToString('o');bytes=$frame.Bytes.Length}|
                        ConvertTo-Json -Compress|Add-Content -LiteralPath (Join-Path $jpqOutput 'frames.jsonl') -Encoding utf8
                } else { Write-JpqMarker 'frame_budget_reached'; $LogsOnly=$true }
            } else { Write-JpqMarker 'frame_discarded' @{reason=if($frame.Ok) {'post_capture_guard'} else {$frame.Error}} }
            $jpqNextFrame=$jpqTimer.Elapsed.TotalSeconds+$FrameIntervalMilliseconds/1000.0
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
    # Only the owned ADB log stream is persistent. Stopping this collector
    # cannot tear down a mirrored display used by the app's projection.
    $logsClosed=Close-JpqChild $jpqLogs
    Write-JpqMarker 'collector_stopped' @{elapsedSeconds=$jpqTimer.Elapsed.TotalSeconds;
        reason=$jpqFinishReason;acceptedFrames=$jpqFrame;frameBytes=$jpqFrameBytes;
        logcatForced=if($null -ne $logsClosed) {$logsClosed.Forced} else {$null}}
}
Write-Output $jpqOutput
