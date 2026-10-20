param(
    [Parameter(Mandatory=$true)][string]$Python,
    [Parameter(Mandatory=$true)][string]$Inputs,
    [Parameter(Mandatory=$true)][string]$OutputDirectory
)
$ErrorActionPreference='Stop'
$jpqRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
function Resolve-Jpq([string]$value) {
    if([IO.Path]::IsPathRooted($value)) { return [IO.Path]::GetFullPath($value) }
    return [IO.Path]::GetFullPath((Join-Path $jpqRoot $value))
}
function Get-JpqFingerprint {
    $files=@('app/src/main','core/src','tools/desktop') | ForEach-Object {
        Get-ChildItem -LiteralPath (Join-Path $jpqRoot $_) -File -Recurse |
            Where-Object { $_.FullName -notmatch '[\\/]__pycache__[\\/]' -and $_.Extension -ne '.pyc' }
    }
    $files+=@('core/build.gradle.kts','app/build.gradle.kts','build.gradle.kts','settings.gradle.kts','gradle.properties','tools/android/build.ps1') |
        ForEach-Object { Get-Item -LiteralPath (Join-Path $jpqRoot $_) }
    $records=@($files|Sort-Object FullName|ForEach-Object {
        [ordered]@{path=$_.FullName.Substring($jpqRoot.Length+1);sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
    })
    return ConvertTo-Json -InputObject $records -Compress
}
$jpqOutput=Resolve-Jpq $OutputDirectory
$jpqCaptures=(Join-Path $jpqRoot 'captures')+[IO.Path]::DirectorySeparatorChar
if(!$jpqOutput.StartsWith($jpqCaptures,[StringComparison]::OrdinalIgnoreCase) -or
    (Test-Path -LiteralPath $jpqOutput)) { throw 'Use a new private captures directory.' }
$jpqParent=[IO.Path]::GetDirectoryName($jpqOutput)
while($jpqParent -and !$jpqParent.Equals($jpqRoot,[StringComparison]::OrdinalIgnoreCase)) {
    if((Test-Path -LiteralPath $jpqParent) -and
        ((Get-Item -LiteralPath $jpqParent -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Output ancestors must not redirect outside the private captures tree.'
    }
    $jpqParent=[IO.Path]::GetDirectoryName($jpqParent)
}
$jpqInputPath=Resolve-Jpq $Inputs
$jpqCases=@(Get-Content -LiteralPath $jpqInputPath -Raw -Encoding UTF8|ConvertFrom-Json)
if(!$jpqCases.Count -or @($jpqCases.id|Sort-Object -Unique).Count -ne $jpqCases.Count) { throw 'Cases need unique IDs.' }
foreach($case in $jpqCases) {
    if($case.id -notmatch '^[A-Za-z0-9_-]{1,80}$') { throw 'Invalid case ID.' }
    $manifest=Get-Content -LiteralPath (Resolve-Jpq $case.manifest) -Raw -Encoding UTF8|ConvertFrom-Json
    if($manifest.kind -eq 'video' -and !$case.expected) { throw 'Video cases require independent manual labels.' }
}
New-Item -ItemType Directory -Path $jpqOutput|Out-Null
Copy-Item -LiteralPath $jpqInputPath -Destination (Join-Path $jpqOutput 'suite-inputs.json')
$jpqBefore=Get-JpqFingerprint
$jpqBefore|Set-Content -LiteralPath (Join-Path $jpqOutput 'source-fingerprint.json') -Encoding UTF8
$jpqResults=@()
foreach($case in $jpqCases) {
    $caseOutput=Join-Path $jpqOutput $case.id
    $record=[ordered]@{id=$case.id;passed=$false;problem=$null}
    try {
        $frozenManifest=Join-Path $jpqOutput ($case.id+'-input.json')
        Copy-Item -LiteralPath (Resolve-Jpq $case.manifest) -Destination $frozenManifest
        $frozenExpected=$null
        if($case.expected) {
            $frozenExpected=Join-Path $jpqOutput ($case.id+'-expected.json')
            Copy-Item -LiteralPath (Resolve-Jpq $case.expected) -Destination $frozenExpected
        }
        & $Python -B (Join-Path $PSScriptRoot 'run_model_replay.py') --manifest $frozenManifest --output $caseOutput *> (Join-Path $jpqOutput ($case.id+'-model.txt'))
        if($LASTEXITCODE -ne 0) { throw 'Fresh model inference or fixture comparison failed.' }
        $report=Get-Content -LiteralPath (Join-Path $caseOutput 'report.json') -Raw -Encoding UTF8|ConvertFrom-Json
        if($report.status -ne 'inference_complete') { throw 'Inference did not complete.' }
        if($report.kind -eq 'images') {
            if(!$report.samples.Count -or @($report.samples|Where-Object { !$_.phoneReferencePresent -or $_.phoneColorMultisetMatches -ne $true }).Count) {
                throw 'Every static fixture needs an explicit comparison and a match.'
            }
        } else {
            & (Join-Path $jpqRoot 'tools/android/build.ps1') -Tasks @(':core:replayPhoneVideo',
                ('-PreplayTape='+(Join-Path $caseOutput 'model-replay.tape')),
                ('-PreplayOutput='+(Join-Path $caseOutput 'core.json'))) *> (Join-Path $jpqOutput ($case.id+'-core.txt'))
            if($LASTEXITCODE -ne 0) { throw 'Shared-core replay failed.' }
            & $Python -B (Join-Path $PSScriptRoot 'validate_round_replay.py') --actual (Join-Path $caseOutput 'core.json') --expected $frozenExpected --inference-report (Join-Path $caseOutput 'report.json') --output (Join-Path $caseOutput 'manual-validation.json') *> (Join-Path $jpqOutput ($case.id+'-validation.txt'))
            if($LASTEXITCODE -ne 0) { throw 'Independent manual play comparison failed.' }
        }
        $record.passed=$true
    } catch { $record.problem=$_.Exception.Message }
    $jpqResults+=$record
    # A failure is retained; independent remaining cases still run so a batch
    # can expose related problems before the next repair, rather than retesting
    # a different partial subset after every small change.
}
$jpqUnchanged=$jpqBefore -eq (Get-JpqFingerprint)
$jpqPassed=$jpqUnchanged -and !@($jpqResults|Where-Object { !$_.passed }).Count
$jpqSummary=[ordered]@{schemaVersion=1;passed=$jpqPassed;codeUnchanged=$jpqUnchanged;cases=$jpqResults;
    scope='Fresh desktop FULL model plus supplied manual labels; not Android capture, OCR, power or phone acceptance';phoneAcceptancePassed=$false}
$jpqSummary|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $jpqOutput 'summary.json') -Encoding UTF8
$jpqSummary|ConvertTo-Json -Depth 8
if(!$jpqPassed) { exit 1 }
