param(
  [string]$Gateway = "http://127.0.0.1:8080",
  [string]$AiService = "http://127.0.0.1:8200",
  [int]$MaxRetries = 30,
  [int]$RetryWaitSeconds = 10,
  [string]$LogPath = "logs/interface-check-loop.log"
)

$ErrorActionPreference = "Stop"

$checks = @(
  @{ Name = "user-service"; Url = "$Gateway/user/health" },
  @{ Name = "user-relations"; Url = "$Gateway/user/blocks?userId=1" },
  @{ Name = "knowledge-list"; Url = "$Gateway/knowledge/list" },
  @{ Name = "knowledge-storage"; Url = "$Gateway/knowledge/storage/status" },
  @{ Name = "knowledge-search"; Url = "$Gateway/knowledge/search/status" },
  @{ Name = "square-feed"; Url = "$Gateway/square/feed" },
  @{ Name = "square-collections"; Url = "$Gateway/square/collections" },
  @{ Name = "following-feed"; Url = "$Gateway/square/following-feed?followedUserIds=1" },
  @{ Name = "message-list"; Url = "$Gateway/message/list?sessionId=1&userId=1" },
  @{ Name = "message-sessions"; Url = "$Gateway/message/sessions?userId=1" },
  @{ Name = "event-bus"; Url = "$Gateway/event/status" },
  @{ Name = "feedback-faq"; Url = "$Gateway/feedback/faqs" },
  @{ Name = "ai-service"; Url = "$AiService/ai/health" },
  @{ Name = "ai-history"; Url = "$AiService/ai/history?user_id=1" },
  @{ Name = "ai-vector"; Url = "$AiService/ai/vector/status" }
)

$logDir = Split-Path -Parent $LogPath
if ($logDir) {
  New-Item -ItemType Directory -Force -Path $logDir | Out-Null
}

function Write-Result {
  param([string]$Message, [string]$Color = "White")
  Write-Host $Message -ForegroundColor $Color
  Add-Content -Path $LogPath -Encoding UTF8 -Value $Message
}

function ConvertTo-Base64Url {
  param([byte[]]$Bytes)
  return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-SmokeToken {
  $secret = [string]$env:AI_KNOWLEDGE_JWT_SECRET
  if ([string]::IsNullOrWhiteSpace($secret)) {
    throw "The local signing secret is not available to the smoke test."
  }

  $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
  $headerJson = @{ alg = "HS256"; typ = "JWT" } | ConvertTo-Json -Compress
  $payloadJson = [ordered]@{
    sub = "demo"
    uid = 1
    role = "USER"
    iat = $now
    exp = $now + 300
    jti = [Guid]::NewGuid().ToString()
  } | ConvertTo-Json -Compress
  $header = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($headerJson))
  $payload = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($payloadJson))
  $signingInput = "$header.$payload"
  $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($secret))
  try {
    $signature = ConvertTo-Base64Url ($hmac.ComputeHash([Text.Encoding]::ASCII.GetBytes($signingInput)))
  } finally {
    $hmac.Dispose()
  }
  return "$signingInput.$signature"
}

Write-Result "START interface loop: MaxRetries=$MaxRetries RetryWaitSeconds=$RetryWaitSeconds" "Cyan"

for ($round = 1; $round -le $MaxRetries; $round++) {
  $failed = 0
  $has503 = $false
  $stamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
  Write-Result "===== Round $round/$MaxRetries - $stamp =====" "Cyan"

  $headers = @{}
  try {
    $headers.Authorization = "Bearer $(New-SmokeToken)"
    Write-Result "[OK] created a short-lived local smoke-test session" "Green"
  } catch {
    $failed++
    Write-Result "[FAIL] authentication $($_.Exception.Message)" "Red"
  }

  foreach ($check in $checks) {
    try {
      $response = Invoke-WebRequest -Uri $check.Url -Headers $headers -UseBasicParsing -TimeoutSec 8
      $status = [int]$response.StatusCode
      $payload = $response.Content | ConvertFrom-Json
      if ($status -ge 200 -and $status -lt 300 -and $payload.code -eq 0) {
        Write-Result "[OK] $($check.Name) HTTP $status $($check.Url)" "Green"
      } else {
        $failed++
        if ($status -eq 503) {
          $has503 = $true
        }
        Write-Result "[FAIL] $($check.Name) HTTP $status business-code=$($payload.code) $($check.Url)" "Red"
      }
    } catch {
      $failed++
      $statusCode = $null
      if ($_.Exception.Response -ne $null) {
        try {
          $statusCode = [int]$_.Exception.Response.StatusCode
        } catch {
          $statusCode = $null
        }
      }

      if ($statusCode -eq 503) {
        $has503 = $true
      }

      if ($statusCode -ne $null) {
        Write-Result "[FAIL] $($check.Name) HTTP $statusCode $($check.Url)" "Red"
      } else {
        Write-Result "[FAIL] $($check.Name) $($check.Url)" "Red"
      }
      Write-Result "       $($_.Exception.Message)" "DarkYellow"
    }
  }

  if ($failed -eq 0) {
    Write-Result "RESULT: SUCCESS at round $round" "Green"
    exit 0
  }

  if ($round -ge $MaxRetries) {
    Write-Result "RESULT: FAILED after $MaxRetries rounds" "Red"
    exit 1
  }

  if ($has503) {
    Write-Result "Detected 503. Waiting $RetryWaitSeconds seconds before retry." "Yellow"
  } else {
    Write-Result "Detected non-success response. Waiting $RetryWaitSeconds seconds before retry." "Yellow"
  }
  Start-Sleep -Seconds $RetryWaitSeconds
}
