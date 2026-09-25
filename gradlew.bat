@echo off
set JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%
set GRADLE_EXE=C:\Users\prabhat\.gradle\wrapper\dists\gradle-7.5-all\6qsw290k5lz422uaf8jf6m7co\gradle-7.5\bin\gradle.bat
if not exist "%GRADLE_EXE%" (
  echo Gradle 7.5 not found, downloading Gradle 8.7...
  powershell -Command "Invoke-WebRequest -Uri https://services.gradle.org/distributions/gradle-8.7-bin.zip -OutFile $env:TEMP\gradle-8.7-bin.zip"
  powershell -Command "Expand-Archive -Force $env:TEMP\gradle-8.7-bin.zip $env:TEMP\gradle87"
  set GRADLE_EXE=%TEMP%\gradle87\gradle-8.7\bin\gradle.bat
)
call "%GRADLE_EXE%" %*
