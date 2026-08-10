[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Archive
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$vendorRoot = Join-Path $repositoryRoot 'app/src/main/cpp/vendor'
$lockPath = Join-Path $vendorRoot 'vendor-lock.json'
$destinationSource = Join-Path $vendorRoot 'lua-5.4.8/src'
$resolvedArchive = (Resolve-Path -LiteralPath $Archive).Path
$expectedArchiveSha256 = '4f18ddae154e793e46eeab727c59ef1c0c0c2b744e7b94219710d76f530629ae'

$originalLockBytes = [IO.File]::ReadAllBytes($lockPath)
$lock = [System.Text.Encoding]::UTF8.GetString($originalLockBytes) | ConvertFrom-Json
if ($lock.status -ne 'not-vendored') {
    throw "Lua vendor lock is not ready for intake: $($lock.status)"
}
if (Test-Path -LiteralPath $destinationSource) {
    throw "Refusing to overwrite an existing Lua source tree: $destinationSource"
}

$archiveSha256 = (Get-FileHash -LiteralPath $resolvedArchive -Algorithm SHA256).Hash.ToLowerInvariant()
if ($archiveSha256 -ne $expectedArchiveSha256 -or $archiveSha256 -ne $lock.sha256) {
    throw "PUC Lua archive digest mismatch: expected $expectedArchiveSha256, got $archiveSha256"
}

$temporaryRoot = Join-Path $vendorRoot ('.lua-intake-' + [IO.Path]::GetRandomFileName())
$resolvedVendorRoot = [IO.Path]::GetFullPath($vendorRoot).TrimEnd(
    [IO.Path]::DirectorySeparatorChar,
    [IO.Path]::AltDirectorySeparatorChar
)
$resolvedTemporaryRoot = [IO.Path]::GetFullPath($temporaryRoot)
if (-not $resolvedTemporaryRoot.StartsWith(
    $resolvedVendorRoot + [IO.Path]::DirectorySeparatorChar,
    [StringComparison]::OrdinalIgnoreCase
)) {
    throw 'Temporary Lua intake directory escaped the vendor directory'
}

$sourcePublished = $false
$lockPublished = $false
$temporaryLock = Join-Path $vendorRoot ('.vendor-lock-' + [IO.Path]::GetRandomFileName() + '.json')
try {
    New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
    & tar -xzf $resolvedArchive -C $temporaryRoot
    if ($LASTEXITCODE -ne 0) {
        throw "PUC Lua archive extraction failed with exit code $LASTEXITCODE"
    }

    $extractedRoot = Join-Path $temporaryRoot 'lua-5.4.8'
    $extractedSource = Join-Path $extractedRoot 'src'
    if (-not (Test-Path -LiteralPath $extractedSource -PathType Container)) {
        throw 'Verified PUC Lua archive did not contain lua-5.4.8/src'
    }

    $inventoryPath = Join-Path $repositoryRoot 'app/src/main/cpp/cmake/lua54-sources.cmake'
    $admittedSources = @(
        Get-Content -LiteralPath $inventoryPath |
            Where-Object { $_ -match '^\s*src/[A-Za-z0-9_.-]+\.c\s*$' } |
            ForEach-Object { $_.Trim() }
    )
    if ($admittedSources.Count -eq 0) {
        throw 'The CMake Lua source admission list is empty'
    }
    foreach ($relativeSource in $admittedSources) {
        if (-not (Test-Path -LiteralPath (Join-Path $extractedRoot $relativeSource) -PathType Leaf)) {
            throw "Verified PUC Lua archive is missing an admitted source: $relativeSource"
        }
    }

    Move-Item -LiteralPath $extractedSource -Destination $destinationSource
    $sourcePublished = $true

    $fingerprintJson = & python (Join-Path $repositoryRoot 'tools/verify_repository.py') --print-vendor-tree
    if ($LASTEXITCODE -ne 0) {
        throw "Lua source-tree fingerprint failed with exit code $LASTEXITCODE"
    }
    $fingerprint = $fingerprintJson | ConvertFrom-Json
    if ($fingerprint.sourceFileCount -le 0 -or $fingerprint.sourceTreeSha256 -notmatch '^[0-9a-f]{64}$') {
        throw 'Lua source-tree fingerprint output is invalid'
    }

    $lock.status = 'vendored'
    $lock.sourceFileCount = [int] $fingerprint.sourceFileCount
    $lock.sourceTreeSha256 = [string] $fingerprint.sourceTreeSha256
    $lock.vendoredAt = [DateTimeOffset]::UtcNow.ToString('O')
    $lock | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $temporaryLock -Encoding utf8NoBOM
    Move-Item -LiteralPath $temporaryLock -Destination $lockPath -Force
    $lockPublished = $true

    & python (Join-Path $repositoryRoot 'tools/verify_repository.py')
    if ($LASTEXITCODE -ne 0) {
        throw "Repository verification failed after Lua intake with exit code $LASTEXITCODE"
    }
    Write-Host "Staged verified PUC Lua 5.4.8 source ($($fingerprint.sourceFileCount) files)"
} catch {
    if ($lockPublished) {
        [IO.File]::WriteAllBytes($lockPath, $originalLockBytes)
    }
    if ($sourcePublished -and (Test-Path -LiteralPath $destinationSource -PathType Container)) {
        Remove-Item -LiteralPath $destinationSource -Recurse -Force
    }
    throw
} finally {
    if (Test-Path -LiteralPath $temporaryLock -PathType Leaf) {
        Remove-Item -LiteralPath $temporaryLock -Force
    }
    if (Test-Path -LiteralPath $temporaryRoot -PathType Container) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
