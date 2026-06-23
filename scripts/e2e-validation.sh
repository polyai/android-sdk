#!/usr/bin/env bash
# e2e-validation.sh — deep E2E for the PolyMessaging Android sample ladder:
#   Part 1:  build + install + launch + screenshot all 14 samples on a booted emulator.
#   Part 2:  live instrumented flow tests on the Standard rung via
#            scripts/run-uitests.sh (connectedDebugAndroidTest).
#   Part 3:  notification-banner E2E — full instrumented suites (flow + UI Automator
#            NotificationBannerTest) on examples 03/06/07, both variants.
#   Part 3b: reboot/resume dedupe on compose 06 — driven over adb (out-of-process):
#            notify for a reply, force-stop, resume the same conversation, and assert
#            no duplicate notification for replayed messages.
#
# The API key MUST be provided via POLY_CONNECTOR_TOKEN — never hard-coded. It is patched
# into each sample's Application class and reverted on exit. (Locally, the skip-worktree
# Application files already carry the dev key, so the patch is a no-op.)
#
# Usage:  POLY_CONNECTOR_TOKEN=xxx scripts/e2e-validation.sh [--part1-only]
# Requires: a booted Android emulator, JDK 17 on JAVA_HOME.
set -uo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"
OUT_DIR="/tmp/poly-e2e-android"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"

# appId|activity|gradle module|Application file (relative)|screenshot
SAMPLES=(
  "ai.poly.examples.hello.compose|.MainActivity|:examples:compose:01-hello|examples/compose/01-hello/src/main/kotlin/ai/poly/examples/hello/compose/HelloApplication.kt|01-hello-compose.png"
  "ai.poly.examples.standard.compose|.MainActivity|:examples:compose:02-standard|examples/compose/02-standard/src/main/kotlin/ai/poly/examples/standard/compose/StandardApplication.kt|02-standard-compose.png"
  "ai.poly.examples.richcontent.compose|.MainActivity|:examples:compose:03-richcontent|examples/compose/03-richcontent/src/main/kotlin/ai/poly/examples/richcontent/compose/RichContentApplication.kt|03-richcontent-compose.png"
  "ai.poly.examples.resilience.compose|.MainActivity|:examples:compose:04-resilience|examples/compose/04-resilience/src/main/kotlin/ai/poly/examples/resilience/compose/ResilienceApplication.kt|04-resilience-compose.png"
  "ai.poly.examples.handoff.compose|.MainActivity|:examples:compose:05-handoff|examples/compose/05-handoff/src/main/kotlin/ai/poly/examples/handoff/compose/HandoffApplication.kt|05-handoff-compose.png"
  "ai.poly.examples.fullreference.compose|.MainActivity|:examples:compose:06-fullreference|examples/compose/06-fullreference/src/main/kotlin/ai/poly/examples/fullreference/compose/FullReferenceApplication.kt|06-fullreference-compose.png"
  "ai.poly.examples.playground.compose|.MainActivity|:examples:compose:07-playground|examples/compose/07-playground/src/main/kotlin/ai/poly/examples/playground/compose/PlaygroundApplication.kt|07-playground-compose.png"
  "ai.poly.examples.hello.views|.ChatActivity|:examples:views:01-hello|examples/views/01-hello/src/main/kotlin/ai/poly/examples/hello/views/HelloApplication.kt|01-hello-views.png"
  "ai.poly.examples.standard.views|.ChatActivity|:examples:views:02-standard|examples/views/02-standard/src/main/kotlin/ai/poly/examples/standard/views/StandardApplication.kt|02-standard-views.png"
  "ai.poly.examples.richcontent.views|.ChatActivity|:examples:views:03-richcontent|examples/views/03-richcontent/src/main/kotlin/ai/poly/examples/richcontent/views/RichContentApplication.kt|03-richcontent-views.png"
  "ai.poly.examples.resilience.views|.ChatActivity|:examples:views:04-resilience|examples/views/04-resilience/src/main/kotlin/ai/poly/examples/resilience/views/ResilienceApplication.kt|04-resilience-views.png"
  "ai.poly.examples.handoff.views|.ChatActivity|:examples:views:05-handoff|examples/views/05-handoff/src/main/kotlin/ai/poly/examples/handoff/views/HandoffApplication.kt|05-handoff-views.png"
  "ai.poly.examples.fullreference.views|.ChatActivity|:examples:views:06-fullreference|examples/views/06-fullreference/src/main/kotlin/ai/poly/examples/fullreference/views/FullReferenceApplication.kt|06-fullreference-views.png"
  "ai.poly.examples.playground.views|.ChatActivity|:examples:views:07-playground|examples/views/07-playground/src/main/kotlin/ai/poly/examples/playground/views/PlaygroundApplication.kt|07-playground-views.png"
)

