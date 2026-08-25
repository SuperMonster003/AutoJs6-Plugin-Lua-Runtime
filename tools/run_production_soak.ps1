[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^emulator-[0-9]+$')]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [string] $HostApk,

    [Parameter(Mandatory = $true)]
    [string] $ProviderApk,

    [Parameter(Mandatory = $true)]
    [string] $LifecycleTestApk,

    [ValidatePattern('^[a-z0-9][a-z0-9._-]{0,63}$')]
    [string] $RoundId = 'r4e-rc2-x86_64-round-1',

    [switch] $QualificationOnly,

    [string] $SdkRoot = $env:ANDROID_HOME
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RequiredDays = 7
$ProductionIterations = 250
$QualificationIterations = 10
$WarmupIterations = 10
$ExecutionsPerIteration = 2
$FdSampleInterval = 25
$HostPackage = 'org.autojs.autojs6'
$ProviderPackage = 'io.github.supermonster003.autojs6.plugin.lua.runtime'
$LifecyclePackage = 'io.github.supermonster003.autojs6.plugin.lua.runtime.host.lifecycle.test'
$LifecycleComponent = "$LifecyclePackage/.LuaHostLifecycleInstrumentation"
$RuntimeProcess = "${ProviderPackage}:lua_runtime"
$ExpectedHostVersionCode = 5276L
$ExpectedProviderVersionCode = 43L
$ExpectedHostRevision = 'b39872e2f1ccc940afcb74a6b95b5458e2fee594'
$ExpectedProviderRevision = 'a0ae189ac8cba042848412a671c91b0b8a7c44e1'
$ExpectedHostSha256 = '813c6be9b051c2eada18b0bbe00acff4abb48facd8e1ddd9ff08d904861d367e'
$ExpectedProviderSha256 = 'c92fbea3c878d7b2ba1c28bdca168201b98c28c6bf62a953f63e2c9771f45a12'
$ExpectedSignerSha256 = '31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213'
$ExpectedApi = 36
$ExpectedAbi = 'x86_64'
$ExpectedAvd = 'DEX_R1_API36_X64'
$SmokeMarker = 'LUA_HOST_OFFICIAL_SMOKE_PASS'
$FailureMarker = 'LUA_HOST_LIFECYCLE_FAIL'
$WatchdogEvent = 'event=lua_runtime_fail_stop'
$script:ResolvedSdkRoot = $null
$script:Adb = $null
$script:ApkSigner = $null
$script:ApkAnalyzer = $null

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

function Get-ShanghaiNow {
    $zone = $null
    foreach ($zoneId in @('China Standard Time', 'Asia/Shanghai')) {
        try {
            $zone = [TimeZoneInfo]::FindSystemTimeZoneById($zoneId)
            break
        } catch {
            $zone = $null
        }
    }
    if ($null -eq $zone) {
        throw 'Neither China Standard Time nor Asia/Shanghai is available'
    }
    return [TimeZoneInfo]::ConvertTime([DateTimeOffset]::UtcNow, $zone)
}

function Write-JsonAtomic {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [object] $Value
    )

    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
        [void] (New-Item -ItemType Directory -Path $parent)
    }
    $temporary = "$Path.tmp-$PID"
    [IO.File]::WriteAllText(
        $temporary,
        (($Value | ConvertTo-Json -Depth 20) + [Environment]::NewLine),
        [Text.UTF8Encoding]::new($false)
    )
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        [IO.File]::Replace($temporary, $Path, $null)
    } else {
        Move-Item -LiteralPath $temporary -Destination $Path
    }
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
    return [ordered]@{
        path = $Apk
        package = $packageMatch.Groups[1].Value
        versionCode = [long] $versionMatch.Groups[1].Value
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $Apk).Hash.ToLowerInvariant()
        bytes = (Get-Item -LiteralPath $Apk).Length
        signerSha256 = $signerMatches[0].Groups[1].Value.ToLowerInvariant()
    }
}

