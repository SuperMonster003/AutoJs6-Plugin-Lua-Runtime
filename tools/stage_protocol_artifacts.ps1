[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $AutoJsCheckout,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F]{40}$')]
    [string] $ExpectedRevision
)

$ErrorActionPreference = 'Stop'

$checkout = (Resolve-Path -LiteralPath $AutoJsCheckout).Path
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$protocolRoot = Join-Path $repositoryRoot 'protocol'

$actualRevision = (& git -C $checkout rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $actualRevision -ne $ExpectedRevision.ToLowerInvariant()) {
    throw "Host revision mismatch: expected $ExpectedRevision, got $actualRevision"
}

$dirty = @(& git -C $checkout status --porcelain --untracked-files=all)
if ($LASTEXITCODE -ne 0 -or $dirty.Count -ne 0) {
    throw 'The complete host checkout must be clean before protocol artifact intake'
}

$gradlew = Join-Path $checkout 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradlew -PathType Leaf)) {
    throw "Host Gradle wrapper is missing: $gradlew"
}
$gradleArgs = @(
    "--project-dir=$checkout"
    ':plugin-api:common-plugin-api:assembleDebug'
    ':plugin-api:protocol-wire-api:assembleDebug'
    ':plugin-api:lua-runtime-api:assembleDebug'
    '-Pautojs.gradle.build.number.auto.increment.enabled=false'
    '-Pautojs.gradle.build.time.update.enabled=false'
    '--rerun-tasks'
    '--no-build-cache'
    '--no-daemon'
    '--console=plain'
)
& $gradlew @gradleArgs
if ($LASTEXITCODE -ne 0) {
    throw "Protocol AAR build failed with exit code $LASTEXITCODE"
}

$revisionAfterBuild = (& git -C $checkout rev-parse HEAD).Trim()
$dirtyAfterBuild = @(& git -C $checkout status --porcelain --untracked-files=all)
if (
    $LASTEXITCODE -ne 0 -or
    $revisionAfterBuild -ne $actualRevision -or
    $dirtyAfterBuild.Count -ne 0
) {
    throw 'Host source revision or worktree changed during the protocol artifact build'
}

$artifacts = @(
    @{
        file = 'common-plugin-api.aar'
        sourceModule = ':plugin-api:common-plugin-api'
        source = 'plugin-api/common-plugin-api/build/outputs/aar/common-plugin-api-debug.aar'
    }
    @{
        file = 'protocol-wire-api.aar'
        sourceModule = ':plugin-api:protocol-wire-api'
        source = 'plugin-api/protocol-wire-api/build/outputs/aar/protocol-wire-api-debug.aar'
    }
    @{
        file = 'lua-runtime-api.aar'
        sourceModule = ':plugin-api:lua-runtime-api'
        source = 'plugin-api/lua-runtime-api/build/outputs/aar/lua-runtime-api-debug.aar'
    }
)

$destinations = $artifacts | ForEach-Object { Join-Path $protocolRoot $_.file }
$existing = @($destinations | Where-Object { Test-Path -LiteralPath $_ })
if ($existing.Count -ne 0) {
    throw "Refusing to overwrite existing protocol artifacts: $($existing -join ', ')"
}

$temporaryRoot = Join-Path $protocolRoot ('.intake-' + [IO.Path]::GetRandomFileName())
$resolvedProtocolRoot = [IO.Path]::GetFullPath($protocolRoot).TrimEnd(
    [IO.Path]::DirectorySeparatorChar,
    [IO.Path]::AltDirectorySeparatorChar
)
$resolvedTemporaryRoot = [IO.Path]::GetFullPath($temporaryRoot)
if (-not $resolvedTemporaryRoot.StartsWith(
    $resolvedProtocolRoot + [IO.Path]::DirectorySeparatorChar,
    [StringComparison]::OrdinalIgnoreCase
)) {
    throw 'Temporary intake directory escaped the protocol directory'
}

$published = [Collections.Generic.List[string]]::new()
try {
    New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
    $lockedArtifacts = foreach ($artifact in $artifacts) {
        $sourcePath = Join-Path $checkout $artifact.source
        if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
            throw "Missing freshly built protocol artifact: $sourcePath"
        }
        $temporaryArtifact = Join-Path $temporaryRoot $artifact.file
        Copy-Item -LiteralPath $sourcePath -Destination $temporaryArtifact
        @{
            file = $artifact.file
            sourceModule = $artifact.sourceModule
            sha256 = (Get-FileHash -LiteralPath $temporaryArtifact -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }

    $lock = [ordered]@{
        schemaVersion = 1
        status = 'staged'
        sourceRepository = 'AutoJs6'
        sourceRevision = $actualRevision
        artifacts = $lockedArtifacts
    }
    $temporaryLock = Join-Path $temporaryRoot 'protocol-artifacts.lock.json'
    $lock | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $temporaryLock -Encoding utf8NoBOM

    foreach ($artifact in $artifacts) {
        $source = Join-Path $temporaryRoot $artifact.file
        $destination = Join-Path $protocolRoot $artifact.file
        Move-Item -LiteralPath $source -Destination $destination
        $published.Add($destination)
    }
    Move-Item -LiteralPath $temporaryLock -Destination (
        Join-Path $protocolRoot 'protocol-artifacts.lock.json'
    ) -Force
    Write-Host "Staged three freshly built protocol artifacts from $actualRevision"
} catch {
    foreach ($path in $published) {
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            Remove-Item -LiteralPath $path -Force
        }
    }
    throw
} finally {
    if (Test-Path -LiteralPath $temporaryRoot -PathType Container) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
