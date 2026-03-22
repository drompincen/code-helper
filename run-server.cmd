@echo off
cd /d "%~dp0"

set "JAVA_HOME=C:\Users\drom\.jdks\openjdk-23.0.2"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not exist "target\javaducker-1.0.0.jar" (
    echo Building project...
    call mvn -q package -DskipTests
)

if "%DB%"=="" set DB=data\javaducker.duckdb
if "%GRPC_PORT%"=="" set GRPC_PORT=9090
if "%INTAKE_DIR%"=="" set INTAKE_DIR=temp\intake

echo Starting JavaDucker Server on gRPC port %GRPC_PORT%
java -jar target\javaducker-1.0.0.jar ^
    --javaducker.db-path="%DB%" ^
    --grpc.server.port=%GRPC_PORT% ^
    --javaducker.intake-dir="%INTAKE_DIR%" %*
