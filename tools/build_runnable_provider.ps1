[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $SigningPropertiesFile,

    [Parameter(Mandatory = $true)]
    [string] $SigningStoreFile,

    [Parameter(Mandatory = $true)]
    [string] $HostApk,

    [string] $SdkRoot = $env:ANDROID_HOME,

    [switch] $SkipBuild
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = Split-Path -Parent $PSScriptRoot

function Resolve-RegularFile {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $Label
    )

    $Path = $Path.Trim().TrimStart('"').TrimEnd('"').Trim().TrimStart("'").TrimEnd("'")
    if (-not (Split-Path -Path $Path -IsAbsolute)) {
        throw "$Label must be an absolute path"
    }
    $item = Get-Item -LiteralPath $Path -ErrorAction Stop
    if ($item.PSIsContainer) {
        throw "$Label must be a regular file"
    }
    return $item.FullName
}

function Resolve-SdkTool {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Name
    )

    $buildToolsRoot = Join-Path $script:ResolvedSdkRoot "build-tools"
    $candidate = Get-ChildItem -LiteralPath $buildToolsRoot -Directory -ErrorAction Stop |
        Sort-Object { [version] $_.Name } -Descending |
        ForEach-Object { Join-Path $_.FullName $Name } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
    if (-not $candidate) {
        throw "Android SDK tool is unavailable: $Name"
    }
    return $candidate
}

function Invoke-Captured {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Executable,

        [Parameter(Mandatory = $true)]
        [string[]] $Arguments
    )

    $output = & $Executable @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "$Executable failed with exit code $LASTEXITCODE`n$($output -join [Environment]::NewLine)"
    }
    return $output -join [Environment]::NewLine
}

function Read-CertificateSha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Apk
    )

    $report = Invoke-Captured $script:ApkSigner @("verify", "--print-certs", $Apk)
    $matches = [regex]::Matches(
        $report,
        "certificate SHA-256 digest:\s*([0-9a-fA-F]{64})"
    )
    if (($matches | Measure-Object).Count -ne 1) {
        throw "Expected exactly one current APK signer in $Apk"
    }
    return $matches[0].Groups[1].Value.ToLowerInvariant()
}

$resolvedSigningProperties = Resolve-RegularFile $SigningPropertiesFile "Signing properties file"
$resolvedSigningStore = Resolve-RegularFile $SigningStoreFile "Signing store file"
$resolvedHostApk = Resolve-RegularFile $HostApk "AutoJs6 host APK"

if ([string]::IsNullOrWhiteSpace($SdkRoot) -or -not (Split-Path -Path ($SdkRoot.Trim().TrimStart('"').TrimEnd('"').TrimStart("'").TrimEnd("'")) -IsAbsolute)) {
    throw "SdkRoot must be an absolute Android SDK path"
}
$script:ResolvedSdkRoot = (Get-Item -LiteralPath ($SdkRoot.Trim().TrimStart('"').TrimEnd('"').TrimStart("'").TrimEnd("'")) -ErrorAction Stop).FullName
$script:ApkSigner = Resolve-SdkTool "apksigner.bat"
$apkAnalyzer = Join-Path $script:ResolvedSdkRoot "cmdline-tools/latest/bin/apkanalyzer.bat"
if (-not (Test-Path -LiteralPath $apkAnalyzer -PathType Leaf)) {
    throw "Android SDK tool is unavailable: apkanalyzer.bat"
}

$sourceStatusBefore = @(& git -C $root status --porcelain --untracked-files=all)
if ($LASTEXITCODE -ne 0 -or $sourceStatusBefore.Count -ne 0) {
    throw "Runnable provider build requires a clean repository: $($sourceStatusBefore -join '; ')"
}
$sourceRevision = (& git -C $root rev-parse HEAD).Trim()
$sourceCommitCount = [int] (& git -C $root rev-list --count HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $sourceRevision -notmatch '^[0-9a-f]{40}$') {
    throw "Unable to resolve the runnable provider source revision"
}
$versionProperties = ConvertFrom-StringData -StringData (
    Get-Content -LiteralPath (Join-Path $root "version.properties") -Raw
)
$expectedVersionCode = [long] $versionProperties["VERSION_BUILD"]
$expectedVersionName = $versionProperties["VERSION_NAME"]
$requiredHostVersionCode = [long] $versionProperties["REQUIRED_HOST_VERSION_CODE"]
if ($expectedVersionCode -ne $sourceCommitCount) {
    throw "VERSION_BUILD must equal the clean source commit count"
}

