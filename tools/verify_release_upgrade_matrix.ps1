[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [string] $OlderHostApk,

    [Parameter(Mandatory = $true)]
    [string] $CurrentHostApk,

    [Parameter(Mandatory = $true)]
    [string] $Rc1ProviderApk,

    [Parameter(Mandatory = $true)]
    [string] $Rc2ProviderApk,

    [Parameter(Mandatory = $true)]
    [string] $LifecycleTestApk,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F]{40}$')]
    [string] $OlderHostRevision,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F]{40}$')]
    [string] $CurrentHostRevision,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F]{40}$')]
    [string] $Rc1Revision,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F]{40}$')]
    [string] $Rc2Revision,

    [string] $SdkRoot = $env:ANDROID_HOME
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$HostPackage = 'org.autojs.autojs6'
$ProviderPackage = 'io.github.supermonster003.autojs6.plugin.lua.runtime'
$LifecyclePackage = 'io.github.supermonster003.autojs6.plugin.lua.runtime.host.lifecycle.test'
$LifecycleComponent = "$LifecyclePackage/.LuaHostLifecycleInstrumentation"
$RuntimeProcess = "${ProviderPackage}:lua_runtime"
$LogTag = 'AutoJs6LuaHostLifecycle'
$SmokeMarker = 'LUA_HOST_OFFICIAL_SMOKE_PASS'
$IncompatibleMarker = 'LUA_HOST_INCOMPATIBLE_REJECT_PASS'
$ExpectedCurrentHostVersionCode = 5276L
$ExpectedRc1VersionName = '0.1.0-rc.1'
$ExpectedRc2VersionName = '0.1.0-rc.2'
$ExpectedOuterCode = 'LUA_RUNTIME_UNAVAILABLE'
$ExpectedRejection = 'HOST_VERSION_UNSUPPORTED'
$script:MutationStarted = $false
$script:MatrixComplete = $false

function Resolve-RegularFile {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $Label
    )

    if (-not [IO.Path]::IsPathRooted($Path)) {
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
        [string] $RelativePath
    )

    $candidate = Join-Path $script:ResolvedSdkRoot $RelativePath
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "Android SDK tool is unavailable: $candidate"
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

    $output = @(& $Executable @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "$Executable failed with exit code $LASTEXITCODE`n$($output -join [Environment]::NewLine)"
    }
    return $output
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments
    )

    return Invoke-Captured $script:Adb (@('-s', $Serial) + $Arguments)
}

function Read-ApkIdentity {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Apk
    )

    $manifest = (Invoke-Captured $script:ApkAnalyzer @('manifest', 'print', $Apk)) -join "`n"
    $packageMatch = [regex]::Match($manifest, '<manifest[^>]+package="([^"]+)"')
    $versionCodeMatch = [regex]::Match($manifest, 'android:versionCode="([0-9]+)"')
    $versionNameMatch = [regex]::Match($manifest, 'android:versionName="([^"]+)"')
    if (-not $packageMatch.Success -or -not $versionCodeMatch.Success -or -not $versionNameMatch.Success) {
        throw "Unable to read APK package/version identity: $Apk"
    }
    $certificate = (Invoke-Captured $script:ApkSigner @('verify', '--print-certs', $Apk)) -join "`n"
    $signerMatches = [regex]::Matches(
        $certificate,
        'certificate SHA-256 digest:\s*([0-9a-fA-F]{64})'
    )
    if ($signerMatches.Count -ne 1) {
        throw "Expected exactly one current signer: $Apk"
    }
    return [pscustomobject]@{
        Package = $packageMatch.Groups[1].Value
        VersionCode = [long] $versionCodeMatch.Groups[1].Value
        VersionName = $versionNameMatch.Groups[1].Value
        Signer = $signerMatches[0].Groups[1].Value.ToLowerInvariant()
        Sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $Apk).Hash.ToLowerInvariant()
        Bytes = (Get-Item -LiteralPath $Apk).Length
    }
}

function Read-DevicePackageIdentity {
    param(
        [Parameter(Mandatory = $true)]
        [string] $PackageName
    )

    $report = (Invoke-Adb @('shell', 'dumpsys', 'package', $PackageName)) -join "`n"
    $versionMatch = [regex]::Match($report, 'versionCode=([0-9]+)')
    $uidMatch = [regex]::Match($report, '(?m)^\s*userId=([0-9]+)\s*$')
    $firstInstallMatch = [regex]::Match($report, '(?m)^\s*firstInstallTime=(.+?)\s*$')
    if (-not $versionMatch.Success -or -not $uidMatch.Success -or -not $firstInstallMatch.Success) {
        throw "Unable to read installed package identity for $PackageName"
    }
    return [pscustomobject]@{
        VersionCode = [long] $versionMatch.Groups[1].Value
        Uid = [int] $uidMatch.Groups[1].Value
        FirstInstallTime = $firstInstallMatch.Groups[1].Value.Trim()
    }
}

