param([switch]$KeepNacos)

$ErrorActionPreference = "Stop"
$Logs = Join-Path $PSScriptRoot "logs"
$ProcessFile = Join-Path $Logs "local-processes.json"
$NacosMarker = Join-Path $Logs "nacos-home.txt"
$failed = $false

if (Test-Path $ProcessFile) {
    $entries = @(Get-Content $ProcessFile -Raw | ConvertFrom-Json)
    foreach ($entry in ($entries | Sort-Object { [int]$_.pid } -Descending)) {
        $pidValue = [int]$entry.pid
        $process = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
        if (-not $process) { continue }

        $expected = [DateTime]::Parse([string]$entry.startTimeUtc).ToUniversalTime()
        $actual = $process.StartTime.ToUniversalTime()
        if ([Math]::Abs(($actual - $expected).TotalSeconds) -gt 2) {
            Write-Host "[$($entry.name)] PID $pidValue was reused; refusing to stop it." -ForegroundColor Yellow
            $failed = $true
            continue
        }

        Write-Host "Stopping [$($entry.name)] process tree (PID $pidValue)..." -ForegroundColor Cyan
        & taskkill.exe /PID $pidValue /T /F 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0 -and (Get-Process -Id $pidValue -ErrorAction SilentlyContinue)) {
            Write-Host "[$($entry.name)] could not be stopped." -ForegroundColor Red
            $failed = $true
        }
    }
    if (-not $failed) { Remove-Item -LiteralPath $ProcessFile -Force -ErrorAction SilentlyContinue }
} else {
    Write-Host "No recorded local project processes were found." -ForegroundColor Yellow
}

if (-not $KeepNacos -and (Test-Path $NacosMarker)) {
    $nacosHome = (Get-Content $NacosMarker -Raw).Trim()
    $shutdown = Join-Path $nacosHome "bin\shutdown.cmd"
    if (Test-Path $shutdown) {
        Write-Host "Stopping Nacos started by this project..." -ForegroundColor Cyan
        & $shutdown 2>$null | Out-Null
    }
    Remove-Item -LiteralPath $NacosMarker -Force -ErrorAction SilentlyContinue
}

if ($failed) { exit 1 }
Write-Host "Recorded local project processes have been stopped." -ForegroundColor Green
exit 0
