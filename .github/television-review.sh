#!/usr/bin/env bash
# Runs on an Android TV emulator with the test-only Plex account. No real sign-in or server is used.
set -euo pipefail
out=app/build/tv-review
mkdir -p "$out"
apk=$(find app/build/outputs/apk/nonMinifiedRelease -name '*.apk' -print -quit)
pkg=$("$ANDROID_HOME/build-tools/37.0.0/aapt" dump badging "$apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
adb install -r "$apk"
adb shell am start -W -n "$pkg/com.nphil.marquee.television.TelevisionActivity"
wait_for() {
  local word=$1
  for (( i=0; i<20; i++ )); do
    if adb shell uiautomator dump /sdcard/window.xml >/dev/null && adb shell cat /sdcard/window.xml >"$out/window.xml" && grep -Fq "text=\"$word\"" "$out/window.xml"; then return 0; fi
    sleep 1
  done
  echo "Did not reach $word" >&2; return 1
}
key() { adb shell input keyevent "$1"; }
capture() { adb exec-out screencap -p >"$out/$1.png"; }
trap 'if [ "$?" -ne 0 ]; then capture failure; adb logcat -b crash -d >"$out/crash.txt"; fi' EXIT
wait_for 'Continue Watching'
capture home
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_CENTER
wait_for A
key KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_CENTER
capture search
key KEYCODE_BACK
wait_for 'Continue Watching'
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_CENTER
wait_for Resume
capture details
key KEYCODE_BACK
wait_for 'Continue Watching'
adb shell dumpsys gfxinfo "$pkg" reset >/dev/null
for direction in RIGHT LEFT RIGHT LEFT; do key "KEYCODE_DPAD_$direction"; done
adb shell dumpsys gfxinfo "$pkg" framestats >"$out/emulator-framestats.txt"