# ---- preflight ----
if [ "${POLY_CONNECTOR_TOKEN:-}" = "" ]; then
  echo "WARNING: POLY_CONNECTOR_TOKEN not set — relying on locally-injected dev keys (skip-worktree)."
fi
[ -x "$ADB" ] || { echo "ERROR: adb not found at $ADB (set ANDROID_HOME)."; exit 2; }
DEVICE="$("$ADB" devices | awk '/device$/{print $1; exit}')"
[ -n "$DEVICE" ] || { echo "==> No emulator booted. Start one and retry."; exit 2; }
echo "==> Using device: $DEVICE"
rm -rf "$OUT_DIR"; mkdir -p "$OUT_DIR"
# UI tests are far less flaky with animations off.
"$ADB" shell settings put global window_animation_scale 0
"$ADB" shell settings put global transition_animation_scale 0
"$ADB" shell settings put global animator_duration_scale 0
"$ADB" shell wm dismiss-keyguard >/dev/null 2>&1

# ---- token patch + revert ----
PATCHED=()
restore() {
  echo "==> Reverting token edits…"
  for f in "${PATCHED[@]:-}"; do [ -f "$f.bak" ] && mv -f "$f.bak" "$f"; done
}
trap restore EXIT INT TERM
patch_app() {
  local f="$REPO_ROOT/$1"
  [ -f "$f" ] || return
  [ -n "${POLY_CONNECTOR_TOKEN:-}" ] || return
  grep -q "YOUR_API_KEY" "$f" || return   # locally-injected already — leave untouched
  cp -p "$f" "$f.bak"; PATCHED+=("$f")
  sed -i '' "s/YOUR_API_KEY/${POLY_CONNECTOR_TOKEN}/g" "$f"
}

