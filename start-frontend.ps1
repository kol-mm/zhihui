Set-Location "$PSScriptRoot\frontend"
$env:CI = "true"
npm.cmd run dev