function Assert-ArtifactIdentity {
    param(
        [Parameter(Mandatory = $true)]
        [System.Collections.IDictionary] $Identity,

        [Parameter(Mandatory = $true)]
        [string] $Label,

        [Parameter(Mandatory = $true)]
        [string] $Package,

        [long] $VersionCode,

        [string] $Sha256
    )

    if ($Identity.package -ne $Package) {
        throw "$Label package drift: $($Identity.package)"
    }
    if ($VersionCode -gt 0 -and $Identity.versionCode -ne $VersionCode) {
        throw "$Label versionCode drift: $($Identity.versionCode)"
    }
    if (-not [string]::IsNullOrEmpty($Sha256) -and $Identity.sha256 -ne $Sha256) {
        throw "$Label SHA-256 drift: $($Identity.sha256)"
    }
    if ($Identity.signerSha256 -ne $ExpectedSignerSha256) {
        throw "$Label signer drift: $($Identity.signerSha256)"
    }
}

function Read-SinglePid {
    $value = ((Invoke-Adb @('shell', 'pidof', $RuntimeProcess)) -join '').Trim()
    if ($value -notmatch '^[0-9]+$') {
        throw "Expected one live PID for $RuntimeProcess, observed '$value'"
    }
    return [int] $value
}

function Read-FdCount {
    param(
        [Parameter(Mandatory = $true)]
        [int] $RuntimePidValue
    )

    $entries = @(Invoke-Adb @('shell', 'ls', '-1', "/proc/$RuntimePidValue/fd"))
    $numericEntries = @($entries | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ -match '^[0-9]+$' })
    if ($numericEntries.Count -eq 0) {
        throw "Unable to count descriptors for runtime PID $RuntimePidValue"
    }
    return $numericEntries.Count
}

function Wait-StableFdCount {
    param(
        [Parameter(Mandatory = $true)]
        [int] $RuntimePidValue,

        [Nullable[int]] $Expected
    )

    $samples = [Collections.Generic.List[int]]::new()
    $streak = 0
    $previous = -1
    for ($attempt = 1; $attempt -le 24; $attempt++) {
        if ((Read-SinglePid) -ne $RuntimePidValue) {
            throw "Runtime PID changed while descriptors were settling: $RuntimePidValue"
        }
        $current = Read-FdCount $RuntimePidValue
        $samples.Add($current)
        if ($null -ne $Expected) {
            $streak = if ($current -eq $Expected.Value) { $streak + 1 } else { 0 }
        } else {
            $streak = if ($current -eq $previous) { $streak + 1 } else { 1 }
        }
        if ($streak -ge 3) {
            return [ordered]@{ value = $current; samples = @($samples) }
        }
        $previous = $current
        Start-Sleep -Milliseconds 500
    }
    $expectation = if ($null -eq $Expected) { 'a stable value' } else { $Expected.Value }
    throw "Runtime FD count did not settle at $expectation; samples=$($samples -join ',')"
}

function Install-Apk {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Apk
    )

    $output = (Invoke-Adb @('install', '-r', $Apk)) -join "`n"
    if ($output -notmatch '(?m)^Success\s*$') {
        throw "ADB did not confirm APK installation: $Apk`n$output"
    }
}

