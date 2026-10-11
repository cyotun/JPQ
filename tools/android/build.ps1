param([string[]]$Tasks = @(':core:test', ':app:assembleDebug', ':app:lintDebug'))
$ErrorActionPreference = 'Stop'
$jpqRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$env:JAVA_HOME = Join-Path $jpqRoot '.android-tools/jdk-17.0.20.1+1'
$env:ANDROID_HOME = Join-Path $jpqRoot '.android-tools/sdk'
$env:GRADLE_USER_HOME = Join-Path $jpqRoot '.gradle-home'
$env:ANDROID_USER_HOME = Join-Path $jpqRoot '.android-tools/android-user'
$jpqGradle = Join-Path $jpqRoot '.android-tools/gradle-8.13/bin/gradle.bat'
Push-Location $jpqRoot
try { & $jpqGradle @Tasks --console=plain; if ($LASTEXITCODE -ne 0) { throw "Build failed: $LASTEXITCODE" } }
finally { Pop-Location }
