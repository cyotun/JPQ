#requires -Version 7.0
param(
    [string]$DeviceSerial = '',
    [string]$EvidenceDirectory = '',
    [switch]$RecoverOnly
)
$ErrorActionPreference='Stop'
$jpqRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$jpqAdb=Join-Path $jpqRoot '.android-tools/sdk/platform-tools/adb.exe'
if(-not (Test-Path -LiteralPath $jpqAdb)) { throw 'The local Android platform tools are not installed.' }

if([string]::IsNullOrWhiteSpace($EvidenceDirectory)) {
    if($RecoverOnly) { throw 'Pass -EvidenceDirectory from the interrupted run.' }
    $EvidenceDirectory=Join-Path $jpqRoot ('captures/phone/ui-suite-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N'))
} elseif(-not [IO.Path]::IsPathRooted($EvidenceDirectory)) {
    $EvidenceDirectory=Join-Path $jpqRoot $EvidenceDirectory
}
$jpqEvidence=[IO.Path]::GetFullPath($EvidenceDirectory)
$jpqAllowed=[IO.Path]::GetFullPath((Join-Path $jpqRoot 'captures/phone'))+[IO.Path]::DirectorySeparatorChar
if(-not $jpqEvidence.StartsWith($jpqAllowed,[StringComparison]::OrdinalIgnoreCase)) { throw 'UI evidence must stay under captures/phone.' }
$jpqManifestPath=Join-Path $jpqEvidence 'ui-run.json'

