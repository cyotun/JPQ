param([string]$Serial='emulator-5580',[ValidateSet('banner','shade')][string]$NotificationMode='banner')
$ErrorActionPreference='Stop'
if($Serial -notmatch '^emulator-\d+$') { throw 'Emulator only.' }
$jpqRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$jpqAdb=Join-Path $jpqRoot '.android-tools/sdk/platform-tools/adb.exe'
$jpqOutput=Join-Path $jpqRoot ('captures/emulator/capture-api37-20261020/candidate-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))
New-Item -ItemType Directory -Path $jpqOutput | Out-Null
$remoteRoot='/sdcard/Android/data/com.cyotun.jpq.candidate/files/emulator-task-capture'
function Invoke-Adb([string[]]$Arguments) {
    $result=& $jpqAdb -s $Serial @Arguments 2>&1
    if($LASTEXITCODE -ne 0) { throw ($result | Out-String) }
    return $result
}
function Wait-Remote([string]$Remote,[string]$Local,[int]$Seconds=60) {
    $deadline=[DateTime]::UtcNow.AddSeconds($Seconds)
    while([DateTime]::UtcNow -lt $deadline) {
        $found=& $jpqAdb -s $Serial shell test -f $Remote
        if($LASTEXITCODE -eq 0) { Invoke-Adb @('pull',$Remote,$Local) | Out-Null;return }
        if($testProcess.HasExited) { throw 'Instrumentation ended before required evidence.' }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for $Remote"
}
$testProcess=$null
$completed=$false
try {
    $projection=Invoke-Adb @('shell','dumpsys','media_projection') | Out-String
    if($projection -notmatch 'Media Projection:\s*null') { throw 'Stop the previous capture first.' }
    $prior=& $jpqAdb -s $Serial shell ls $remoteRoot 2>&1
    if($LASTEXITCODE -ne 0) {
        if(($prior | Out-String) -notmatch 'No such file or directory') { throw ($prior | Out-String) }
        $prior=@()
    }
    Invoke-Adb @('shell','am','force-stop','com.cyotun.jpq.scopeoverlay') | Out-Null
    # Recents can retain a fixture activity whose required intent extras expired.
    # Start a fresh opted-in colour task before the system's app selector sees it.
    Invoke-Adb @('shell','am','start','-W','-f','0x10008000','-n',
        'com.cyotun.jpq.tests/com.cyotun.jpq.vision.ScopeProbeActivity',
        '--ez','scopeProbe','true','--es','orientation','landscape') | Out-Null
    Start-Sleep -Milliseconds 600
    Invoke-Adb @('shell','screencap','-p','/sdcard/jpq-scope-baseline.png') | Out-Null
    Invoke-Adb @('pull','/sdcard/jpq-scope-baseline.png',(Join-Path $jpqOutput 'baseline-initial.png')) | Out-Null
    Invoke-Adb @('shell','rm','-f','/sdcard/jpq-scope-baseline.png') | Out-Null
    $testProcess=Start-Process -FilePath $jpqAdb -ArgumentList @('-s',$Serial,'shell','am','instrument','-w','-e','emulatorTaskCapture','true','-e','class','com.cyotun.jpq.vision.EmulatorTaskCaptureTest','com.cyotun.jpq.candidate.tests/androidx.test.runner.AndroidJUnitRunner') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $jpqOutput 'instrumentation.txt') -RedirectStandardError (Join-Path $jpqOutput 'instrumentation-stderr.txt') -PassThru
    # Instrumentation first restarts its target process. Do not race the home
    # launch against that restart; the fresh run.json is our explicit ready signal.
    $readyDeadline=[DateTime]::UtcNow.AddSeconds(60)
    $runs=@()
    while([DateTime]::UtcNow -lt $readyDeadline) {
        $listing=& $jpqAdb -s $Serial shell ls $remoteRoot 2>&1
        if($LASTEXITCODE -eq 0) {
            $runs=@($listing | Where-Object { $_ -notin $prior -and $_ -match '^run-[0-9a-f-]+$' })
            if($runs.Count -eq 1) { break }
        }
        if($testProcess.HasExited) { throw 'Instrumentation exited during initialization.' }
        Start-Sleep -Milliseconds 500
    }
    if($runs.Count -ne 1) { throw 'Exactly one new instrumented run is required.' }
    $remoteRun=$remoteRoot+'/'+$runs[0]
    Wait-Remote "$remoteRun/run.json" (Join-Path $jpqOutput 'initial-run.json') 20
    & (Join-Path $PSScriptRoot 'start-emulator-candidate-consent.ps1') -Serial $Serial -OutputDirectory ([IO.Path]::GetRelativePath($jpqRoot,(Join-Path $jpqOutput 'consent')))
    foreach($stage in @('own','other','notification')) {
        $readyFile=Join-Path $jpqOutput "awaiting-$stage.json"
        Wait-Remote "$remoteRun/awaiting-$stage.json" $readyFile 150
        $ready=Get-Content -LiteralPath $readyFile -Raw | ConvertFrom-Json
        if($stage -eq 'own') {
            # Cover the fixture's verified solid region, not just its excluded
            # title strip; the pixel verifier must witness real occlusion.
            $bar=$ready.barMask.rectangle
            $dragX=[int]($bar[0]+($bar[2]-$bar[0])*.6)
            $dragY=[int](($bar[1]+$bar[3])/2)
            $toY=[int]($ready.barMask.screenHeight*.25)
            Invoke-Adb @('shell','input','-d','0','swipe',"$dragX","$dragY","$dragX","$toY",'900') | Out-Null
        }
        if($stage -eq 'other') {
            Invoke-Adb @('shell','am','start','-W','-n','com.cyotun.jpq.scopeoverlay/.ScopeOverlayLauncherActivity','--ez','overlayProbe','true') | Out-Null
            Start-Sleep -Milliseconds 600
            # Use the actual persisted bar position; fixed coordinates could pull
            # down System UI instead of dragging an already-moved counter.
            $bar=$ready.barMask.rectangle
            $dragX=[int]($bar[0]+($bar[2]-$bar[0])*.6)
            $dragY=[int](($bar[1]+$bar[3])/2)
            $toX=[Math]::Min($ready.barMask.screenWidth-100,$dragX+100)
            $toY=[int]($ready.barMask.screenHeight*.45)
            Invoke-Adb @('shell','input','-d','0','swipe',"$dragX","$dragY","$toX","$toY",'900') | Out-Null
        }
        if($stage -eq 'notification') {
            Invoke-Adb @('shell','am','force-stop','com.cyotun.jpq.scopeoverlay') | Out-Null
            Invoke-Adb @('shell','am','start','-W','-n','com.cyotun.jpq.scopeoverlay/.ScopeOverlayLauncherActivity','--ez','overlayProbe','true','--ez','notifyProbe','true') | Out-Null
            Start-Sleep -Milliseconds 800
            if($NotificationMode -eq 'shade') {
                Invoke-Adb @('shell','cmd','statusbar','expand-notifications') | Out-Null
                # Immersive tasks can ignore the shell expansion until the edge
                # gesture reveals system bars. Use actual primary-display swipes.
                $edgeX=[int]($ready.barMask.screenWidth*.9)
                $panelY=[int]($ready.barMask.screenHeight*.9)
                Invoke-Adb @('shell','input','-d','0','swipe',"$edgeX",'1',"$edgeX","$panelY",'600') | Out-Null
                Invoke-Adb @('shell','input','-d','0','swipe',"$edgeX",'1',"$edgeX","$panelY",'600') | Out-Null
                # Revealed bars do not prove the panel expanded. Request it
                # again after the gestures, then require its actual UI bounds.
                $panelConfirmed=$false
                for($attempt=0;$attempt -lt 3;$attempt++) {
                    Invoke-Adb @('shell','cmd','statusbar','expand-notifications') | Out-Null
                    $panelRemote="/sdcard/jpq-shade-$([IO.Path]::GetFileName($jpqOutput))-$attempt.xml"
                    $panelLocal=Join-Path $jpqOutput "notification-panel-$attempt.xml"
                    try {
                        $dump=Invoke-Adb @('shell','uiautomator','dump','--compressed',$panelRemote) | Out-String
                        if($dump.Contains("dumped to: $panelRemote")) {
                            Invoke-Adb @('pull',$panelRemote,$panelLocal) | Out-Null
                            [xml]$panelUi=Get-Content -LiteralPath $panelLocal -Raw -Encoding UTF8
                            foreach($node in $panelUi.SelectNodes('//node')) {
                                if($node.package -eq 'com.android.systemui' -and $node.'resource-id' -eq 'com.android.systemui:id/notification_stack_scroller' -and
                                    $node.bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$' -and
                                    ([int]$Matches[3]-[int]$Matches[1]) -gt $ready.barMask.screenWidth*.5 -and
                                    ([int]$Matches[4]-[int]$Matches[2]) -gt $ready.barMask.screenHeight*.5) { $panelConfirmed=$true }
                            }
                        }
                    } finally { Invoke-Adb @('shell','rm','-f',$panelRemote) | Out-Null }
                    if($panelConfirmed) { break }
                }
                if(-not $panelConfirmed) { throw 'Notification panel did not become physically visible.' }
            }
        }
        $record=[ordered]@{stage=$stage;notificationMode=$NotificationMode;hostBeforeScreenshotMs=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()}
        Invoke-Adb @('shell','screencap','-p',"/sdcard/jpq-$stage-sync.png") | Out-Null
        Invoke-Adb @('pull',"/sdcard/jpq-$stage-sync.png",(Join-Path $jpqOutput "$stage-physical.png")) | Out-Null
        $record.hostAfterScreenshotMs=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $control=Join-Path $jpqOutput "control-$stage"
        [IO.File]::WriteAllText($control,$ready.controlToken)
        Invoke-Adb @('push',$control,$ready.controlPath) | Out-Null
        $record | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $jpqOutput "$stage-host.json")
        Invoke-Adb @('shell','dumpsys','media_projection') | Set-Content -LiteralPath (Join-Path $jpqOutput "$stage-projection.txt")
        Invoke-Adb @('shell','dumpsys','window','windows') | Set-Content -LiteralPath (Join-Path $jpqOutput "$stage-windows.txt")
        Wait-Remote "$remoteRun/stage-$stage.json" (Join-Path $jpqOutput "stage-$stage.json") 45
        Write-Output "Fresh candidate stage saved: $stage"
    }
    Wait-Remote "$remoteRun/summary.json" (Join-Path $jpqOutput 'summary.json') 45
    Invoke-Adb @('pull',$remoteRun,(Join-Path $jpqOutput 'run')) | Out-Null
    $summary=Get-Content -LiteralPath (Join-Path $jpqOutput 'summary.json') -Raw | ConvertFrom-Json
    if(-not $summary.completed -or -not $summary.appStopped -or $summary.stageCount -ne 3) { throw 'Incomplete instrumented stages.' }
    $testProcess.WaitForExit(10000) | Out-Null
    if((Get-Content -LiteralPath (Join-Path $jpqOutput 'instrumentation.txt') -Raw) -notmatch 'OK \(1 test\)') { throw 'Instrumentation did not report success.' }
    $completed=$true
    Write-Output "Candidate capture stages complete: $jpqOutput. Physical visibility and captured pixel comparisons still require validation."
} finally {
    # Only the explicitly named emulator lab helpers/candidate may be stopped here.
    if(-not $completed) {
        $active=& $jpqAdb -s $Serial shell dumpsys media_projection | Out-String
        if($active -match 'packageName: com\.cyotun\.jpq\.candidate\s') {
            & $jpqAdb -s $Serial shell am force-stop com.cyotun.jpq.candidate | Out-Null
        }
    }
    & $jpqAdb -s $Serial shell cmd statusbar collapse | Out-Null
    & $jpqAdb -s $Serial shell am force-stop com.cyotun.jpq.scopeoverlay | Out-Null
    & $jpqAdb -s $Serial shell dumpsys media_projection | Set-Content -LiteralPath (Join-Path $jpqOutput 'stopped-projection.txt')
    if($completed) {
        # Opening animations can tint a pre-consent screenshot by one RGB level.
        # The same live fixture remains in front after all overlay cleanup.
        Start-Sleep -Milliseconds 600
        Invoke-Adb @('shell','screencap','-p','/sdcard/jpq-scope-baseline.png') | Out-Null
        Invoke-Adb @('pull','/sdcard/jpq-scope-baseline.png',(Join-Path $jpqOutput 'baseline.png')) | Out-Null
        Invoke-Adb @('shell','rm','-f','/sdcard/jpq-scope-baseline.png') | Out-Null
    }
}
