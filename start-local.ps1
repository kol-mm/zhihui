param(
    [switch]$SkipNacos,
    [switch]$SkipMinio,
    [switch]$UseExternalMinio,
    [switch]$SkipBuild,
    [switch]$SkipAi,
    [switch]$SkipFrontend,
    [switch]$SkipSmokeTest,
    [switch]$Restart,
    [int]$WaitSeconds = 2,
    [int]$StartupTimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"

$Root = $PSScriptRoot
$Backend = Join-Path $Root "backend"
$AiService = Join-Path $Root "ai-service"
$Frontend = Join-Path $Root "frontend"
$Logs = Join-Path $Root "logs"
$ProcessFile = Join-Path $Logs "local-processes.json"
$NacosMarker = Join-Path $Logs "nacos-home.txt"
$script:ManagedProcesses = [System.Collections.ArrayList]::new()

New-Item -ItemType Directory -Force -Path $Logs | Out-Null

if ([string]::IsNullOrWhiteSpace([string]$env:AI_KNOWLEDGE_JWT_SECRET)) {
    $secretDirectory = Join-Path $Root ".local-secrets"
    $secretFile = Join-Path $secretDirectory "jwt-secret.txt"
    New-Item -ItemType Directory -Force -Path $secretDirectory | Out-Null
    if (-not (Test-Path $secretFile)) {
        $bytes = New-Object byte[] 48
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        [System.IO.File]::WriteAllText($secretFile, [Convert]::ToBase64String($bytes))
    }
    $env:AI_KNOWLEDGE_JWT_SECRET = [System.IO.File]::ReadAllText($secretFile).Trim()
}

function Normalize-ProcessPathEnvironment {
    $variables = [Environment]::GetEnvironmentVariables()
    $pathKeys = @($variables.Keys | Where-Object { $_ -ieq "Path" })
    if ($pathKeys.Count -le 1) { return }

    $pathValue = if ($variables.Contains("Path")) {
        [string]$variables["Path"]
    } else {
        [string]$variables[$pathKeys[0]]
    }
    [Environment]::SetEnvironmentVariable("PATH", $null, "Process")
    [Environment]::SetEnvironmentVariable("Path", $pathValue, "Process")
}

Normalize-ProcessPathEnvironment

function Assert-Command {
    param([string]$Name, [string]$InstallHint)
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $command) {
        throw "Required command '$Name' was not found. $InstallHint"
    }
    return $command.Source
}

function Assert-NativeSuccess {
    param([string]$Step)
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE."
    }
}

function Test-Port {
    param([int]$Port)
    $client = $null
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $async = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne(500)) { return $false }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        if ($client) { $client.Close() }
    }
}

function Test-HealthUrl {
    param([string]$Url)
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 3
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 300
    } catch {
        return $false
    }
}

function Test-RegisteredListener {
    param([int]$Port)
    if (-not (Test-Path -LiteralPath $ProcessFile)) { return $false }

    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $listener) { return $false }
    $process = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
    if (-not $process) { return $false }

    try {
        $entries = @(Get-Content -LiteralPath $ProcessFile -Raw | ConvertFrom-Json)
        foreach ($entry in $entries) {
            $registeredPid = if ($entry.listenerPid) { [int]$entry.listenerPid } else { [int]$entry.pid }
            $registeredStart = if ($entry.listenerStartTimeUtc) { [string]$entry.listenerStartTimeUtc } else { [string]$entry.startTimeUtc }
            if ([int]$entry.port -ne $Port -or $registeredPid -ne $process.Id) { continue }
            $expected = [DateTime]::Parse($registeredStart).ToUniversalTime()
            $actual = $process.StartTime.ToUniversalTime()
            return [Math]::Abs(($actual - $expected).TotalSeconds) -le 2
        }
    } catch {
        return $false
    }
    return $false
}

function Save-ProcessRegistry {
    $script:ManagedProcesses | ConvertTo-Json -Depth 4 | Set-Content -Path $ProcessFile -Encoding UTF8
}

function Show-ServiceLogs {
    param([string]$Name)
    foreach ($path in @((Join-Path $Logs "$Name.log"), (Join-Path $Logs "$Name.error.log"))) {
        if (Test-Path $path) {
            Write-Host "--- $path (last 30 lines) ---" -ForegroundColor Yellow
            Get-Content $path -Tail 30 -ErrorAction SilentlyContinue
        }
    }
}

