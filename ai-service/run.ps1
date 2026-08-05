$ErrorActionPreference = "Stop"

$Venv = Join-Path $PSScriptRoot ".venv"
$VenvPython = Join-Path $Venv "Scripts\python.exe"
$Requirements = Join-Path $PSScriptRoot "requirements.txt"
$RequirementsMarker = Join-Path $Venv ".requirements.sha256"

if ([string]::IsNullOrWhiteSpace([string]$env:AI_KNOWLEDGE_JWT_SECRET)) {
    $sharedSecret = Join-Path (Split-Path $PSScriptRoot -Parent) ".local-secrets\jwt-secret.txt"
    if (Test-Path -LiteralPath $sharedSecret) {
        $env:AI_KNOWLEDGE_JWT_SECRET = [System.IO.File]::ReadAllText($sharedSecret).Trim()
    }
}

function Assert-NativeSuccess {
    param([string]$Step)
    if ($LASTEXITCODE -ne 0) { throw "$Step failed with exit code $LASTEXITCODE." }
}

if (-not (Test-Path $VenvPython)) {
    $pythonCommand = Get-Command python.exe -ErrorAction SilentlyContinue
    if (-not $pythonCommand) {
        throw "Python was not found. Install Python 3.11+ and add python.exe to PATH."
    }
    Write-Host "[ai-service] creating virtual environment: $Venv" -ForegroundColor Cyan
    & $pythonCommand.Source -m venv $Venv
    Assert-NativeSuccess "Virtual environment creation"
}

$requirementsHash = (Get-FileHash -Algorithm SHA256 -Path $Requirements).Hash
$installedHash = if (Test-Path $RequirementsMarker) { (Get-Content $RequirementsMarker -Raw).Trim() } else { "" }
if ($requirementsHash -ne $installedHash) {
    Write-Host "[ai-service] requirements changed; installing into .venv..." -ForegroundColor Cyan
    & $VenvPython -m pip install -r $Requirements
    Assert-NativeSuccess "AI dependency installation"
    Set-Content -Path $RequirementsMarker -Value $requirementsHash -Encoding ASCII
} else {
    Write-Host "[ai-service] virtual-environment dependencies are up to date."
}

& $VenvPython -m uvicorn app.main:app --host 127.0.0.1 --port 8200
Assert-NativeSuccess "AI service"
