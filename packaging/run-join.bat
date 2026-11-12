@echo off
setlocal
if "%JAVA_OPTS%"=="" set "JAVA_OPTS=-Xms64m -Xmx512m"
if not "%MPMC_DATA_DIR%"=="" (
  "%~dp0bin\mpmc.bat" join --data-dir "%MPMC_DATA_DIR%" %*
) else (
  "%~dp0bin\mpmc.bat" join %*
)
