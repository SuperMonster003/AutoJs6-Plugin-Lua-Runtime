[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [string] $BaselineHostApk,

    [Parameter(Mandatory = $true)]
    [string] $UpdatedHostApk,

    [Parameter(Mandatory = $true)]
    [string] $ProviderApk,

    [Parameter(Mandatory = $true)]
    [string] $LifecycleTestApk,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F]{40}$')]
    [string] $HostRevision,

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
$ArmedMarker = 'LUA_HOST_LIFECYCLE_ARMED'
$VerifyMarker = 'LUA_HOST_LIFECYCLE_VERIFY_PASS'
$script:ArmOutstanding = $false

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
    $versionMatch = [regex]::Match($manifest, 'android:versionCode="([0-9]+)"')
    if (-not $packageMatch.Success -or -not $versionMatch.Success) {
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
        VersionCode = [long] $versionMatch.Groups[1].Value
        Signer = $signerMatches[0].Groups[1].Value.ToLowerInvariant()
        Sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $Apk).Hash.ToLowerInvariant()
        Bytes = (Get-Item -LiteralPath $Apk).Length
    }
}

function Read-DeviceVersionCode {
    param(
        [Parameter(Mandatory = $true)]
        [string] $PackageName
    )

    $report = (Invoke-Adb @('shell', 'dumpsys', 'package', $PackageName)) -join "`n"
    $match = [regex]::Match($report, 'versionCode=([0-9]+)')
    if (-not $match.Success) {
        throw "Unable to read installed versionCode for $PackageName"
    }
    return [long] $match.Groups[1].Value
}

