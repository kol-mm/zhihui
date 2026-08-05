$ErrorActionPreference = "Stop"

Set-Location "$PSScriptRoot\frontend"

npm.cmd run build
if ($LASTEXITCODE -ne 0) {
    throw "Frontend build failed with exit code $LASTEXITCODE."
}

node.exe .\local-server.mjs
exit $LASTEXITCODE
