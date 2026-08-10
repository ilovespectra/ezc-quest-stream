#!/bin/bash

cd /Users/tanny/quest/StreamApp

# Find gradle in Android SDK
GRADLE=""
if [ -f "$ANDROID_HOME/gradle/gradle-*/bin/gradle" ]; then
    GRADLE="$ANDROID_HOME/gradle/gradle-*/bin/gradle"
elif [ -f ~/Library/Android/sdk/gradle/*/bin/gradle ]; then
    GRADLE=~/Library/Android/sdk/gradle/*/bin/gradle
elif command -v gradle &> /dev/null; then
    GRADLE="gradle"
fi

if [ -z "$GRADLE" ]; then
    echo "✗ Gradle not found"
    echo "Installing via Homebrew..."
    brew install gradle
    GRADLE="gradle"
fi

echo "Building Android app..."
$GRADLE assembleDebug

if [ $? -eq 0 ]; then
    echo ""
    echo "✓ Build successful!"
    echo ""
    echo "APK location: /Users/tanny/quest/StreamApp/app/build/outputs/apk/debug/app-debug.apk"
    echo ""
    echo "To install on Quest 2:"
    echo "  adb install -r /Users/tanny/quest/StreamApp/app/build/outputs/apk/debug/app-debug.apk"
    echo ""
    echo "Then on the Quest:"
    echo "  1. Open 'EZC Quest Stream' app"
    echo "  2. Tap 'Start Stream' and grant permission"
    echo "  3. Note the IP address shown"
    echo ""
    echo "To view from Mac:"
    echo "  open http://QUEST_IP:8080/stream"
else
    echo "✗ Build failed"
    exit 1
fi