$invocationStartedAtUtc = $null
if (-not $SkipBuild) {
    $invocationStartedAtUtc = [DateTimeOffset]::UtcNow.ToString(
        "yyyy-MM-ddTHH:mm:ss.ffffffZ"
    )
    $gradleArgs = @(
        ":app:clean"
        ":app:testProviderDebugUnitTest"
        ":app:assembleProviderRelease"
        "-Pautojs.lua.release.signingPropertiesFile=$resolvedSigningProperties"
        "-Pautojs.lua.release.signingStoreFile=$resolvedSigningStore"
        "--rerun-tasks"
        "--offline"
        "--no-daemon"
        "--console=plain"
    )
    & (Join-Path $root "gradlew.bat") @gradleArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Runnable Lua provider build failed with exit code $LASTEXITCODE"
    }
}

$outputRoot = Join-Path $root "app/build/outputs/apk/provider/release"
$metadataPath = Join-Path $outputRoot "output-metadata.json"
$metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
$universal = @($metadata.elements) | Where-Object { $_.type -eq "UNIVERSAL" }
$universalCount = ($universal | Measure-Object).Count
if ($universalCount -ne 1) {
    throw "Expected exactly one universal release APK"
}
if ([long] $universal[0].versionCode -ne $expectedVersionCode) {
    throw "Runnable APK versionCode does not match version.properties"
}
if ([string] $universal[0].versionName -ne $expectedVersionName) {
    throw "Runnable APK versionName does not match version.properties"
}
$universalApk = Resolve-RegularFile `
    (Join-Path $outputRoot $universal[0].outputFile) `
    "Universal Lua provider APK"

$manifest = Invoke-Captured $apkAnalyzer @("manifest", "print", $universalApk)
foreach ($requiredToken in @(
    "org.autojs.plugin.INFO",
    "org.autojs.plugin.lua.RUNTIME",
    "LuaPluginInfoService",
    "LuaRuntimeService",
    "org.autojs.permission.PLUGIN"
)) {
    if (-not $manifest.Contains($requiredToken)) {
        throw "Runnable APK manifest is missing $requiredToken"
    }
}
if ($manifest -match 'android:enabled="false"') {
    throw "Runnable APK contains an explicitly disabled component"
}

$files = Invoke-Captured $apkAnalyzer @("files", "list", $universalApk)
foreach ($abi in @("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) {
    if ($files -notmatch "/lib/$abi/libautojs_lua_runtime\.so") {
        throw "Runnable APK is missing the $abi Lua native runtime"
    }
}

$pluginSigner = Read-CertificateSha256 $universalApk
$hostSigner = Read-CertificateSha256 $resolvedHostApk
if ($pluginSigner -ne $hostSigner) {
    throw "Lua provider and AutoJs6 host APK signers do not match"
}
$hostManifest = Invoke-Captured $apkAnalyzer @("manifest", "print", $resolvedHostApk)
$hostVersionMatch = [regex]::Match($hostManifest, 'android:versionCode="([0-9]+)"')
if (-not $hostVersionMatch.Success) {
    throw "Unable to read the AutoJs6 host APK versionCode"
}
$hostVersionCode = [long] $hostVersionMatch.Groups[1].Value
if ($hostVersionCode -lt $requiredHostVersionCode) {
    throw "AutoJs6 host APK versionCode $hostVersionCode is older than required $requiredHostVersionCode"
}

$gitStatus = @(& git -C $root status --porcelain --untracked-files=all)
if ($LASTEXITCODE -ne 0) {
    throw "Unable to inspect the Lua provider source status"
}
$sourceClean = ($gitStatus | Measure-Object).Count -eq 0
if (-not $sourceClean -or (& git -C $root rev-parse HEAD).Trim() -ne $sourceRevision) {
    throw "Runnable provider source changed during artifact construction"
}

$artifactGateVerified = -not $SkipBuild
if ($artifactGateVerified) {
    & (Join-Path $root "tools/verify_release_candidate_artifacts.ps1") `
        -InvocationStartedAtUtc $invocationStartedAtUtc `
        -SigningPropertiesFile $resolvedSigningProperties `
        -SigningStoreFile $resolvedSigningStore `
        -SdkRoot $script:ResolvedSdkRoot
}
$universalItem = Get-Item -LiteralPath $universalApk
$universalSha256 = (Get-FileHash -LiteralPath $universalApk -Algorithm SHA256).Hash.ToLowerInvariant()

Write-Host (
    "RUNNABLE_LUA_PROVIDER_OK " +
    "revision=$sourceRevision apk=$universalApk apkBytes=$($universalItem.Length) " +
    "apkSha256=$universalSha256 versionName=$expectedVersionName versionCode=$expectedVersionCode " +
    "hostVersionCode=$hostVersionCode abis=arm64-v8a,armeabi-v7a,x86_64,x86 signerSha256=$pluginSigner " +
    "sourceClean=$($sourceClean.ToString().ToLowerInvariant()) " +
    "artifactGateVerified=$($artifactGateVerified.ToString().ToLowerInvariant()) " +
    "deviceVerified=false runtimeVerified=false"
)
