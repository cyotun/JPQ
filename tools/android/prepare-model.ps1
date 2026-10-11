param([Parameter(Mandatory=$true)][string]$ModelPath)
$ErrorActionPreference='Stop'
$jpqRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$jpqModel=(Resolve-Path -LiteralPath $ModelPath).Path
if((Get-FileHash -LiteralPath $jpqModel -Algorithm SHA256).Hash.ToLowerInvariant() -ne '87493b13b285027b7ee0f727bc9ca5b9a2aec8c7e0e730e43a79417121f3a90c') { throw 'Unexpected model asset' }
$jpqDestination=Join-Path $jpqRoot 'app/src/main/assets/models'
New-Item -ItemType Directory -Path $jpqDestination -Force | Out-Null
Copy-Item -LiteralPath $jpqModel -Destination (Join-Path $jpqDestination 'cards.tflite')
Write-Output 'Verified local model prepared. Binary remains outside Git.'
