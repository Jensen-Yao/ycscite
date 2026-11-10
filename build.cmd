@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\build-java.ps1" %*
exit /b %errorlevel%
