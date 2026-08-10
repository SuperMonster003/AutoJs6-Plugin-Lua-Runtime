[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$')]
    [string] $InvocationStartedAtUtc,
    [string] $SdkRoot,
    [string] $BuildToolsVersion = '37.0.0',
    [string] $NdkVersion = '28.2.13676358'
)

$ErrorActionPreference = 'Stop'

# Release signing and a release APK are intentionally out of scope here. The negative gate binds
# the unsigned release variant's generated BuildConfig, merged manifest, compiled classes, and both
# ABI-native outputs to one clean canonical invocation; the debug APK supplies the positive side.

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$status = @(& git -C $repositoryRoot status --porcelain --untracked-files=all)
if ($LASTEXITCODE -ne 0 -or $status.Count -ne 0) {
    throw "Fault artifact verification requires a clean repository: $($status -join '; ')"
}
$revision = (& git -C $repositoryRoot rev-parse HEAD).Trim()
$commitCount = [int](& git -C $repositoryRoot rev-list --count HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $revision -notmatch '^[0-9a-f]{40}$') {
    throw 'Unable to resolve the canonical sibling revision'
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
    throw "VERSION_BUILD must equal the canonical commit count: version=$versionCode commits=$commitCount"
}
$invocationStarted = [DateTimeOffset]::Parse(
    $InvocationStartedAtUtc,
    [Globalization.CultureInfo]::InvariantCulture,
    [Globalization.DateTimeStyles]::AssumeUniversal -bor
        [Globalization.DateTimeStyles]::AdjustToUniversal
)
if ($invocationStarted -gt [DateTimeOffset]::UtcNow.AddMinutes(1)) {
    throw 'Canonical invocation start time is in the future'
}

function Assert-CurrentInvocationOutput([string] $path) {
    $item = Get-Item -LiteralPath $path
    if ($item.LastWriteTimeUtc -lt $invocationStarted.UtcDateTime.AddSeconds(-2)) {
        throw "Artifact predates the canonical invocation: $($item.FullName)"
    }
}
if (-not $SdkRoot) {
    $SdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
}
if (-not $SdkRoot) {
    throw 'Android SDK root is required through -SdkRoot, ANDROID_HOME, or ANDROID_SDK_ROOT'
}
$resolvedSdkRoot = (Resolve-Path -LiteralPath $SdkRoot).Path
$runningOnWindows = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$executableSuffix = if ($runningOnWindows) { '.exe' } else { '' }
$hostTag = if ($runningOnWindows) { 'windows-x86_64' } else { 'linux-x86_64' }
$aapt = Join-Path $resolvedSdkRoot "build-tools/$BuildToolsVersion/aapt$executableSuffix"
$readelf = Join-Path $resolvedSdkRoot (
    "ndk/$NdkVersion/toolchains/llvm/prebuilt/$hostTag/bin/llvm-readelf$executableSuffix"
)
foreach ($tool in @($aapt, $readelf)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "Required artifact inspector is missing: $tool"
    }
}

function Read-UniqueBuildConfig([string] $variant) {
    $path = Join-Path $repositoryRoot (
        "app/build/generated/source/buildConfig/$variant/" +
        'io/github/supermonster003/autojs6/plugin/lua/runtime/BuildConfig.java'
    )
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Generated $variant BuildConfig is missing: $path"
    }
    Assert-CurrentInvocationOutput $path
    return Get-Content -LiteralPath $path -Raw
}

$debugBuildConfig = Read-UniqueBuildConfig 'debug'
$releaseBuildConfig = Read-UniqueBuildConfig 'release'
if (-not $debugBuildConfig.Contains('LUA_FAULT_HARNESS_ENABLED = true;')) {
    throw 'Debug BuildConfig did not opt into the fault harness'
}
foreach ($token in @(
    'LUA_FAULT_HARNESS_ENABLED = false;',
    'LUA_PROVIDER_ENABLED = false;'
)) {
    if (-not $releaseBuildConfig.Contains($token)) {
        throw "Release BuildConfig drift: $token"
    }
}
foreach ($buildConfig in @($debugBuildConfig, $releaseBuildConfig)) {
    if (-not $buildConfig.Contains("VERSION_CODE = $versionCode;")) {
        throw "Generated BuildConfig version is not bound to revision $revision"
    }
}