function Read-DevicePackageSnapshot {
    param(
        [Parameter(Mandatory = $true)]
        [string] $PackageName,

        [Parameter(Mandatory = $true)]
        [string] $Label,

        [Parameter(Mandatory = $true)]
        [string] $EvidenceRoot
    )

    $report = (Invoke-Adb @('shell', 'dumpsys', 'package', $PackageName)) -join "`n"
    $versionMatch = [regex]::Match($report, 'versionCode=([0-9]+)')
    $firstInstallMatch = [regex]::Match($report, 'firstInstallTime=([^\r\n]+)')
    $lastUpdateMatch = [regex]::Match($report, 'lastUpdateTime=([^\r\n]+)')
    if (-not $versionMatch.Success -or -not $firstInstallMatch.Success -or -not $lastUpdateMatch.Success) {
        throw "Unable to read installed package identity for $PackageName"
    }
    $packagePaths = @(
        Invoke-Adb @('shell', 'pm', 'path', $PackageName) |
            ForEach-Object { $_.ToString().Trim() } |
            Where-Object { $_.StartsWith('package:') }
    )
    if ($packagePaths.Count -ne 1) {
        throw "Expected one installed base APK for $PackageName, observed $($packagePaths.Count)"
    }
    $remotePath = $packagePaths[0].Substring('package:'.Length)
    $temporary = Join-Path $EvidenceRoot "installed-$Label-$PID.tmp.apk"
    $pullOutput = (Invoke-Adb @('pull', $remotePath, $temporary)) -join "`n"
    if (-not (Test-Path -LiteralPath $temporary -PathType Leaf)) {
        throw "ADB did not materialize installed APK for $PackageName`n$pullOutput"
    }
    try {
        $digest = (Get-FileHash -Algorithm SHA256 -LiteralPath $temporary).Hash.ToLowerInvariant()
        $bytes = (Get-Item -LiteralPath $temporary).Length
    } finally {
        Remove-Item -LiteralPath $temporary -Force
    }
    return [ordered]@{
        package = $PackageName
        versionCode = [long] $versionMatch.Groups[1].Value
        sha256 = $digest
        bytes = $bytes
        firstInstallTime = $firstInstallMatch.Groups[1].Value.Trim()
        lastUpdateTime = $lastUpdateMatch.Groups[1].Value.Trim()
    }
}

function Assert-InstalledSnapshot {
    param(
        [Parameter(Mandatory = $true)]
        [System.Collections.IDictionary] $Artifact,

        [Parameter(Mandatory = $true)]
        [System.Collections.IDictionary] $Installed,

        [Parameter(Mandatory = $true)]
        [string] $Label
    )

    if ($Installed.package -ne $Artifact.package -or
        $Installed.versionCode -ne $Artifact.versionCode -or
        $Installed.sha256 -ne $Artifact.sha256 -or
        $Installed.bytes -ne $Artifact.bytes) {
        throw "$Label installed APK does not exactly match the supplied artifact"
    }
}

function Invoke-Smoke {
    param(
        [Parameter(Mandatory = $true)]
        [string] $RunId,

        [Parameter(Mandatory = $true)]
        [string] $InstrumentationLog
    )

    $output = (Invoke-Adb @(
        'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'mode', 'smoke',
        '-e', 'runId', $RunId,
        '-e', 'expectedHostVersionCode', $ExpectedHostVersionCode.ToString(),
        '-e', 'expectedProviderVersionCode', $ExpectedProviderVersionCode.ToString(),
        $LifecycleComponent
    )) -join "`n"
    [IO.File]::AppendAllText(
        $InstrumentationLog,
        "===== $RunId =====`n$output`n",
        [Text.UTF8Encoding]::new($false)
    )
    if (-not $output.Contains("$SmokeMarker runId=$RunId") -or
        $output.Contains("$FailureMarker runId=$RunId") -or
        $output -notmatch 'INSTRUMENTATION_CODE:\s*-1') {
        throw "Official Host smoke failed: runId=$RunId"
    }
}

function Invoke-Warmup {
    param(
        [Parameter(Mandatory = $true)]
        [string] $InstrumentationLog
    )

    for ($iteration = 1; $iteration -le $WarmupIterations; $iteration++) {
        $runId = "warmup-i$('{0:d2}' -f $iteration)-$([Guid]::NewGuid().ToString('N'))"
        Invoke-Smoke $runId $InstrumentationLog
    }
}

