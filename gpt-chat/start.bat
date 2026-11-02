@echo off
cd /d "%~dp0"
where node >nul 2>nul || (echo [!] Node.js is not installed. Install Node.js LTS from https://nodejs.org and run again. & pause & exit /b 1)
where codex >nul 2>nul || (echo Installing Codex CLI... & call npm i -g @openai/codex)
where claude >nul 2>nul || (echo Installing Claude Code CLI... & call npm i -g @anthropic-ai/claude-code)
start "" http://localhost:3939
node server.js
pause
