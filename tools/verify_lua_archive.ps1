[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Archive
)

$ErrorActionPreference = 'Stop'
$resolvedArchive = (Resolve-Path -LiteralPath $Archive).Path
$expected = '4f18ddae154e793e46eeab727c59ef1c0c0c2b744e7b94219710d76f530629ae'
$actual = (Get-FileHash -LiteralPath $resolvedArchive -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $expected) {
    throw "PUC Lua 5.4.8 archive digest mismatch: expected $expected, got $actual"
}
Write-Host 'PUC Lua 5.4.8 archive SHA-256 verified'
