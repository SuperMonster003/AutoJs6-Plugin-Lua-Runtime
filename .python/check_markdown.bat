@echo off
cd /d "%~dp0.."
py .python/generate_markdown.py --check
exit /b %errorlevel%
