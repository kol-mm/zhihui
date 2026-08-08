param(
    [switch]$WithDevFrontend,
    [switch]$SkipBuild,
    [switch]$SkipSmokeTest
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot

if ([string]::IsNullOrWhiteSpace([string]$env:MYSQL_PASSWORD)) {
    throw "Set MYSQL_PASSWORD in the current PowerShell session before starting 4 GB mode."
}

# Keep the existing service boundaries, but remove discovery and object-storage daemons.
if ([string]::IsNullOrWhiteSpace([string]$env:SPRING_PROFILES_ACTIVE)) {
    $env:SPRING_PROFILES_ACTIVE = "mysql"
}
$env:SPRING_CLOUD_NACOS_DISCOVERY_ENABLED = "false"
$env:USER_SERVICE_URL = "http://127.0.0.1:8101"
$env:KNOWLEDGE_SERVICE_URL = "http://127.0.0.1:8102"
$env:COMMUNITY_SERVICE_URL = "http://127.0.0.1:8103"
$env:MESSAGE_SERVICE_URL = "http://127.0.0.1:8104"
$env:KNOWLEDGE_STORAGE_MODE = "local"
$env:USER_AVATAR_STORAGE_MODE = "local"
$env:COMMUNITY_STORAGE_MODE = "local"
$env:AI_VECTOR_MODE = "local"
$env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = "4"
$env:SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE = "1"
$env:SERVER_TOMCAT_THREADS_MAX = "40"
$env:SERVER_TOMCAT_THREADS_MIN_SPARE = "4"

if ([string]::IsNullOrWhiteSpace([string]$env:JAVA_TOOL_OPTIONS)) {
    $env:JAVA_TOOL_OPTIONS = "-Xms64m -Xmx384m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC"
}

foreach ($directory in @("data\uploads", "data\knowledge-media", "data\community-media", "data\user-avatars")) {
    New-Item -ItemType Directory -Force -Path (Join-Path $Root $directory) | Out-Null
}

$skipFrontend = -not $WithDevFrontend
& (Join-Path $Root "start-local.ps1") `
    -SkipNacos -SkipMinio -SkipBuild:$SkipBuild -SkipFrontend:$skipFrontend -SkipSmokeTest:$SkipSmokeTest
exit $LASTEXITCODE
