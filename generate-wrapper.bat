@echo off
echo Generating Gradle Wrapper...
echo.

REM Install Gradle if not present
where gradle >nul 2>nul
if %errorlevel% neq 0 (
    echo Gradle not found. Installing via Chocolatey...
    echo Please run: choco install gradle
    echo.
    echo Or download from: https://gradle.org/releases/
    echo.
    echo After installing Gradle, run this script again.
    pause
    exit /b 1
)

REM Generate wrapper
echo Running: gradle wrapper --gradle-version 8.4
gradle wrapper --gradle-version 8.4

if %errorlevel% equ 0 (
    echo.
    echo ========================================
    echo SUCCESS! Gradle wrapper generated.
    echo ========================================
    echo.
    echo Now commit and push:
    echo   git add gradle/
    echo   git add gradlew
    echo   git add gradlew.bat
    echo   git commit -m "Add Gradle wrapper"
    echo   git push
    echo.
) else (
    echo.
    echo ERROR: Failed to generate wrapper
    echo.
)

pause
