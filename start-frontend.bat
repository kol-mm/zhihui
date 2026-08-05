@echo off
setlocal
cd /d "%~dp0"
title AI Knowledge Platform - Frontend

echo Starting frontend at http://127.0.0.1:5173/ ...
echo Keep this window open while using the website.
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-frontend.ps1"
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if not "%EXIT_CODE%"=="0" echo Frontend stopped with exit code %EXIT_CODE%.
pause
exit /b %EXIT_CODE%
