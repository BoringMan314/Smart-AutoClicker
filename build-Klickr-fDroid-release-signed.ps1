# Implementation detail for build_win10.bat (preferred entry on Windows).
param(
    [string]$SigningStorePassword,
    [string]$SigningKeyAlias,
    [string]$SigningKeyPassword,
    [string]$ReleaseKeystorePassphrase,
    [string]$ReleaseKeystoreAscPath = "smartautoclicker.jks.asc",
    [switch]$SkipClean
)

$ErrorActionPreference = "Stop"

if ($Host.Name -eq "ConsoleHost") {
    try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch { }
}

function Write-BuildProgress {
    param(
        [int]$Step,
        [string]$Message
    )
    $total = 8
    if ($env:BUILD_PROGRESS_TOTAL) {
        $parsed = 0
        if ([int]::TryParse($env:BUILD_PROGRESS_TOTAL, [ref]$parsed) -and $parsed -gt 0) {
            $total = $parsed
        }
    }
    $pct = [int][math]::Floor(($Step * 100) / $total)
    if ($pct -lt 0) { $pct = 0 }
    if ($pct -gt 100) { $pct = 100 }
    Write-Host ("[{0}/{1} {2}%] {3}" -f $Step, $total, $pct, $Message)
}

$progressStart = 4
if ($env:BUILD_PROGRESS_START) {
    $parsedStart = 0
    if ([int]::TryParse($env:BUILD_PROGRESS_START, [ref]$parsedStart) -and $parsedStart -gt 0) {
        $progressStart = $parsedStart
    }
}

$rootDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$localPropertiesPath = Join-Path $rootDir "local.properties"
$keystorePath = Join-Path $rootDir "smartautoclicker/smartautoclicker.jks"
$releaseKeystoreAscFullPath = Join-Path $rootDir $ReleaseKeystoreAscPath
$apkOutputDir = Join-Path $rootDir "smartautoclicker/build/outputs/apk/fDroid/release"
$gradleKtsPath = Join-Path $rootDir "smartautoclicker/build.gradle.kts"

function Get-LocalPropertyValue {
    param(
        [string]$Path,
        [string]$Key
    )

    if (-not (Test-Path $Path)) { return $null }

    foreach ($line in Get-Content -Path $Path -Encoding UTF8) {
        if ($line -match "^$([regex]::Escape($Key))=(.*)$") {
            return $Matches[1].Trim()
        }
    }

    return $null
}

function Get-VersionNameFromGradle {
    param([string]$Path)

    if (-not (Test-Path $Path)) {
        throw "build.gradle.kts not found: $Path"
    }

    $content = Get-Content -Path $Path -Raw -Encoding UTF8
    if ($content -match 'versionName\s*=\s*"([^"]+)"') {
        return $Matches[1]
    }

    throw "Unable to parse versionName from build.gradle.kts"
}

function Get-UniversalKlickrApkFileName {
    param(
        [string]$GradleApkFileName,
        [string]$VersionName
    )

    if ($GradleApkFileName -eq "smartautoclicker-fDroid-universal-release.apk") {
        return "Klickr-fDroid-release-$VersionName.apk"
    }

    if ($GradleApkFileName -eq "smartautoclicker-fDroid-release.apk") {
        return "Klickr-fDroid-release-$VersionName.apk"
    }

    return $null
}

if (-not $SigningStorePassword) { $SigningStorePassword = $env:SIGNING_STORE_PASSWORD }
if (-not $SigningKeyAlias) { $SigningKeyAlias = $env:SIGNING_KEY_ALIAS }
if (-not $SigningKeyPassword) { $SigningKeyPassword = $env:SIGNING_KEY_PASSWORD }

if (-not $SigningStorePassword) { $SigningStorePassword = Get-LocalPropertyValue -Path $localPropertiesPath -Key "signingStorePassword" }
if (-not $SigningKeyAlias) { $SigningKeyAlias = Get-LocalPropertyValue -Path $localPropertiesPath -Key "signingKeyAlias" }
if (-not $SigningKeyPassword) { $SigningKeyPassword = Get-LocalPropertyValue -Path $localPropertiesPath -Key "signingKeyPassword" }
if (-not $ReleaseKeystorePassphrase) { $ReleaseKeystorePassphrase = $env:RELEASE_KEYSTORE_PASSPHRASE }
if (-not $ReleaseKeystorePassphrase) { $ReleaseKeystorePassphrase = Get-LocalPropertyValue -Path $localPropertiesPath -Key "releaseKeystorePassphrase" }

