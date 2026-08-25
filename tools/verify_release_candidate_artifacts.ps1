[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$')]
    [string] $InvocationStartedAtUtc,
    [Parameter(Mandatory = $true)]
    [string] $SigningPropertiesFile,
    [Parameter(Mandatory = $true)]
    [string] $SigningStoreFile,
    [string] $SdkRoot,
    [string] $BuildToolsVersion = '37.0.0',
    [string] $NdkVersion = '28.2.13676358'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$verificationProperties = ConvertFrom-StringData -StringData (
    Get-Content -LiteralPath (Join-Path $repositoryRoot 'verification.properties') -Raw
)
if ($verificationProperties.Count -ne 1 -or
    -not $verificationProperties.ContainsKey('JVM_TEST_COUNT') -or
    [string]$verificationProperties.JVM_TEST_COUNT -notmatch '^[1-9][0-9]{0,5}$') {
    throw 'verification.properties must contain exactly one positive JVM_TEST_COUNT'
}
$expectedTests = [int]$verificationProperties.JVM_TEST_COUNT
if (-not $SdkRoot) {
    $SdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
}
if (-not $SdkRoot) {
    throw 'Android SDK root is required through -SdkRoot, ANDROID_HOME, or ANDROID_SDK_ROOT'
}
$resolvedSdkRoot = (Resolve-Path -LiteralPath $SdkRoot).Path
$runningOnWindows = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$executableSuffix = if ($runningOnWindows) { '.exe' } else { '' }
$scriptSuffix = if ($runningOnWindows) { '.bat' } else { '' }
$buildToolsRoot = Join-Path $resolvedSdkRoot "build-tools/$BuildToolsVersion"
$zipalign = Join-Path $buildToolsRoot "zipalign$executableSuffix"
$apksigner = Join-Path $buildToolsRoot "apksigner$scriptSuffix"
$aapt = Join-Path $buildToolsRoot "aapt$executableSuffix"
$hostTag = if ($runningOnWindows) { 'windows-x86_64' } else { 'linux-x86_64' }
$readelf = Join-Path $resolvedSdkRoot (
    "ndk/$NdkVersion/toolchains/llvm/prebuilt/$hostTag/bin/llvm-readelf$executableSuffix"
)
foreach ($tool in @($zipalign, $apksigner, $aapt, $readelf)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "Required release artifact tool is missing: $tool"
    }
}
$keytoolCommand = Get-Command keytool -ErrorAction Stop
if (-not [IO.Path]::IsPathRooted($SigningPropertiesFile) -or
    -not [IO.Path]::IsPathRooted($SigningStoreFile)) {
    throw 'Signing properties and keystore paths must be absolute'
}
$resolvedSigningProperties = (Resolve-Path -LiteralPath $SigningPropertiesFile).Path
$resolvedSigningStore = (Resolve-Path -LiteralPath $SigningStoreFile).Path
if (-not (Test-Path -LiteralPath $resolvedSigningProperties -PathType Leaf) -or
    -not (Test-Path -LiteralPath $resolvedSigningStore -PathType Leaf)) {
    throw 'Signing properties and keystore must be regular files'
}
$signingValues = @{}
foreach ($rawSigningLine in Get-Content -LiteralPath $resolvedSigningProperties) {
    $signingLine = $rawSigningLine.Trim()
    if (-not $signingLine -or $signingLine.StartsWith('#')) { continue }
    $signingParts = $signingLine.Split('=', 2)
    if ($signingParts.Count -ne 2) { continue }
    $signingKey = $signingParts[0].Trim()
    if ($signingValues.ContainsKey($signingKey)) {
        throw "Duplicate external signing property: $signingKey"
    }
    $signingValues[$signingKey] = $signingParts[1].Trim()
}
$signingAlias = [string]$signingValues.keyAlias
$signingStorePassword = [string]$signingValues.storePassword
if (-not $signingAlias -or -not $signingStorePassword) {
    throw 'External signing properties do not contain a non-empty alias and store password'
}
$passwordEnvironmentName = 'AUTOJS_LUA_RELEASE_VERIFY_STORE_PASSWORD'
$previousPasswordEnvironmentValue = [Environment]::GetEnvironmentVariable(
    $passwordEnvironmentName,
    [EnvironmentVariableTarget]::Process
)
try {
    [Environment]::SetEnvironmentVariable(
        $passwordEnvironmentName,
        $signingStorePassword,
        [EnvironmentVariableTarget]::Process
    )
    $certificateOutput = @(
        & $keytoolCommand.Source -exportcert -rfc `
            -keystore $resolvedSigningStore `
            -alias $signingAlias `
            -storepass:env $passwordEnvironmentName 2>&1
    )
    if ($LASTEXITCODE -ne 0) { throw 'Unable to export the pinned release signing certificate' }
} finally {
    [Environment]::SetEnvironmentVariable(
        $passwordEnvironmentName,
        $previousPasswordEnvironmentValue,
        [EnvironmentVariableTarget]::Process
    )
    $signingStorePassword = $null
    $signingAlias = $null
    $signingValues.Clear()
}
$certificateBase64 = ($certificateOutput | Where-Object {
    $_ -match '^[A-Za-z0-9+/]+={0,2}$'
}) -join ''
if (-not $certificateBase64) { throw 'Pinned signing certificate export was malformed' }
$certificateBytes = [Convert]::FromBase64String($certificateBase64)
$sha256 = [Security.Cryptography.SHA256]::Create()
try {
    $expectedSigner = -join @(
        $sha256.ComputeHash($certificateBytes) | ForEach-Object { $_.ToString('x2') }
    )
} finally {
    $sha256.Dispose()
}

$status = @(& git -C $repositoryRoot status --porcelain --untracked-files=all)
if ($LASTEXITCODE -ne 0 -or $status.Count -ne 0) {
    throw "Release artifact verification requires a clean repository: $($status -join '; ')"
}
$revision = (& git -C $repositoryRoot rev-parse HEAD).Trim()
$commitCount = [int](& git -C $repositoryRoot rev-list --count HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $revision -notmatch '^[0-9a-f]{40}$') {
    throw 'Unable to resolve the release-candidate revision'
}
$versionProperties = @{}
foreach ($rawLine in Get-Content -LiteralPath (Join-Path $repositoryRoot 'version.properties')) {
    $line = $rawLine.Trim()
    if (-not $line -or $line.StartsWith('#')) { continue }
    $parts = $line.Split('=', 2)
    if ($parts.Count -ne 2 -or $versionProperties.ContainsKey($parts[0])) {
        throw "Malformed or duplicate version property: $line"
    }
    $versionProperties[$parts[0]] = $parts[1]
}
$versionCode = [int]$versionProperties.VERSION_BUILD
$versionName = [string]$versionProperties.VERSION_NAME
if ($versionCode -le 0 -or $versionCode -ne $commitCount) {
    throw "VERSION_BUILD must equal the positive commit count: version=$versionCode commits=$commitCount"
}
if ($versionName -notmatch '^\d+\.\d+\.\d+-rc\.\d+$') {
    throw "Release-candidate VERSION_NAME is invalid: $versionName"
}
$invocationStarted = [DateTimeOffset]::Parse(
    $InvocationStartedAtUtc,
    [Globalization.CultureInfo]::InvariantCulture,
    [Globalization.DateTimeStyles]::AssumeUniversal -bor
        [Globalization.DateTimeStyles]::AdjustToUniversal
)
if ($invocationStarted -gt [DateTimeOffset]::UtcNow.AddMinutes(1)) {
    throw 'Canonical release invocation start time is in the future'
}
function Assert-CurrentInvocationOutput([string] $path) {
    $item = Get-Item -LiteralPath $path
    if ($item.LastWriteTimeUtc -lt $invocationStarted.UtcDateTime.AddSeconds(-2)) {
        throw "Release artifact predates the canonical invocation: $($item.FullName)"
    }
    return $item
}

$testRoot = Join-Path $repositoryRoot 'app/build/test-results/testDebugUnitTest'
$testReports = @(Get-ChildItem -LiteralPath $testRoot -Filter '*.xml' -File)
if ($testReports.Count -eq 0) { throw 'No release-invocation unit-test XML reports were found' }
$tests = 0
$failures = 0
$errors = 0
$skipped = 0
foreach ($report in $testReports) {
    [void](Assert-CurrentInvocationOutput $report.FullName)
    [xml]$document = Get-Content -LiteralPath $report.FullName -Raw
    $tests += [int]$document.testsuite.tests
    $failures += [int]$document.testsuite.failures
    $errors += [int]$document.testsuite.errors
    $skipped += [int]$document.testsuite.skipped
}
if ($tests -ne $expectedTests -or $failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0) {
    throw "Unit-test gate failed: tests=$tests failures=$failures errors=$errors skipped=$skipped"
}

$apkRoot = Join-Path $repositoryRoot 'app/build/outputs/apk/release'
$metadataPath = Join-Path $apkRoot 'output-metadata.json'
[void](Assert-CurrentInvocationOutput $metadataPath)
$metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
if ($metadata.applicationId -ne 'io.github.supermonster003.autojs6.plugin.lua.runtime' -or
    $metadata.variantName -ne 'release') {
    throw 'Release APK metadata identity drift'
}
$expectedApks = [ordered]@{
    'app-arm64-v8a-release.apk' = @('arm64-v8a')
    'app-x86_64-release.apk' = @('x86_64')
    'app-universal-release.apk' = @('arm64-v8a', 'x86_64')
}
$elements = @($metadata.elements)
if ($elements.Count -ne $expectedApks.Count) {
    throw "Unexpected release APK count: $($elements.Count)"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) (
    'autojs-lua-release-audit-' + [Guid]::NewGuid().ToString('N')
)
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
$signerDigests = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$nativeDigests = @{}
$artifactRecords = [Collections.Generic.List[object]]::new()
try {
    foreach ($element in $elements) {
        if (-not $expectedApks.Contains($element.outputFile)) {
            throw "Unexpected release APK metadata output: $($element.outputFile)"
        }
        if ([int]$element.versionCode -ne $versionCode -or
            [string]$element.versionName -ne $versionName) {
            throw "APK version metadata drift: $($element.outputFile)"
        }
        $apkPath = [IO.Path]::GetFullPath((Join-Path $apkRoot $element.outputFile))
        $resolvedApkRoot = [IO.Path]::GetFullPath($apkRoot).TrimEnd(
            [IO.Path]::DirectorySeparatorChar,
            [IO.Path]::AltDirectorySeparatorChar
        )
        if (-not $apkPath.StartsWith(
            $resolvedApkRoot + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase
        )) {
            throw "APK metadata path escaped its output directory: $($element.outputFile)"
        }
        $apk = Assert-CurrentInvocationOutput $apkPath
        if ($apk.Length -le 0) { throw "Release APK is empty: $($element.outputFile)" }

        & $zipalign -c -P 16 4 $apk.FullName
        if ($LASTEXITCODE -ne 0) { throw "16 KiB ZIP alignment failed: $($element.outputFile)" }
        $signatureOutput = @(& $apksigner verify --verbose --print-certs $apk.FullName 2>&1)
        if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $($element.outputFile)" }
        if (@($signatureOutput | Select-String '^Number of signers: 1$').Count -ne 1) {
            throw "Release APK must have exactly one signer: $($element.outputFile)"
        }
        $certificateMatches = @(
            $signatureOutput |
                Select-String 'certificate SHA-256 digest: ([0-9a-fA-F]{64})' |
                ForEach-Object { $_.Matches[0].Groups[1].Value.ToLowerInvariant() }
        )
        if ($certificateMatches.Count -ne 1 -or $certificateMatches[0] -ne $expectedSigner) {
            throw "Release APK signer does not match the explicitly pinned signer: $($element.outputFile)"
        }
        [void]$signerDigests.Add($certificateMatches[0])

        $badging = @(& $aapt dump badging $apk.FullName)
        if ($LASTEXITCODE -ne 0) { throw "Unable to read APK badging: $($element.outputFile)" }
        $packageLine = @($badging | Where-Object { $_ -match '^package:' })
        if ($packageLine.Count -ne 1 -or
            $packageLine[0] -notmatch "name='io.github.supermonster003.autojs6.plugin.lua.runtime'" -or
            $packageLine[0] -notmatch "versionCode='$versionCode'" -or
            @($badging | Select-String '^application-debuggable').Count -ne 0) {
            throw "Packaged release identity/debuggability drift: $($element.outputFile)"
        }

        $expectedAbis = @($expectedApks[$element.outputFile])
        $archive = [IO.Compression.ZipFile]::OpenRead($apk.FullName)
        try {
            $allNativeEntries = @($archive.Entries | Where-Object {
                $_.FullName -match '^lib/[^/]+/[^/]+$'
            })
            $nativeEntries = @($archive.Entries | Where-Object {
                $_.FullName -match '^lib/([^/]+)/libautojs_lua_runtime\.so$'
            })
            if ($allNativeEntries.Count -ne $nativeEntries.Count) {
                throw "Unexpected packaged native library in $($element.outputFile): $($allNativeEntries.FullName -join ',')"
            }
            $actualAbis = @($nativeEntries | ForEach-Object {
                [regex]::Match($_.FullName, '^lib/([^/]+)/').Groups[1].Value
            } | Sort-Object)
            if (Compare-Object ($expectedAbis | Sort-Object) $actualAbis) {
                throw "Packaged native ABI drift in $($element.outputFile): $($actualAbis -join ',')"
            }
            foreach ($entry in $nativeEntries) {
                $abi = [regex]::Match($entry.FullName, '^lib/([^/]+)/').Groups[1].Value
                $destination = Join-Path $temporaryRoot "$($element.outputFile)-$abi.so"
                $input = $entry.Open()
                $output = [IO.File]::Create($destination)
                try { $input.CopyTo($output) } finally { $output.Dispose(); $input.Dispose() }
                $header = @(& $readelf -h $destination)
                $programHeaders = @(& $readelf -lW $destination)
                if ($LASTEXITCODE -ne 0) { throw "ELF inspection failed: $($entry.FullName)" }
                $expectedMachine = if ($abi -eq 'arm64-v8a') { 'AArch64' } else { 'X86-64' }
                if (@($header | Select-String "Machine:.*$expectedMachine").Count -ne 1) {
                    throw "ELF machine drift: $($entry.FullName)"
                }
                $loads = @($programHeaders | Select-String '^  LOAD')
                if ($loads.Count -eq 0) { throw "ELF has no LOAD segment: $($entry.FullName)" }
                foreach ($load in $loads) {
                    $alignment = (($load.Line.Trim() -split '\s+')[-1])
                    if ($alignment -notmatch '^0x[0-9a-fA-F]+$' -or
                        [Convert]::ToInt64($alignment.Substring(2), 16) -lt 16384) {
                        throw "ELF LOAD alignment is below 16 KiB: $($entry.FullName) $alignment"
                    }
                }
                $symbols = @(& $readelf --dyn-syms --wide $destination)
                if ($LASTEXITCODE -ne 0 -or
                    @($symbols | Select-String 'NativeLuaFaults_native(?:Crash|Wedge)').Count -ne 0) {
                    throw "Debug fault JNI entered release ELF: $($entry.FullName)"
                }
                $dynamic = @(& $readelf --dynamic $destination)
                if ($LASTEXITCODE -ne 0) { throw "ELF dynamic inspection failed: $($entry.FullName)" }
                $needed = @(
                    $dynamic |
                        Select-String '\(NEEDED\).*Shared library: \[([^]]+)\]' |
                        ForEach-Object { $_.Matches[0].Groups[1].Value } |
                        Sort-Object
                )
                $expectedNeeded = @('libc.so', 'libdl.so', 'liblog.so', 'libm.so')
                if (Compare-Object $expectedNeeded $needed) {
                    throw "ELF dependency drift: $($entry.FullName) needed=$($needed -join ',')"
                }
                $digest = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
                if ($nativeDigests.ContainsKey($abi) -and $nativeDigests[$abi] -ne $digest) {
                    throw "Split/universal native payload mismatch for $abi"
                }
                $nativeDigests[$abi] = $digest
            }
        } finally {
            $archive.Dispose()
        }
        $artifactRecords.Add([pscustomobject]@{
            file = $element.outputFile
            bytes = $apk.Length
            sha256 = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            abis = $expectedAbis
        })
    }
} finally {
    if (Test-Path -LiteralPath $temporaryRoot -PathType Container) {
        [IO.Directory]::Delete($temporaryRoot, $true)
    }
}
if ($signerDigests.Count -ne 1 -or @($signerDigests)[0] -ne $expectedSigner) {
    throw 'Release APK signer drift across outputs'
}

$universalApk = Join-Path $apkRoot 'app-universal-release.apk'
$resources = @(& $aapt dump resources $universalApk)
if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect packaged release resource table' }
$resourceText = $resources -join "`n"
if ($resourceText -notmatch 'lua_runtime_provider_enabled[\s\S]*?t=0x12 d=0xffffffff') {
    throw 'Packaged release Provider discovery is not enabled'
}
$faultHarnessResourcePresent = $resourceText.Contains('lua_runtime_fault_harness_enabled')
if (
    $faultHarnessResourcePresent -and
    $resourceText -notmatch 'lua_runtime_fault_harness_enabled[\s\S]*?t=0x12 d=0x00000000'
) {
    throw 'Packaged release fault harness resource is not false'
}
$manifest = @(& $aapt dump xmltree $universalApk AndroidManifest.xml)
if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect packaged release manifest' }
foreach ($service in @('LuaPluginInfoService', 'LuaRuntimeService')) {
    if (@($manifest | Select-String $service).Count -ne 1) {
        throw "Packaged production service inventory drift: $service"
    }
}
if (@($manifest | Select-String 'LuaRuntimeFaultService').Count -ne 0) {
    throw 'Debug fault service entered the release manifest'
}

$buildConfigPath = Join-Path $repositoryRoot (
    'app/build/generated/source/buildConfig/release/' +
    'io/github/supermonster003/autojs6/plugin/lua/runtime/BuildConfig.java'
)
[void](Assert-CurrentInvocationOutput $buildConfigPath)
$buildConfig = Get-Content -LiteralPath $buildConfigPath -Raw
foreach ($token in @(
    "VERSION_CODE = $versionCode;",
    'LUA_NATIVE_ENABLED = true;',
    'LUA_PROVIDER_ENABLED = true;',
    'LUA_FAULT_HARNESS_ENABLED = false;'
)) {
    if (-not $buildConfig.Contains($token)) { throw "Generated release BuildConfig drift: $token" }
}

$summary = [ordered]@{
    evidence = 'signed-packaging-only'
    deviceVerified = $false
    runtimeVerified = $false
    revision = $revision
    versionCode = $versionCode
    versionName = $versionName
    tests = $tests
    signerSha256 = $expectedSigner
    nativeSha256 = $nativeDigests
    artifacts = $artifactRecords
}
$summary | ConvertTo-Json -Depth 6
Write-Host 'SIGNED_RELEASE_CANDIDATE_ARTIFACT_GATE_PASS provider=true faultHarness=false elfPageAlign=16384 zipPageAlign=16384 deviceVerified=false runtimeVerified=false'
