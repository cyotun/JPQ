param(
    [ValidateSet('display','task')][string]$Scope='display',
    [string]$Serial='emulator-5580',
    [string]$OutputDirectory='captures/emulator/capture-api37-20261020'
)
$ErrorActionPreference='Stop'
if($Serial -notmatch '^emulator-\d+$') { throw 'This tool only operates on an emulator.' }
$jpqRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$jpqAdb=Join-Path $jpqRoot '.android-tools/sdk/platform-tools/adb.exe'
$jpqOutput=[IO.Path]::GetFullPath((Join-Path $jpqRoot $OutputDirectory))
if(-not $jpqOutput.StartsWith($jpqRoot+[IO.Path]::DirectorySeparatorChar)) { throw 'Output must stay in the workspace.' }
New-Item -ItemType Directory -Force -Path $jpqOutput | Out-Null
$jpqOutput=Join-Path $jpqOutput ("control-$Scope-"+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ'))
New-Item -ItemType Directory -Path $jpqOutput | Out-Null
$jpqSequence=0
function Invoke-Adb([string[]]$Arguments) {
    $result=& $jpqAdb -s $Serial @Arguments 2>&1
    if($LASTEXITCODE -ne 0) { throw ($result | Out-String) }
    return $result
}
function Get-ProjectionOwner([string]$Text) {
    $packageMatch=[regex]::Match($Text,'(?m)^\s*packageName:\s*(\S+)\s*$')
    if(-not $packageMatch.Success) { $packageMatch=[regex]::Match($Text,'(?m)^\s*package:\s*(\S+)\s*$') }
    if($packageMatch.Success) { return $packageMatch.Groups[1].Value }
    return $null
}
function Get-FixtureUid {
    $packageInfo=Invoke-Adb @('shell','dumpsys','package','com.cyotun.jpq.tests') | Out-String
    $uidMatch=[regex]::Match($packageInfo,'(?m)^\s*userId=(\d+)\s*$')
    if(-not $uidMatch.Success) { $uidMatch=[regex]::Match($packageInfo,'(?m)^\s*appId=(\d+)\s*$') }
    if(-not $uidMatch.Success) { throw 'Could not determine fixture package UID.' }
    return [int]$uidMatch.Groups[1].Value
}
function Get-PriorRuns {
    $result=& $jpqAdb -s $Serial shell ls /sdcard/Android/data/com.cyotun.jpq.tests/files/scope-probe/ 2>&1
    $exitCode=$LASTEXITCODE
    if($exitCode -eq 0) { return @($result | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ }) }
    $message=$result | Out-String
    if($message -match '(?i)no such file or directory') { return @() }
    throw $message
}
function Assert-ProjectionScope([string]$Text,[string]$ExpectedScope,[int]$FixtureUid) {
    $owner=Get-ProjectionOwner $Text
    if($owner -ne 'com.cyotun.jpq.tests') { throw "Projection owner mismatch: '$owner'." }
    if($ExpectedScope -eq 'display') {
        if($Text -notmatch 'contentToRecord\s*=\s*RECORD_CONTENT_DISPLAY' -or
           $Text -notmatch 'targetUid\s*=\s*-1') { throw 'Projection is not a whole-display capture.' }
        return
    }
    if($Text -notmatch 'contentToRecord\s*=\s*RECORD_CONTENT_TASK') { throw 'Projection is not a task capture.' }
    if($Text -notmatch "targetUid\s*=\s*$FixtureUid(?:\s|[,}])") { throw 'Task projection target UID does not match the fixture package.' }
    if($Text -notmatch 'tokenToRecord\s*=\s*[^\r\n]*com\.cyotun\.jpq\.tests') { throw 'Task projection token does not identify the fixture task.' }
}
function Read-Ui {
    $script:jpqSequence++
    $remote="/sdcard/jpq-scope-$Scope-$jpqSequence.xml"
    Invoke-Adb @('shell','uiautomator','dump','--compressed',$remote) | Out-Null
    $local=Join-Path $jpqOutput "$Scope-ui-$jpqSequence.xml"
    Invoke-Adb @('pull',$remote,$local) | Out-Null
    [xml]$ui=Get-Content -LiteralPath $local -Raw
    return $ui
}
function Tap-Node($Node) {
    if($Node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'Invalid bounds.' }
    $x=[int](([int]$Matches[1]+[int]$Matches[3])/2)
    $y=[int](([int]$Matches[2]+[int]$Matches[4])/2)
    Invoke-Adb @('shell','input','-d','0','tap',"$x","$y") | Out-Null
}
$normalStopCompleted=$false
$fixtureUid=Get-FixtureUid
try {
$projection=Invoke-Adb @('shell','dumpsys','media_projection') | Out-String
if($projection -notmatch 'Media Projection:\s*null') { throw 'Stop any existing projection first.' }
$priorRuns=@(Get-PriorRuns)
Invoke-Adb @('shell','am','start','-W','-n','com.cyotun.jpq.tests/com.cyotun.jpq.vision.ScopeProbeActivity',
    '--ez','scopeProbe','true','--es','orientation','landscape','--ei','timeoutSeconds','90') | Out-Null
$ui=Read-Ui
$start=@($ui.SelectNodes('//node') | Where-Object { $_.package -eq 'com.cyotun.jpq.tests' -and $_.'content-desc' -eq 'scope-probe-start' })
if($start.Count -ne 1) { throw 'Dedicated scope fixture must be in front.' }
Tap-Node $start[0]
Start-Sleep -Milliseconds 500
$ui=Read-Ui
if(-not @($ui.SelectNodes('//node') | Where-Object { $_.package -eq 'com.android.systemui' }).Count) { throw 'Normal System UI consent missing.' }
if($Scope -eq 'display') {
    $spinner=@($ui.SelectNodes('//node') | Where-Object { $_.'resource-id' -eq 'com.android.systemui:id/screen_share_mode_options' })
    if($spinner.Count -ne 1) { throw 'Expected scope selector missing.' }
    Tap-Node $spinner[0]
    $ui=Read-Ui
    $display=@($ui.SelectNodes('//node') | Where-Object { $_.text -eq 'Share entire screen' })
    if($display.Count -ne 1) { throw 'Whole-screen positive-control choice missing.' }
    Tap-Node $display[0]
}
$consented=$false
for($attempt=0;$attempt -lt 4;$attempt++) {
    $ui=Read-Ui
    $button=@($ui.SelectNodes('//node') | Where-Object {
        $_.package -eq 'com.android.systemui' -and $_.text -in @('Start now','Start sharing','Next','Share screen','Start')
    })
    if($button.Count -eq 1) { Tap-Node $button[0];$consented=$true;break }
    $scroll=@($ui.SelectNodes('//node') | Where-Object { $_.'resource-id' -eq 'com.android.systemui:id/screen_share_permission_dialog' })
    if($scroll.Count -ne 1) { throw 'Unexpected consent screen; inspect saved XML.' }
    Invoke-Adb @('shell','input','-d','0','swipe','1000','590','1000','180','350') | Out-Null
}
if(-not $consented) { throw 'Standard consent button was not found.' }
if($Scope -eq 'task') {
    $ui=Read-Ui
    $fixture=@($ui.SelectNodes('//node') | Where-Object {
        $_.text -eq '记牌器手机测试' -or $_.'content-desc' -eq '记牌器手机测试'
    })
    if($fixture.Count -ne 1) { throw 'Fixture task must be selected explicitly; inspect XML.' }
    Tap-Node $fixture[0]
}
Start-Sleep -Seconds 8
$projection=Invoke-Adb @('shell','dumpsys','media_projection') | Out-String
Assert-ProjectionScope $projection $Scope $fixtureUid
$projection | Set-Content -LiteralPath (Join-Path $jpqOutput "$Scope-projection.txt")
Invoke-Adb @('shell','dumpsys','window','windows') | Set-Content -LiteralPath (Join-Path $jpqOutput "$Scope-windows.txt")
Invoke-Adb @('shell','screencap','-p',"/sdcard/jpq-$Scope-physical.png") | Out-Null
Invoke-Adb @('pull',"/sdcard/jpq-$Scope-physical.png",(Join-Path $jpqOutput "$Scope-physical.png")) | Out-Null
$currentRuns=@(Get-PriorRuns)
$newRun=@($currentRuns | Where-Object { $_ -notin $priorRuns -and $_ -match '^\d+$' })
if($newRun.Count -ne 1) { throw 'Exactly one new probe run is required; no old run may be used.' }
$remoteRun='/sdcard/Android/data/com.cyotun.jpq.tests/files/scope-probe/'+$newRun[0]
Invoke-Adb @('pull',$remoteRun,(Join-Path $jpqOutput "$Scope-probe")) | Out-Null
$ui=Read-Ui
$stop=@($ui.SelectNodes('//node') | Where-Object { $_.package -eq 'com.cyotun.jpq.tests' -and $_.'content-desc' -eq 'scope-probe-stop' })
if($stop.Count -ne 1) { throw 'Normal stop button missing.' }
Tap-Node $stop[0]
Start-Sleep -Milliseconds 500
$stoppedProjection=Invoke-Adb @('shell','dumpsys','media_projection') | Out-String
$stoppedProjection | Set-Content -LiteralPath (Join-Path $jpqOutput "$Scope-stopped.txt")
if($stoppedProjection -notmatch 'Media Projection:\s*null') { throw 'Projection did not stop after the normal UI stop.' }
$normalStopCompleted=$true
Invoke-Adb @('pull',$remoteRun,(Join-Path $jpqOutput "$Scope-probe-final")) | Out-Null
Write-Output "Scope $Scope finished; inspect actual system session, simultaneous physical pixels, fresh captured frames, summary and stopped state."
} finally {
    if(-not $normalStopCompleted) {
        try {
            $active=Invoke-Adb @('shell','dumpsys','media_projection') | Out-String
            if((Get-ProjectionOwner $active) -eq 'com.cyotun.jpq.tests') {
                Invoke-Adb @('shell','am','force-stop','com.cyotun.jpq.tests') | Out-Null
                $released=$false
                for($wait=0;$wait -lt 10;$wait++) {
                    Start-Sleep -Milliseconds 500
                    $active=Invoke-Adb @('shell','dumpsys','media_projection') | Out-String
                    if($active -match 'Media Projection:\s*null') { $released=$true;break }
                    if((Get-ProjectionOwner $active) -ne 'com.cyotun.jpq.tests') { break }
                }
                if(-not $released) { Write-Warning 'Test package was stopped, but its projection did not become null within 5 seconds.' }
            }
        } catch { Write-Warning "Failure cleanup could not verify or release the fixture projection: $_" }
    }
}
