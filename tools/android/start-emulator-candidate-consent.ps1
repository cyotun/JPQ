param([string]$Serial='emulator-5580',[string]$OutputDirectory='captures/emulator/capture-api37-20261020/candidate')
$ErrorActionPreference='Stop'
if($Serial -notmatch '^emulator-\d+$') { throw 'Emulator only.' }
$jpqRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$jpqAdb=Join-Path $jpqRoot '.android-tools/sdk/platform-tools/adb.exe'
$jpqOutput=[IO.Path]::GetFullPath((Join-Path $jpqRoot $OutputDirectory))
if(-not $jpqOutput.StartsWith($jpqRoot+[IO.Path]::DirectorySeparatorChar)) { throw 'Output must stay in workspace.' }
New-Item -ItemType Directory -Force -Path $jpqOutput | Out-Null
$jpqSequence=0
function Invoke-Adb([string[]]$Arguments) {
    $result=& $jpqAdb -s $Serial @Arguments 2>&1
    if($LASTEXITCODE -ne 0) { throw ($result | Out-String) }
    return $result
}
function Read-Ui {
    $script:jpqSequence++
    $remote="/sdcard/jpq-candidate-consent-$jpqSequence.xml"
    Invoke-Adb @('shell','uiautomator','dump','--compressed',$remote) | Out-Null
    $local=Join-Path $jpqOutput "ui-$jpqSequence.xml"
    Invoke-Adb @('pull',$remote,$local) | Out-Null
    [xml]$ui=Get-Content -LiteralPath $local -Raw
    return $ui
}
function Tap-Node($Node) {
    if($Node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'Invalid bounds.' }
    Invoke-Adb @('shell','input','-d','0','tap',"$([int](([int]$Matches[1]+[int]$Matches[3])/2))","$([int](([int]$Matches[2]+[int]$Matches[4])/2))") | Out-Null
}
$projection=Invoke-Adb @('shell','dumpsys','media_projection') | Out-String
if($projection -notmatch 'Media Projection:\s*null') { throw 'Another projection is active.' }
Invoke-Adb @('shell','am','start','-W','-f','0x10008000','-n','com.cyotun.jpq.candidate/com.cyotun.jpq.MainActivity','--es','page','home') | Out-Null
$ui=Read-Ui
$start=@($ui.SelectNodes('//node') | Where-Object { $_.package -eq 'com.cyotun.jpq.candidate' -and $_.text -eq '开始记牌' })
if($start.Count -ne 1) { throw 'Candidate start button missing.' }
Tap-Node $start[0]
Start-Sleep -Milliseconds 500
$ui=Read-Ui
if(-not @($ui.SelectNodes('//node') | Where-Object { $_.package -eq 'com.android.systemui' }).Count) { throw 'Standard consent missing.' }
$next=@($ui.SelectNodes('//node') | Where-Object { $_.package -eq 'com.android.systemui' -and $_.text -eq 'Next' })
if($next.Count -ne 1) { throw 'APP-only Next button missing; inspect saved UI.' }
Tap-Node $next[0]
$ui=Read-Ui
$fixture=@($ui.SelectNodes('//node') | Where-Object { $_.text -eq '记牌器手机测试' -or $_.'content-desc' -eq '记牌器手机测试' })
if($fixture.Count -ne 1) { throw 'Dedicated fixture task missing.' }
Tap-Node $fixture[0]
Start-Sleep -Seconds 2
$projection=Invoke-Adb @('shell','dumpsys','media_projection') | Out-String
$projection | Set-Content -LiteralPath (Join-Path $jpqOutput 'projection.txt')
$package=Invoke-Adb @('shell','dumpsys','package','com.cyotun.jpq.tests') | Out-String
if($package -notmatch '(?:userId|appId)=(\d+)') { throw 'Fixture UID unavailable.' }
$fixtureUid=$Matches[1]
if($projection -notmatch 'packageName: com.cyotun.jpq.candidate' -or $projection -notmatch 'RECORD_CONTENT_TASK' -or
    $projection -notmatch "targetUid = $fixtureUid(?:\s|})" -or
    $projection -notmatch 'tokenToRecord = [^\r\n]*com\.cyotun\.jpq\.tests') { throw 'Projection target identity mismatch.' }
Write-Output 'Normal APP-only consent completed; actual projection targets the dedicated fixture UID.'
