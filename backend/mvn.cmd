@echo off
set "MAVEN_HOME=%~dp0apache-maven-3.9.6"
set "PATH=%MAVEN_HOME%\bin;%PATH%"
"%MAVEN_HOME%\bin\mvn.cmd" %*