function Write-UiManifest([hashtable]$Values) {
    if($Values.Count -gt 0) { $script:jpqManifest|Add-Member -NotePropertyMembers $Values -Force }
    $temporary="$jpqManifestPath.pending"
    [IO.File]::WriteAllText($temporary,($script:jpqManifest|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $jpqManifestPath -Force
}

function Invoke-UiAdb([string[]]$Arguments,[string]$LogPath) {
    $output=& $jpqAdb @Arguments 2>&1 | Out-String
    $exitCode=$LASTEXITCODE
    [IO.File]::WriteAllText($LogPath,$output,[Text.UTF8Encoding]::new($false))
    [pscustomobject]@{ExitCode=$exitCode;Output=$output;Ok=($exitCode -eq 0)}
}

function Invoke-UiInstrumentation([string]$RunId,[string]$Action,[string]$TestClass,[string]$LogName) {
    $jpqInstrumentationArgs=@('-s',$script:jpqDevice,'shell','am','instrument','-w','-r',
        '-e','displayConfigAction',$Action,'-e','displayConfigRunId',$RunId)
    if($Action -eq 'run') {
        $jpqInstrumentationArgs+=@('-e','displayInteractionProbe','true','-e','appUiProbe','true',
            '-e','endToEndCaptureProbe','true','-e','class',$TestClass)
    } else { $jpqInstrumentationArgs+=@('-e','class',"com.cyotun.jpq.vision.DeviceEvidenceTest#$TestClass") }
    $jpqInstrumentationArgs+='com.cyotun.jpq.tests/androidx.test.runner.AndroidJUnitRunner'
    $result=Invoke-UiAdb $jpqInstrumentationArgs (Join-Path $jpqEvidence $LogName)
    $passed=$result.Ok -and $result.Output -match '(?m)^OK \(\d+ tests?\)\s*$' -and
        $result.Output -notmatch 'FAILURES!!!|shortMsg=Process crashed' -and
        $result.Output -notmatch '(?m)^INSTRUMENTATION_STATUS_CODE: -[1-4]\s*$'
    [pscustomobject]@{Passed=$passed;ExitCode=$result.ExitCode;Output=$result.Output}
}

function Resolve-UiDevice([string]$Requested) {
    if($Requested) { return $Requested }
    $devices=Invoke-UiAdb @('devices') (Join-Path $jpqEvidence 'adb-devices.txt')
    if(-not $devices.Ok) { throw 'Could not read the connected Android devices.' }
    $connected=@([regex]::Matches($devices.Output,'(?m)^([^\s]+)\s+device\s*$')|ForEach-Object {$_.Groups[1].Value})
    if($connected.Count -ne 1) { throw 'Pass -DeviceSerial when exactly one authorized Android device is not connected.' }
    return $connected[0]
}

function Assert-StoppedCapture {
    # Instrumentation may replace the app process before AppState.running can
    # be checked. Inspect the system first, without starting an instrumentation.
    $services=Invoke-UiAdb @('-s',$script:jpqDevice,'shell','dumpsys','activity','services','com.cyotun.jpq') (Join-Path $jpqEvidence 'preflight-services.txt')
    $projection=Invoke-UiAdb @('-s',$script:jpqDevice,'shell','dumpsys','media_projection') (Join-Path $jpqEvidence 'preflight-projection.txt')
    if(-not $services.Ok -or -not $projection.Ok) { throw 'Cannot verify stopped capture; no instrumentation was started.' }
    if($services.Output -match 'ServiceRecord[^\r\n]*com\.cyotun\.jpq/[^\r\n]*CaptureService' -or
        $projection.Output -notmatch 'Media Projection:\s*null') {
        throw 'A capture service or screen sharing is active. Finish the match and stop sharing before running UI tests; no instrumentation was started.'
    }
}

if($RecoverOnly) {
    if(-not (Test-Path -LiteralPath $jpqManifestPath)) { throw 'The UI run manifest is missing; no device restore was attempted.' }
    $script:jpqManifest=Get-Content -LiteralPath $jpqManifestPath -Raw|ConvertFrom-Json
    $jpqRunId=[string]$script:jpqManifest.runId
    if($jpqRunId -notmatch '^[A-Za-z0-9._-]{1,80}$') { throw 'The saved run id is invalid.' }
    if(-not $DeviceSerial) { $DeviceSerial=[string]$script:jpqManifest.deviceSerial }
    $script:jpqDevice=Resolve-UiDevice $DeviceSerial
    New-Item -ItemType Directory -Path $jpqEvidence -Force|Out-Null
    Assert-StoppedCapture
    Write-UiManifest @{deviceSerial=$script:jpqDevice;recoveryStartedUtc=[DateTimeOffset]::UtcNow.ToString('o')}
    $restore=Invoke-UiInstrumentation $jpqRunId 'restore' 'restoreDisplayConfigAfterUiTestRun' 'restore-instrumentation.txt'
    if(-not $restore.Passed) {
        Write-UiManifest @{recoveryStatus='failed';restoreExitCode=$restore.ExitCode}
        throw "Display config restore failed. Evidence and on-device backup remain at $jpqEvidence."
    }
    $remote="/sdcard/Android/data/com.cyotun.jpq/files/phone-preflight/ui-config-$jpqRunId"
    $pull=Invoke-UiAdb @('-s',$script:jpqDevice,'pull',$remote,(Join-Path $jpqEvidence 'device-config-recovery')) (Join-Path $jpqEvidence 'restore-pull.txt')
    if(-not $pull.Ok) {
        Write-UiManifest @{recoveryStatus='restored_device_evidence_pull_failed'}
        throw "Config was restored, but device evidence could not be copied. Run recovery again; evidence remains on device for $jpqRunId."
    }
    Write-UiManifest @{recoveryStatus='restored';recoveredAtUtc=[DateTimeOffset]::UtcNow.ToString('o')}
    Write-Output "Display config restored. Evidence: $jpqEvidence"
    return
}

if(Test-Path -LiteralPath $jpqEvidence) { throw 'Use a new UI evidence directory.' }
New-Item -ItemType Directory -Path $jpqEvidence -Force|Out-Null
$script:jpqDevice=Resolve-UiDevice $DeviceSerial
Assert-StoppedCapture
$jpqRunId=[guid]::NewGuid().ToString('N')
$script:jpqManifest=[pscustomobject]@{
    schemaVersion=1;runId=$jpqRunId;deviceSerial=$script:jpqDevice
    startedAtUtc=[DateTimeOffset]::UtcNow.ToString('o');recoveryCommand="pwsh -NoProfile -File tools/android/run-ui-suite.ps1 -RecoverOnly -EvidenceDirectory `"$jpqEvidence`""
    testClasses=@('com.cyotun.jpq.vision.DisplayInteractionTest','com.cyotun.jpq.vision.HomeNavigationTest',
        'com.cyotun.jpq.vision.OverlayPersistenceTest','com.cyotun.jpq.vision.EndToEndCaptureTest')
    backupStatus='pending';testStatus='not_started';recoveryStatus='pending'
}
Write-UiManifest @{}
$jpqBackedUp=$false
$jpqTestError=$null
$jpqRestoreError=$null
try {
    $backup=Invoke-UiInstrumentation $jpqRunId 'backup' 'backupDisplayConfigForUiTestRun' 'backup-instrumentation.txt'
    if(-not $backup.Passed) { throw 'Could not create a persistent device display-config backup; the UI suite was not started.' }
    $remote="/sdcard/Android/data/com.cyotun.jpq/files/phone-preflight/ui-config-$jpqRunId"
    $pull=Invoke-UiAdb @('-s',$script:jpqDevice,'pull',$remote,(Join-Path $jpqEvidence 'device-config-before-tests')) (Join-Path $jpqEvidence 'backup-pull.txt')
    if(-not $pull.Ok -or -not (Test-Path -LiteralPath (Join-Path $jpqEvidence 'device-config-before-tests/backup.json'))) {
        throw 'Device backup exists, but its host evidence copy failed; the UI suite was not started.'
    }
    $jpqBackedUp=$true
    Write-UiManifest @{backupStatus='saved_on_device_and_host';backupAtUtc=[DateTimeOffset]::UtcNow.ToString('o')}
    $classes=$script:jpqManifest.testClasses -join ','
    Write-UiManifest @{testStatus='running';testStartedAtUtc=[DateTimeOffset]::UtcNow.ToString('o')}
    $suite=Invoke-UiInstrumentation $jpqRunId 'run' $classes 'ui-suite.txt'
    if(-not $suite.Passed) { throw "UI suite failed or was interrupted; inspect $jpqEvidence/ui-suite.txt." }
    Write-UiManifest @{testStatus='passed';testFinishedAtUtc=[DateTimeOffset]::UtcNow.ToString('o')}
} catch {
    $jpqTestError=$_.Exception.Message
    Write-UiManifest @{testStatus='failed_or_interrupted';testError=$jpqTestError;testFinishedAtUtc=[DateTimeOffset]::UtcNow.ToString('o')}
} finally {
    if($jpqBackedUp) {
        try {
            $restore=Invoke-UiInstrumentation $jpqRunId 'restore' 'restoreDisplayConfigAfterUiTestRun' 'restore-instrumentation.txt'
            if(-not $restore.Passed) { throw 'Restore instrumentation did not report success.' }
            $remote="/sdcard/Android/data/com.cyotun.jpq/files/phone-preflight/ui-config-$jpqRunId"
            $pull=Invoke-UiAdb @('-s',$script:jpqDevice,'pull',$remote,(Join-Path $jpqEvidence 'device-config-after-tests')) (Join-Path $jpqEvidence 'restore-pull.txt')
            if(-not $pull.Ok) { throw 'Device restore evidence copy failed.' }
            Write-UiManifest @{recoveryStatus='restored';recoveredAtUtc=[DateTimeOffset]::UtcNow.ToString('o')}
        } catch {
            $jpqRestoreError=$_.Exception.Message
            Write-UiManifest @{recoveryStatus='restore_pending';restoreError=$jpqRestoreError}
        }
    } else {
        Write-UiManifest @{recoveryStatus='no_config_mutation_started'}
    }
}

if($jpqRestoreError) {
    throw "$jpqRestoreError Use the persisted recovery command in $jpqManifestPath after the test host is stopped."
}
if($jpqTestError) { throw "$jpqTestError Display config restore completed. Evidence: $jpqEvidence" }
Write-Output "UI suite passed and display config was restored. Evidence: $jpqEvidence"
