#!/usr/bin/env bash
set -euo pipefail
apks=${1:-test-apks}
adb wait-for-device
adb install -r "$apks/Vishal-Jarvis-1.4.0-debug.apk"
adb install -r "$apks/Vishal-Jarvis-tests.apk"
# Grant only permissions needed by the fixture tests. CALL_PHONE is intercepted by
# the test launcher, so no real call is placed; READ_PHONE_STATE stays ungranted
# until the test explicitly checks the existing-grant compatibility path.
adb shell pm grant com.myraa.mobile android.permission.RECORD_AUDIO || true
adb shell pm grant com.myraa.mobile android.permission.CALL_PHONE || true
adb shell pm grant com.myraa.mobile android.permission.POST_NOTIFICATIONS || true
run_test() {
  local name="$1" marker="$2" log="/tmp/${1}.log"
  echo "--- Running $name ---"
  adb shell am instrument -w -r "com.myraa.mobile.tests/com.myraa.mobile.tests.$name" | tee "$log"
  grep -q 'INSTRUMENTATION_CODE: -1' "$log"
  if grep -qE '(^|[[:space:]])FAIL:|BACKGROUND_TESTS_FAILED' "$log"; then return 1; fi
  if [[ -n "$marker" ]]; then grep -q "$marker" "$log"; fi
}
run_test BackgroundInstrumentation BACKGROUND_TESTS_PASSED
run_test ActionsInstrumentation 'Checks:'
run_test SmokeInstrumentation 'native Activity and WebView started'
run_test AuthInstrumentation 'no live Google traffic'
