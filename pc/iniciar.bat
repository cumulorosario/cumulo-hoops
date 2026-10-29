@echo off
title Cumulo Hoops - Servidor del tablero
cd /d "%~dp0"
chcp 65001 >nul
where java >nul 2>nul
if errorlevel 1 (
  echo.
  echo  No se encontro Java.
  echo  Instalalo desde https://adoptium.net  ^(Temurin 17 o superior^) y volve a abrir este archivo.
  echo.
  pause
  exit /b
)
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar cumulo-hoops-pc.jar --abrir
pause