function Read-InstalledApkSha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string] $PackageName
    )

    $paths = @((Invoke-Adb @('shell', 'pm', 'path', $PackageName)) | ForEach-Object { $_.Trim() })
    if ($paths.Count -ne 1 -or -not $paths[0].StartsWith('package:')) {
        throw "Expected one installed base APK for $PackageName"
    }
    $remotePath = $paths[0].Substring('package:'.Length)
    $digestOutput = (Invoke-Adb @('shell', 'sha256sum', $remotePath)) -join "`n"
    $digestMatch = [regex]::Match($digestOutput, '^([0-9a-fA-F]{64})\s')
    if (-not $digestMatch.Success) {
        throw "Unable to read the installed APK digest for $PackageName"
    }
    return $digestMatch.Groups[1].Value.ToLowerInvariant()
}

function Test-PackageInstalled {
    param(
        [Parameter(Mandatory = $true)]
        [string] $PackageName
    )

    $output = @(& $script:Adb -s $Serial shell pm path $PackageName 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        return $false
    }
    return (($output -join '').Trim()).StartsWith('package:')
}

function Read-OptionalPid {
    param(
        [Parameter(Mandatory = $true)]
        [string] $ProcessName
    )

    $output = @(& $script:Adb -s $Serial shell pidof $ProcessName 2>&1)
    $exitCode = $LASTEXITCODE
    $value = ($output -join '').Trim()
    if ($exitCode -ne 0 -and $value.Length -eq 0) {
        return $null
    }
    if ($exitCode -ne 0 -or $value -notmatch '^[0-9]+$') {
        throw "Expected zero or one live PID for $ProcessName, observed '$value'"
    }
    return [int] $value
}

function Install-Apk {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Apk,

        [switch] $Replace,

        [switch] $AllowDowngrade
    )

    $arguments = @('install')
    if ($Replace) { $arguments += '-r' }
    if ($AllowDowngrade) { $arguments += '-d' }
    $arguments += $Apk
    $output = (Invoke-Adb $arguments) -join "`n"
    if ($output -notmatch '(?m)^Success\s*$') {
        throw "ADB did not confirm APK installation: $Apk`n$output"
    }
}

function Uninstall-Package {
    param(
        [Parameter(Mandatory = $true)]
        [string] $PackageName
    )

    if (-not (Test-PackageInstalled $PackageName)) {
        return
    }
    $output = (Invoke-Adb @('uninstall', $PackageName)) -join "`n"
    if ($output -notmatch '(?m)^Success\s*$') {
        throw "ADB did not confirm package uninstall: $PackageName`n$output"
    }
}

function Assert-InstalledArtifact {
    param(
        [Parameter(Mandatory = $true)]
        [string] $PackageName,

        [Parameter(Mandatory = $true)]
        [long] $VersionCode,

        [Parameter(Mandatory = $true)]
        [string] $Sha256
    )

    $identity = Read-DevicePackageIdentity $PackageName
    if ($identity.VersionCode -ne $VersionCode) {
        throw "Installed versionCode mismatch for $PackageName`: expected=$VersionCode actual=$($identity.VersionCode)"
    }
    $installedSha256 = Read-InstalledApkSha256 $PackageName
    if ($installedSha256 -ne $Sha256) {
        throw "Installed APK digest mismatch for $PackageName`: expected=$Sha256 actual=$installedSha256"
    }
    return $identity
}

function Invoke-HostProof {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Mode,

        [Parameter(Mandatory = $true)]
        [string] $RunId,

        [Parameter(Mandatory = $true)]
        [long] $HostVersionCode,

        [Parameter(Mandatory = $true)]
        [long] $ProviderVersionCode,

        [Parameter(Mandatory = $true)]
        [string] $Marker,

        [string[]] $RequiredLogFragments = @()
    )

    Invoke-Adb @('logcat', '-c') | Out-Null
    $output = (Invoke-Adb @(
        'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'mode', $Mode,
        '-e', 'runId', $RunId,
        '-e', 'expectedHostVersionCode', $HostVersionCode.ToString(),
        '-e', 'expectedProviderVersionCode', $ProviderVersionCode.ToString(),
        $LifecycleComponent
    )) -join "`n"
    if (-not $output.Contains("$Marker runId=$RunId") -or
        $output -notmatch 'INSTRUMENTATION_CODE:\s*-1') {
        throw "Real-Host proof failed for mode=$Mode runId=$RunId`n$output"
    }
    $log = (Invoke-Adb @('logcat', '-d', '-v', 'raw', '-s', "${LogTag}:I", '*:S')) -join "`n"
    $markerLine = @($log -split "`r?`n" | Where-Object { $_.Contains("$Marker runId=$RunId") })
    if ($markerLine.Count -ne 1) {
        throw "Expected exactly one $Marker log for runId=$RunId`n$log"
    }
    foreach ($fragment in $RequiredLogFragments) {
        if (-not $markerLine[0].Contains($fragment)) {
            throw "Host proof marker is missing '$fragment' for runId=$RunId`n$($markerLine[0])"
        }
    }
    return $markerLine[0]
}

