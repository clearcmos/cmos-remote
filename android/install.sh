#!/usr/bin/env bash
set -e

echo "Installing CMOS Remote..."

if ! adb devices | grep -q "device$"; then
    echo "No device connected via ADB"
    echo "Make sure USB debugging is enabled and device is connected"
    echo ""
    echo "To connect wirelessly:"
    echo "1. Enable Wireless Debugging on your phone"
    echo "2. Run: adb pair <IP>:<PORT> <CODE>"
    echo "3. Run: adb connect <IP>:<DEBUG_PORT>"
    exit 1
fi

# Build and install
./gradlew installDebug

echo ""
echo "Installation complete!"
