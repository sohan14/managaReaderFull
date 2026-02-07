@echo off
echo ========================================
echo Manga Voice Reader - Setup Checker
echo ========================================
echo.

REM Check Java version
echo Checking Java installation...
java -version 2>nul
if %errorlevel% neq 0 (
    echo [X] Java NOT found!
    echo    Please install JDK 17 from: https://adoptium.net/temurin/releases/?version=17
    echo.
) else (
    echo [OK] Java is installed
    java -version 2>&1 | findstr /i "17"
    if %errorlevel% neq 0 (
        echo [!] Warning: You might not have JDK 17
        echo    Android Studio requires JDK 17
    ) else (
        echo [OK] JDK 17 detected
    )
    echo.
)

REM Check Android Studio
echo Checking Android Studio...
if exist "%LOCALAPPDATA%\Android\Sdk" (
    echo [OK] Android SDK found at: %LOCALAPPDATA%\Android\Sdk
    echo.
) else (
    echo [X] Android SDK NOT found!
    echo    Please install Android Studio from: https://developer.android.com/studio
    echo.
)

REM Check Gradle wrapper
echo Checking Gradle wrapper...
if exist "gradlew.bat" (
    echo [OK] Gradle wrapper found
    echo.
) else (
    echo [!] Warning: gradlew.bat not found
    echo    This is OK - Android Studio will generate it
    echo.
)

REM Check project structure
echo Checking project files...
if exist "app\build.gradle" (
    echo [OK] Project structure looks good
    echo.
) else (
    echo [X] Project structure incomplete!
    echo    Make sure you extracted the full MangaReader.zip
    echo.
)

echo ========================================
echo Setup Check Complete!
echo ========================================
echo.
echo NEXT STEPS:
echo 1. Fix any [X] errors above
echo 2. Open Android Studio
echo 3. File -^> Open -^> Select this folder
echo 4. Wait for Gradle sync (10-15 minutes first time)
echo 5. Build -^> Build APK
echo.
echo If you see errors, check ERROR_FIXES.md
echo.
pause
