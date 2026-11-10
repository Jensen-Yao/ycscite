@echo off
rem Ycscite launcher (GUI + CLI). See README.md for usage.
setlocal
set JAVA_CMD=
if exist "D:\JDK\jdk-21-temurin\bin\java.exe" set "JAVA_CMD=D:\JDK\jdk-21-temurin\bin\java.exe"
if not defined JAVA_CMD if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_CMD set "JAVA_CMD=java"
set "YCS_HOME=%~dp0"
if "%~1"=="" (
  "%JAVA_CMD%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "%YCS_HOME%out" ycscite.Main
) else if /i "%~1"=="gui" (
  "%JAVA_CMD%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "%YCS_HOME%out" ycscite.Main
) else (
  "%JAVA_CMD%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "%YCS_HOME%out" ycscite.Cli %*
)
