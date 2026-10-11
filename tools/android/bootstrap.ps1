$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$jpqRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$jpqTools = Join-Path $jpqRoot '.android-tools'
New-Item -ItemType Directory -Force -Path $jpqTools | Out-Null
function Get-JpqText([string]$url) {
    $result = Invoke-WebRequest -Uri $url -TimeoutSec 120
    if ($result.Content -is [byte[]]) { return [Text.Encoding]::UTF8.GetString($result.Content).Trim() }
    return ([string]$result.Content).Trim()
}
function Get-JpqArchive([string]$url, [string]$path, [string]$hash, [string]$algorithm) {
    if (-not (Test-Path -LiteralPath $path)) {
        Write-Output "Downloading $([IO.Path]::GetFileName($path))"
        Invoke-WebRequest -Uri $url -OutFile $path -TimeoutSec 1800
    }
    if ((Get-FileHash -LiteralPath $path -Algorithm $algorithm).Hash.ToLowerInvariant() -ne $hash.ToLowerInvariant()) {
        throw "Checksum mismatch: $path. Preserve the file for inspection; do not execute it."
    }
}
$jpqJdk = Join-Path $jpqTools 'jdk-17.0.20.1+1'
if (-not (Test-Path -LiteralPath (Join-Path $jpqJdk 'bin/javac.exe'))) {
    $jdkZip = Join-Path $jpqTools 'jdk17.zip'
    Get-JpqArchive 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip' $jdkZip 'e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0' 'SHA256'
    Expand-Archive -LiteralPath $jdkZip -DestinationPath $jpqTools -Force
}
$jpqGradle = Join-Path $jpqTools 'gradle-8.13'
if (-not (Test-Path -LiteralPath (Join-Path $jpqGradle 'bin/gradle.bat'))) {
    $gradleZip = Join-Path $jpqTools 'gradle-8.13.zip'
    $gradleHash = Get-JpqText 'https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256'
    if ($gradleHash -notmatch '^[a-f0-9]{64}$') { throw 'Invalid Gradle checksum response' }
    Get-JpqArchive 'https://services.gradle.org/distributions/gradle-8.13-bin.zip' $gradleZip $gradleHash 'SHA256'
    Expand-Archive -LiteralPath $gradleZip -DestinationPath $jpqTools -Force
}
$jpqSdk = Join-Path $jpqTools 'sdk'
$jpqSdkManager = Join-Path $jpqSdk 'cmdline-tools/cmdline-tools/bin/sdkmanager.bat'
if (-not (Test-Path -LiteralPath $jpqSdkManager)) {
    [xml]$repo = Get-JpqText 'https://dl.google.com/android/repository/repository2-3.xml'
    $package = $repo.SelectSingleNode("//*[local-name()='remotePackage'][@path='cmdline-tools;latest']")
    $archive = $package.SelectSingleNode(".//*[local-name()='archive'][*[local-name()='host-os']='windows']/*[local-name()='complete']")
    $url = $archive.SelectSingleNode("*[local-name()='url']").InnerText
    $checksum = $archive.SelectSingleNode("*[local-name()='checksum']")
    $algorithm = if ($checksum.InnerText.Length -eq 40) { 'SHA1' } else { 'SHA256' }
    $sdkZip = Join-Path $jpqTools 'sdk-command-tools.zip'
    Get-JpqArchive ("https://dl.google.com/android/repository/" + $url) $sdkZip $checksum.InnerText $algorithm
    Expand-Archive -LiteralPath $sdkZip -DestinationPath (Join-Path $jpqSdk 'cmdline-tools') -Force
}
$env:JAVA_HOME = $jpqJdk
$env:ANDROID_HOME = $jpqSdk
$env:ANDROID_USER_HOME = Join-Path $jpqTools 'android-user'
$env:GRADLE_USER_HOME = Join-Path $jpqRoot '.gradle-home'
$env:Path = (Join-Path $jpqJdk 'bin') + ';' + $env:Path
# Current Google tools expose slash-delimited package names via Android CLI.
$jpqAndroid = Join-Path $jpqSdk 'cmdline-tools/cmdline-tools/bin/android.exe'
& $jpqAndroid --no-metrics "--sdk=$jpqSdk" sdk install 'platform-tools' 'platforms/android-36' 'build-tools/35.0.0'
if ($LASTEXITCODE -ne 0) { throw 'SDK package setup failed' }
if (-not (Test-Path -LiteralPath (Join-Path $jpqSdk 'platforms/android-36/android.jar'))) { throw 'Android 36 platform missing' }
if (-not (Test-Path -LiteralPath (Join-Path $jpqSdk 'build-tools/35.0.0/aapt2.exe'))) { throw 'Build tools missing' }
[IO.File]::WriteAllText((Join-Path $jpqRoot 'local.properties'), ('sdk.dir=' + $jpqSdk.Replace('\','/')) + "`n", [Text.UTF8Encoding]::new($false))
Write-Output 'JPQ Android build environment ready.'
