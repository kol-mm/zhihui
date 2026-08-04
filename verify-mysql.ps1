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

    $query = @"
SELECT 'user_db.user' AS table_name, COUNT(*) AS row_count FROM user_db.user
UNION ALL SELECT 'knowledge_db.knowledge_category', COUNT(*) FROM knowledge_db.knowledge_category
UNION ALL SELECT 'community_db.post', COUNT(*) FROM community_db.post
UNION ALL SELECT 'message_db.faq', COUNT(*) FROM message_db.faq;
"@
    & $MySqlExe "--user=$Username" --batch --table -e $query
    if ($LASTEXITCODE -ne 0) { throw "MySQL verification query failed." }
    Write-Host "MySQL schema and seed verification passed." -ForegroundColor Green
} finally {
    $env:MYSQL_PWD = $previousPassword
}