$releaseManifest = Join-Path $repositoryRoot (
    'app/build/intermediates/merged_manifest/release/' +
    'processReleaseMainManifest/AndroidManifest.xml'
)
if (-not (Test-Path -LiteralPath $releaseManifest -PathType Leaf)) {
    throw "Release main merged manifest is missing: $releaseManifest"
}
Assert-CurrentInvocationOutput $releaseManifest
$releaseManifestText = Get-Content -LiteralPath $releaseManifest -Raw
if (
    $releaseManifestText.Contains('LuaRuntimeFaultService') -or
    $releaseManifestText.Contains('lua_runtime_fault_harness_enabled')
) {
    throw "Fault harness entered the release main merged manifest: $releaseManifest"
}

$debugManifest = Join-Path $repositoryRoot (
    'app/build/intermediates/merged_manifest/debug/' +
    'processDebugMainManifest/AndroidManifest.xml'
)
if (-not (Test-Path -LiteralPath $debugManifest -PathType Leaf)) {
    throw "Debug main merged manifest is missing: $debugManifest"
}
$androidNamespace = 'http://schemas.android.com/apk/res/android'
Assert-CurrentInvocationOutput $debugManifest
[xml]$debugManifestDocument = Get-Content -LiteralPath $debugManifest -Raw
$faultServices = @($debugManifestDocument.manifest.application.service | Where-Object {
    $_.GetAttribute('name', $androidNamespace) -in @(
        '.debug.LuaRuntimeFaultService',
        'io.github.supermonster003.autojs6.plugin.lua.runtime.debug.LuaRuntimeFaultService'
    )
})
if ($faultServices.Count -ne 1) {
    throw "Debug merged fault service inventory drift: $debugManifest"
}
$faultService = $faultServices[0]
$faultIntentFilters = @($faultService.SelectNodes('./intent-filter'))
if (
    $faultService.GetAttribute('enabled', $androidNamespace) -ne '@bool/lua_runtime_fault_harness_enabled' -or
    $faultService.GetAttribute('exported', $androidNamespace) -ne 'false' -or
    $faultService.GetAttribute('process', $androidNamespace) -ne ':lua_runtime' -or
    $faultIntentFilters.Count -ne 0
) {
    throw "Debug merged fault service isolation drift: $debugManifest"
}

