#!/usr/bin/env bash
# build-all.sh — assemble every sample module.
# Informational tally; use verify.sh as the gate. Run with JDK 17 on JAVA_HOME.
set -uo pipefail
cd "$(dirname "$0")/.."

# Extend as rungs land (Compose×7 + Views×7).
MODULES=(
  ":examples:compose:01-hello"
  ":examples:views:01-hello"
)

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
