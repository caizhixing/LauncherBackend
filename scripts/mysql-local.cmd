@echo off
rem Wrapper because this machine refuses unsigned .ps1 under the default policy
rem (Get-ExecutionPolicy reports RemoteSigned but scripts need a signature).
rem -ExecutionPolicy Bypass is applied per-invocation so the machine policy stays untouched.
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0mysql-local.ps1" %*
exit /b %ERRORLEVEL%
