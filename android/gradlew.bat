@rem Gradle wrapper startup script for Windows
@echo off
setlocal
set APP_HOME=%~dp0
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

if defined JAVA_HOME goto findJavaFromJavaHome
set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% EQU 0 goto execute
echo ERROR: JAVA_HOME is not set and no java command could be found. 1>&2
exit /b 1

:findJavaFromJavaHome
set JAVA_EXE=%JAVA_HOME%\bin\java.exe
if exist "%JAVA_EXE%" goto execute
echo ERROR: JAVA_HOME points to an invalid directory: %JAVA_HOME% 1>&2
exit /b 1

:execute
"%JAVA_EXE%" -Dorg.gradle.appname=gradlew -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
