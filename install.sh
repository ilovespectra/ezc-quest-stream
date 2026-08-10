#!/bin/bash
# EZC Quest Stream - Build and Install Script

set -e  # Exit on error

DEVICE_SERIAL="1WMHHA655K2045"
PROJECT_DIR="/Users/tanny/quest/StreamApp"

echo "=========================================="
echo " EZC Quest Stream - Build and Install"
echo "=========================================="
echo ""

# Check device
echo "[1/5] Checking device connection..."
if ! adb devices | grep -q "$DEVICE_SERIAL"; then
    echo ""
    echo "⚠️  Device not found or needs authorization"
    echo ""
    echo "REQUIRED STEPS:"
    echo "1. Make sure Quest 2 USB cable is connected"
    echo "2. On the Quest, look for an authorization prompt"
    echo "3. TAP 'Allow' to grant USB debugging access"
    echo "4. Run this script again"
    echo ""
    exit 1
fi

echo "✓ Device found: $DEVICE_SERIAL"
echo ""

# Build APK
echo "[2/5] Building Android APK..."
cd "$PROJECT_DIR"

if ! command -v gradle &> /dev/null; then
    echo "Installing Gradle..."
    brew install gradle 2>&1 | tail -5
fi

echo "Compiling..."
gradle assembleDebug 2>&1 | grep -E "BUILD|Task|error" || true

APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$APK_PATH" ]; then
    echo ""
    echo "✗ Build failed - APK not created"
    echo ""
    echo "Try building manually with Android Studio:"
    echo "  1. Open Android Studio"
    echo "  2. File → Open → $PROJECT_DIR"
    echo "  3. Build → Build APK"
    echo "  4. When done, run: adb install app/build/outputs/apk/debug/app-debug.apk"
    exit 1
fi

echo "✓ APK created: $APK_PATH"
echo ""

# Install APK
echo "[3/5] Installing APK on device..."
echo "This may take a few seconds..."

if adb -s "$DEVICE_SERIAL" install -r "$APK_PATH"; then
    echo "✓ Installation successful"
else
    echo "✗ Installation failed"
    echo "Try manual install:"
    echo "  adb install $APK_PATH"
    exit 1
fi

echo ""
echo "[4/5] Waiting for app to be ready..."
sleep 2

echo ""
echo "[5/5] Getting device IP..."

IP=$(adb -s "$DEVICE_SERIAL" shell ip route | grep -v default | awk '{print $9}' | head -1)

if [ -z "$IP" ]; then
    IP="<QUEST_IP>"
    echo "⚠️  Could not determine Quest IP automatically"
    echo "Check the app's display for IP address"
fi

echo ""
echo "=========================================="
echo " Installation complete"
echo "=========================================="
echo ""
echo "NEXT STEPS:"
echo ""
echo "1. On the Quest headset:"
echo "   - Open 'EZC Quest Stream'"
echo "   - Tap 'Start Stream'"
echo "   - Grant screen capture permission"
echo "   - Wait for streaming status in the app"
echo ""
echo "2. On your Mac, view the stream:"
if [ "$IP" != "<QUEST_IP>" ]; then
    echo "   bash /Users/tanny/quest/open-stream.sh $IP"
    echo "   or open http://$IP:8080/stream"
else
    echo "   bash /Users/tanny/quest/open-stream.sh QUEST_IP"
    echo "   (Replace QUEST_IP with the address shown in the app)"
fi
echo ""
echo "3. You can now remove the Quest and set it down."
echo ""
echo "To stop streaming: Close the app on the Quest or tap 'Stop Stream'"
echo ""
