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
  @{ Name = "knowledge-list"; Url = "$Gateway/knowledge/list" },
  @{ Name = "square-feed"; Url = "$Gateway/square/feed" },
  @{ Name = "message-list"; Url = "$Gateway/message/list?sessionId=1" },
  @{ Name = "feedback-faq"; Url = "$Gateway/feedback/faqs" },
  @{ Name = "ai-service"; Url = "$AiService/ai/health" }
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

Write-Result "START interface loop: MaxRetries=$MaxRetries RetryWaitSeconds=$RetryWaitSeconds" "Cyan"

for ($round = 1; $round -le $MaxRetries; $round++) {
  $failed = 0
  $has503 = $false
  $stamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
  Write-Result "===== Round $round/$MaxRetries - $stamp =====" "Cyan"

  foreach ($check in $checks) {
    try {
      $response = Invoke-WebRequest -Uri $check.Url -UseBasicParsing -TimeoutSec 8
      $status = [int]$response.StatusCode
      if ($status -ge 200 -and $status -lt 300) {
        Write-Result "[OK] $($check.Name) HTTP $status $($check.Url)" "Green"
      } else {
        $failed++
        if ($status -eq 503) {
          $has503 = $true
        }
        Write-Result "[FAIL] $($check.Name) HTTP $status $($check.Url)" "Red"
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