function Archive-ServiceLogs {
    param([string]$Name)
    $archive = Join-Path $Logs "archive"
    New-Item -ItemType Directory -Force -Path $archive | Out-Null
    $stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
    foreach ($suffix in @(".log", ".error.log")) {
        $source = Join-Path $Logs "$Name$suffix"
        if (Test-Path -LiteralPath $source) {
            Move-Item -LiteralPath $source -Destination (Join-Path $archive "$Name-$stamp$suffix") -Force
        }
    }
    Get-ChildItem -LiteralPath $archive -Filter "$Name-*" -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -Skip 20 |
        Remove-Item -Force -ErrorAction SilentlyContinue
}

function Wait-Service {
    param(
        [string]$Name,
        [string]$HealthUrl,
        [System.Diagnostics.Process]$Process,
        [int]$TimeoutSeconds = $StartupTimeoutSeconds
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (Test-HealthUrl $HealthUrl) {
            Write-Host "[$Name] ready: $HealthUrl" -ForegroundColor Green
            return
        }
        if ($Process -and $Process.HasExited) {
            $Process.Refresh()
            Show-ServiceLogs $Name
            $exitCode = if ($null -ne $Process.ExitCode) { $Process.ExitCode } else { "unknown" }
            throw "[$Name] exited before becoming ready (exit code $exitCode)."
        }
        Start-Sleep -Seconds ([Math]::Max(1, $WaitSeconds))
    } while ((Get-Date) -lt $deadline)

    Show-ServiceLogs $Name
    throw "[$Name] did not become healthy within $TimeoutSeconds seconds: $HealthUrl"
}

