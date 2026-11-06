@echo off
REM ============================================================
REM  Gym project Maven wrapper.
REM
REM  Why this file exists:
REM    The "mvn" script fails under Git Bash / MSYS because path
REM    conversion turns "D:/apache-maven-3.6.3" into "/d/apache-maven-3.6.3",
REM    so the Java launcher cannot find plexus-classworlds.
REM    This wrapper invokes the launcher directly with real Windows paths.
REM
REM  Usage:
REM    build.bat clean
REM    build.bat compile
REM    build.bat package
REM    build.bat spring-boot:run -pl gym-web
REM
REM  Prerequisite: JDK 17+ (this machine has JDK 21) and the JDK 8
REM  default on PATH cannot compile records, so JAVA_HOME is pinned here.
REM ============================================================
setlocal

set JAVA_HOME=C:\Program Files\Java\jdk-21.0.11
set MAVEN_HOME=D:\apache-maven-3.6.3
set MAVEN_OPTS=-Xmx1024m -Dfile.encoding=UTF-8

"%JAVA_HOME%\bin\java.exe" ^
  -classpath "%MAVEN_HOME%\boot\plexus-classworlds-2.6.0.jar" ^
  -Dclassworlds.conf="%MAVEN_HOME%\bin\m2.conf" ^
  -Dmaven.home="%MAVEN_HOME%" ^
  -Dmaven.multiModuleProjectDirectory="%~dp0." ^
  org.codehaus.plexus.classworlds.launcher.Launcher -B %*

endlocal
