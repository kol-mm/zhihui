param(
    [switch]$WithDevFrontend,
    [switch]$SkipBuild,
    [switch]$SkipSmokeTest
)

$ErrorActionPreference = "Stop"

# Nacos is not used by this profile, but it may be managed by another local
# application. Stop only the business processes recorded by this project.
& (Join-Path $PSScriptRoot "stop-local.ps1") -KeepNacos
if ($LASTEXITCODE -ne 0) {
    throw "Failed to stop the previous project processes."
}

& (Join-Path $PSScriptRoot "start-lightweight.ps1") `
    -WithDevFrontend:$WithDevFrontend -SkipBuild:$SkipBuild -SkipSmokeTest:$SkipSmokeTest
exit $LASTEXITCODE
