#!/usr/bin/env bash
set -e

echo "Building CMOS Remote APK..."
./gradlew assembleDebug

echo ""
echo "Build complete!"
echo ""
echo "APK location: app/build/outputs/apk/debug/app-debug.apk"
