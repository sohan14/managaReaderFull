#!/bin/bash

echo "========================================"
echo "Manga Voice Reader - Setup Checker"
echo "========================================"
echo ""

# Check Java version
echo "Checking Java installation..."
if command -v java &> /dev/null; then
    echo "[OK] Java is installed"
    java_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
    if [[ $java_version == 17.* ]]; then
        echo "[OK] JDK 17 detected: $java_version"
    else
        echo "[!] Warning: You have Java $java_version"
        echo "    Android Studio requires JDK 17"
        echo "    Download from: https://adoptium.net/temurin/releases/?version=17"
    fi
    echo ""
else
    echo "[X] Java NOT found!"
    echo "    Please install JDK 17 from: https://adoptium.net/temurin/releases/?version=17"
    echo ""
fi

# Check Android SDK
echo "Checking Android SDK..."
if [ "$(uname)" == "Darwin" ]; then
    # macOS
    SDK_PATH="$HOME/Library/Android/sdk"
elif [ "$(expr substr $(uname -s) 1 5)" == "Linux" ]; then
    # Linux
    SDK_PATH="$HOME/Android/Sdk"
fi

if [ -d "$SDK_PATH" ]; then
    echo "[OK] Android SDK found at: $SDK_PATH"
    echo ""
else
    echo "[X] Android SDK NOT found!"
    echo "    Please install Android Studio from: https://developer.android.com/studio"
    echo ""
fi

# Check Gradle wrapper
echo "Checking Gradle wrapper..."
if [ -f "gradlew" ]; then
    echo "[OK] Gradle wrapper found"
    chmod +x gradlew
    echo ""
else
    echo "[!] Warning: gradlew not found"
    echo "    This is OK - Android Studio will generate it"
    echo ""
fi

# Check project structure
echo "Checking project files..."
if [ -f "app/build.gradle" ]; then
    echo "[OK] Project structure looks good"
    echo ""
else
    echo "[X] Project structure incomplete!"
    echo "    Make sure you extracted the full MangaReader.zip"
    echo ""
fi

echo "========================================"
echo "Setup Check Complete!"
echo "========================================"
echo ""
echo "NEXT STEPS:"
echo "1. Fix any [X] errors above"
echo "2. Open Android Studio"
echo "3. File -> Open -> Select this folder"
echo "4. Wait for Gradle sync (10-15 minutes first time)"
echo "5. Build -> Build APK"
echo ""
echo "If you see errors, check ERROR_FIXES.md"
echo ""
