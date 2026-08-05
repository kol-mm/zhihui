param(
    [string]$BackupRoot = "$(Join-Path $PSScriptRoot 'backups')",
    [string]$MySqlExe = "D:\software\mysql-8.0.26-winx64\bin\mysqldump.exe",
    [string]$MySqlUsername = $(if ($env:MYSQL_USERNAME) { $env:MYSQL_USERNAME } else { "root" }),
    [string]$MinioClient = "mc.exe",
    [string]$MinioAlias = "local",
    [switch]$SkipMinio
)

$ErrorActionPreference = "Stop"
if (-not $env:MYSQL_PASSWORD) { throw "Set MYSQL_PASSWORD before creating a backup." }
if (-not (Test-Path -LiteralPath $MySqlExe)) { throw "mysqldump.exe was not found at $MySqlExe." }

$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmss")
$target = Join-Path $BackupRoot $stamp
New-Item -ItemType Directory -Force -Path $target | Out-Null
$previousPassword = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $env:MYSQL_PASSWORD
    $dumpPath = Join-Path $target "mysql-all.sql"
    & $MySqlExe "--user=$MySqlUsername" "--all-databases" "--single-transaction" "--routines" "--events" "--result-file=$dumpPath"
    if ($LASTEXITCODE -ne 0) { throw "MySQL backup failed." }

    if (-not $SkipMinio) {
        $mc = Get-Command $MinioClient -ErrorAction SilentlyContinue
        if (-not $mc) { throw "mc.exe was not found. Configure the MinIO alias or pass -SkipMinio." }
        $objectTarget = Join-Path $target "minio"
        New-Item -ItemType Directory -Force -Path $objectTarget | Out-Null
        foreach ($bucket in @("ai-knowledge", "ai-community", "ai-user-avatar")) {
            & $mc.Source mirror --overwrite "$MinioAlias/$bucket" (Join-Path $objectTarget $bucket)
            if ($LASTEXITCODE -ne 0) { throw "MinIO backup failed for bucket $bucket." }
        }
    }
    Write-Host "Backup created: $target" -ForegroundColor Green
} finally {
    $env:MYSQL_PWD = $previousPassword
}