if ($Serial -notmatch '^emulator-[0-9]+$') {
    throw 'Release upgrade mutation is restricted to emulator-* serials'
}
if ([string]::IsNullOrWhiteSpace($SdkRoot) -or -not [IO.Path]::IsPathRooted($SdkRoot)) {
    throw 'SdkRoot must be an absolute Android SDK path'
}
$script:ResolvedSdkRoot = (Get-Item -LiteralPath $SdkRoot -ErrorAction Stop).FullName
$script:Adb = Resolve-SdkTool 'platform-tools/adb.exe'
$script:ApkSigner = Resolve-SdkTool 'build-tools/37.0.0/apksigner.bat'
$script:ApkAnalyzer = Resolve-SdkTool 'cmdline-tools/latest/bin/apkanalyzer.bat'

$olderHost = Resolve-RegularFile $OlderHostApk 'Older Host APK'
$currentHost = Resolve-RegularFile $CurrentHostApk 'Current Host APK'
$rc1Provider = Resolve-RegularFile $Rc1ProviderApk 'rc.1 Provider APK'
$rc2Provider = Resolve-RegularFile $Rc2ProviderApk 'rc.2 Provider APK'
$lifecycle = Resolve-RegularFile $LifecycleTestApk 'Host lifecycle test APK'

$state = ((Invoke-Adb @('get-state')) -join '').Trim()
$isQemu = ((Invoke-Adb @('shell', 'getprop', 'ro.kernel.qemu')) -join '').Trim()
if ($state -ne 'device' -or $isQemu -ne '1') {
    throw "Serial $Serial is not an online Android emulator"
}
$api = [int] (((Invoke-Adb @('shell', 'getprop', 'ro.build.version.sdk')) -join '').Trim())
$abis = ((Invoke-Adb @('shell', 'getprop', 'ro.product.cpu.abilist')) -join '').Trim()
if ($api -lt 24 -or $abis -notmatch '(^|,)x86_64(,|$)') {
    throw "Release upgrade matrix requires API >= 24 with x86_64; api=$api abis=$abis"
}

$olderHostIdentity = Read-ApkIdentity $olderHost
$currentHostIdentity = Read-ApkIdentity $currentHost
$rc1Identity = Read-ApkIdentity $rc1Provider
$rc2Identity = Read-ApkIdentity $rc2Provider
$lifecycleIdentity = Read-ApkIdentity $lifecycle
if ($olderHostIdentity.Package -ne $HostPackage -or $currentHostIdentity.Package -ne $HostPackage) {
    throw 'Both Host APKs must own org.autojs.autojs6'
}
if ($olderHostIdentity.VersionCode -ge $currentHostIdentity.VersionCode -or
    $currentHostIdentity.VersionCode -ne $ExpectedCurrentHostVersionCode) {
    throw "Host versions must be older-than-5276 and exactly 5276: $($olderHostIdentity.VersionCode) -> $($currentHostIdentity.VersionCode)"
}
if ($rc1Identity.Package -ne $ProviderPackage -or $rc2Identity.Package -ne $ProviderPackage) {
    throw 'Both Provider APKs must own the official Lua Provider package'
}
if ($rc1Identity.VersionName -ne $ExpectedRc1VersionName -or
    $rc2Identity.VersionName -ne $ExpectedRc2VersionName -or
    $rc1Identity.VersionCode -ge $rc2Identity.VersionCode) {
    throw "Provider APKs do not form the required rc.1 -> rc.2 upgrade: $($rc1Identity.VersionName)/$($rc1Identity.VersionCode) -> $($rc2Identity.VersionName)/$($rc2Identity.VersionCode)"
}
if ($lifecycleIdentity.Package -ne $LifecyclePackage) {
    throw "Unexpected lifecycle test package: $($lifecycleIdentity.Package)"
}
$signers = @(@(
        $olderHostIdentity.Signer,
        $currentHostIdentity.Signer,
        $rc1Identity.Signer,
        $rc2Identity.Signer,
        $lifecycleIdentity.Signer
    ) | Select-Object -Unique)
