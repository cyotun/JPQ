param(
    [string]$Serial='emulator-5580',
    [string]$OutputDirectory='captures/emulator/unknown-diagnostic-20261021'
)
$ErrorActionPreference='Stop'
if($Serial -notmatch '^emulator-\d+$') { throw 'Only an Android emulator is allowed.' }
$jpqRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$jpqAdb=Join-Path $jpqRoot '.android-tools/sdk/platform-tools/adb.exe'
if(-not (Test-Path -LiteralPath $jpqAdb -PathType Leaf)) { throw 'Workspace Android platform-tools adb.exe is required.' }
$jpqOutput=[IO.Path]::GetFullPath((Join-Path $jpqRoot $OutputDirectory))
if(-not $jpqOutput.StartsWith($jpqRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {
    throw 'Output must stay inside the workspace.'
}
$runDir=Join-Path $jpqOutput ('run-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Path $runDir | Out-Null
$process=$null;$complete=$false;$runId=$null;$failure=$null
function Invoke-Adb([string[]]$Arguments) {
    $result=& $jpqAdb -s $Serial @Arguments 2>&1
    if($LASTEXITCODE -ne 0) { throw ($result | Out-String) }
    return $result
}
function Save-HostSummary([string]$Status,[string]$Detail) {
    [ordered]@{
        schemaVersion=1; status=$Status; detail=$Detail; runId=$runId; serial=$Serial
        completedWallMs=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        instrumentationLog=(Join-Path $runDir 'instrumentation.txt')
        samplingJson=(Join-Path $runDir 'candidate-unknown-diagnostic.json')
        diagnosticZip=(Join-Path $runDir 'candidate-unknown-diagnostic.zip')
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runDir 'host-summary.json')
}
try {
    $projection=Invoke-Adb @('shell','dumpsys','media_projection') | Out-String
    if($projection -notmatch 'Media Projection:\s*null') { throw 'Another projection is active; stop it before this emulator-only test.' }

    $diagnosticRunNonce=[guid]::NewGuid().ToString('D')
    $instrumentArgs=@('-s',$Serial,'shell','am','instrument','-w','-e','candidateUnknownDiagnostic','true',
        '-e','diagnosticRunNonce',$diagnosticRunNonce,
        '-e','class','com.cyotun.jpq.vision.CandidateUnknownDiagnosticTest',
        'com.cyotun.jpq.candidate.tests/androidx.test.runner.AndroidJUnitRunner')
    $process=Start-Process -FilePath $jpqAdb -ArgumentList $instrumentArgs -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $runDir 'instrumentation.txt') `
        -RedirectStandardError (Join-Path $runDir 'instrumentation-stderr.txt') -PassThru

    $readyPath='/sdcard/Android/data/com.cyotun.jpq.candidate/files/phone-preflight/candidate-unknown-diagnostic-ready.json'
    $ready=$null
    for($i=0;$i -lt 100;$i++) {
        $remoteReady=& $jpqAdb -s $Serial shell cat $readyPath 2>$null
        if($LASTEXITCODE -eq 0) {
            try { $candidateReady=($remoteReady | Out-String) | ConvertFrom-Json }
            catch { $candidateReady=$null }
            if($candidateReady -and $candidateReady.runId -and
                $candidateReady.diagnosticRunNonce -eq $diagnosticRunNonce -and
                $candidateReady.expectedCaptureTargetPackage -eq 'com.cyotun.jpq.tests' -and
                $candidateReady.expectedCaptureScope -eq 'APP_TASK') {
                $ready=$candidateReady;break
            }
        }
        if($process.HasExited) { throw 'Unknown-diagnostic instrumentation ended before readiness.' }
        Start-Sleep -Milliseconds 200
    }
    if(-not $ready) { throw "Readiness record did not answer this run's random challenge." }
    $runId=[string]$ready.runId
    $ready | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runDir 'ready.json')

    # Create the dedicated helper task only after the test has published its
    # fresh readiness marker. Consent then selects that task via the standard UI.
    Invoke-Adb @('shell','am','start','-W','-n','com.cyotun.jpq.tests/com.cyotun.jpq.vision.ScopeProbeActivity',
        '--ez','scopeProbe','true','--es','orientation','landscape','--ei','timeoutSeconds','90') | Out-Null
    & (Join-Path $PSScriptRoot 'start-emulator-candidate-consent.ps1') `
        -Serial $Serial -FixtureCase 0 -RenderMode image `
        -OutputDirectory ([IO.Path]::GetRelativePath($jpqRoot,(Join-Path $runDir 'consent')))

    # The system picker can return to its owner task. Bring the same selected
    # helper activity back to the foreground so APP_TASK sees the real fixture.
    Invoke-Adb @('shell','am','start','-W','-n','com.cyotun.jpq.tests/com.cyotun.jpq.vision.ScopeProbeActivity',
        '--ez','scopeProbe','true','--es','orientation','landscape','--ei','timeoutSeconds','90') | Out-Null
    Invoke-Adb @('shell','dumpsys','media_projection') | Set-Content -LiteralPath (Join-Path $runDir 'projection-during.txt')

    $deadline=[DateTime]::UtcNow.AddSeconds(190)
    while(-not $process.HasExited -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 300 }
    if(-not $process.HasExited) { throw 'Unknown-diagnostic instrumentation timed out.' }
    $process.WaitForExit()

    $remoteRoot='/sdcard/Android/data/com.cyotun.jpq.candidate/files/phone-preflight'
    Invoke-Adb @('pull',"$remoteRoot/candidate-unknown-diagnostic.json",(Join-Path $runDir 'candidate-unknown-diagnostic.json')) | Out-Null
    Invoke-Adb @('pull',"$remoteRoot/candidate-unknown-diagnostic.zip",(Join-Path $runDir 'candidate-unknown-diagnostic.zip')) | Out-Null
    $finalProjection=Invoke-Adb @('shell','dumpsys','media_projection') | Out-String
    $finalProjection | Set-Content -LiteralPath (Join-Path $runDir 'projection-stopped.txt')
    if($finalProjection -notmatch 'Media Projection:\s*null') { throw 'System projection was not released after normal STOP.' }
    $sampling=Get-Content -LiteralPath (Join-Path $runDir 'candidate-unknown-diagnostic.json') -Raw | ConvertFrom-Json
    if($sampling.runId -ne $runId -or $sampling.success -ne $true -or
        $sampling.captureScope -ne 'APP_TASK' -or $sampling.verifiedDiagnosticCount -lt 2 -or
        $sampling.scope -ne 'emulator_scope_probe_unknown_diagnostic_only') {
        throw 'Exported report is missing two verified UNKNOWN diagnostic frames from this run.'
    }
    if((Get-Content -LiteralPath (Join-Path $runDir 'instrumentation.txt') -Raw) -notmatch 'OK \(1 test\)') {
        throw 'Instrumentation did not report a passing unknown-diagnostic test.'
    }
    $complete=$true
    Save-HostSummary 'PASS' 'Two complete sparse UNKNOWN diagnostic images matched one new APP_TASK session and the projection stopped.'
    Write-Output "PASS emulator-only unknown diagnostic run=$runId at $runDir"
} catch {
    $failure=$_.Exception.Message
    throw
} finally {
    if(-not $complete) {
        if($process -and -not $process.HasExited) {
            $failureDeadline=[DateTime]::UtcNow.AddSeconds(75)
            while(-not $process.HasExited -and [DateTime]::UtcNow -lt $failureDeadline) { Start-Sleep -Milliseconds 300 }
            if(-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
        }
        $active=try { Invoke-Adb @('shell','dumpsys','media_projection') | Out-String } catch { '' }
        if($active -match 'packageName: com\.cyotun\.jpq\.candidate') {
            try { Invoke-Adb @('shell','am','force-stop','com.cyotun.jpq.candidate') | Out-Null } catch { }
        }
        $remoteRoot='/sdcard/Android/data/com.cyotun.jpq.candidate/files/phone-preflight'
        try { Invoke-Adb @('pull',"$remoteRoot/candidate-unknown-diagnostic.json",(Join-Path $runDir 'candidate-unknown-diagnostic.json')) | Out-Null } catch { }
        try { Invoke-Adb @('pull',"$remoteRoot/candidate-unknown-diagnostic.zip",(Join-Path $runDir 'candidate-unknown-diagnostic.zip')) | Out-Null } catch { }
    }
    try {
        $final=Invoke-Adb @('shell','dumpsys','media_projection') | Out-String
        if(-not $complete -and $final -match 'packageName: com\.cyotun\.jpq\.candidate') {
            Invoke-Adb @('shell','am','force-stop','com.cyotun.jpq.candidate') | Out-Null
            $final=Invoke-Adb @('shell','dumpsys','media_projection') | Out-String
        }
        if($final -match 'Media Projection:\s*null') {
            Invoke-Adb @('shell','am','force-stop','com.cyotun.jpq.tests') | Out-Null
        }
        $final | Set-Content -LiteralPath (Join-Path $runDir 'projection-finally.txt')
    } catch { }
    Save-HostSummary $(if($complete) { 'PASS' } else { 'FAIL' }) $(if($complete) { 'Verified.' } else { $failure })
}
