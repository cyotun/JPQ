#requires -Version 7.0
param([string]$RollbackDirectory='artifacts/rollback-v0.2.26')
$ErrorActionPreference='Stop'
$jpqRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$jpqRollback=if([IO.Path]::IsPathRooted($RollbackDirectory)) { [IO.Path]::GetFullPath($RollbackDirectory) }
    else { [IO.Path]::GetFullPath((Join-Path $jpqRoot $RollbackDirectory)) }
$jpqManifest=Get-Content -LiteralPath (Join-Path $jpqRollback 'rollback-manifest.json') -Raw | ConvertFrom-Json
if($jpqManifest.stableCommit -ne 'ea5568147ecd24b59120a03360fcb4cd4248d01d' -or
    $jpqManifest.stablePackage -ne 'com.cyotun.jpq' -or
    $jpqManifest.candidatePackage -ne 'com.cyotun.jpq.candidate') { throw 'Unexpected rollback identity.' }
if($jpqManifest.apk -ne 'jpq-stable-v0.2.26.apk') { throw 'Unexpected stable APK filename.' }
$jpqApk=Join-Path $jpqRollback $jpqManifest.apk
$jpqExpectedApk='7033136f84a8f28b16106724c26e3651f9e156b9e5d29098fc6b413b70b8411c'
if($jpqManifest.apkSha256 -ne $jpqExpectedApk -or
    (Get-FileHash -LiteralPath $jpqApk -Algorithm SHA256).Hash.ToLowerInvariant() -ne $jpqExpectedApk -or
    (Get-Item -LiteralPath $jpqApk).Length -ne 73050126) { throw 'Stable APK hash/size mismatch.' }
$jpqBundle=Join-Path $jpqRollback 'source-v0.2.26.bundle'
if((Get-FileHash -LiteralPath $jpqBundle -Algorithm SHA256).Hash.ToLowerInvariant() -ne $jpqManifest.sourceBundleSha256) {
    throw 'Stable source bundle hash mismatch.'
}
& git -C $jpqRoot bundle verify $jpqBundle
if($LASTEXITCODE -ne 0) { throw 'Stable bundle verification failed.' }
$jpqTag=& git -C $jpqRoot rev-parse 'v0.2.26^{}'
if($LASTEXITCODE -ne 0 -or $jpqTag.Trim() -ne $jpqManifest.stableCommit) { throw 'Stable tag changed.' }
$env:JAVA_HOME=Join-Path $jpqRoot '.android-tools/jdk-17.0.20.1+1'
$jpqSignature=& (Join-Path $jpqRoot '.android-tools/sdk/build-tools/35.0.0/apksigner.bat') verify --print-certs $jpqApk 2>&1 | Out-String
if($LASTEXITCODE -ne 0 -or $jpqSignature -notmatch 'ebe83c802f083bdb1adafc6163744dfa2cbd30f5677c04ab5ff29356c5cff71f') {
    throw 'Stable APK signature mismatch.'
}
[pscustomobject]@{verified=$true;stableCommit=$jpqTag.Trim();apkSha256=$jpqExpectedApk;deviceTouched=$false}
