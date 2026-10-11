param([Parameter(Mandatory=$true)][string]$FontPath)
$ErrorActionPreference = 'Stop'
$jpqRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$jpqExpected = '6f6a1e15f33d559ff3d0d7dcc8bd9ab25a6dbd8e9bcd29865daf7615a17ea284'
if ((Get-FileHash -LiteralPath $FontPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $jpqExpected) { throw 'Use the verified official DingTalk JinBuTi font.' }
$jpqAssets = Join-Path $jpqRoot 'app/src/main/assets'
New-Item -ItemType Directory -Force -Path $jpqAssets | Out-Null
Copy-Item -LiteralPath $FontPath -Destination (Join-Path $jpqAssets 'overlay.ttf')
Write-Output 'Prepared local overlay font; it remains excluded from Git.'
