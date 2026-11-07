#!/usr/bin/env bash
set -euo pipefail
adb install -r apk/Vishal-Jarvis-1.1.0-private.apk
adb install -r apk/Vishal-Jarvis-tests.apk
for test in SmokeInstrumentation AuthInstrumentation ActionsInstrumentation; do
  echo "Running $test"
  output=$(timeout 300 adb shell am instrument -w "com.myraa.mobile.tests/com.myraa.mobile.tests.$test" 2>&1) || { echo "::error::Instrumentation command failed for $test"; exit 1; }
  printf '%s\n' "$output"
  if ! grep -q 'INSTRUMENTATION_CODE: -1' <<<"$output" || grep -qE 'FAIL:|INSTRUMENTATION_FAILED|Process crashed' <<<"$output"; then
    echo "::error::Instrumentation $test failed: $(printf '%s\n' "$output" | tail -12 | tr '\n' ' ')"
    exit 1
  fi
done
