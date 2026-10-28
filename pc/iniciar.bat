@echo off
title Cumulo Hoops - Servidor del tablero
cd /d "%~dp0"
where node >nul 2>nul
if errorlevel 1 (
  echo.
  echo  No se encontro Node.js.
  echo  Instalalo desde https://nodejs.org  ^(version LTS^) y volve a abrir este archivo.
  echo.
  pause
  exit /b
)
node server.js --abrir
pause
