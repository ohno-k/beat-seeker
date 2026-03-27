@echo off
set "MAVEN_HOME=%~dp0apache-maven-3.9.6"
"%MAVEN_HOME%\bin\mvn.cmd" -f "%~dp0pom.xml" compile -B > "%~dp0compile_new.log" 2>&1
echo EXIT_CODE=%ERRORLEVEL% >> "%~dp0compile_new.log"
