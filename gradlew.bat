@echo off
set "DEFAULT_JVM_OPTS=-Xmx64m -Xms64m"
set "APP_NAME=Gradle"
set "APP_BASE_NAME=%~nx0"
set "GRADLE_HOME=%USERPROFILE%\.gradle\wrapper\dists\gradle-8.9-bin"
set "GRADLE_BIN=%GRADLE_HOME%\gradle-8.9\bin\gradle.bat"
if exist "%GRADLE_BIN%" goto run
if exist "%~dp0\gradle\wrapper\gradle-wrapper.jar" goto wrapper
echo ERROR: Could not find Gradle >&2
exit /b 1
:run
exec "%GRADLE_BIN%" %*
exit /b 0
:wrapper
java %DEFAULT_JVM_OPTS% -jar "%~dp0\gradle\wrapper\gradle-wrapper.jar" %*
exit /b 0
