@echo off

echo 1. Closing Java application processes...
:: This kills all running Java processes (Producer and Consumer)
taskkill /F /IM java.exe >nul 2>&1
:: This kills any lingering Maven processes
taskkill /F /IM mvn.cmd >nul 2>&1



