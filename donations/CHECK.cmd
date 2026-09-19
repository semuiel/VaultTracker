@echo off
cd /d "%~dp0"
"%~dp0runtime\python.exe" "%~dp0service.py" --config "%~dp0config.local.json" --check
pause
