param(
    [string]$MySqlExe = "D:\software\mysql-8.0.26-winx64\bin\mysql.exe",
    [string]$Username = $(if ($env:MYSQL_USERNAME) { $env:MYSQL_USERNAME } else { "root" })
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$schemaPath = Join-Path $projectRoot "docs\sql\schema.sql"

if (-not (Test-Path -LiteralPath $MySqlExe)) {
    throw "mysql.exe was not found at $MySqlExe. Pass -MySqlExe with the installed path."
}
if (-not $env:MYSQL_PASSWORD) {
    throw "Set MYSQL_PASSWORD in this PowerShell session before running the MySQL verification."
}

$previousPassword = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $env:MYSQL_PASSWORD
    Get-Content -LiteralPath $schemaPath -Raw | & $MySqlExe "--user=$Username" --default-character-set=utf8mb4
    if ($LASTEXITCODE -ne 0) { throw "MySQL schema initialization failed." }

    # Tables and seed data are created by the services (Flyway) when they first start against these databases.
    $query = @"
SELECT table_schema AS database_name, 'yes' AS managed_by_flyway
FROM information_schema.tables
WHERE table_name = 'flyway_schema_history' AND table_schema IN ('user_db', 'knowledge_db', 'community_db', 'message_db')
GROUP BY table_schema;
"@
    & $MySqlExe "--user=$Username" --batch --table -e $query
    if ($LASTEXITCODE -ne 0) { throw "MySQL verification query failed." }
    Write-Host "Databases are ready; start the services to create or upgrade their tables." -ForegroundColor Green
} finally {
    $env:MYSQL_PWD = $previousPassword
}
