@echo off
setlocal

if defined NACOS_HOME (
  set "NACOS_DIR=%NACOS_HOME%"
) else if exist "D:\software\nacos-server-2.3.2\nacos\bin\startup.cmd" (
  set "NACOS_DIR=D:\software\nacos-server-2.3.2\nacos"
) else (
  echo Nacos not found.
  echo Set NACOS_HOME to your Nacos root directory, for example:
  echo   set NACOS_HOME=D:\software\nacos-server-2.3.2\nacos
  pause
  exit /b 1
)

cd /d "%NACOS_DIR%\bin"
call startup.cmd -m standalone
pause