function Read-SinglePid {
    param(
        [Parameter(Mandatory = $true)]
        [string] $ProcessName
    )

    $value = ((Invoke-Adb @('shell', 'pidof', $ProcessName)) -join '').Trim()
    if ($value -notmatch '^[0-9]+$') {
        throw "Expected one live PID for $ProcessName, observed '$value'"
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

function Wait-ForLogMarker {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Marker,

        [Parameter(Mandatory = $true)]
        [string] $RunId,

        [int] $TimeoutMillis = 10000
    )

    $deadline = [Environment]::TickCount64 + $TimeoutMillis
    do {
        $log = (Invoke-Adb @('logcat', '-d', '-v', 'raw', '-s', "${LogTag}:I", '*:S')) -join "`n"
        if ($log.Contains("$Marker runId=$RunId")) {
            return
        }
        Start-Sleep -Milliseconds 100
    } while ([Environment]::TickCount64 -lt $deadline)
    throw "Timed out waiting for $Marker runId=$RunId"
}

function Start-ArmedHostExecution {
    param(
        [Parameter(Mandatory = $true)]
        [string] $CaseName
    )

    $runId = "$CaseName-$([Guid]::NewGuid().ToString('N'))"
    Invoke-Adb @(
        'shell', 'am', 'instrument', '-r',
        '-e', 'mode', 'arm',
        '-e', 'runId', $runId,
        $LifecycleComponent
    ) | Out-Null
    $script:ArmOutstanding = $true
    Wait-ForLogMarker $ArmedMarker $runId
    $hostPid = Read-SinglePid $HostPackage
    $runtimePid = Read-SinglePid $RuntimeProcess
    return [pscustomobject]@{
        RunId = $runId
        HostPid = $hostPid
        RuntimePid = $runtimePid
    }
}

function Invoke-RecoveryVerification {
    param(
        [Parameter(Mandatory = $true)]
        [string] $RunId,

        [Parameter(Mandatory = $true)]
        [int] $ExpectedRuntimePid
    )

    $beforePid = Read-SinglePid $RuntimeProcess
    if ($beforePid -ne $ExpectedRuntimePid) {
        throw "Lua runtime PID changed before recovery: expected=$ExpectedRuntimePid observed=$beforePid"
    }
    $output = (Invoke-Adb @(
        'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'mode', 'verify',
        '-e', 'runId', $RunId,
        $LifecycleComponent
    )) -join "`n"
    if (-not $output.Contains("$VerifyMarker runId=$RunId") -or
        $output -notmatch 'INSTRUMENTATION_CODE:\s*-1') {
        throw "Host lifecycle recovery verification failed`n$output"
    }
    $afterPid = Read-SinglePid $RuntimeProcess
    if ($afterPid -ne $ExpectedRuntimePid) {
        throw "Lua runtime PID changed across stale-watchdog proof: expected=$ExpectedRuntimePid observed=$afterPid"
    }
    return $afterPid
}

if ($Serial -notmatch '^emulator-[0-9]+$') {
    throw 'Host lifecycle mutation is restricted to emulator-* serials'
}
if ([string]::IsNullOrWhiteSpace($SdkRoot) -or -not [IO.Path]::IsPathRooted($SdkRoot)) {
    throw 'SdkRoot must be an absolute Android SDK path'
}
$script:ResolvedSdkRoot = (Get-Item -LiteralPath $SdkRoot -ErrorAction Stop).FullName
$script:Adb = Resolve-SdkTool 'platform-tools/adb.exe'
$script:ApkSigner = Resolve-SdkTool 'build-tools/37.0.0/apksigner.bat'
$script:ApkAnalyzer = Resolve-SdkTool 'cmdline-tools/latest/bin/apkanalyzer.bat'

$baselineHost = Resolve-RegularFile $BaselineHostApk 'Baseline Host APK'
$updatedHost = Resolve-RegularFile $UpdatedHostApk 'Updated Host APK'
$provider = Resolve-RegularFile $ProviderApk 'Lua provider APK'
$lifecycle = Resolve-RegularFile $LifecycleTestApk 'Host lifecycle test APK'

$state = ((Invoke-Adb @('get-state')) -join '').Trim()
$isQemu = ((Invoke-Adb @('shell', 'getprop', 'ro.kernel.qemu')) -join '').Trim()
if ($state -ne 'device' -or $isQemu -ne '1') {
    throw "Serial $Serial is not an online Android emulator"
}
$api = [int] (((Invoke-Adb @('shell', 'getprop', 'ro.build.version.sdk')) -join '').Trim())
$abis = ((Invoke-Adb @('shell', 'getprop', 'ro.product.cpu.abilist')) -join '').Trim()
if ($api -lt 24 -or $abis -notmatch '(^|,)x86_64(,|$)') {
    throw "Lifecycle matrix requires API >= 24 with x86_64; api=$api abis=$abis"
}

$baselineIdentity = Read-ApkIdentity $baselineHost
$updatedIdentity = Read-ApkIdentity $updatedHost
$providerIdentity = Read-ApkIdentity $provider
$lifecycleIdentity = Read-ApkIdentity $lifecycle
if ($baselineIdentity.Package -ne $HostPackage -or $updatedIdentity.Package -ne $HostPackage) {
    throw 'Both Host APKs must own org.autojs.autojs6'
}
if ($baselineIdentity.VersionCode -ge $updatedIdentity.VersionCode) {
    throw "Host update must increase versionCode: $($baselineIdentity.VersionCode) -> $($updatedIdentity.VersionCode)"
}
if ($providerIdentity.Package -ne $ProviderPackage) {
    throw "Unexpected Lua provider package: $($providerIdentity.Package)"
}
if ($lifecycleIdentity.Package -ne $LifecyclePackage) {
    throw "Unexpected lifecycle test package: $($lifecycleIdentity.Package)"
}
$signers = @(@(
        $baselineIdentity.Signer,
        $updatedIdentity.Signer,
        $providerIdentity.Signer,
        $lifecycleIdentity.Signer
    ) | Select-Object -Unique)
if ($signers.Count -ne 1) {
    throw 'Host, provider, and lifecycle APKs do not share one signer'
}
$providerResources = (Invoke-Captured $script:ApkAnalyzer @(
        'resources', 'value',
        '--config', 'default',
        '--type', 'bool',
        '--name', 'lua_runtime_provider_enabled',
        $provider
    )) -join "`n"
if ($providerResources -notmatch '(?i)true') {
    throw 'Lua provider APK does not enable production discovery'
}

try {
    Install-Apk $provider -Replace
    Install-Apk $baselineHost -Replace -AllowDowngrade
    Install-Apk $lifecycle -Replace
    if ((Read-DeviceVersionCode $HostPackage) -ne $baselineIdentity.VersionCode) {
        throw 'Baseline Host version was not installed exactly'
    }

    $updateArm = Start-ArmedHostExecution 'update'
    Install-Apk $updatedHost -Replace
    $script:ArmOutstanding = $false
    if ((Read-DeviceVersionCode $HostPackage) -ne $updatedIdentity.VersionCode) {
        throw 'Updated Host version was not installed exactly'
    }
    Install-Apk $lifecycle -Replace
    $updateRuntimePid = Invoke-RecoveryVerification `
        -RunId $updateArm.RunId `
        -ExpectedRuntimePid $updateArm.RuntimePid

    $uninstallArm = Start-ArmedHostExecution 'uninstall-reinstall'
    $uninstallOutput = (Invoke-Adb @('uninstall', $HostPackage)) -join "`n"
    $script:ArmOutstanding = $false
    if ($uninstallOutput -notmatch '(?m)^Success\s*$') {
        throw "ADB did not confirm Host uninstall`n$uninstallOutput"
    }
    $hostPathAfterUninstall = @(& $script:Adb -s $Serial shell pm path $HostPackage 2>&1)
    if ($LASTEXITCODE -eq 0 -and (($hostPathAfterUninstall -join '').Trim()).Length -ne 0) {
        throw 'Host package remained installed after uninstall'
    }
    $providerPath = ((Invoke-Adb @('shell', 'pm', 'path', $ProviderPackage)) -join '').Trim()
    if (-not $providerPath.StartsWith('package:')) {
        throw 'Lua provider was removed with the Host package'
    }

    Install-Apk $updatedHost
    Install-Apk $lifecycle -Replace
    if ((Read-DeviceVersionCode $HostPackage) -ne $updatedIdentity.VersionCode) {
        throw 'Reinstalled Host version was not installed exactly'
    }
    $uninstallRuntimePid = Invoke-RecoveryVerification `
        -RunId $uninstallArm.RunId `
        -ExpectedRuntimePid $uninstallArm.RuntimePid

    Write-Host (
        'HOST_LIFECYCLE_MATRIX_PASS ' +
        "serial=$Serial api=$api abis=$abis hostRevision=$($HostRevision.ToLowerInvariant()) " +
        "hostVersionCodes=$($baselineIdentity.VersionCode)->$($updatedIdentity.VersionCode) " +
        "hostBaselineSha256=$($baselineIdentity.Sha256) hostUpdatedSha256=$($updatedIdentity.Sha256) " +
        "providerVersionCode=$($providerIdentity.VersionCode) providerSha256=$($providerIdentity.Sha256) " +
        "lifecycleSha256=$($lifecycleIdentity.Sha256) signerSha256=$($signers[0]) " +
        "updateRuntimePid=$updateRuntimePid uninstallRuntimePid=$uninstallRuntimePid " +
        'update=pass uninstallReinstall=pass recoveryExecutions=4 staleWatchdogProofMillis=7000'
    )
} finally {
    if ($script:ArmOutstanding) {
        & $script:Adb -s $Serial shell am force-stop $HostPackage 2>&1 | Out-Null
    }
}
