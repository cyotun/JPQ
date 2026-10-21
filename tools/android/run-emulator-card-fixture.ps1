param(
    [ValidateRange(1,4)][int]$Case=1,
    [ValidateSet('landscape','portrait')][string]$InitialOrientation='landscape',
    [ValidateSet('image','surface','gl')][string]$RenderMode='image',
    [switch]$RecreateSurfaceAfterConsent,
    [switch]$RefreshTaskSurface,
    [switch]$ExpectStartupRecovery,
    [switch]$RecreateOnTaskReturn,
    [ValidateRange(100,1500)][int]$RecentHoldMilliseconds=700,
    [switch]$RecentTaskCycle,
    [string]$Serial='emulator-5580'
)
$ErrorActionPreference='Stop'
if($Serial -notmatch '^emulator-\d+$') { throw 'Only an Android emulator is allowed.' }
if($RecreateSurfaceAfterConsent -and $RenderMode -notin @('surface','gl')) { throw 'Surface recreation requires -RenderMode surface or gl.' }
if($ExpectStartupRecovery -and $RefreshTaskSurface) { throw 'Automatic and manual recovery checks must be separate.' }
if($RecreateOnTaskReturn -and ($RenderMode -notin @('surface','gl') -or -not $RecentTaskCycle)) { throw 'Task-return recreation requires surface or gl mode and a recent-task cycle.' }
$fixtureActivity=switch($RenderMode) {
    'surface' { 'com.cyotun.jpq.vision.SurfaceCardFixtureProbeActivity' }
    'gl' { 'com.cyotun.jpq.vision.GlCardFixtureProbeActivity' }
    default { 'com.cyotun.jpq.vision.CardFixtureProbeActivity' }
}
$jpqRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$jpqAdb=Join-Path $jpqRoot '.android-tools/sdk/platform-tools/adb.exe'
$out=Join-Path $jpqRoot ('captures/emulator/card-fixture-20261021/case'+$Case+'-'+$InitialOrientation+'-'+$RenderMode+'-'+[bool]$RecreateSurfaceAfterConsent+'-'+[bool]$RecentTaskCycle+'-refresh'+[bool]$RefreshTaskSurface+'-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))
New-Item -ItemType Directory -Path $out | Out-Null
$process=$null;$complete=$false
function Adb([string[]]$Arguments) {
    $result=& $jpqAdb -s $Serial @Arguments 2>&1
    if($LASTEXITCODE -ne 0) { throw ($result | Out-String) }
    return $result
}
try {
    if((Adb @('shell','dumpsys','media_projection') | Out-String) -notmatch 'Media Projection:\s*null') { throw 'Stop the old emulator capture first.' }
    Adb @('shell','am','force-stop','com.cyotun.jpq.tests') | Out-Null
    Adb @('shell','am','start','-W','-n',"com.cyotun.jpq.tests/$fixtureActivity",'--ez','cardFixtureProbe','true','--ei','caseIndex',"$Case",'--es','orientation',$InitialOrientation,'--ei','timeoutSeconds','180') | Out-Null
    $oldPid=(& $jpqAdb -s $Serial shell pidof com.cyotun.jpq.candidate | Out-String).Trim()
    $startedWall=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $process=Start-Process $jpqAdb -ArgumentList @('-s',$Serial,'shell','am','instrument','-w','-e','candidateLobbySampling','true','-e','candidateDiagnostics','true','-e','expectedFixtureCase',"$Case",'-e','refreshTaskSurface',([string][bool]$RefreshTaskSurface).ToLowerInvariant(),'-e','expectStartupRecovery',([string][bool]$ExpectStartupRecovery).ToLowerInvariant(),'-e','class','com.cyotun.jpq.vision.CandidateCaptureSamplingTest','com.cyotun.jpq.candidate.tests/androidx.test.runner.AndroidJUnitRunner') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $out 'instrumentation.txt') -RedirectStandardError (Join-Path $out 'instrumentation-stderr.txt') -PassThru
    $fresh=$false
    for($i=0;$i -lt 60;$i++) {
        $newPid=(& $jpqAdb -s $Serial shell pidof com.cyotun.jpq.candidate | Out-String).Trim()
        if($newPid -and $newPid -ne $oldPid) { $fresh=$true;break }
        if($process.HasExited) { throw 'Fixture instrumentation ended before consent.' }
        Start-Sleep -Milliseconds 200
    }
    if(-not $fresh) { throw 'No new instrumented candidate process.' }
    $ready=$false
    for($i=0;$i -lt 40;$i++) {
        $remoteReady=& $jpqAdb -s $Serial shell cat /sdcard/Android/data/com.cyotun.jpq.candidate/files/phone-preflight/candidate-sampling-ready.json 2>$null
        if($LASTEXITCODE -eq 0) {
            $readyRecord=($remoteReady | Out-String) | ConvertFrom-Json
            if($readyRecord.readyWallMs -ge $startedWall -and $readyRecord.expectedFixtureCase -eq $Case) { $ready=$true;break }
        }
        if($process.HasExited) { throw 'Instrumentation ended before readiness signal.' }
        Start-Sleep -Milliseconds 200
    }
    if(-not $ready) { throw 'Fresh fixture instrumentation readiness signal is required.' }
    $consentArgs=@{ Serial=$Serial; FixtureCase=$Case; OutputDirectory=[IO.Path]::GetRelativePath($jpqRoot,(Join-Path $out 'consent')); RenderMode=$RenderMode }
    if($RecreateSurfaceAfterConsent) { $consentArgs.RecreateSurfaceAfterConsent=$true }
    & (Join-Path $PSScriptRoot 'start-emulator-candidate-consent.ps1') @consentArgs
    # Stock Android can return to the capture owner after selecting a task;
    # choosing a task and making that task visible are separate operations.
    Adb @('shell','am','start','-W','-n',"com.cyotun.jpq.tests/$fixtureActivity",'--ez','cardFixtureProbe','true','--ei','caseIndex',"$Case",'--es','orientation','landscape','--ei','timeoutSeconds','180') | Out-Null
    if($RecentTaskCycle) {
        Adb @('shell','input','-d','0','keyevent','KEYCODE_APP_SWITCH') | Out-Null
        Start-Sleep -Milliseconds $RecentHoldMilliseconds
    }
    if($RecentTaskCycle -or $InitialOrientation -eq 'portrait') {
        $returnArgs=@('shell','am','start','-W','-n',"com.cyotun.jpq.tests/$fixtureActivity",'--ez','cardFixtureProbe','true','--ei','caseIndex',"$Case",'--es','orientation','landscape','--ei','timeoutSeconds','180')
        if($RecreateOnTaskReturn) { $returnArgs+=@('--ez','recreateSurface','true') }
        Adb $returnArgs | Out-Null
    }
    Start-Sleep -Seconds 6
    Adb @('shell','screencap','-p','/sdcard/jpq-card-fixture.png') | Out-Null
    Adb @('pull','/sdcard/jpq-card-fixture.png',(Join-Path $out 'physical.png')) | Out-Null
    Adb @('shell','rm','-f','/sdcard/jpq-card-fixture.png') | Out-Null
    Adb @('shell','dumpsys','media_projection') | Set-Content -LiteralPath (Join-Path $out 'projection-during.txt')
    $deadline=[DateTime]::UtcNow.AddSeconds(180);$exportForeground=$false
    while(-not $process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
        if(-not $exportForeground -and (Adb @('shell','dumpsys','media_projection') | Out-String) -match 'Media Projection:\s*null') {
            Adb @('shell','am','start','-W','-n','com.cyotun.jpq.candidate/com.cyotun.jpq.MainActivity','--es','page','home') | Out-Null
            $exportForeground=$true
        }
        Start-Sleep -Milliseconds 400
    }
    if(-not $process.HasExited) { throw 'Fixture sampling timed out.' }
    $remote='/sdcard/Android/data/com.cyotun.jpq.candidate/files/phone-preflight'
    Adb @('pull',"$remote/candidate-isolation-sampling.json",(Join-Path $out 'sampling.json')) | Out-Null
    Adb @('pull',"$remote/candidate-isolation-sampling.zip",(Join-Path $out 'diagnostics.zip')) | Out-Null
    if((Get-Content (Join-Path $out 'instrumentation.txt') -Raw) -notmatch 'OK \(1 test\)') { throw "Real capture-to-model fixture assertion failed: $out" }
    $record=Get-Content (Join-Path $out 'sampling.json') -Raw | ConvertFrom-Json
    if($record.expectedFixtureCase -ne $Case -or $record.sameFrameGoldenChecks.Count -lt 1 -or $record.productionGoldenFrames -lt 10) { throw 'Missing actual golden model and production recognizer assertions.' }
    if((Adb @('shell','dumpsys','media_projection') | Out-String) -notmatch 'Media Projection:\s*null') { throw 'The system projection was not released.' }
    $complete=$true
    Write-Output "PASS actual capture-to-model fixture case=$Case initial=$InitialOrientation render=$RenderMode recreateSurfaceAfterConsent=$RecreateSurfaceAfterConsent recent=$RecentTaskCycle at $out"
} finally {
    if(-not $complete) {
        $active=Adb @('shell','dumpsys','media_projection') | Out-String
        if($active -match 'packageName: com\.cyotun\.jpq\.candidate') { Adb @('shell','am','force-stop','com.cyotun.jpq.candidate') | Out-Null }
    }
    Adb @('shell','dumpsys','media_projection') | Set-Content -LiteralPath (Join-Path $out 'projection-stopped.txt')
}
