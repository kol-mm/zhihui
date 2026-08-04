param(
    [switch]$SkipNacos,
    [switch]$SkipBuild,
    [switch]$SkipFrontend,
    [switch]$SkipSmokeTest
)

& (Join-Path $PSScriptRoot "start-local.ps1") -Restart -SkipNacos:$SkipNacos `
    -SkipBuild:$SkipBuild -SkipFrontend:$SkipFrontend -SkipSmokeTest:$SkipSmokeTest
exit $LASTEXITCODE
