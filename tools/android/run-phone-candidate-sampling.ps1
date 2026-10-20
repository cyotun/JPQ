param(
    [ValidateSet('fixture','game')][string]$Target='fixture',
    [ValidateSet('floats','shade')][string]$Mode='floats',
    [switch]$DragCounter,
    [switch]$GameLobbyConfirmed,
    [string]$Serial='f29c66d0'
)
$ErrorActionPreference='Stop'
if($Serial -ne 'f29c66d0') { throw 'Only the explicitly authorized phone is supported.' }
if($Target -eq 'game' -and -not $GameLobbyConfirmed) { throw 'Visually verify the lobby and pass -GameLobbyConfirmed before this non-match test. Never use instrumentation in a real match.' }
$jpqRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$jpqAdb=Join-Path $jpqRoot '.android-tools/sdk/platform-tools/adb.exe'
$jpqOutput=Join-Path $jpqRoot ("captures/phone/beta-app-task-20261021/$Target-$Mode-"+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))
New-Item -ItemType Directory -Path $jpqOutput | Out-Null
$displayId='4630946457447247251'
$testProcess=$null
$finished=$false
function Invoke-Adb([string[]]$Arguments) {
    $result=& $jpqAdb -s $Serial @Arguments 2>&1
    if($LASTEXITCODE -ne 0) { throw ($result | Out-String) }
    return $result
}
try {
    $projection=Invoke-Adb @('shell','dumpsys','media_projection') | Out-String
    if($projection -notmatch 'Media Projection:\s*null') { throw 'An existing projection must be stopped first.' }
    $started=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $oldPidResult=& $jpqAdb -s $Serial shell pidof com.cyotun.jpq.candidate 2>&1
    if($LASTEXITCODE -ne 0 -and ($oldPidResult | Out-String).Trim()) { throw ($oldPidResult | Out-String) }
    $oldPid=($oldPidResult | Out-String).Trim()
    Invoke-Adb @('shell','am','force-stop','com.cyotun.jpq.scopeoverlay') | Out-Null
    Invoke-Adb @('shell','am','start','-W','-n','com.cyotun.jpq.scopeoverlay/.ScopeOverlayLauncherActivity','--ez','overlayProbe','true','--ez','notifyProbe','true') | Out-Null
    $testProcess=Start-Process -FilePath $jpqAdb -ArgumentList @('-s',$Serial,'shell','am','instrument','-w','-e','candidateLobbySampling','true','-e','candidateDiagnostics','true','-e','class','com.cyotun.jpq.vision.CandidateCaptureSamplingTest','com.cyotun.jpq.candidate.tests/androidx.test.runner.AndroidJUnitRunner') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $jpqOutput 'instrumentation.txt') -RedirectStandardError (Join-Path $jpqOutput 'instrumentation-stderr.txt') -PassThru
    $newProcess=$false
    for($i=0;$i -lt 40;$i++) {
        $candidatePid=& $jpqAdb -s $Serial shell pidof com.cyotun.jpq.candidate
        if($LASTEXITCODE -eq 0 -and "$candidatePid" -ne $oldPid) { $newProcess=$true;break }
        if($testProcess.HasExited) { throw 'Instrumentation ended during initialization.' }
        Start-Sleep -Milliseconds 200
    }
    if(-not $newProcess) { throw 'Instrumentation did not initialize a fresh process.' }
    & (Join-Path $PSScriptRoot 'start-phone-candidate-consent.ps1') -Serial $Serial -Target $Target -GameLobbyConfirmed:$GameLobbyConfirmed -OutputDirectory ([IO.Path]::GetRelativePath($jpqRoot,(Join-Path $jpqOutput 'consent')))
    if($DragCounter) {
        $windows=Invoke-Adb @('shell','dumpsys','window','windows') | Out-String
        $windows | Set-Content -LiteralPath (Join-Path $jpqOutput 'windows-before-drag.txt')
        $bars=@([regex]::Matches($windows,'(?ms)^  Window #.*?(?=^  Window #|\z)') | Where-Object {
            $_.Value -match 'package=com.cyotun.jpq.candidate\s' -and
            $_.Value -match 'ty=APPLICATION_OVERLAY' -and $_.Value -match 'mViewVisibility=0x0'
        })
        if($bars.Count -ne 1 -or $bars[0].Value -notmatch 'Frames:.*?display=\[0,0\]\[(\d+),(\d+)\] frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw 'A single visible candidate bar with current geometry is required for drag.' }
        $screenW=[int]$Matches[1]; $screenH=[int]$Matches[2]
        $left=[int]$Matches[3]; $top=[int]$Matches[4]; $right=[int]$Matches[5]; $bottom=[int]$Matches[6]
        if($screenW -le $screenH -or $right-$left -lt 400) { throw 'Expected landscape waiting bar for non-match drag.' }
        $dragX=[int]($left+($right-$left)*.6); $dragY=[int](($top+$bottom)/2)
        $toY=[int]($screenH*.5)
        Invoke-Adb @('shell','input','-d','0','swipe',"$dragX","$dragY","$dragX","$toY",'900') | Out-Null
        Start-Sleep -Milliseconds 300
        Invoke-Adb @('shell','dumpsys','window','windows') | Set-Content -LiteralPath (Join-Path $jpqOutput 'windows-after-drag.txt')
        [ordered]@{from=@($dragX,$dragY);to=@($dragX,$toY);sourceBounds=@($left,$top,$right,$bottom);physicalScreen=@($screenW,$screenH)} |
            ConvertTo-Json | Set-Content -LiteralPath (Join-Path $jpqOutput 'drag.json')
    }
    if($Mode -eq 'shade') {
        Invoke-Adb @('shell','cmd','statusbar','expand-notifications') | Out-Null
        Start-Sleep -Milliseconds 800
    }
    $screens=@()
    for($i=0;$i -lt 12;$i++) {
        if($testProcess.HasExited) { throw 'Instrumentation ended before physical sampling finished.' }
        $entry=[ordered]@{index=$i;hostBeforeMs=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()}
        $entry.deviceBeforeMs=([string](Invoke-Adb @('shell','date','+%s%3N'))).Trim()
        $name="physical-$i.png"
        try {
            Invoke-Adb @('shell','screencap','-d',$displayId,'-p','/sdcard/jpq-beta-physical.png') | Out-Null
            Invoke-Adb @('pull','/sdcard/jpq-beta-physical.png',(Join-Path $jpqOutput $name)) | Out-Null
        } finally {
            & $jpqAdb -s $Serial shell rm -f /sdcard/jpq-beta-physical.png | Out-Null
            if($LASTEXITCODE -ne 0) { throw 'Could not clean device-side temporary screenshot.' }
        }
        $entry.deviceAfterMs=([string](Invoke-Adb @('shell','date','+%s%3N'))).Trim()
        $entry.hostAfterMs=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $entry.file=$name
        $screens+=[pscustomobject]$entry
        Start-Sleep -Milliseconds 250
    }
    $screens | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $jpqOutput 'physical-times.json')
    Invoke-Adb @('shell','dumpsys','media_projection') | Set-Content -LiteralPath (Join-Path $jpqOutput 'projection-during.txt')
    Invoke-Adb @('shell','dumpsys','window','windows') | Set-Content -LiteralPath (Join-Path $jpqOutput 'windows-during.txt')
    $completionDeadline=[DateTime]::UtcNow.AddSeconds(70)
    $foregroundedExport=$false
    while(-not $testProcess.HasExited -and [DateTime]::UtcNow -lt $completionDeadline) {
        $active=Invoke-Adb @('shell','dumpsys','media_projection') | Out-String
        if(-not $foregroundedExport -and $active -match 'Media Projection:\s*null') {
            # HyperOS can freeze the test worker as soon as its FGS stops.
            # The completed capture is never restarted; foreground only the
            # candidate's own home so the post-stop local export can finish.
            Invoke-Adb @('shell','am','start','-W','-f','0x10008000','-n','com.cyotun.jpq.candidate/com.cyotun.jpq.MainActivity','--es','page','home') | Out-Null
            $foregroundedExport=$true
        }
        Start-Sleep -Milliseconds 250
    }
    if(-not $testProcess.HasExited) { throw 'Instrumented sampling did not finish.' }
    $result=Get-Content -LiteralPath (Join-Path $jpqOutput 'instrumentation.txt') -Raw
    if($result -notmatch 'OK \(1 test\)') { throw 'Instrumented sampling failed; inspect its output.' }
    $remote='/sdcard/Android/data/com.cyotun.jpq.candidate/files/phone-preflight'
    Invoke-Adb @('pull',"$remote/candidate-isolation-sampling.json",(Join-Path $jpqOutput 'sampling.json')) | Out-Null
    $sampling=Get-Content -LiteralPath (Join-Path $jpqOutput 'sampling.json') -Raw | ConvertFrom-Json
    if($sampling.sampleStartedWallMs -lt $started -or $sampling.probeIds.Count -ne 3 -or $sampling.observedFrames -le 10) { throw 'Fresh complete sampling evidence is required.' }
    Invoke-Adb @('pull',"$remote/candidate-isolation-sampling.zip",(Join-Path $jpqOutput 'diagnostics.zip')) | Out-Null
    # The installed test exports the complete local candidate diagnostic history.
    # Keep that private archive in ignored captures; use only these three IDs
    # for this lab's pixel evidence. No server/upload action is performed.
    [ordered]@{ target=$Target;mode=$Mode;dragRequested=[bool]$DragCounter;operatorConfirmedGameLobby=[bool]$GameLobbyConfirmed;automatedSceneVerification=$false;archiveScope='complete_private_candidate_diagnostics';pixelEvidenceProbeIds=$sampling.probeIds } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $jpqOutput 'host-scope.json')
    $stopped=Invoke-Adb @('shell','dumpsys','media_projection') | Out-String
    if($stopped -notmatch 'Media Projection:\s*null') { throw 'Normal capture stop did not release the system projection.' }
    $finished=$true
    Write-Output "Fresh phone capture sampling saved: $jpqOutput. Review physical visibility, target identity and raw pixels before acceptance."
} finally {
    & $jpqAdb -s $Serial shell rm -f /sdcard/jpq-beta-physical.png | Out-Null
    if(-not $finished) {
        $active=& $jpqAdb -s $Serial shell dumpsys media_projection | Out-String
        if($active -match 'packageName: com\.cyotun\.jpq\.candidate\s') {
            & $jpqAdb -s $Serial shell am force-stop com.cyotun.jpq.candidate | Out-Null
        }
    }
    & $jpqAdb -s $Serial shell cmd statusbar collapse | Out-Null
    & $jpqAdb -s $Serial shell am force-stop com.cyotun.jpq.scopeoverlay | Out-Null
    $finalProjection=& $jpqAdb -s $Serial shell dumpsys media_projection | Out-String
    $finalProjection | Set-Content -LiteralPath (Join-Path $jpqOutput 'projection-stopped.txt')
    if($finished -and ($LASTEXITCODE -ne 0 -or $finalProjection -notmatch 'Media Projection:\s*null')) { throw 'Final projection-release check failed.' }
}