function Capture-And-AssertLogs {
    param(
        [Parameter(Mandatory = $true)]
        [string] $LogPath
    )

    $lines = @(Invoke-Adb @('logcat', '-b', 'all', '-d', '-v', 'threadtime'))
    [IO.File]::WriteAllLines($LogPath, [string[]] $lines, [Text.UTF8Encoding]::new($false))
    $relatedNames = @($HostPackage, $ProviderPackage, $LifecyclePackage, $RuntimeProcess)
    $violations = [Collections.Generic.List[string]]::new()
    foreach ($lineValue in $lines) {
        $line = $lineValue.ToString()
        if ($line.Contains($WatchdogEvent) -or $line.Contains($FailureMarker)) {
            $violations.Add($line)
            continue
        }
        $isAnrOrCrash = $line.Contains('am_anr') -or $line.Contains('am_crash') -or $line.Contains('ANR in ')
        if ($isAnrOrCrash -and @($relatedNames | Where-Object { $line.Contains($_) }).Count -gt 0) {
            $violations.Add($line)
        }
    }
    if ($violations.Count -ne 0) {
        throw "Relevant fail-stop, ANR, or crash log detected`n$($violations -join [Environment]::NewLine)"
    }
    return [ordered]@{
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $LogPath).Hash.ToLowerInvariant()
        bytes = (Get-Item -LiteralPath $LogPath).Length
        violations = 0
    }
}

function Invoke-MeasuredWorkload {
    param(
        [Parameter(Mandatory = $true)]
        [int] $Day,

        [Parameter(Mandatory = $true)]
        [int] $Iterations,

        [Parameter(Mandatory = $true)]
        [int] $RuntimePidValue,

        [Parameter(Mandatory = $true)]
        [int] $BaselineFd,

        [Parameter(Mandatory = $true)]
        [string] $InstrumentationLog
    )

    $fdSamples = [Collections.Generic.List[object]]::new()
    $fdSamples.Add([ordered]@{ iteration = 0; count = $BaselineFd })
    for ($iteration = 1; $iteration -le $Iterations; $iteration++) {
        $runId = "soak-d$('{0:d2}' -f $Day)-i$('{0:d4}' -f $iteration)-$([Guid]::NewGuid().ToString('N'))"
        Invoke-Smoke $runId $InstrumentationLog
        $observedPid = Read-SinglePid
        if ($observedPid -ne $RuntimePidValue) {
            throw "Runtime PID changed during workload: expected=$RuntimePidValue observed=$observedPid"
        }
        if ($iteration % $FdSampleInterval -eq 0 -or $iteration -eq $Iterations) {
            Start-Sleep -Milliseconds 250
            $fdCount = Read-FdCount $RuntimePidValue
            $fdSamples.Add([ordered]@{ iteration = $iteration; count = $fdCount })
            Write-Host "SOAK_PROGRESS day=$Day iterations=$iteration/$Iterations runtimePid=$RuntimePidValue fd=$fdCount"
        }
    }
    return @($fdSamples)
}

function Test-StateArtifact {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Recorded,

        [Parameter(Mandatory = $true)]
        [System.Collections.IDictionary] $Current,

        [Parameter(Mandatory = $true)]
        [string] $Label
    )

    foreach ($property in @('package', 'versionCode', 'sha256', 'bytes', 'signerSha256')) {
        if ($Recorded.$property -ne $Current[$property]) {
            throw "$Label artifact changed after the soak round started: $property"
        }
    }
}

if ($Serial -notmatch '^emulator-[0-9]+$') {
    throw 'Production soak mutation is restricted to emulator-* serials'
}
if ([string]::IsNullOrWhiteSpace($SdkRoot) -or -not [IO.Path]::IsPathRooted($SdkRoot)) {
    throw 'SdkRoot must be an absolute Android SDK path'
}
$script:ResolvedSdkRoot = (Get-Item -LiteralPath $SdkRoot -ErrorAction Stop).FullName
$script:Adb = Resolve-SdkTool 'platform-tools/adb.exe'
$script:ApkSigner = Resolve-SdkTool 'build-tools/37.0.0/apksigner.bat'
$script:ApkAnalyzer = Resolve-SdkTool 'cmdline-tools/latest/bin/apkanalyzer.bat'