function Start-ManagedProcess {
    param(
        [string]$Name,
        [string]$FilePath,
        [string[]]$ArgumentList,
        [string]$WorkingDirectory,
        [int]$Port,
        [string]$HealthUrl
    )

    if (Test-Port $Port) {
        if ((Test-HealthUrl $HealthUrl) -and (Test-RegisteredListener -Port $Port)) {
            Write-Host "[$Name] already healthy on port $Port; reusing it." -ForegroundColor Yellow
            return $null
        }
        throw "[$Name] cannot start: port $Port is occupied by a process that is not registered to this local project run. Stop the conflicting process first."
    }

    $outputLog = Join-Path $Logs "$Name.log"
    $errorLog = Join-Path $Logs "$Name.error.log"
    Archive-ServiceLogs $Name
    $process = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList `
        -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $outputLog -RedirectStandardError $errorLog

    $record = [ordered]@{
        name = $Name
        pid = $process.Id
        startTimeUtc = $process.StartTime.ToUniversalTime().ToString("o")
        port = $Port
    }
    [void]$script:ManagedProcesses.Add($record)
    Save-ProcessRegistry
    Write-Host "[$Name] process started (PID $($process.Id)). Waiting for health check..." -ForegroundColor Cyan
    Wait-Service -Name $Name -HealthUrl $HealthUrl -Process $process
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) {
        $listenerProcess = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
        if ($listenerProcess) {
            $record.listenerPid = $listenerProcess.Id
            $record.listenerStartTimeUtc = $listenerProcess.StartTime.ToUniversalTime().ToString("o")
            Save-ProcessRegistry
        }
    }
    return $process
}

try {
    Write-Host "Starting AI Knowledge Platform local usable version..." -ForegroundColor Cyan
    Write-Host "Project: $Root"

    if ($SkipMinio -and $UseExternalMinio) {
        throw "SkipMinio and UseExternalMinio cannot be used together."
    }

    if ($SkipNacos) {
        $env:SPRING_CLOUD_NACOS_DISCOVERY_ENABLED = "false"
        $env:USER_SERVICE_URL = "http://127.0.0.1:8101"
        $env:KNOWLEDGE_SERVICE_URL = "http://127.0.0.1:8102"
        $env:COMMUNITY_SERVICE_URL = "http://127.0.0.1:8103"
        $env:MESSAGE_SERVICE_URL = "http://127.0.0.1:8104"
        Write-Host "[nacos] skipped; gateway will use direct local service addresses." -ForegroundColor Yellow
    }

    $PowerShellExe = Assert-Command "powershell.exe" "PowerShell is required on Windows."
    $MavenExe = Assert-Command "mvn.cmd" "Install Maven 3.6+ and add it to PATH."
    if (-not $SkipFrontend) { $NpmExe = Assert-Command "npm.cmd" "Install Node.js 20+ and add npm to PATH." }

    if ($UseExternalMinio) {
        if (-not (Test-HealthUrl "http://127.0.0.1:9000/minio/health/live")) {
            throw "External MinIO is not healthy at http://127.0.0.1:9000. Start it before running this command."
        }
        if (-not $env:MINIO_ACCESS_KEY) { $env:MINIO_ACCESS_KEY = "minioadmin" }
        if (-not $env:MINIO_SECRET_KEY) { $env:MINIO_SECRET_KEY = "minioadmin" }
        $env:KNOWLEDGE_STORAGE_MODE = "minio"
        Write-Host "[minio] using externally managed service on port 9000." -ForegroundColor Yellow
    } elseif (-not $SkipMinio) {
        $MinioExe = $env:MINIO_EXE
        if (-not $MinioExe) {
            $knownMinio = Get-ChildItem -LiteralPath "D:\software" -Recurse -Filter "minio.exe" -File -ErrorAction SilentlyContinue |
                Select-Object -First 1 -ExpandProperty FullName
            if ($knownMinio -and (Test-Path -LiteralPath $knownMinio)) { $MinioExe = $knownMinio }
        }
        if (-not $MinioExe -or -not (Test-Path -LiteralPath $MinioExe)) {
            throw "MinIO executable was not found. Set MINIO_EXE or use -SkipMinio for local file storage."
        }
        if (-not $env:MINIO_ACCESS_KEY) { $env:MINIO_ACCESS_KEY = "aiknowledge" }
        if (-not $env:MINIO_SECRET_KEY) { $env:MINIO_SECRET_KEY = "ai-knowledge-local-change-me" }
        $env:MINIO_ROOT_USER = $env:MINIO_ACCESS_KEY
        $env:MINIO_ROOT_PASSWORD = $env:MINIO_SECRET_KEY
        $env:KNOWLEDGE_STORAGE_MODE = "minio"
    }

    if (-not $SkipBuild) {
        Write-Host "[backend] building and installing modules..." -ForegroundColor Cyan
        Push-Location $Backend
        try {
            & $MavenExe -s maven-settings.xml clean install -DskipTests
            Assert-NativeSuccess "Backend build"
        } finally {
            Pop-Location
        }
    } else {
        Write-Host "[backend] SkipBuild selected; ensuring parent POM and common module are installed..." -ForegroundColor Cyan
        Push-Location $Backend
        try {
            & $MavenExe -s maven-settings.xml -N install -DskipTests
            Assert-NativeSuccess "Backend parent POM installation"
            & $MavenExe -s maven-settings.xml -pl common install -DskipTests
            Assert-NativeSuccess "Backend common module installation"
        } finally {
            Pop-Location
        }
    }

    if (-not $SkipFrontend -and -not (Test-Path (Join-Path $Frontend "node_modules"))) {
        Write-Host "[frontend] node_modules is missing; installing dependencies..." -ForegroundColor Cyan
        Push-Location $Frontend
        try {
            & $NpmExe install
            Assert-NativeSuccess "Frontend dependency installation"
        } finally {
            Pop-Location
        }
    }

    if ($Restart) {
        Write-Host "Stopping processes recorded by the previous local run..." -ForegroundColor Cyan
        & (Join-Path $Root "stop-local.ps1") -KeepNacos:$SkipNacos
        if ($LASTEXITCODE -ne 0) { throw "Failed to stop the previous local run." }
    }

    if (-not $SkipMinio -and -not $UseExternalMinio) {
        $minioData = Join-Path $Root "data\minio"
        New-Item -ItemType Directory -Force -Path $minioData | Out-Null
        Start-ManagedProcess -Name "minio" -FilePath $MinioExe `
            -ArgumentList @("server", ('"{0}"' -f $minioData), "--address", "127.0.0.1:9000", "--console-address", "127.0.0.1:9001") `
            -WorkingDirectory (Split-Path $MinioExe) -Port 9000 -HealthUrl "http://127.0.0.1:9000/minio/health/live" | Out-Null
    } elseif ($SkipMinio -and -not $env:KNOWLEDGE_STORAGE_MODE) {
        $env:KNOWLEDGE_STORAGE_MODE = "local"
    }

    if (-not $SkipNacos) {
        if (Test-HealthUrl "http://127.0.0.1:8848/nacos/v1/console/health/readiness") {
            Write-Host "[nacos] already healthy on port 8848." -ForegroundColor Yellow
        } else {
            $nacosHome = $env:NACOS_HOME
            if (-not $nacosHome) {
                $knownNacos = "D:\software\nacos-server-2.3.2\nacos"
                if (Test-Path (Join-Path $knownNacos "bin\startup.cmd")) { $nacosHome = $knownNacos }
            }
            $startup = if ($nacosHome) { Join-Path $nacosHome "bin\startup.cmd" } else { "" }
            if (-not $nacosHome -or -not (Test-Path $startup)) {
                throw "Nacos was not found. Set NACOS_HOME to the Nacos root directory or use -SkipNacos."
            }
            Start-Process -FilePath $startup -ArgumentList "-m", "standalone" -WorkingDirectory (Split-Path $startup) -WindowStyle Hidden | Out-Null
            Set-Content -Path $NacosMarker -Value $nacosHome -Encoding UTF8
            Wait-Service -Name "nacos" -HealthUrl "http://127.0.0.1:8848/nacos/v1/console/health/readiness" -Process $null
        }
    }

    if (-not $SkipAi) {
        Start-ManagedProcess -Name "ai-service" -FilePath $PowerShellExe `
            -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ('"{0}"' -f (Join-Path $AiService "run.ps1"))) `
            -WorkingDirectory $AiService -Port 8200 -HealthUrl "http://127.0.0.1:8200/ai/health" | Out-Null
    }

    Start-ManagedProcess -Name "user-service" -FilePath $MavenExe `
        -ArgumentList @("-s", "maven-settings.xml", "-pl", "user-service", "spring-boot:run") `
        -WorkingDirectory $Backend -Port 8101 -HealthUrl "http://127.0.0.1:8101/user/health" | Out-Null
    Start-ManagedProcess -Name "knowledge-service" -FilePath $MavenExe `
        -ArgumentList @("-s", "maven-settings.xml", "-pl", "knowledge-service", "spring-boot:run") `
        -WorkingDirectory $Backend -Port 8102 -HealthUrl "http://127.0.0.1:8102/knowledge/health" | Out-Null
    Start-ManagedProcess -Name "community-service" -FilePath $MavenExe `
        -ArgumentList @("-s", "maven-settings.xml", "-pl", "community-service", "spring-boot:run") `
        -WorkingDirectory $Backend -Port 8103 -HealthUrl "http://127.0.0.1:8103/post/health" | Out-Null
    Start-ManagedProcess -Name "message-service" -FilePath $MavenExe `
        -ArgumentList @("-s", "maven-settings.xml", "-pl", "message-service", "spring-boot:run") `
        -WorkingDirectory $Backend -Port 8104 -HealthUrl "http://127.0.0.1:8104/message/health" | Out-Null

    Start-ManagedProcess -Name "gateway" -FilePath $MavenExe `
        -ArgumentList @("-s", "maven-settings.xml", "-pl", "gateway", "spring-boot:run") `
        -WorkingDirectory $Backend -Port 8080 -HealthUrl "http://127.0.0.1:8080/user/health" | Out-Null

    if (-not $SkipFrontend) {
        Start-ManagedProcess -Name "frontend" -FilePath $PowerShellExe `
            -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ('"{0}"' -f (Join-Path $Root "start-frontend.ps1"))) `
            -WorkingDirectory $Root -Port 5173 `
            -HealthUrl "http://127.0.0.1:5173" | Out-Null
    }

    if (-not $SkipSmokeTest) {
        Write-Host "Running interface acceptance checks..." -ForegroundColor Cyan
        & $PowerShellExe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Root "smoke-test.ps1") -MaxRetries 5 -RetryWaitSeconds 2
        Assert-NativeSuccess "Interface acceptance checks"
    }

    Write-Host ""
    Write-Host "All requested services are healthy." -ForegroundColor Green
    if (-not $SkipFrontend) { Write-Host "Frontend: http://127.0.0.1:5173" }
    Write-Host "Gateway:  http://127.0.0.1:8080"
    if (-not $SkipAi) { Write-Host "AI:       http://127.0.0.1:8200/ai/health" }
    Write-Host "Logs:     $Logs"
    Write-Host "Accounts: demo / demo, admin / admin123"
    exit 0
} catch {
    Write-Host ""
    Write-Host "Startup failed: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Run .\stop-local.ps1 before retrying if this was a partial startup." -ForegroundColor Yellow
    exit 1
}
