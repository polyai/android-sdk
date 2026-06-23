#!/usr/bin/env bash
# verify.sh — local ship-readiness gate (NOT customer-facing): SDK unit + resilience tests,
# then assemble all sample apps. The only script that returns a meaningful exit code.
# Run with JDK 17 on JAVA_HOME.
set -uo pipefail
cd "$(dirname "$0")/.."
FAIL=0

echo "============================================================"
echo "VERIFY 1/3: SDK unit + resilience tests + apiCheck"
echo "============================================================"
if ./gradlew :polymessaging:test :polymessaging:apiCheck --console=plain 2>&1 | tail -4 | grep -q "BUILD SUCCESSFUL"; then
  echo "    PASS  unit tests + apiCheck green"
else
  echo "    FAIL  unit tests / apiCheck"; FAIL=1
fi

echo "============================================================"
echo "VERIFY 2/3: lint"
echo "============================================================"
if ./gradlew :polymessaging:lint --console=plain 2>&1 | tail -4 | grep -q "BUILD SUCCESSFUL"; then
  echo "    PASS  lint clean"
else
  echo "    FAIL  lint"; FAIL=1
fi

echo "============================================================"
echo "VERIFY 3/3: assemble all sample apps"
echo "============================================================"
if ./scripts/build-all.sh 2>&1 | tail -2 | grep -q "0 failed"; then
  echo "    PASS  all samples built"
else
  echo "    FAIL  sample assembly"; FAIL=1
fi

echo ""
[ "$FAIL" -eq 0 ] && echo "VERIFY: ALL GREEN" || echo "VERIFY: FAILURES ABOVE"
exit "$FAIL"
