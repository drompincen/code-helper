@echo off
cd /d "%~dp0"

set "JAVA_HOME=C:\Users\drom\.jdks\openjdk-23.0.2"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if "%HTTP_PORT%"=="" set HTTP_PORT=8080
if "%JAVADUCKER_HOST%"=="" set JAVADUCKER_HOST=localhost

if not exist "target\classes\com\javaducker\cli\InteractiveCli.class" (
    echo Compiling...
    call mvn -q compile -DskipTests
)

if not exist "target\dependency" (
    echo Copying dependencies...
    call mvn -q dependency:copy-dependencies
)

java -cp "target\classes;target\dependency\*" ^
    com.javaducker.cli.InteractiveCli --host %JAVADUCKER_HOST% --port %HTTP_PORT% %*
