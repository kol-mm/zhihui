param(
    [string]$BackupRoot = "$(Join-Path $PSScriptRoot 'backups-lightweight')",
    [string]$MySqlExe = "D:\software\mysql-8.0.26-winx64\bin\mysqldump.exe",
    [string]$MySqlUsername = $(if ($env:MYSQL_USERNAME) { $env:MYSQL_USERNAME } else { "root" })
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace([string]$env:MYSQL_PASSWORD)) {
    throw "Set MYSQL_PASSWORD before creating a backup."
}
if (-not (Test-Path -LiteralPath $MySqlExe)) {
    throw "mysqldump.exe was not found. Pass -MySqlExe with the installed path."
}

$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmss")
$target = Join-Path $BackupRoot $stamp
$fileTarget = Join-Path $target "files"
New-Item -ItemType Directory -Force -Path $fileTarget | Out-Null

$previousPassword = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $env:MYSQL_PASSWORD
    $dumpPath = Join-Path $target "mysql-all.sql"
    & $MySqlExe "--user=$MySqlUsername" "--all-databases" "--single-transaction" "--routines" "--events" "--result-file=$dumpPath"
    if ($LASTEXITCODE -ne 0) { throw "MySQL backup failed." }

    foreach ($relativePath in @(
        "data\uploads",
        "data\knowledge-media",
        "data\community-media",
        "data\user-avatars",
        "ai-service\data\ai_service.db",
        ".local-secrets\jwt-secret.txt"
    )) {
        $source = Join-Path $PSScriptRoot $relativePath
        if (-not (Test-Path -LiteralPath $source)) { continue }
        $destination = Join-Path $fileTarget $relativePath
        $destinationParent = Split-Path -Parent $destination
        New-Item -ItemType Directory -Force -Path $destinationParent | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination -Recurse -Force
    }

    @(
        "created_at_utc=$((Get-Date).ToUniversalTime().ToString('o'))",
        "profile=4g-lightweight",
        "contains=mysql,local-files,ai-sqlite,jwt-secret"
    ) | Set-Content -Path (Join-Path $target "manifest.txt") -Encoding ASCII

    Write-Host "Lightweight backup created: $target" -ForegroundColor Green
} finally {
    $env:MYSQL_PWD = $previousPassword
}
