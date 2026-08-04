param(
    [switch]$SkipNacos,
    [switch]$SkipMinio,
    [switch]$UseExternalMinio,
    [switch]$SkipBuild,
    [switch]$SkipFrontend,
    [switch]$SkipSmokeTest
)

& (Join-Path $PSScriptRoot "start-local.ps1") -Restart -SkipNacos:$SkipNacos `
    -SkipMinio:$SkipMinio -UseExternalMinio:$UseExternalMinio -SkipBuild:$SkipBuild `
    -SkipFrontend:$SkipFrontend -SkipSmokeTest:$SkipSmokeTest
exit $LASTEXITCODE
