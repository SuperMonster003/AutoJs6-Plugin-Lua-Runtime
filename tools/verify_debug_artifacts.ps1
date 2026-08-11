[CmdletBinding()]
param(
    [string] $SdkRoot,
    [string] $BuildToolsVersion = '37.0.0',
    [string] $NdkVersion = '28.2.13676358',
    [int] $ExpectedTests = 39,
    [switch] $RequireFaultHarness
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
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
        throw "Required artifact tool is missing: $tool"
    }
}

$status = @(& git -C $repositoryRoot status --porcelain --untracked-files=all)
if ($LASTEXITCODE -ne 0 -or $status.Count -ne 0) {
    throw "Artifact verification requires a clean repository: $($status -join '; ')"
}
$revision = (& git -C $repositoryRoot rev-parse HEAD).Trim()
$commitCount = [int](& git -C $repositoryRoot rev-list --count HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $revision -notmatch '^[0-9a-f]{40}$') {
    throw 'Unable to resolve the sibling repository revision'
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
if ($versionCode -le 0 -or $versionCode -ne $commitCount) {
    throw "VERSION_BUILD must equal the positive commit count: version=$versionCode commits=$commitCount"
}

$testRoot = Join-Path $repositoryRoot 'app/build/test-results/testDebugUnitTest'
$testReports = @(Get-ChildItem -LiteralPath $testRoot -Filter '*.xml' -File)
if ($testReports.Count -eq 0) { throw 'No debug unit-test XML reports were found' }
$tests = 0
$failures = 0
$errors = 0
$skipped = 0
foreach ($report in $testReports) {
    [xml]$document = Get-Content -LiteralPath $report.FullName -Raw
    $tests += [int]$document.testsuite.tests
    $failures += [int]$document.testsuite.failures
    $errors += [int]$document.testsuite.errors
    $skipped += [int]$document.testsuite.skipped
}
if (
    $tests -ne $ExpectedTests -or
    $failures -ne 0 -or
    $errors -ne 0 -or
    $skipped -ne 0
) {
    throw "Unit-test gate failed: tests=$tests failures=$failures errors=$errors skipped=$skipped"
}

$apkRoot = Join-Path $repositoryRoot 'app/build/outputs/apk/debug'
$metadataPath = Join-Path $apkRoot 'output-metadata.json'
$metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
if (
    $metadata.applicationId -ne 'io.github.supermonster003.autojs6.plugin.lua.runtime' -or
    $metadata.variantName -ne 'debug'
) {
    throw 'Debug APK metadata identity drift'
}
$expectedApks = [ordered]@{
    'app-arm64-v8a-debug.apk' = @('arm64-v8a')
    'app-x86_64-debug.apk' = @('x86_64')
    'app-universal-debug.apk' = @('arm64-v8a', 'x86_64')
}
$elements = @($metadata.elements)
if ($elements.Count -ne $expectedApks.Count) {
    throw "Unexpected debug APK count: $($elements.Count)"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) (
    'autojs-lua-apk-audit-' + [Guid]::NewGuid().ToString('N')
)
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
$signerDigests = [Collections.Generic.HashSet[string]]::new(
    [StringComparer]::OrdinalIgnoreCase
)
$nativeDigests = @{}
$faultSymbols = @(
    'Java_io_github_supermonster003_autojs6_plugin_lua_runtime_debug_NativeLuaFaults_nativeCrash',
    'Java_io_github_supermonster003_autojs6_plugin_lua_runtime_debug_NativeLuaFaults_nativeWedge'
)
$expectedFaultSymbolCount = if ($RequireFaultHarness) { 1 } else { 0 }
$artifactRecords = [Collections.Generic.List[object]]::new()
try {
    foreach ($element in $elements) {
        if (-not $expectedApks.Contains($element.outputFile)) {
            throw "Unexpected debug APK metadata output: $($element.outputFile)"
        }
        if (
            [int]$element.versionCode -ne $versionCode -or
            [string]$element.versionName -ne $versionProperties.VERSION_NAME
        ) {
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
        $apk = Get-Item -LiteralPath $apkPath
        if ($apk.Length -le 0) { throw "Debug APK is empty: $($element.outputFile)" }

        & $zipalign -c -P 16 4 $apk.FullName
        if ($LASTEXITCODE -ne 0) { throw "16 KiB ZIP alignment failed: $($element.outputFile)" }
        $signatureOutput = @(& $apksigner verify --verbose --print-certs $apk.FullName 2>&1)
        if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $($element.outputFile)" }
        if (@($signatureOutput | Select-String '^Number of signers: 1$').Count -ne 1) {
            throw "Debug APK must have exactly one signer: $($element.outputFile)"
        }
        $certificateMatches = @(
            $signatureOutput |
                Select-String 'certificate SHA-256 digest: ([0-9a-fA-F]{64})' |
                ForEach-Object { $_.Matches[0].Groups[1].Value.ToLowerInvariant() }
        )
        if ($certificateMatches.Count -ne 1) {
            throw "Unable to pin one APK signer digest: $($element.outputFile)"
        }
        [void]$signerDigests.Add($certificateMatches[0])

        $badging = @(& $aapt dump badging $apk.FullName)
        if ($LASTEXITCODE -ne 0) { throw "Unable to read APK badging: $($element.outputFile)" }
        $packageLine = @($badging | Where-Object { $_ -match '^package:' })
        if (
            $packageLine.Count -ne 1 -or
            $packageLine[0] -notmatch "name='io.github.supermonster003.autojs6.plugin.lua.runtime'" -or
            $packageLine[0] -notmatch "versionCode='$versionCode'"
        ) {
            throw "Packaged application identity drift: $($element.outputFile)"
        }

        $expectedAbis = @($expectedApks[$element.outputFile])
        $archive = [IO.Compression.ZipFile]::OpenRead($apk.FullName)
        try {
            $nativeEntries = @(
                $archive.Entries |
                    Where-Object { $_.FullName -match '^lib/([^/]+)/libautojs_lua_runtime\.so$' }
            )
            $actualAbis = @($nativeEntries | ForEach-Object {
                [regex]::Match($_.FullName, '^lib/([^/]+)/').Groups[1].Value
            } | Sort-Object)
            if (Compare-Object ($expectedAbis | Sort-Object) $actualAbis) {
                throw "Packaged native ABI drift in $($element.outputFile): $($actualAbis -join ',')"
            }
            foreach ($entry in $nativeEntries) {
                $abi = [regex]::Match($entry.FullName, '^lib/([^/]+)/').Groups[1].Value
                $destination = Join-Path $temporaryRoot (
                    "$($element.outputFile)-$abi-libautojs_lua_runtime.so"
                )
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
                    if (
                        $alignment -notmatch '^0x[0-9a-fA-F]+$' -or
                        [Convert]::ToInt64($alignment.Substring(2), 16) -lt 16384
                    ) {
                        throw "ELF LOAD alignment is below 16 KiB: $($entry.FullName) $alignment"
                    }
                }
                $symbols = @(& $readelf --dyn-syms --wide $destination)
                if ($LASTEXITCODE -ne 0) { throw "ELF symbol inspection failed: $($entry.FullName)" }
                foreach ($symbol in $faultSymbols) {
                    $count = @($symbols | Select-String ([regex]::Escape($symbol))).Count
                    if ($count -ne $expectedFaultSymbolCount) {
                        throw (
                            "Debug fault JNI symbol drift in $($entry.FullName): " +
                            "$symbol count=$count expected=$expectedFaultSymbolCount"
                        )
                    }
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
if ($signerDigests.Count -ne 1) { throw 'Debug APK signer drift across outputs' }

$universalApk = Join-Path $apkRoot 'app-universal-debug.apk'
$resources = @(& $aapt dump resources $universalApk)
if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect packaged resource table' }
$resourceText = $resources -join "`n"
if (
    $resourceText -notmatch
        'lua_runtime_provider_enabled[\s\S]*?t=0x12 d=0x00000000'
) {
    throw 'Packaged provider-discovery resource is not false'
}
$expectedFaultResource = if ($RequireFaultHarness) { '0xffffffff' } else { '0x00000000' }
if (
    $resourceText -notmatch
        "lua_runtime_fault_harness_enabled[\s\S]*?t=0x12 d=$expectedFaultResource"
) {
    throw "Packaged fault-harness resource does not match RequireFaultHarness=$RequireFaultHarness"
}
$manifest = @(& $aapt dump xmltree $universalApk AndroidManifest.xml)
if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect packaged Android manifest' }

function Get-PackagedServiceBlock([string[]] $lines, [string] $serviceName) {
    $nameMatches = @(
        for ($index = 0; $index -lt $lines.Count; $index++) {
            if ($lines[$index].Contains($serviceName)) { $index }
        }
    )
    if ($nameMatches.Count -ne 1) {
        throw "Packaged service inventory drift: $serviceName"
    }
    $nameIndex = $nameMatches[0]
    $start = $nameIndex
    while ($start -ge 0 -and $lines[$start] -notmatch '^(\s*)E: service\b') { $start-- }
    if ($start -lt 0) { throw "Unable to locate service node: $serviceName" }
    $indent = ([regex]::Match($lines[$start], '^(\s*)')).Groups[1].Value.Length
    $end = $lines.Count
    for ($index = $start + 1; $index -lt $lines.Count; $index++) {
        $match = [regex]::Match($lines[$index], '^(\s*)E: ')
        if ($match.Success -and $match.Groups[1].Value.Length -le $indent) {
            $end = $index
            break
        }
    }
    return @($lines[$start..($end - 1)])
}

foreach ($service in @('LuaPluginInfoService', 'LuaRuntimeService')) {
    $block = Get-PackagedServiceBlock $manifest $service
    if (
        @($block | Select-String 'android:enabled.*=@0x').Count -ne 1 -or
        @($block | Select-String 'android:exported.*0xffffffff').Count -ne 1 -or
        @($block | Select-String 'android:process.*:lua_runtime').Count -ne 1
    ) {
        throw "Packaged production service boundary drift: $service"
    }
}
$faultBlock = Get-PackagedServiceBlock $manifest 'LuaRuntimeFaultService'
if (
    @($faultBlock | Select-String 'android:enabled.*=@0x').Count -ne 1 -or
    @($faultBlock | Select-String 'android:exported.*0x0').Count -ne 1 -or
    @($faultBlock | Select-String 'android:process.*:lua_runtime').Count -ne 1 -or
    @($faultBlock | Select-String 'E: intent-filter').Count -ne 0
) {
    throw 'Packaged debug fault service is not explicit, non-exported, and process-isolated'
}

$buildConfigPath = Join-Path $repositoryRoot (
    'app/build/generated/source/buildConfig/debug/' +
    'io/github/supermonster003/autojs6/plugin/lua/runtime/BuildConfig.java'
)
if (-not (Test-Path -LiteralPath $buildConfigPath -PathType Leaf)) {
    throw "Generated main debug BuildConfig is missing: $buildConfigPath"
}
$buildConfig = Get-Content -LiteralPath $buildConfigPath -Raw
foreach ($token in @(
    "VERSION_CODE = $versionCode;",
    'LUA_NATIVE_ENABLED = true;',
    'LUA_PROVIDER_ENABLED = false;',
    "LUA_FAULT_HARNESS_ENABLED = $($RequireFaultHarness.ToString().ToLowerInvariant());"
)) {
    if (-not $buildConfig.Contains($token)) { throw "Generated BuildConfig drift: $token" }
}

$summary = [ordered]@{
    revision = $revision
    versionCode = $versionCode
    tests = $tests
    signerSha256 = @($signerDigests)[0]
    nativeSha256 = $nativeDigests
    artifacts = $artifactRecords
}
$summary | ConvertTo-Json -Depth 6
Write-Host "DEBUG_ARTIFACT_GATE_PASS provider=false faultHarness=$($RequireFaultHarness.ToString().ToLowerInvariant()) elfPageAlign=16384 zipPageAlign=16384"
