#!/usr/bin/env bash
# build-all.sh — assemble every sample module.
# Informational tally; use verify.sh as the gate. Run with JDK 17 on JAVA_HOME.
set -uo pipefail
cd "$(dirname "$0")/.."

# Read from settings.gradle.kts so this list can't drift from the real modules.
MODULES=()
while IFS= read -r module; do
  MODULES+=("$module")
done < <(sed -n 's/^include("\(:examples:[^"]*\)").*/\1/p' settings.gradle.kts)

if [ "${#MODULES[@]}" -eq 0 ]; then
  echo "No sample modules found in settings.gradle.kts."
  exit 1
fi

mkdir -p build-logs
pass=0; fail=0; failed=()
for m in "${MODULES[@]}"; do
  name="$(echo "$m" | tr ':' '_')"
  echo "==> assembleDebug $m"
  if ./gradlew "${m}:assembleDebug" --console=plain > "build-logs/${name}.log" 2>&1; then
    echo "    PASS  $m"; pass=$((pass+1))
  else
    echo "    FAIL  $m  (see build-logs/${name}.log)"; fail=$((fail+1)); failed+=("$m")
  fi
done

echo ""
echo "SUMMARY: $pass succeeded, $fail failed."
[ "$fail" -gt 0 ] && printf '  failed: %s\n' "${failed[@]}"
exit 0
