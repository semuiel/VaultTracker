@echo off
cd /d "%~dp0"
"%~dp0runtime\python.exe" "%~dp0check_local.py"
pause
