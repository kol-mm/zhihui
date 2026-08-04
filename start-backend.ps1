param([switch]$SkipBuild)

Write-Host "Starting backend services through the managed local launcher..." -ForegroundColor Cyan
& (Join-Path $PSScriptRoot "start-local.ps1") -SkipNacos -SkipFrontend -SkipAi -SkipSmokeTest -SkipBuild:$SkipBuild
exit $LASTEXITCODE