# ---- uiautomator helpers (screencaps go stale on this emulator; the dump is truth) ----
ui_dump() { "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; "$ADB" shell cat /sdcard/ui.xml 2>/dev/null; }
# wait_ui <substring> <seconds> — poll the view hierarchy for a substring (text or resource-id)
wait_ui() {
  local deadline=$(( $(date +%s) + $2 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    ui_dump | grep -qiF "$1" && return 0
    sleep 2
  done
  return 1
}
# tap_text <substring> — tap the center of the first node whose text/desc contains it
tap_text() {
  ui_dump > /tmp/poly-ui.xml
  local xy
  xy="$(python3 - "$1" << 'PY'
import re, sys
needle = sys.argv[1].lower()
xml = open('/tmp/poly-ui.xml', encoding='utf-8', errors='ignore').read()
for node in re.findall(r'<node[^>]*>', xml):
    if needle in node.lower():
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
PY
)"
  [ -n "$xy" ] || return 1
  "$ADB" shell input tap $xy
}
# count of currently-posted notifications for a package
notif_count() { "$ADB" shell dumpsys notification 2>/dev/null | grep -c "pkg=$1"; }

echo "============================================================"
echo "PART 1: build + install + launch + screenshot (${#SAMPLES[@]} samples)"
echo "============================================================"
pass=0; fail=0
for entry in "${SAMPLES[@]}"; do
  IFS='|' read -r appId activity module appfile shot <<< "$entry"
  echo ""; echo "==> [$appId] patch + assemble + install + launch + screenshot"
  patch_app "$appfile"
  if ! ./gradlew "${module}:assembleDebug" --console=plain > "$OUT_DIR/${appId}.log" 2>&1; then
    echo "    FAIL build (see $OUT_DIR/${appId}.log)"; fail=$((fail+1)); continue
  fi
  moddir="examples/$(echo "${module#:examples:}" | tr ':' '/')"
  apk="$(find "$REPO_ROOT/$moddir" -path '*/outputs/apk/debug/*.apk' 2>/dev/null | head -1)"
  "$ADB" install -r -t "$apk" >/dev/null 2>&1
  "$ADB" shell am start -W -n "$appId/$activity" >/dev/null 2>&1
  sleep 6
  "$ADB" exec-out screencap -p > "$OUT_DIR/$shot" 2>/dev/null
  "$ADB" shell am force-stop "$appId"
  if [ -s "$OUT_DIR/$shot" ]; then echo "    PASS  -> $OUT_DIR/$shot"; pass=$((pass+1)); else echo "    FAIL  screenshot"; fail=$((fail+1)); fi
done

if [ "${1:-}" = "--part1-only" ]; then
  echo ""; echo "SUMMARY (part 1 only): $pass ok / $fail failed — artifacts in $OUT_DIR"
  exit "$fail"
fi

echo ""
echo "============================================================"
echo "PART 2: live instrumented flow tests on the Standard rung"
echo "============================================================"
if ./scripts/run-uitests.sh :examples:compose:02-standard :examples:views:02-standard \
     > "$OUT_DIR/part2-flow.log" 2>&1; then
  echo "    PASS  Standard flow suites (log: $OUT_DIR/part2-flow.log)"
else
  echo "    FAIL  Standard flow suites (log: $OUT_DIR/part2-flow.log)"; fail=$((fail+1))
fi

echo ""
echo "============================================================"
echo "PART 3: notification-banner E2E suites (03/06/07 × compose+views)"
echo "============================================================"
if ./scripts/run-uitests.sh \
     :examples:compose:03-richcontent :examples:views:03-richcontent \
     :examples:compose:06-fullreference :examples:views:06-fullreference \
     :examples:compose:07-playground :examples:views:07-playground \
     > "$OUT_DIR/part3-banner.log" 2>&1; then
  echo "    PASS  banner suites (log: $OUT_DIR/part3-banner.log)"
else
  echo "    FAIL  banner suites (log: $OUT_DIR/part3-banner.log)"; fail=$((fail+1))
fi

echo ""
echo "============================================================"
echo "PART 3b: resume dedupe on compose 06 (adb-driven)"
echo "============================================================"
PKG="ai.poly.examples.fullreference.compose"
part3b() {
  "$ADB" shell pm clear "$PKG" >/dev/null
  "$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null
  "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
  # Fresh start → connect screen → Start Chat → greeting.
  wait_ui "Start Chat" 20 && tap_text "Start Chat"
  wait_ui "Webchat" 45 || { echo "    FAIL  greeting never rendered"; return 1; }
  sleep 3
  # Send via the composer (resource-id "composer"), then background → banner should post.
  tap_text 'resource-id="'"$PKG"':id/composer"' || tap_text '"composer"' || { echo "    FAIL  composer not found"; return 1; }
  sleep 1
  "$ADB" shell input text "hello%sthere"
  "$ADB" shell input keyevent 66
  sleep 1
  "$ADB" shell input keyevent 3   # HOME — notifier posts when the app is not visible
  local deadline=$(( $(date +%s) + 90 )) n=0
  while [ "$(date +%s)" -lt "$deadline" ]; do
    n="$(notif_count "$PKG")"; [ "$n" -ge 1 ] && break; sleep 3
  done
  [ "$n" -ge 1 ] || { echo "    FAIL  no notification posted for the agent reply"; return 1; }
  "$ADB" exec-out screencap -p > "$OUT_DIR/3b-banner.png" 2>/dev/null
  echo "    OK    launch 1 notified ($n notification(s)) — relaunching to resume"
  # Relaunch (force-stop clears the app's notifications) and RESUME the same session.
  "$ADB" shell am force-stop "$PKG"; sleep 2
  "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
  wait_ui "Resume Chat" 20 && tap_text "Resume Chat" || { echo "    FAIL  Resume Chat not offered"; return 1; }
  wait_ui "composer" 30 || true
  # History replays .agentMessage — the persisted notified-store must suppress re-banners.
  sleep 20
  local dup; dup="$(notif_count "$PKG")"
  "$ADB" exec-out screencap -p > "$OUT_DIR/3b-resume.png" 2>/dev/null
  if [ "$dup" -eq 0 ]; then echo "    PASS  resume did not re-notify replayed messages"; return 0
  else echo "    FAIL  resume re-notified ($dup duplicate notification(s))"; return 1; fi
}
if part3b; then :; else fail=$((fail+1)); fi
"$ADB" shell am force-stop "$PKG"

echo ""
echo "============================================================"
echo "SUMMARY: $fail part(s) failed — artifacts in $OUT_DIR"
echo "============================================================"
exit "$fail"
