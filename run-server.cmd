@echo off
cd /d "%~dp0"

set "JAVA_HOME=C:\Users\drom\.jdks\openjdk-23.0.2"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo Building project...
call mvn -q package -DskipTests

if "%DB%"=="" set DB=data\javaducker.duckdb
if "%HTTP_PORT%"=="" set HTTP_PORT=8080
if "%INTAKE_DIR%"=="" set INTAKE_DIR=temp\intake

echo Starting JavaDucker Server on HTTP port %HTTP_PORT%
java -jar target\javaducker-1.0.0.jar ^
    --javaducker.db-path="%DB%" ^
    --server.port=%HTTP_PORT% ^
    --javaducker.intake-dir="%INTAKE_DIR%" %*
