param(
    [string]$Serial='emulator-5580',
    [string]$OutputDirectory='captures/emulator/capture-api37-20261020/candidate',
    [ValidateRange(0,4)][int]$FixtureCase=0,
    [ValidateSet('image','surface','gl')][string]$RenderMode='image',
    [switch]$RecreateSurfaceAfterConsent
)
$ErrorActionPreference='Stop'
if($Serial -notmatch '^emulator-\d+$') { throw 'Emulator only.' }
if($RecreateSurfaceAfterConsent -and ($RenderMode -notin @('surface','gl') -or $FixtureCase -eq 0)) { throw 'Surface recreation requires -RenderMode surface or gl and a fixture case.' }
$fixtureActivity=switch($RenderMode) {
    'surface' { 'com.cyotun.jpq.vision.SurfaceCardFixtureProbeActivity' }
    'gl' { 'com.cyotun.jpq.vision.GlCardFixtureProbeActivity' }
    default { 'com.cyotun.jpq.vision.CardFixtureProbeActivity' }
}
$jpqRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$jpqAdb=Join-Path $jpqRoot '.android-tools/sdk/platform-tools/adb.exe'
$jpqOutput=[IO.Path]::GetFullPath((Join-Path $jpqRoot $OutputDirectory))
if(-not $jpqOutput.StartsWith($jpqRoot+[IO.Path]::DirectorySeparatorChar)) { throw 'Output must stay in workspace.' }
New-Item -ItemType Directory -Force -Path $jpqOutput | Out-Null
$jpqSequence=0
$jpqUiRun=[Guid]::NewGuid().ToString('N')
function Invoke-Adb([string[]]$Arguments) {
    $result=& $jpqAdb -s $Serial @Arguments 2>&1
    if($LASTEXITCODE -ne 0) { throw ($result | Out-String) }
    return $result
}
function Read-Ui {
    $script:jpqSequence++
    $remote="/sdcard/jpq-candidate-consent-$jpqUiRun-$jpqSequence.xml"
    $deadline=[DateTime]::UtcNow.AddSeconds(20)
    try {
        do {
            # A failed dump can leave an old XML behind and still exit zero.
            # Never pull a previous observation as evidence for the current UI.
            Invoke-Adb @('shell','rm','-f',$remote) | Out-Null
            $dump=Invoke-Adb @('shell','uiautomator','dump','--compressed',$remote) | Out-String
            if($dump.Contains('dumped to:') -and $dump.Contains($remote)) {
                $local=Join-Path $jpqOutput "ui-$jpqSequence.xml"
                Invoke-Adb @('pull',$remote,$local) | Out-Null
                [xml]$ui=Get-Content -LiteralPath $local -Raw -Encoding UTF8
                if($ui.SelectNodes('/hierarchy/node').Count -gt 0) { return $ui }
            }
            Start-Sleep -Milliseconds 250
        } while([DateTime]::UtcNow -lt $deadline)
        throw 'No fresh UI hierarchy was produced; do not use an old XML.'
    } finally {
        Invoke-Adb @('shell','rm','-f',$remote) | Out-Null
    }
}
function Tap-Node($Node) {
    if($Node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'Invalid bounds.' }
    Invoke-Adb @('shell','input','-d','0','tap',"$([int](([int]$Matches[1]+[int]$Matches[3])/2))","$([int](([int]$Matches[2]+[int]$Matches[4])/2))") | Out-Null
}
$projection=Invoke-Adb @('shell','dumpsys','media_projection') | Out-String
if($projection -notmatch 'Media Projection:\s*null') { throw 'Another projection is active.' }
Invoke-Adb @('shell','am','start','-W','-f','0x10008000','-n','com.cyotun.jpq.candidate/com.cyotun.jpq.MainActivity','--es','page','home') | Out-Null
$start=@()
for($i=0;$i -lt 4;$i++) {
    $ui=Read-Ui
    $start=@($ui.SelectNodes('//node') | Where-Object { $_.package -eq 'com.cyotun.jpq.candidate' -and $_.text -eq '开始记牌' })
    if($start.Count -eq 1) { break }
    Start-Sleep -Milliseconds 200
}
if($start.Count -ne 1) { throw 'Candidate start button missing.' }
Tap-Node $start[0]
$deadline=[DateTime]::UtcNow.AddSeconds(25)
$next=@()
do {
    $ui=Read-Ui
    $next=@($ui.SelectNodes('//node') | Where-Object { $_.package -eq 'com.android.systemui' -and $_.text -eq 'Next' })
    if($next.Count -eq 1) { break }
    Start-Sleep -Milliseconds 250
} while([DateTime]::UtcNow -lt $deadline)
if($next.Count -ne 1) { throw 'APP-only Next button missing; inspect saved UI.' }
Tap-Node $next[0]
$deadline=[DateTime]::UtcNow.AddSeconds(20)
$fixture=@()
do {
    $ui=Read-Ui
    $fixture=@($ui.SelectNodes('//node') | Where-Object { $_.text -eq '记牌器手机测试' -or $_.'content-desc' -eq '记牌器手机测试' })
    if($fixture.Count -eq 1) { break }
    Start-Sleep -Milliseconds 250
} while([DateTime]::UtcNow -lt $deadline)
if($fixture.Count -ne 1) { throw 'Dedicated fixture task missing.' }
Tap-Node $fixture[0]
if($FixtureCase -gt 0) {
    $fixtureArgs=@('shell','am','start','-W','-n',"com.cyotun.jpq.tests/$fixtureActivity",'--ez','cardFixtureProbe','true','--ei','caseIndex',"$FixtureCase",'--es','orientation','landscape','--ei','timeoutSeconds','180')
    if($RecreateSurfaceAfterConsent) { $fixtureArgs+=@('--ez','recreateSurface','true') }
    Invoke-Adb $fixtureArgs | Out-Null
}
$package=Invoke-Adb @('shell','dumpsys','package','com.cyotun.jpq.tests') | Out-String
if($package -notmatch '(?:userId|appId)=(\d+)') { throw 'Fixture UID unavailable.' }
$fixtureUid=$Matches[1]
$projection=''
for($i=0;$i -lt 40;$i++) {
    $projection=Invoke-Adb @('shell','dumpsys','media_projection') | Out-String
    $projection | Set-Content -LiteralPath (Join-Path $jpqOutput 'projection.txt')
    if($projection -match 'RECORD_CONTENT_TASK' -and $projection -match "targetUid = $fixtureUid(?:\s|})") { break }
    Start-Sleep -Milliseconds 250
}
if($projection -notmatch 'packageName: com.cyotun.jpq.candidate' -or $projection -notmatch 'RECORD_CONTENT_TASK' -or
    $projection -notmatch "targetUid = $fixtureUid(?:\s|})" -or
    $projection -notmatch 'tokenToRecord = [^\r\n]*com\.cyotun\.jpq\.tests') { throw 'Projection target identity mismatch.' }
Write-Output "Normal APP-only consent completed; actual projection targets the dedicated fixture UID (render=$RenderMode recreateSurfaceAfterConsent=$RecreateSurfaceAfterConsent)."
