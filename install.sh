#!/bin/bash
# Build + install PixScroll to phone (auto-detects device, falls back to build-only)
set -e

export JAVA_HOME=/d/Android/jdk
export ANDROID_HOME=/d/Android/sdk

cd "$(dirname "$0")"

echo "==> Checking for connected device..."
DEVICE_COUNT=$(adb devices 2>/dev/null | grep -v "List of devices" | grep -c "device$" || echo 0)

if [ "$DEVICE_COUNT" -gt 0 ]; then
    echo "==> Device found, installing..."
    ./gradlew installDebug
else
    echo "==> No device connected, build-only mode..."
    ./gradlew assembleDebug
fi

echo "==> Done!"
