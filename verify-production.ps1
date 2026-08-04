param(
    [string]$ApiBaseUrl = "http://127.0.0.1:8080",
    [string]$AiBaseUrl = "http://127.0.0.1:8200"
)

$ErrorActionPreference = "Stop"
$failures = [System.Collections.Generic.List[string]]::new()

function Add-Check {
    param([string]$Name, [bool]$Passed, [string]$Failure)
    if ($Passed) {
        Write-Host "[PASS] $Name" -ForegroundColor Green
    } else {
        Write-Host "[FAIL] $Name - $Failure" -ForegroundColor Red
        $failures.Add("$Name`: $Failure")
    }
}

function Test-TcpPort {
    param([int]$Port)
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connection = $client.ConnectAsync("127.0.0.1", $Port)
        return $connection.Wait(1000) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Test-HttpHealth {
    param([string]$Url)
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 3
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 300
    } catch {
        return $false
    }
}

$jwtSecret = [string]$env:AI_KNOWLEDGE_JWT_SECRET
Add-Check "JWT secret" ($jwtSecret.Length -ge 32 -and $jwtSecret -ne "local-dev-secret-change-before-production") "set AI_KNOWLEDGE_JWT_SECRET to at least 32 random characters"
Add-Check "MySQL password" (-not [string]::IsNullOrWhiteSpace($env:MYSQL_PASSWORD)) "set MYSQL_PASSWORD"
Add-Check "MinIO access key" (-not [string]::IsNullOrWhiteSpace($env:MINIO_ACCESS_KEY)) "set MINIO_ACCESS_KEY"
Add-Check "MinIO secret key" (-not [string]::IsNullOrWhiteSpace($env:MINIO_SECRET_KEY) -and $env:MINIO_SECRET_KEY -ne "minioadmin") "set a non-default MINIO_SECRET_KEY"

foreach ($service in @(
    @{ Name = "MySQL"; Port = 3306 },
    @{ Name = "Redis"; Port = 6379 },
    @{ Name = "Nacos"; Port = 8848 },
    @{ Name = "MinIO API"; Port = 9000 }
)) {
    Add-Check "$($service.Name) port" (Test-TcpPort $service.Port) "127.0.0.1:$($service.Port) is not listening"
}

foreach ($health in @(
    @{ Name = "User service"; Url = "$ApiBaseUrl/user/health" },
    @{ Name = "Knowledge service"; Url = "$ApiBaseUrl/knowledge/health" },
    @{ Name = "Community service"; Url = "$ApiBaseUrl/post/health" },
    @{ Name = "Message service"; Url = "$ApiBaseUrl/message/health" },
    @{ Name = "AI service"; Url = "$AiBaseUrl/ai/health" },
    @{ Name = "MinIO health"; Url = "http://127.0.0.1:9000/minio/health/live" }
)) {
    Add-Check $health.Name (Test-HttpHealth $health.Url) "$($health.Url) is not healthy"
}

if ($failures.Count -gt 0) {
    Write-Host "Production readiness failed with $($failures.Count) issue(s)." -ForegroundColor Red
    exit 1
}

Write-Host "Production readiness checks passed." -ForegroundColor Green
