#!/usr/bin/env sh
set -eu
# Tested interface to Gradle; requires externally installed Android SDK.
cd "$(dirname "$0")"
if ! command -v gradle >/dev/null 2>&1 && [ ! -x ./gradlew ]; then
  echo 'Gradle executable not found. Open the project in Android Studio or install Gradle 8.11.1.' >&2
  exit 127
fi
if [ -x ./gradlew ]; then ./gradlew --no-daemon :app:assembleDebug; else gradle --no-daemon :app:assembleDebug; fi