$hostPath = Resolve-RegularFile $HostApk 'Host APK'
$providerPath = Resolve-RegularFile $ProviderApk 'Provider APK'
$lifecyclePath = Resolve-RegularFile $LifecycleTestApk 'Lifecycle test APK'
$hostIdentity = Read-ApkIdentity $hostPath
$providerIdentity = Read-ApkIdentity $providerPath
$lifecycleIdentity = Read-ApkIdentity $lifecyclePath
Assert-ArtifactIdentity $hostIdentity 'Host' $HostPackage $ExpectedHostVersionCode $ExpectedHostSha256
Assert-ArtifactIdentity $providerIdentity 'Provider' $ProviderPackage $ExpectedProviderVersionCode $ExpectedProviderSha256
Assert-ArtifactIdentity $lifecycleIdentity 'Lifecycle test' $LifecyclePackage 0 $null
$providerResources = (Invoke-Captured $script:ApkAnalyzer @(
    'resources', 'value', '--config', 'default', '--type', 'bool',
    '--name', 'lua_runtime_provider_enabled', $providerPath
)) -join "`n"
if ($providerResources -notmatch '(?i)true') {
    throw 'Provider APK does not enable production discovery'
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$soakRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'build/soak'))
if (-not (Test-Path -LiteralPath $soakRoot -PathType Container)) {
    [void] (New-Item -ItemType Directory -Path $soakRoot)
}
$roundRoot = [IO.Path]::GetFullPath((Join-Path $soakRoot $RoundId))
$requiredPrefix = $soakRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $roundRoot.StartsWith($requiredPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Round evidence path escaped build/soak'
}
$statePath = Join-Path $roundRoot 'state.json'
$activeStates = @(
    Get-ChildItem -LiteralPath $soakRoot -Recurse -File -Filter state.json -ErrorAction Stop |
        Where-Object {
            try {
                (Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json).status -eq 'in_progress'
            } catch {
                $false
            }
        }
)
if ($QualificationOnly -and $activeStates.Count -ne 0) {
    throw 'Qualification cannot replace APKs while a production soak round is in progress'
}
# qualification cannot create or advance production state; only the daily branch writes state.json.

[void] (Invoke-Captured $script:Adb @('-s', $Serial, 'root'))
[void] (Invoke-Captured $script:Adb @('-s', $Serial, 'wait-for-device'))
$state = ((Invoke-Adb @('get-state')) -join '').Trim()
$isQemu = ((Invoke-Adb @('shell', 'getprop', 'ro.kernel.qemu')) -join '').Trim()
$identity = ((Invoke-Adb @('shell', 'id')) -join '').Trim()
if ($state -ne 'device' -or $isQemu -ne '1' -or -not $identity.StartsWith('uid=0(root)')) {
    throw "Serial $Serial is not an online rooted Android emulator"
}
$api = [int] (((Invoke-Adb @('shell', 'getprop', 'ro.build.version.sdk')) -join '').Trim())
$abis = ((Invoke-Adb @('shell', 'getprop', 'ro.product.cpu.abilist')) -join '').Trim()
$avd = ((Invoke-Adb @('shell', 'getprop', 'ro.boot.qemu.avd_name')) -join '').Trim()
$bootId = ((Invoke-Adb @('shell', 'cat', '/proc/sys/kernel/random/boot_id')) -join '').Trim().ToLowerInvariant()
$expectedAbiPattern = '(^|,)' + [regex]::Escape($ExpectedAbi) + '(,|$)'
if ($api -ne $ExpectedApi -or $abis -notmatch $expectedAbiPattern -or $avd -ne $ExpectedAvd) {
    throw "Soak requires the dedicated API 36 x86_64 AVD; api=$api abis=$abis avd=$avd"
}
if ($bootId -notmatch '^[0-9a-f-]{36}$') {
    throw "Invalid emulator boot ID: $bootId"
}

$now = Get-ShanghaiNow
$localDate = $now.ToString('yyyy-MM-dd')
$startedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
$productionState = $null
$day = 0
$iterations = if ($QualificationOnly) { $QualificationIterations } else { $ProductionIterations }

