param(
    [Parameter(Mandatory=$true)][string]$NativeLibraryDirectory,
    [Parameter(Mandatory=$true)][string]$PackedModelDirectory
)
$ErrorActionPreference='Stop'
$jpqRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$jpqNative=(Resolve-Path -LiteralPath $NativeLibraryDirectory).Path
$jpqModels=(Resolve-Path -LiteralPath $PackedModelDirectory).Path
$jpqManifest=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'reference-preprocess-assets.json') -Raw | ConvertFrom-Json
$jpqCopies=@()
foreach($jpqAsset in $jpqManifest.assets) {
    $jpqIsModel=$jpqAsset.name.EndsWith('.tflite')
    $jpqSource=Join-Path $(if($jpqIsModel) { $jpqModels } else { $jpqNative }) $jpqAsset.name
    if((Get-FileHash -LiteralPath $jpqSource -Algorithm SHA256).Hash.ToLowerInvariant() -ne $jpqAsset.sha256) {
        throw "Unexpected reference asset: $($jpqAsset.name)"
    }
    $jpqTarget=Join-Path $jpqRoot $(if($jpqIsModel) { 'app/src/main/assets/models' } else { 'app/src/main/jniLibs/arm64-v8a' })
    $jpqCopies+=@{Source=$jpqSource;Target=$jpqTarget;Name=$jpqAsset.name}
}
foreach($jpqCopy in $jpqCopies) {
    New-Item -ItemType Directory -Path $jpqCopy.Target -Force | Out-Null
    Copy-Item -LiteralPath $jpqCopy.Source -Destination (Join-Path $jpqCopy.Target $jpqCopy.Name) -Force
}
Write-Output 'Verified reference preprocessing assets prepared locally; binaries remain outside Git.'
