#!/bin/bash
set -e

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
DOWNLOADS="/mnt/chromeos/MyFiles/Downloads/appProjects/PadMap"

VERSION=$(grep 'versionCode ' "$PROJECT_ROOT/app/build.gradle" | tr -d ' ' | cut -d'=' -f2)

echo "Building PadMap v$VERSION..."

echo "[1/2] Building APK..."
cd "$PROJECT_ROOT" && ./gradlew assembleDebug

echo "[2/2] Copying APK..."
cp "$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk" \
   "$DOWNLOADS/PadMap-debug-v$VERSION.apk"

echo ""
echo "Done: PadMap-debug-v$VERSION.apk"