$releaseCompiledClasses = @(
    Get-ChildItem -LiteralPath (Join-Path $repositoryRoot 'app/build/intermediates') -Filter '*.class' -File -Recurse |
        Where-Object { $_.FullName -match '[\\/]release[\\/]' }
)
if ($releaseCompiledClasses.Count -eq 0) {
    throw 'No compiled release classes were found for the exclusion gate'
}
$releaseCompiledClasses | ForEach-Object { Assert-CurrentInvocationOutput $_.FullName }
$knownReleaseClasses = @($releaseCompiledClasses | Where-Object {
    $_.Name -ceq 'LuaRuntimeService.class'
})
if ($knownReleaseClasses.Count -ne 1) {
    throw "Fresh compiled release LuaRuntimeService class inventory drift: $($knownReleaseClasses.Count)"
}
$releaseFaultClasses = @($releaseCompiledClasses | Where-Object {
    $_.Name -match 'LuaRuntimeFault|NativeLuaFault'
})
if ($releaseFaultClasses.Count -ne 0) {
    throw "Fault harness class entered release intermediates: $($releaseFaultClasses.FullName -join ', ')"
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$releaseClassJars = @(
    Get-ChildItem -LiteralPath (Join-Path $repositoryRoot 'app/build/intermediates') -Filter '*.jar' -File -Recurse |
        Where-Object { $_.FullName -match '[\\/]release[\\/]' }
)
foreach ($jar in $releaseClassJars) {
    Assert-CurrentInvocationOutput $jar.FullName
    $archive = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
    try {
        $faultEntries = @($archive.Entries | Where-Object {
            $_.FullName -match 'LuaRuntimeFault|NativeLuaFault'
        })
        if ($faultEntries.Count -ne 0) {
            throw "Fault harness class entered a release JAR: $($jar.FullName)"
        }
    } finally {
        $archive.Dispose()
    }
}

$faultSymbols = @(
    'Java_io_github_supermonster003_autojs6_plugin_lua_runtime_debug_NativeLuaFaults_nativeCrash',
    'Java_io_github_supermonster003_autojs6_plugin_lua_runtime_debug_NativeLuaFaults_nativeWedge'
)
$expectedAbis = @('arm64-v8a', 'x86_64')
$nativeIntermediates = Join-Path $repositoryRoot 'app/build/intermediates/cxx'
$nativeVariants = @(
    @{ Label = 'Debug'; CmakeDirectory = 'Debug'; ExpectedFaultSymbolCount = 1 },
    @{ Label = 'Release'; CmakeDirectory = 'RelWithDebInfo'; ExpectedFaultSymbolCount = 0 }
)
foreach ($variant in $nativeVariants) {
    $variantRoot = Join-Path $nativeIntermediates $variant.CmakeDirectory
    $libraries = @(
        Get-ChildItem -LiteralPath $variantRoot -Filter libautojs_lua_runtime.so -File -Recurse
    )
    foreach ($abi in $expectedAbis) {
        $abiLibraries = @($libraries | Where-Object {
            $_.FullName -match "[\\/]$([regex]::Escape($abi))[\\/]"
        })
        if ($abiLibraries.Count -eq 0) {
            throw "$($variant.Label) native output is missing for $abi"
        }
        foreach ($library in $abiLibraries) {
            Assert-CurrentInvocationOutput $library.FullName
            $symbols = @(& $readelf --dyn-syms --wide $library.FullName 2>&1)
            if ($LASTEXITCODE -ne 0) {
                throw "Unable to inspect native symbols: $($library.FullName)"
            }
            foreach ($symbol in $faultSymbols) {
                $count = @($symbols | Select-String ([regex]::Escape($symbol))).Count
                if ($count -ne $variant.ExpectedFaultSymbolCount) {
                    throw "$($variant.Label) fault JNI symbol inventory drift for ${abi}: " +
                        "$symbol count=$count expected=$($variant.ExpectedFaultSymbolCount)"
                }
            }
        }
    }
}


function Resolve-DebugUniversalApk() {
    $root = Join-Path $repositoryRoot 'app/build/outputs/apk/debug'
    $metadataPath = Join-Path $root 'output-metadata.json'
    Assert-CurrentInvocationOutput $metadataPath
    $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
    $matches = @($metadata.elements | Where-Object {
        $_.outputFile -ceq 'app-universal-debug.apk'
    })
    if ($matches.Count -ne 1) {
        throw 'Unable to resolve one universal debug APK from output metadata'
    }
    $path = (Get-Item -LiteralPath (Join-Path $root $matches[0].outputFile)).FullName
    Assert-CurrentInvocationOutput $path
    return $path
}

$debugApk = Resolve-DebugUniversalApk
$debugResources = @(& $aapt dump resources $debugApk)
if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect debug APK resources' }
$debugResourceText = $debugResources -join "`n"
if (
    $debugResourceText -notmatch 'lua_runtime_provider_enabled[\s\S]*?t=0x12 d=0x00000000' -or
    $debugResourceText -notmatch 'lua_runtime_fault_harness_enabled[\s\S]*?t=0x12 d=0xffffffff'
) {
    throw 'Debug APK did not package provider=false and faultHarness=true'
}
$debugManifest = @(& $aapt dump xmltree $debugApk AndroidManifest.xml)
if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect the packaged debug manifest' }
if (@($debugManifest | Select-String 'LuaRuntimeFaultService').Count -ne 1) {
    throw 'Debug APK does not contain exactly one fault service'
}

$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) (
    'autojs-lua-fault-artifact-' + [Guid]::NewGuid().ToString('N')
)
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
try {
    $archive = [IO.Compression.ZipFile]::OpenRead($debugApk)
    try {
        foreach ($abi in $expectedAbis) {
            $entryName = "lib/$abi/libautojs_lua_runtime.so"
            $entries = @($archive.Entries | Where-Object { $_.FullName -ceq $entryName })
            if ($entries.Count -ne 1) {
                throw "Debug APK native inventory drift: $entryName"
            }
            $destination = Join-Path $temporaryRoot "Debug-$abi.so"
            $input = $entries[0].Open()
            $output = [IO.File]::Create($destination)
            try { $input.CopyTo($output) } finally { $output.Dispose(); $input.Dispose() }
            $symbols = @(& $readelf --dyn-syms --wide $destination 2>&1)
            if ($LASTEXITCODE -ne 0) { throw "Unable to inspect packaged native symbols: $entryName" }
            foreach ($symbol in $faultSymbols) {
                $count = @($symbols | Select-String ([regex]::Escape($symbol))).Count
                if ($count -ne 1) {
                    throw "Debug APK fault JNI symbol drift for ${abi}: $symbol count=$count"
                }
            }
        }
    } finally {
        $archive.Dispose()
    }
} finally {
    if (Test-Path -LiteralPath $temporaryRoot -PathType Container) {
        [IO.Directory]::Delete($temporaryRoot, $true)
    }
}

Write-Host (
    "RELEASE_VARIANT_FAULT_HARNESS_EXCLUSION_PASS revision=$revision versionCode=$versionCode " +
    'manifest=absent classes=absent arm64-v8a=absent x86_64=absent'
)