$versionName = Get-VersionNameFromGradle -Path $gradleKtsPath

if (-not (Test-Path $keystorePath) -and (Test-Path $releaseKeystoreAscFullPath)) {
    if (-not $ReleaseKeystorePassphrase) {
        throw "Keystore is missing and '$ReleaseKeystoreAscPath' exists, but release keystore passphrase is not provided."
    }

    Write-BuildProgress ($progressStart) "Restore keystore ($ReleaseKeystoreAscPath)"
    & gpg -d --batch --yes --passphrase "$ReleaseKeystorePassphrase" --output "$keystorePath" "$releaseKeystoreAscFullPath"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to restore keystore from $ReleaseKeystoreAscPath"
    }
} elseif (Test-Path $keystorePath) {
    Write-BuildProgress ($progressStart) "Check signing and keystore"
}

if (-not (Test-Path $keystorePath)) {
    throw "Keystore not found: $keystorePath (or provide '$ReleaseKeystoreAscPath' and passphrase)"
}
if (-not $SigningStorePassword -or -not $SigningKeyAlias -or -not $SigningKeyPassword) {
    throw "Missing signing config. Provide signingStorePassword, signingKeyAlias, signingKeyPassword via args, env, or local.properties"
}

$env:JAVA_TOOL_OPTIONS = "-Djava.net.preferIPv4Stack=true"

# Match release.yml: no random applicationId (see nightly-obfuscation.yml for -PrandomizeAppId=true).
# Prefer :smartautoclicker:clean: root clean often fails on Windows (locked jars).
$gradleArgs = @(
    "--info"
    "-PrandomizeAppId=false"
    "-PsigningStorePassword=$SigningStorePassword"
    "-PsigningKeyAlias=$SigningKeyAlias"
    "-PsigningKeyPassword=$SigningKeyPassword"
)
if (-not $SkipClean) {
    Write-BuildProgress ($progressStart + 1) "Clean smartautoclicker module (pass skipclean to skip)"
    & "$rootDir/gradlew.bat" ":smartautoclicker:clean"
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "smartautoclicker:clean failed; continuing with --rerun-tasks"
        $gradleArgs += "--rerun-tasks"
    }
} else {
    Write-BuildProgress ($progressStart + 1) "Skip clean (skipclean)"
}

Write-BuildProgress ($progressStart + 2) "Gradle build (assembleFDroidRelease, may take several minutes)"
& "$rootDir/gradlew.bat" @gradleArgs "assembleFDroidRelease"

if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed, exit code $LASTEXITCODE"
}

if (-not (Test-Path $apkOutputDir)) {
    throw "APK output dir not found: $apkOutputDir"
}

$gradleApks = Get-ChildItem -Path $apkOutputDir -Filter "*.apk" -File | Sort-Object Name
if (-not $gradleApks -or $gradleApks.Count -eq 0) {
    throw "Gradle produced no APKs: $apkOutputDir"
}

Write-BuildProgress ($progressStart + 3) "Copy universal APK to project root (Klickr-fDroid-release-$versionName.apk)"

$copiedApks = @()
foreach ($gradleApk in $gradleApks) {
    $finalName = Get-UniversalKlickrApkFileName -GradleApkFileName $gradleApk.Name -VersionName $versionName
    if (-not $finalName) {
        Write-Host ("     Skip non-universal: {0}" -f $gradleApk.Name)
        continue
    }

    $finalApkPath = Join-Path $rootDir $finalName
    Copy-Item -Path $gradleApk.FullName -Destination $finalApkPath -Force
    (Get-Item -LiteralPath $finalApkPath).LastWriteTime = Get-Date
    $copiedApks += $finalApkPath
    Write-Host ("     Exported: {0}" -f $finalApkPath)
}

if ($copiedApks.Count -eq 0) {
    throw "Universal APK was not copied to project root"
}

$expectedPath = Join-Path $rootDir ("Klickr-fDroid-release-{0}.apk" -f $versionName)
if (-not (Test-Path $expectedPath)) {
    throw "Missing expected APK: $expectedPath"
}

$env:BUILD_VERSION_NAME = $versionName
$env:BUILD_APK_NAME = [IO.Path]::GetFileName($expectedPath)
$env:BUILD_APK_COUNT = [string]$copiedApks.Count
