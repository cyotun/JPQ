param(
    [string]$Serial='f29c66d0',
    [ValidateSet('fixture','game')][string]$Target='fixture',
    [switch]$GameLobbyConfirmed,
    [string]$EvidenceRoot='captures/phone/beta-app-task-20261021',
    [string]$OutputDirectory=''
)
$ErrorActionPreference='Stop'
if($Serial -ne 'f29c66d0') { throw 'This preflight is limited to the explicitly authorized test phone.' }
if($Target -eq 'game' -and -not $GameLobbyConfirmed) { throw 'First visually confirm the game lobby and no real match, then pass -GameLobbyConfirmed. This is operator confirmation, not automated scene detection.' }
$jpqRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$jpqAdb=Join-Path $jpqRoot '.android-tools/sdk/platform-tools/adb.exe'
$phoneCaptureRoot=[IO.Path]::GetFullPath((Join-Path $jpqRoot 'captures/phone'))
$phoneCapturePrefix=$phoneCaptureRoot+[IO.Path]::DirectorySeparatorChar
$jpqEvidenceRoot=if([IO.Path]::IsPathRooted($EvidenceRoot)) { [IO.Path]::GetFullPath($EvidenceRoot) } else { [IO.Path]::GetFullPath((Join-Path $jpqRoot $EvidenceRoot)) }
if(-not $jpqEvidenceRoot.StartsWith($phoneCapturePrefix,[StringComparison]::OrdinalIgnoreCase)) { throw 'EvidenceRoot must stay inside captures/phone.' }
if([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $jpqOutput=[IO.Path]::GetFullPath((Join-Path $jpqEvidenceRoot 'consent'))
} elseif([IO.Path]::IsPathRooted($OutputDirectory)) {
    $jpqOutput=[IO.Path]::GetFullPath($OutputDirectory)
} elseif($OutputDirectory -match '^captures[\\/]phone([\\/]|$)') {
    # Preserve the former workspace-relative OutputDirectory form.
    $jpqOutput=[IO.Path]::GetFullPath((Join-Path $jpqRoot $OutputDirectory))
} else {
    $jpqOutput=[IO.Path]::GetFullPath((Join-Path $jpqEvidenceRoot $OutputDirectory))
}
if(-not $jpqOutput.StartsWith($phoneCapturePrefix,[StringComparison]::OrdinalIgnoreCase)) { throw 'Output must stay inside captures/phone.' }
New-Item -ItemType Directory -Force -Path $jpqOutput | Out-Null
$jpqSequence=0
$jpqUiRun=[Guid]::NewGuid().ToString('N')
function Invoke-Adb([string[]]$Arguments) {
    $result=& $jpqAdb -s $Serial @Arguments 2>&1
    if($LASTEXITCODE -ne 0) { throw ($result | Out-String) }
    return $result
}
function Read-Ui([int]$TimeoutSeconds=5) {
    $script:jpqSequence++
    $remote="/sdcard/jpq-phone-candidate-$jpqUiRun-$jpqSequence.xml"
    $local=Join-Path $jpqOutput "ui-$jpqUiRun-$jpqSequence.xml"
    $deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    try {
        do {
            Invoke-Adb @('shell','rm','-f',$remote) | Out-Null
            $dump=Invoke-Adb @('shell','uiautomator','dump','--compressed',$remote) | Out-String
            if($dump.Contains('dumped to:') -and $dump.Contains($remote)) {
                Invoke-Adb @('pull',$remote,$local) | Out-Null
                [xml]$ui=Get-Content -LiteralPath $local -Raw -Encoding UTF8
                if($ui.SelectNodes('/hierarchy/node').Count -gt 0) { return $ui }
            }
            Start-Sleep -Milliseconds 250
        } while([DateTime]::UtcNow -lt $deadline)
        throw 'No fresh UI hierarchy was produced; do not use an old XML.'
    } finally {
        & $jpqAdb -s $Serial shell rm -f $remote | Out-Null
        if($LASTEXITCODE -ne 0) { throw 'Could not clean device-side temporary UI hierarchy.' }
    }
}
function Tap-Node($Node) {
    if($Node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'Invalid bounds.' }
    $x=[int](([int]$Matches[1]+[int]$Matches[3])/2)
    $y=[int](([int]$Matches[2]+[int]$Matches[4])/2)
    Invoke-Adb @('shell','input','-d','0','tap',"$x","$y") | Out-Null
}
$projection=Invoke-Adb @('shell','dumpsys','media_projection') | Out-String
if($projection -notmatch 'Media Projection:\s*null') { throw 'Stop existing projection before consenting.' }
$policy=Invoke-Adb @('shell','dumpsys','window','policy') | Out-String
if($policy -notmatch 'showing=false') { throw 'Phone must already be unlocked.' }
Invoke-Adb @('shell','am','start','-W','-f','0x10008000','-n','com.cyotun.jpq.candidate/com.cyotun.jpq.MainActivity','--es','page','home') | Out-Null
$deadline=[DateTime]::UtcNow.AddSeconds(25)
$start=@()
do {
    $ui=Read-Ui
    $start=@($ui.SelectNodes('//node') | Where-Object { $_.package -eq 'com.cyotun.jpq.candidate' -and $_.text -eq '开始记牌' -and $_.enabled -eq 'true' })
    if($start.Count -eq 1) { break }
    Start-Sleep -Milliseconds 250
} while([DateTime]::UtcNow -lt $deadline)
if($start.Count -ne 1) { throw 'Candidate start button missing.' }
Tap-Node $start[0]
$deadline=[DateTime]::UtcNow.AddSeconds(25)
$next=@()
do {
    $ui=Read-Ui
    $next=@($ui.SelectNodes('//node') | Where-Object { $_.package -eq 'com.android.systemui' -and $_.text -in @('下一步','Next') })
    if($next.Count -eq 1) { break }
    Start-Sleep -Milliseconds 250
} while([DateTime]::UtcNow -lt $deadline)
if($next.Count -ne 1) { throw 'Expected normal APP-only system consent not found.' }
Tap-Node $next[0]
$targetLabel=if($Target -eq 'fixture') { '记牌器手机测试' } else { '大掼蛋' }
$targetPackage=if($Target -eq 'fixture') { 'com.cyotun.jpq.tests' } else { 'com.tencent.guandansy' }
$selected=$false
$deadline=[DateTime]::UtcNow.AddSeconds(25)
do {
    $ui=Read-Ui
    $title=@($ui.SelectNodes('//node') | Where-Object { $_.package -eq 'com.android.systemui' -and $_.text -in @('选择要分享的应用','选择要共享的应用','Choose app to share') })
    if($title.Count -gt 1) { throw 'Ambiguous system app picker.' }
    if($title.Count -ne 1) { Start-Sleep -Milliseconds 250; continue }
    $targetNode=@($ui.SelectNodes('//node') | Where-Object {
        $_.package -eq 'com.android.systemui' -and ($_.text -eq $targetLabel -or $_.'content-desc' -eq $targetLabel)
    })
    # A recent-task card and an all-apps label can name the same app. Prefer
    # the single enabled task card, then still verify the actual target UID.
    $cards=@($targetNode | Where-Object { $_.clickable -eq 'true' -and $_.enabled -eq 'true' })
    if($cards.Count -eq 1) { $targetNode=$cards }
    if($targetNode.Count -eq 1) { Tap-Node $targetNode[0];$selected=$true;break }
    if($targetNode.Count -gt 1) { throw 'Ambiguous target choice.' }
    $lists=@($ui.SelectNodes('//node') | Where-Object { $_.package -eq 'com.android.systemui' -and $_.scrollable -eq 'true' })
    if($lists.Count -ne 1 -or $lists[0].bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'Unambiguous app-list scroll bounds required.' }
    $scrollX=[int](([int]$Matches[1]+[int]$Matches[3])/2)
    $scrollTop=[int]$Matches[2]; $scrollHeight=[int]$Matches[4]-$scrollTop
    $fromY=[int]($scrollTop+$scrollHeight*.8); $toY=[int]($scrollTop+$scrollHeight*.3)
    Invoke-Adb @('shell','input','-d','0','swipe',"$scrollX","$fromY","$scrollX","$toY",'700') | Out-Null
} while(-not $selected -and [DateTime]::UtcNow -lt $deadline)
if(-not $selected) { throw 'Explicit target app was not found.' }
Start-Sleep -Milliseconds 500
$projection=Invoke-Adb @('shell','dumpsys','media_projection') | Out-String
$projection | Set-Content -LiteralPath (Join-Path $jpqOutput 'projection.txt')
$packageInfo=Invoke-Adb @('shell','dumpsys','package',$targetPackage) | Out-String
if($packageInfo -notmatch '(?:userId|appId)=(\d+)') { throw 'Target UID unavailable.' }
$uid=$Matches[1]
if($projection -notmatch 'packageName: com\.cyotun\.jpq\.candidate\s' -or
    $projection -notmatch 'RECORD_CONTENT_TASK' -or $projection -notmatch "targetUid = $uid(?:\s|})" -or
    $projection -notmatch ('tokenToRecord = [^\r\n]*'+[regex]::Escape($targetPackage))) { throw 'Actual projection target identity mismatch.' }
Write-Output "Normal APP-only consent verified: $targetPackage UID=$uid"