if ($QualificationOnly) {
    $evidenceRoot = Join-Path $soakRoot "qualification-$($now.ToString('yyyyMMdd-HHmmss'))-$PID"
    [void] (New-Item -ItemType Directory -Path $evidenceRoot)
} elseif (-not (Test-Path -LiteralPath $statePath -PathType Leaf)) {
    if ($activeStates.Count -ne 0) {
        throw "Another production soak round is already in progress: $($activeStates[0].FullName)"
    }
    if (Test-Path -LiteralPath $roundRoot) {
        throw "Round directory already exists without a resumable state: $roundRoot"
    }
    [void] (New-Item -ItemType Directory -Path $roundRoot)
    $evidenceRoot = $roundRoot
    $day = 1
} else {
    $evidenceRoot = $roundRoot
    $productionState = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    if ($productionState.schemaVersion -ne 1 -or $productionState.roundId -ne $RoundId -or
        $productionState.status -ne 'in_progress' -or $productionState.requiredDays -ne $RequiredDays -or
        $productionState.iterationsPerDay -ne $ProductionIterations -or
        $productionState.executionsPerIteration -ne $ExecutionsPerIteration) {
        throw 'Production soak state schema or frozen standard drifted'
    }
    $day = [int] $productionState.completedDays + 1
    if ($day -lt 2 -or $day -gt $RequiredDays) {
        throw "Invalid next production soak day: $day"
    }
    $expectedDate = [datetime]::ParseExact(
        $productionState.startDate,
        'yyyy-MM-dd',
        [Globalization.CultureInfo]::InvariantCulture
    ).AddDays($productionState.completedDays).ToString('yyyy-MM-dd')
    if ($localDate -lt $expectedDate) {
        throw "Production day $day is not due until $expectedDate (Asia/Shanghai)"
    }
    if ($localDate -gt $expectedDate) {
        $productionState.status = 'invalid'
        $productionState.invalidatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        $productionState.invalidationReason = "missed consecutive date $expectedDate"
        Write-JsonAtomic $statePath $productionState
        throw "Production soak round missed required date $expectedDate and is now invalid"
    }
    if ($productionState.serial -ne $Serial -or $productionState.api -ne $api -or
        $productionState.abis -ne $abis -or $productionState.avd -ne $avd -or
        $productionState.bootId -ne $bootId) {
        $productionState.status = 'invalid'
        $productionState.invalidatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        $productionState.invalidationReason = 'emulator identity or boot instance changed'
        Write-JsonAtomic $statePath $productionState
        throw 'Emulator identity or boot instance changed; the soak round is now invalid'
    }
    try {
        Test-StateArtifact $productionState.artifacts.host $hostIdentity 'Host'
        Test-StateArtifact $productionState.artifacts.provider $providerIdentity 'Provider'
        Test-StateArtifact $productionState.artifacts.lifecycleTest $lifecycleIdentity 'Lifecycle test'
    } catch {
        $productionState.status = 'invalid'
        $productionState.invalidatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        $productionState.invalidationReason = $_.Exception.Message
        Write-JsonAtomic $statePath $productionState
        throw
    }
}

$dayLabel = if ($QualificationOnly) { 'qualification' } else { 'day-{0:d2}' -f $day }
$instrumentationLog = Join-Path $evidenceRoot "$dayLabel-instrumentation.txt"
$logcatPath = Join-Path $evidenceRoot "$dayLabel-logcat-all.txt"
$receiptPath = Join-Path $evidenceRoot "$dayLabel-receipt.json"

