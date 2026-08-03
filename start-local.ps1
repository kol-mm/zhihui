param(
    [switch]$SkipNacos,
    [switch]$SkipBuild,
    [switch]$SkipFrontend,
    [int]$WaitSeconds = 8
)

$ErrorActionPreference = "Stop"

$Root = $PSScriptRoot
$Backend = Join-Path $Root "backend"
$AiService = Join-Path $Root "ai-service"
$Frontend = Join-Path $Root "frontend"
$Logs = Join-Path $Root "logs"

New-Item -ItemType Directory -Force -Path $Logs | Out-Null

function Test-Port {
    param([int]$Port)
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $async = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
        $success = $async.AsyncWaitHandle.WaitOne(500)
        if ($success) {
            $client.EndConnect($async)
            $client.Close()
            return $true
        }
        $client.Close()
        return $false
    } catch {
        return $false
    }
}

function Start-LocalProcess {
    param(
        [string]$Name,
        [string]$WorkingDirectory,
        [string]$Command,
        [int]$Port = 0
    )

    if ($Port -gt 0 -and (Test-Port $Port)) {
        Write-Host "[$Name] port $Port is already in use, assuming it is running." -ForegroundColor Yellow
        return
    }

    $logFile = Join-Path $Logs "$Name.log"
    $psCommand = "Set-Location '$WorkingDirectory'; $Command *> '$logFile'"
    Start-Process powershell -ArgumentList "-NoExit", "-ExecutionPolicy", "Bypass", "-Command", $psCommand -WorkingDirectory $WorkingDirectory | Out-Null
    Write-Host "[$Name] started. Log: $logFile" -ForegroundColor Green
}

Write-Host "Starting AI Knowledge Platform local usable version..." -ForegroundColor Cyan
Write-Host "Project: $Root"

if (-not $SkipNacos) {
    if (Test-Port 8848) {
        Write-Host "[nacos] port 8848 is already in use." -ForegroundColor Yellow
    } else {
        $nacosHome = $env:NACOS_HOME
        if (-not $nacosHome) {
            $knownNacos = "D:\software\nacos-server-2.3.2\nacos"
            if (Test-Path (Join-Path $knownNacos "bin\startup.cmd")) {
                $nacosHome = $knownNacos
            }
        }

        if ($nacosHome -and (Test-Path (Join-Path $nacosHome "bin\startup.cmd"))) {
            $nacosBin = Join-Path $nacosHome "bin"
        Start-LocalProcess -Name "nacos" -WorkingDirectory $nacosBin -Command ".\startup.cmd -m standalone" -Port 8848
        Start-Sleep -Seconds $WaitSeconds
        } else {
            Write-Host "[nacos] not started automatically. Set NACOS_HOME to your Nacos directory, or start Nacos manually:" -ForegroundColor Yellow
            Write-Host "        startup.cmd -m standalone"
        }
    }
}

if (-not $SkipBuild) {
    Write-Host "[backend] installing modules to local Maven repository..." -ForegroundColor Cyan
    Push-Location $Backend
    mvn -s maven-settings.xml clean install -DskipTests
    Pop-Location
} else {
    Write-Host "[backend] SkipBuild was set. Make sure you have run: mvn -s maven-settings.xml clean install -DskipTests" -ForegroundColor Yellow
}

Start-LocalProcess -Name "ai-service" -WorkingDirectory $AiService -Command ".\run.ps1" -Port 8200
Start-Sleep -Seconds 3

Start-LocalProcess -Name "user-service" -WorkingDirectory $Backend -Command "mvn -s maven-settings.xml -pl user-service spring-boot:run" -Port 8101
Start-LocalProcess -Name "knowledge-service" -WorkingDirectory $Backend -Command "mvn -s maven-settings.xml -pl knowledge-service spring-boot:run" -Port 8102
Start-LocalProcess -Name "community-service" -WorkingDirectory $Backend -Command "mvn -s maven-settings.xml -pl community-service spring-boot:run" -Port 8103
Start-LocalProcess -Name "message-service" -WorkingDirectory $Backend -Command "mvn -s maven-settings.xml -pl message-service spring-boot:run" -Port 8104

Start-Sleep -Seconds $WaitSeconds
Start-LocalProcess -Name "gateway" -WorkingDirectory $Backend -Command "mvn -s maven-settings.xml -pl gateway spring-boot:run" -Port 8080

if (-not $SkipFrontend) {
    Start-Sleep -Seconds 3
    Start-LocalProcess -Name "frontend" -WorkingDirectory $Frontend -Command "npm.cmd run dev" -Port 5173
}

Write-Host ""
Write-Host "Startup commands have been launched." -ForegroundColor Green
Write-Host "Frontend: http://127.0.0.1:5173"
Write-Host "Gateway:  http://127.0.0.1:8080"
Write-Host "AI:       http://127.0.0.1:8200/ai/health"
Write-Host "Logs:     $Logs"
Write-Host ""
Write-Host "Demo account: demo / demo"
Write-Host "If a service fails, check its log file or the opened PowerShell window."
