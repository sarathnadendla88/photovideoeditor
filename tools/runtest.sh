#!/bin/bash
# Usage: tools/runtest.sh [fully.qualified.TestClass[#method]]
# Emulator only. Builds, installs, pushes fixtures (they vanish when the app is uninstalled) and runs the tests with
# `am instrument`. Never use connectedAndroidTest: it uninstalls the app and wipes the fixtures.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
[ -f "$ROOT/tools/.fixtures/p0.jpg" ] || python3 "$ROOT/tools/gen_fixtures.py"
cd "$ROOT"; ./gradlew :app:installDebug :app:installDebugAndroidTest -q 2>&1 | grep -E "^e:|FAIL|What went wrong" -A2 || true
D=/sdcard/Android/data/com.video.framora/files; adb shell mkdir -p $D
F="$ROOT/tools/.fixtures"
adb push "$F/tone.wav" $D/test_tone.wav >/dev/null; adb push "$F/beats.wav" $D/test_beats.wav >/dev/null
for p in p0 p1 p2; do adb push "$F/$p.jpg" $D/$p.jpg >/dev/null; done
[ -f "$F/test_av.mp4" ] && adb push "$F/test_av.mp4" $D/test_av.mp4 >/dev/null
RUN() { adb shell am instrument -w ${1:+-e class $1} com.video.framora.test/androidx.test.runner.AndroidJUnitRunner 2>&1; }
if [ ! -f "$F/test_av.mp4" ]; then   # build the AAC video fixture on the device once, then keep a copy
  RUN com.video.framora.FixtureBuilderTest >/dev/null
  adb pull $D/test_av.mp4 "$F/test_av.mp4" >/dev/null
fi
RUN "$1" | grep -E "^OK|FAILURES|Tests run|AssertionError|Error:|expected|failed|timed|Exception" | sort -u | head -60
