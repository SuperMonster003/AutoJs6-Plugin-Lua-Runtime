[CmdletBinding()]
param(
    [string] $PythonCommand = 'python'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$python = Get-Command $PythonCommand -ErrorAction Stop
$verificationProperties = ConvertFrom-StringData -StringData (
    Get-Content -LiteralPath (Join-Path $repositoryRoot 'verification.properties') -Raw
)
if ($verificationProperties.Count -ne 1 -or
    -not $verificationProperties.ContainsKey('JVM_TEST_COUNT') -or
    [string]$verificationProperties.JVM_TEST_COUNT -notmatch '^[1-9][0-9]{0,5}$') {
    throw 'verification.properties must contain exactly one positive JVM_TEST_COUNT'
}
$expectedTests = [int]$verificationProperties.JVM_TEST_COUNT

Push-Location $repositoryRoot
try {
    & $python.Source 'tools/verify_repository.py' '--require-build-ready'
    if ($LASTEXITCODE -ne 0) {
        throw "Repository verifier failed with exit code $LASTEXITCODE"
    }

    & $python.Source '-m' 'unittest' 'discover' '-s' 'tools/tests'
    if ($LASTEXITCODE -ne 0) {
        throw "Python boundary tests failed with exit code $LASTEXITCODE"
    }

    $gradleArguments = @(
        ':app:testDebugUnitTest'
        '-Pautojs.lua.native.enabled=true'
        '-Pautojs.lua.provider.enabled=false'
        '--offline'
        '--no-daemon'
        '--console=plain'
    )
    & (Join-Path $repositoryRoot 'gradlew.bat') @gradleArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Offline JVM Gradle gate failed with exit code $LASTEXITCODE"
    }

    $reports = @(
        Get-ChildItem -LiteralPath (
            Join-Path $repositoryRoot 'app/build/test-results/testDebugUnitTest'
        ) -Filter '*.xml' -File
    )
    if ($reports.Count -eq 0) {
        throw 'Offline JVM Gradle gate produced no XML reports'
    }
    $tests = 0
    $failures = 0
    $errors = 0
    $skipped = 0
    foreach ($report in $reports) {
        [xml]$document = Get-Content -LiteralPath $report.FullName -Raw
        $tests += [int]$document.testsuite.tests
        $failures += [int]$document.testsuite.failures
        $errors += [int]$document.testsuite.errors
        $skipped += [int]$document.testsuite.skipped
    }
    if ($tests -ne $expectedTests -or
        $failures -ne 0 -or
        $errors -ne 0 -or
        $skipped -ne 0) {
        throw (
            "Offline JVM test count drift: expected=$expectedTests tests=$tests " +
            "failures=$failures errors=$errors skipped=$skipped"
        )
    }

    Write-Host (
        "LOCAL_OFFLINE_GATE_PASS tests=$tests " +
        'protocol=ready lua=ready provider=false network=disabled'
    )
} finally {
    Pop-Location
}
