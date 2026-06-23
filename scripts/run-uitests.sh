#!/usr/bin/env bash
# run-uitests.sh — ad-hoc instrumented (connected) test runner. Runs
# connectedDebugAndroidTest for each given Gradle module path, with an inter-suite
# throttle to ease live-backend session-creation rate limits.
#
# Usage:   scripts/run-uitests.sh :examples:compose:02-standard :polymessaging [...]
# Requires: a booted emulator/device (adb devices), JDK 17 on JAVA_HOME
#           (e.g. export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home")
set -uo pipefail
cd "$(dirname "$0")/.."

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
THROTTLE="${UITEST_THROTTLE:-25}" # seconds between suites

[ "$#" -ge 1 ] || { echo "usage: $0 <:gradle:module:path> [...]"; exit 2; }
[ -x "$ADB" ] || { echo "ERROR: adb not found at $ADB (set ANDROID_HOME)."; exit 2; }
"$ADB" devices | awk '/device$/{found=1} END{exit !found}' \
  || { echo "ERROR: no booted emulator/device (adb devices)."; exit 2; }

PASS=0; FAIL=0; FAILED=""
FIRST=1
for MODULE in "$@"; do
  [ "$FIRST" -eq 1 ] && FIRST=0 || sleep "$THROTTLE"
  echo "===== UITEST $MODULE ====="
  # Go HOME and stop every example app first. Force-stopping a FOREGROUND app makes
  # the system resurrect the previous example task ("next-top-activity"), which can
  # collide with the instrumentation spawning the same package -> "Process crashed".
  "$ADB" shell input keyevent 3; sleep 1
  for p in $("$ADB" shell pm list packages | grep ai.poly.examples | sed 's/package://'); do
    "$ADB" shell am force-stop "$p"
  done
  if ./gradlew "$MODULE:connectedDebugAndroidTest" --console=plain 2>&1 | tail -5 | grep -q "BUILD SUCCESSFUL"; then
    echo "$MODULE: UITEST PASSED"; PASS=$((PASS+1))
  else
    echo "$MODULE: UITEST FAILED"; FAIL=$((FAIL+1)); FAILED="$FAILED $MODULE"
  fi
done
echo "================================"
echo "UITEST SUMMARY: $PASS passed, $FAIL failed.${FAILED:+ Failed:$FAILED}"
[ "$FAIL" -eq 0 ]
