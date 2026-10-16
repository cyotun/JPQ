param(
    [Parameter(Mandatory=$true)][string]$Inputs,
    [Parameter(Mandatory=$true)][string]$SourceLock,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [Parameter(Mandatory=$true)][string]$Python,
    [string]$Exclusions,
    [string]$RawArchive
)
$ErrorActionPreference='Stop'
$jpqRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
function WorkspacePath([string]$value) {
    if([IO.Path]::IsPathRooted($value)) { return [IO.Path]::GetFullPath($value) }
    return [IO.Path]::GetFullPath((Join-Path $jpqRoot $value))
}
function SourceFingerprint {
    $files=@('app/src','core/src','tools/android') | ForEach-Object {
        # Assets and fixtures affect recognition too. Only generated Python
        # caches are excluded; build output is outside these source directories.
        Get-ChildItem -LiteralPath (Join-Path $jpqRoot $_) -File -Recurse |
            Where-Object { $_.FullName -notmatch '[\\/]__pycache__[\\/]' -and $_.Extension -ne '.pyc' }
    }
    $files+=@('app/build.gradle.kts','core/build.gradle.kts','build.gradle.kts','settings.gradle.kts','gradle.properties') |
        ForEach-Object { Get-Item -LiteralPath (Join-Path $jpqRoot $_) }
    $items=@($files | Sort-Object FullName -Unique | ForEach-Object {
        [ordered]@{ path=$_.FullName.Substring($jpqRoot.Length+1).Replace('\','/'); sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
    return ConvertTo-Json -InputObject $items -Depth 6 -Compress
}
$inputPath=WorkspacePath $Inputs
$output=WorkspacePath $OutputDirectory
if(!$output.StartsWith($jpqRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {
    throw 'Reports must stay inside the workspace.'
}
if(Test-Path -LiteralPath $output) { throw 'Use a new output directory; previous failures must be preserved.' }
$entries=@(Get-Content -LiteralPath $inputPath -Raw -Encoding UTF8 | ConvertFrom-Json)
if($entries.Count -ne 33) { throw 'The fixed cohort requires all 33 source sessions.' }
$seen=@{}
$frozen=@($entries | Sort-Object session | ForEach-Object {
    if([IO.Path]::GetFileName($_.session) -ne $_.session -or $seen.ContainsKey($_.session)) { throw 'Invalid or duplicate source ID.' }
    $seen[$_.session]=$true
    $source=WorkspacePath $_.path
    if([IO.Path]::GetFileName($source) -ne ($_.session+'.tape')) { throw 'Source path does not match its ID.' }
    [ordered]@{ session=$_.session; frames=$_.frames; path=$source; sha256=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() }
})
$locked=@(Get-Content -LiteralPath (WorkspacePath $SourceLock) -Raw -Encoding UTF8 | ConvertFrom-Json)
if($locked.Count -ne 33) { throw 'The original source lock must contain 33 sessions.' }
foreach($item in $frozen) {
    $match=@($locked | Where-Object { $_.session -eq $item.session })
    if($match.Count -ne 1 -or $match[0].frames -ne $item.frames -or $match[0].sha256 -ne $item.sha256) {
        throw ('Fixed source changed: '+$item.session)
    }
}
$parents=@($frozen | ForEach-Object { [IO.Path]::GetDirectoryName($_.path) } | Sort-Object -Unique)
if($parents.Count -ne 1) { throw 'The cohort must use one frozen input directory.' }
$actual=@(Get-ChildItem -LiteralPath $parents[0] -File -Filter '*.tape')
if($actual.Count -ne 33 -or @($actual | Where-Object { !$seen.ContainsKey($_.BaseName) }).Count) {
    throw 'Input directory differs from the fixed source list.'
}
New-Item -ItemType Directory -Path $output | Out-Null
$frozenPath=Join-Path $output 'frozen-inputs.json'
$utf8=[Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText($frozenPath,(ConvertTo-Json -InputObject $frozen -Depth 6),$utf8)
$exclusionPath=$null
if($Exclusions) {
    $exclusionPath=Join-Path $output 'frozen-exclusions.json'
    Copy-Item -LiteralPath (WorkspacePath $Exclusions) -Destination $exclusionPath
}
$before=SourceFingerprint
$before | Set-Content -LiteralPath (Join-Path $output 'source-fingerprint.json') -Encoding UTF8
$buildPassed=$false; $unchanged=$false; $gatePassed=$false; $failure=$null
$replayDirectory=$parents[0]; $visibility=$null; $archivePath=$null
try {
    if($RawArchive) {
        $archivePath=WorkspacePath $RawArchive
        $replayDirectory=Join-Path $output 'source-mask-replay'
        $visibilityPath=Join-Path $output 'source-mask-manifest.json'
        & $Python (Join-Path $PSScriptRoot 'prepare-replay-visibility.py') --archive $archivePath --inputs $frozenPath --output $replayDirectory --manifest $visibilityPath *> (Join-Path $output 'source-mask-preparation.txt')
        if($LASTEXITCODE -ne 0) { throw 'Original source-mask verification failed.' }
        $visibility=Get-Content -LiteralPath $visibilityPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if($visibility.sources.Count -ne 33) { throw 'Incomplete source-mask provenance.' }
    }
    & (Join-Path $PSScriptRoot 'build.ps1') -Tasks @(':core:cleanTest',':core:test',':core:replayLoggedFrames',
        ('-PloggedTape='+$replayDirectory),('-PloggedOutput='+(Join-Path $output 'reports')),
        ':app:assembleRelease',':app:assembleReleaseAndroidTest',':app:lintRelease','--continue') *> (Join-Path $output 'build.txt')
    $buildPassed=$true
} catch { $failure=$_.Exception.Message }
try {
    $unchanged=$before -eq (SourceFingerprint)
    foreach($item in $frozen) {
        if((Get-FileHash -LiteralPath $item.path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $item.sha256) { $unchanged=$false }
        if($visibility) {
            $provenance=@($visibility.sources | Where-Object { $_.session -eq $item.session })
            if($provenance.Count -ne 1 -or $provenance[0].originalTapeSha256 -ne $item.sha256 -or
                (Get-FileHash -LiteralPath (Join-Path $replayDirectory ($item.session+'.tape')) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $provenance[0].replaySha256) { $unchanged=$false }
        }
    }
    if($visibility -and (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $visibility.archiveSha256) { $unchanged=$false }
    if(!$unchanged) { throw 'Code or sample changed during the sweep. Discard this run and restart the full cohort.' }
    $arguments=@((Join-Path $PSScriptRoot 'audit-replay-cohort.py'),'--inputs',$frozenPath,
        '--reports',(Join-Path $output 'reports'),'--output',(Join-Path $output 'audit.json'))
    if($exclusionPath) { $arguments+=@('--exclusions',$exclusionPath) }
    & $Python @arguments *> (Join-Path $output 'audit.txt')
    if($LASTEXITCODE -notin @(0,1)) { throw 'Replay audit could not complete.' }
    $gatePassed=(Get-Content -LiteralPath (Join-Path $output 'audit.json') -Raw -Encoding UTF8 | ConvertFrom-Json).gatePassed -eq $true
} catch {
    $messages=@($failure,$_.Exception.Message) | Where-Object { $_ }
    $failure=$messages -join '; '
}
[ordered]@{ buildPassed=$buildPassed; codeAndInputsUnchanged=$unchanged; gatePassed=$gatePassed;
    recordedReplayGatePassed=($buildPassed -and $unchanged -and $gatePassed -and !$failure); failure=$failure;
    scope='Fixed recorded model/scene replay; independent card truth is a separate requirement.' } |
    ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $output 'verification.json') -Encoding UTF8
Get-Content -LiteralPath (Join-Path $output 'verification.json')
if(!$buildPassed -or !$unchanged -or !$gatePassed -or $failure) { exit 1 }
