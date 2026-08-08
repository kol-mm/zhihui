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

function Get-ServiceData {
    param([string]$Url)
    $response = Invoke-RestMethod -Uri $Url -TimeoutSec 5
    if ([int]$response.code -ne 0) { throw "Business check failed for $Url" }
    return $response.data
}

Add-Check "MySQL port" (Test-TcpPort 3306) "MySQL is not listening on 127.0.0.1:3306"

# Nacos or MinIO may be managed outside this project. Their ports being open
# does not mean the lightweight profile is using them, so report that state
# as an informational warning and validate the effective service modes below.
if (Test-TcpPort 8848) {
    Write-Host "[WARN] Nacos port 8848 is listening externally; direct routing is still required below." -ForegroundColor Yellow
} else {
    Write-Host "[PASS] Nacos port 8848 is not listening." -ForegroundColor Green
}
if (Test-TcpPort 9000) {
    Write-Host "[WARN] MinIO port 9000 is listening externally; local file mode is still required below." -ForegroundColor Yellow
} else {
    Write-Host "[PASS] MinIO port 9000 is not listening." -ForegroundColor Green
}

try {
    $gateway = Get-ServiceData "$ApiBaseUrl/gateway/status"
    $user = Get-ServiceData "$ApiBaseUrl/user/health"
    $knowledge = Get-ServiceData "$ApiBaseUrl/knowledge/health"
    $community = Get-ServiceData "$ApiBaseUrl/post/health"
    $message = Get-ServiceData "$ApiBaseUrl/message/health"
    $storage = Get-ServiceData "$ApiBaseUrl/knowledge/storage/status"
    $search = Get-ServiceData "$ApiBaseUrl/knowledge/search/status"
    $events = Get-ServiceData "$ApiBaseUrl/event/status"
    $ai = Get-ServiceData "$AiBaseUrl/ai/health"
    $vector = Get-ServiceData "$AiBaseUrl/ai/vector/status"

    Add-Check "Direct service routing" ($gateway.discoveryMode -eq "direct") "gateway is not in direct mode"
    Add-Check "Local rate limiting" ($gateway.rateLimitMode -eq "local") "gateway rate limiting is not local"
    Add-Check "MySQL business data" (@($user.dataMode, $knowledge.dataMode, $community.dataMode, $message.dataMode) -notcontains "local") "one or more services are using local business data"
    Add-Check "Local file storage" ($storage.mode -eq "local" -and $user.avatarStorageMode -eq "local" -and $community.mediaStorageMode -eq "local") "file storage is not fully local"
    Add-Check "Local search" ($search.mode -eq "local") "search is not local"
    Add-Check "Local events" ($events.mode -eq "local") "events are not local"
    Add-Check "Local AI vector search" ($vector.mode -eq "local") "AI vector mode is not local"
    Add-Check "AI SQLite" ($ai.database_mode -eq "sqlite") "AI database is not SQLite"
} catch {
    Add-Check "Service status endpoints" $false $_.Exception.Message
}

if ($failures.Count -gt 0) {
    Write-Host "Lightweight verification failed with $($failures.Count) issue(s)." -ForegroundColor Red
    exit 1
}

Write-Host "Lightweight single-server verification passed." -ForegroundColor Green
exit 0
