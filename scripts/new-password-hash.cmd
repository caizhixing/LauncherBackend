@echo off
rem Wrapper because this machine refuses unsigned .ps1 under the default policy.
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0New-PasswordHash.ps1" %*
exit /b %ERRORLEVEL%
