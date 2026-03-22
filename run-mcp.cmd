@echo off
set "PROJECT_ROOT=%~dp0"
if "%PROJECT_ROOT:~-1%"=="\" set "PROJECT_ROOT=%PROJECT_ROOT:~0,-1%"

set "JAVA_HOME=C:\Users\drom\.jdks\openjdk-23.0.2"
set "PATH=%JAVA_HOME%\bin;%PATH%"

jbang "%~dp0JavaDuckerMcpServer.java"