try {
    if ($QualificationOnly -or $day -eq 1) {
        Install-Apk $providerPath
        Install-Apk $hostPath
        Install-Apk $lifecyclePath
    }

    $installedHost = Read-DevicePackageSnapshot $HostPackage 'host' $evidenceRoot
    $installedProvider = Read-DevicePackageSnapshot $ProviderPackage 'provider' $evidenceRoot
    $installedLifecycle = Read-DevicePackageSnapshot $LifecyclePackage 'lifecycle' $evidenceRoot
    Assert-InstalledSnapshot $hostIdentity $installedHost 'Host'
    Assert-InstalledSnapshot $providerIdentity $installedProvider 'Provider'
    Assert-InstalledSnapshot $lifecycleIdentity $installedLifecycle 'Lifecycle test'

    if ($null -ne $productionState) {
        foreach ($pair in @(
            [ordered]@{
                recorded = $productionState.installed.host
                current = $installedHost
                label = 'Host'
            },
            [ordered]@{
                recorded = $productionState.installed.provider
                current = $installedProvider
                label = 'Provider'
            },
            [ordered]@{
                recorded = $productionState.installed.lifecycleTest
                current = $installedLifecycle
                label = 'Lifecycle test'
            }
        )) {
            if ($pair.recorded.firstInstallTime -ne $pair.current.firstInstallTime -or
                $pair.recorded.lastUpdateTime -ne $pair.current.lastUpdateTime -or
                $pair.recorded.sha256 -ne $pair.current.sha256) {
                throw "$($pair.label) installation changed after the soak round started"
            }
        }
    }

    if ($QualificationOnly -or $day -eq 1) {
        Invoke-Warmup $instrumentationLog
        $runtimePid = Read-SinglePid
        $baselineObservation = Wait-StableFdCount $runtimePid $null
        $baselineFd = [int] $baselineObservation.value
    } else {
        $runtimePid = Read-SinglePid
        if ($runtimePid -ne $productionState.runtimePid) {
            throw "Runtime PID changed between soak days: expected=$($productionState.runtimePid) observed=$runtimePid"
        }
        $baselineFd = [int] $productionState.baselineFd
        $baselineObservation = Wait-StableFdCount $runtimePid $baselineFd
    }

    if (-not $QualificationOnly -and $day -eq 1) {
        $productionState = [ordered]@{
            schemaVersion = 1
            roundId = $RoundId
            status = 'in_progress'
            timezone = 'Asia/Shanghai'
            startDate = $localDate
            completedDays = 0
            requiredDays = $RequiredDays
            iterationsPerDay = $ProductionIterations
            executionsPerIteration = $ExecutionsPerIteration
            serial = $Serial
            api = $api
            abis = $abis
            avd = $avd
            bootId = $bootId
            runtimePid = $runtimePid
            baselineFd = $baselineFd
            hostRevision = $ExpectedHostRevision
            providerRevision = $ExpectedProviderRevision
            signerSha256 = $ExpectedSignerSha256
            artifacts = [ordered]@{
                host = $hostIdentity
                provider = $providerIdentity
                lifecycleTest = $lifecycleIdentity
            }
            installed = [ordered]@{
                host = $installedHost
                provider = $installedProvider
                lifecycleTest = $installedLifecycle
            }
            days = @()
            invalidatedAtUtc = $null
            invalidationReason = $null
        }
        Write-JsonAtomic $statePath $productionState
    }

    [void] (Invoke-Adb @('logcat', '-b', 'all', '-c'))
    $fdSamples = Invoke-MeasuredWorkload $day $iterations $runtimePid $baselineFd $instrumentationLog
    $finalObservation = Wait-StableFdCount $runtimePid $baselineFd
    $finalFd = [int] $finalObservation.value
    $logEvidence = Capture-And-AssertLogs $logcatPath
    $finishedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    $instrumentationSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $instrumentationLog).Hash.ToLowerInvariant()
    $instrumentationBytes = (Get-Item -LiteralPath $instrumentationLog).Length
    $receipt = [ordered]@{
        schemaVersion = 1
        result = 'pass'
        qualificationOnly = [bool] $QualificationOnly
        roundId = if ($QualificationOnly) { $null } else { $RoundId }
        day = if ($QualificationOnly) { 0 } else { $day }
        localDate = $localDate
        timezone = 'Asia/Shanghai'
        startedAtUtc = $startedAtUtc
        finishedAtUtc = $finishedAtUtc
        iterations = $iterations
        executions = $iterations * $ExecutionsPerIteration
        warmupIterations = if ($QualificationOnly -or $day -eq 1) { $WarmupIterations } else { 0 }
        serial = $Serial
        api = $api
        abis = $abis
        avd = $avd
        bootId = $bootId
        runtimePid = $runtimePid
        baselineFd = $baselineFd
        finalFd = $finalFd
        baselineSettleSamples = @($baselineObservation.samples)
        periodicFdSamples = @($fdSamples)
        finalSettleSamples = @($finalObservation.samples)
        artifacts = [ordered]@{
            host = $hostIdentity
            provider = $providerIdentity
            lifecycleTest = $lifecycleIdentity
        }
        installed = [ordered]@{
            host = $installedHost
            provider = $installedProvider
            lifecycleTest = $installedLifecycle
        }
        hostRevision = $ExpectedHostRevision
        providerRevision = $ExpectedProviderRevision
        signerSha256 = $ExpectedSignerSha256
        instrumentation = [ordered]@{
            sha256 = $instrumentationSha256
            bytes = $instrumentationBytes
            failures = 0
        }
        logcat = $logEvidence
        failStops = 0
        relevantAnrs = 0
        relevantCrashes = 0
    }
    Write-JsonAtomic $receiptPath $receipt

    if ($QualificationOnly) {
        Write-Host (
            'PRODUCTION_SOAK_QUALIFICATION_PASS ' +
            "serial=$Serial api=$api avd=$avd iterations=$iterations executions=$($iterations * 2) " +
            "runtimePid=$runtimePid baselineFd=$baselineFd finalFd=$finalFd " +
            "hostSha256=$($hostIdentity.sha256) providerSha256=$($providerIdentity.sha256) " +
            "lifecycleSha256=$($lifecycleIdentity.sha256) receipt=$receiptPath"
        )
        return
    }

    $dayRecord = [ordered]@{
        day = $day
        localDate = $localDate
        startedAtUtc = $startedAtUtc
        finishedAtUtc = $finishedAtUtc
        iterations = $ProductionIterations
        executions = $ProductionIterations * $ExecutionsPerIteration
        runtimePid = $runtimePid
        baselineFd = $baselineFd
        finalFd = $finalFd
        failStops = 0
        relevantAnrs = 0
        relevantCrashes = 0
        instrumentationFailures = 0
        receiptSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $receiptPath).Hash.ToLowerInvariant()
    }
    $productionState.days = @($productionState.days) + @($dayRecord)
    $productionState.completedDays = $day
    if ($day -eq $RequiredDays) {
        $productionState.status = 'complete'
    }
    Write-JsonAtomic $statePath $productionState
    Write-Host (
        'PRODUCTION_SOAK_DAY_PASS ' +
        "roundId=$RoundId day=$day/$RequiredDays localDate=$localDate iterations=$ProductionIterations " +
        "executions=$($ProductionIterations * 2) runtimePid=$runtimePid baselineFd=$baselineFd finalFd=$finalFd " +
        "failStops=0 relevantAnrs=0 relevantCrashes=0 instrumentationFailures=0 " +
        "receipt=$receiptPath status=$($productionState.status)"
    )
} catch {
    $failureMessage = $_.Exception.Message
    $failureReceipt = [ordered]@{
        schemaVersion = 1
        result = 'fail'
        qualificationOnly = [bool] $QualificationOnly
        roundId = if ($QualificationOnly) { $null } else { $RoundId }
        day = if ($QualificationOnly) { 0 } else { $day }
        localDate = $localDate
        startedAtUtc = $startedAtUtc
        failedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        reason = $failureMessage
    }
    Write-JsonAtomic $receiptPath $failureReceipt
    if (-not $QualificationOnly -and $null -ne $productionState) {
        $productionState.status = 'invalid'
        $productionState.invalidatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        $productionState.invalidationReason = $failureMessage
        Write-JsonAtomic $statePath $productionState
    }
    throw
}