if ($signers.Count -ne 1) {
    throw 'Both Hosts, both Providers, and the lifecycle APK do not share one signer'
}
foreach ($provider in @($rc1Provider, $rc2Provider)) {
    $providerResources = (Invoke-Captured $script:ApkAnalyzer @(
        'resources', 'value',
        '--config', 'default',
        '--type', 'bool',
        '--name', 'lua_runtime_provider_enabled',
        $provider
    )) -join "`n"
    if ($providerResources -notmatch '(?i)true') {
        throw "Provider APK does not enable production discovery: $provider"
    }
}

try {
    $script:MutationStarted = $true
    Install-Apk $currentHost -Replace -AllowDowngrade
    Uninstall-Package $ProviderPackage
    Install-Apk $rc1Provider
    Install-Apk $lifecycle -Replace
    Assert-InstalledArtifact $HostPackage $currentHostIdentity.VersionCode $currentHostIdentity.Sha256 | Out-Null
    $rc1Installed = Assert-InstalledArtifact $ProviderPackage $rc1Identity.VersionCode $rc1Identity.Sha256
    $rc1RunId = "rc1-before-upgrade-$([Guid]::NewGuid().ToString('N'))"
    Invoke-HostProof `
        -Mode 'smoke' `
        -RunId $rc1RunId `
        -HostVersionCode $currentHostIdentity.VersionCode `
        -ProviderVersionCode $rc1Identity.VersionCode `
        -Marker $SmokeMarker `
        -RequiredLogFragments @('executions=2', 'discovery=pass', 'result=pass', 'console=pass') | Out-Null

    Install-Apk $rc2Provider -Replace
    $rc2Upgraded = Assert-InstalledArtifact $ProviderPackage $rc2Identity.VersionCode $rc2Identity.Sha256
    if ($rc2Upgraded.Uid -ne $rc1Installed.Uid -or
        $rc2Upgraded.FirstInstallTime -ne $rc1Installed.FirstInstallTime) {
        throw 'rc.1 -> rc.2 replacement did not preserve package UID and first-install identity'
    }
    $upgradeRunId = "rc2-after-upgrade-$([Guid]::NewGuid().ToString('N'))"
    Invoke-HostProof `
        -Mode 'smoke' `
        -RunId $upgradeRunId `
        -HostVersionCode $currentHostIdentity.VersionCode `
        -ProviderVersionCode $rc2Identity.VersionCode `
        -Marker $SmokeMarker `
        -RequiredLogFragments @('executions=2', 'discovery=pass', 'result=pass', 'console=pass') | Out-Null
    Write-Host (
        'RELEASE_UPGRADE_CASE_PASS case=rc1-to-rc2 ' +
        "rc1RunId=$rc1RunId rc2RunId=$upgradeRunId packageUid=$($rc2Upgraded.Uid) " +
        'firstInstallTimePreserved=true installReplace=true smokeBefore=pass smokeAfter=pass'
    )

    $uninstallOutput = (Invoke-Adb @('uninstall', $ProviderPackage)) -join "`n"
    if ($uninstallOutput -notmatch '(?m)^Success\s*$') {
        throw "ADB did not confirm rc.2 uninstall`n$uninstallOutput"
    }
    $packageAbsent = -not (Test-PackageInstalled $ProviderPackage)
    $runtimeAbsent = (Read-OptionalPid $RuntimeProcess) -eq $null
    if (-not $packageAbsent -or -not $runtimeAbsent) {
        throw "rc.2 uninstall left residual state: packageAbsent=$packageAbsent runtimeAbsent=$runtimeAbsent"
    }
    Install-Apk $rc2Provider
    Assert-InstalledArtifact $ProviderPackage $rc2Identity.VersionCode $rc2Identity.Sha256 | Out-Null
    $reinstallRunId = "rc2-after-reinstall-$([Guid]::NewGuid().ToString('N'))"
    Invoke-HostProof `
        -Mode 'smoke' `
        -RunId $reinstallRunId `
        -HostVersionCode $currentHostIdentity.VersionCode `
        -ProviderVersionCode $rc2Identity.VersionCode `
        -Marker $SmokeMarker `
        -RequiredLogFragments @('executions=2', 'discovery=pass', 'result=pass', 'console=pass') | Out-Null
    Write-Host (
        'RELEASE_UPGRADE_CASE_PASS case=rc2-uninstall-reinstall ' +
        "runId=$reinstallRunId packageAbsent=$($packageAbsent.ToString().ToLowerInvariant()) " +
        "runtimeAbsent=$($runtimeAbsent.ToString().ToLowerInvariant()) installedSha256=$($rc2Identity.Sha256) smoke=pass"
    )

    Install-Apk $olderHost -Replace -AllowDowngrade
    Install-Apk $lifecycle -Replace
    Assert-InstalledArtifact $HostPackage $olderHostIdentity.VersionCode $olderHostIdentity.Sha256 | Out-Null
    $incompatibleRunId = "rc2-older-host-$([Guid]::NewGuid().ToString('N'))"
    Invoke-HostProof `
        -Mode 'incompatible' `
        -RunId $incompatibleRunId `
        -HostVersionCode $olderHostIdentity.VersionCode `
        -ProviderVersionCode $rc2Identity.VersionCode `
        -Marker $IncompatibleMarker `
        -RequiredLogFragments @(
            "outerCode=$ExpectedOuterCode",
            "rejection=$ExpectedRejection",
            'dispatch=not-entered'
        ) | Out-Null
    $rejectionRuntimePid = Read-OptionalPid $RuntimeProcess
    if ($rejectionRuntimePid -eq $null) {
        throw 'Older Host rejection did not probe the Provider runtime-info endpoint'
    }
    Write-Host (
        'RELEASE_UPGRADE_CASE_PASS case=older-host-rejection ' +
        "runId=$incompatibleRunId hostVersionCode=$($olderHostIdentity.VersionCode) " +
        "providerVersionCode=$($rc2Identity.VersionCode) outerCode=$ExpectedOuterCode " +
        "rejection=$ExpectedRejection dispatch=not-entered runtimeInfoProbePid=$rejectionRuntimePid"
    )

    Install-Apk $currentHost -Replace
    Install-Apk $lifecycle -Replace
    Assert-InstalledArtifact $HostPackage $currentHostIdentity.VersionCode $currentHostIdentity.Sha256 | Out-Null
    $restoreRunId = "rc2-current-host-restored-$([Guid]::NewGuid().ToString('N'))"
    Invoke-HostProof `
        -Mode 'smoke' `
        -RunId $restoreRunId `
        -HostVersionCode $currentHostIdentity.VersionCode `
        -ProviderVersionCode $rc2Identity.VersionCode `
        -Marker $SmokeMarker `
        -RequiredLogFragments @('executions=2', 'discovery=pass', 'result=pass', 'console=pass') | Out-Null

    $script:MatrixComplete = $true
    Write-Host (
        'RELEASE_UPGRADE_MATRIX_PASS ' +
        "serial=$Serial api=$api abis=$abis " +
        "olderHostRevision=$($OlderHostRevision.ToLowerInvariant()) olderHostVersionCode=$($olderHostIdentity.VersionCode) olderHostSha256=$($olderHostIdentity.Sha256) " +
        "currentHostRevision=$($CurrentHostRevision.ToLowerInvariant()) currentHostVersionCode=$($currentHostIdentity.VersionCode) currentHostSha256=$($currentHostIdentity.Sha256) " +
        "rc1Revision=$($Rc1Revision.ToLowerInvariant()) rc1VersionCode=$($rc1Identity.VersionCode) rc1Sha256=$($rc1Identity.Sha256) " +
        "rc2Revision=$($Rc2Revision.ToLowerInvariant()) rc2VersionCode=$($rc2Identity.VersionCode) rc2Sha256=$($rc2Identity.Sha256) " +
        "lifecycleVersionCode=$($lifecycleIdentity.VersionCode) lifecycleSha256=$($lifecycleIdentity.Sha256) signerSha256=$($signers[0]) " +
        "rc1RunId=$rc1RunId upgradeRunId=$upgradeRunId reinstallRunId=$reinstallRunId incompatibleRunId=$incompatibleRunId restoreRunId=$restoreRunId " +
        "outerCode=$ExpectedOuterCode rejection=$ExpectedRejection dispatch=not-entered " +
        'upgrade=pass uninstallReinstall=pass olderHostRejection=pass currentHostRestore=pass'
    )
} finally {
    if ($script:MutationStarted -and -not $script:MatrixComplete) {
        & $script:Adb -s $Serial install -r $currentHost 2>&1 | Out-Null
        if (-not (Test-PackageInstalled $ProviderPackage)) {
            & $script:Adb -s $Serial install $rc2Provider 2>&1 | Out-Null
        }
        & $script:Adb -s $Serial install -r $lifecycle 2>&1 | Out-Null
    }
}
