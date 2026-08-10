#!/bin/bash

set -e

# Auto-discover Quest IP from connected device
DEVICE=$(adb devices | grep -w device | head -1 | awk '{print $1}')

if [ -z "$DEVICE" ]; then
    echo "❌ No Quest device found"
    echo "Make sure your Quest is connected via USB and USB debugging is enabled"
    exit 1
fi

echo "🔍 Discovering IP on device $DEVICE..."
IP=$(adb -s "$DEVICE" shell ip route | grep -v default | awk '{print $9}' | head -1)

if [ -z "$IP" ]; then
    echo "❌ Could not determine Quest IP"
    echo "Check that Quest has a network connection"
    exit 1
fi

URL="http://$IP:8080"
echo "✅ Opening stream at $URL"
sleep 1
open "$URL"
