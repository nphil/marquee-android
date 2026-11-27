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
  local word=$1 attribute=${2:-text}
  for (( i=0; i<20; i++ )); do
    if adb shell uiautomator dump /sdcard/window.xml >/dev/null && adb shell cat /sdcard/window.xml >"$out/window.xml" && python3 - "$out/window.xml" "$attribute" "$word" <<'PY'
import sys, xml.etree.ElementTree as E
root = E.parse(sys.argv[1])
attr, word = sys.argv[2:]
if attr == "selected-text":
    # Android projects Compose's selected button as a checked, clickable parent of its label.
    found = any((n.get("selected") == "true" or (n.get("checkable") == "true" and n.get("checked") == "true"))
                and any(c.get("text") == word for c in n.iter("node")) for n in root.iter("node"))
else:
    found = any(n.get(attr) == word for n in root.iter("node"))
raise SystemExit(not found)
PY
    then return 0; fi
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
key KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_CENTER
wait_for 'Sort & Filter'
key KEYCODE_DPAD_CENTER
wait_for 'Show'
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_CENTER
wait_for 'Caminandes'
! grep -Fq 'text="Wing It!"' "$out/window.xml" || exit 1
capture watchlist-shows
key KEYCODE_BACK
wait_for 'Continue Watching'
key KEYCODE_DPAD_LEFT
key KEYCODE_DPAD_CENTER
wait_for 'Browse TV Shows'
key KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_CENTER
wait_for Episodes
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_CENTER
key KEYCODE_DPAD_LEFT
key KEYCODE_DPAD_CENTER
wait_for 'Big Buck Bunny'
capture library-episodes
key KEYCODE_BACK
key KEYCODE_BACK
wait_for 'Continue Watching'
key KEYCODE_DPAD_LEFT
key KEYCODE_DPAD_CENTER
wait_for 'Browse Library'
key KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_CENTER
wait_for On
key KEYCODE_BACK
key KEYCODE_DPAD_CENTER
wait_for 'Unwatched · Title · A–Z'
capture library-filter-restored
key KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_CENTER
wait_for Off
key KEYCODE_BACK
key KEYCODE_DPAD_LEFT
key KEYCODE_DPAD_CENTER
wait_for A
key KEYCODE_DPAD_CENTER
wait_for 'Search in'
for i in 1 2 3; do key KEYCODE_DPAD_DOWN; done
key KEYCODE_DPAD_CENTER
wait_for 'People'
for i in 1 2 3 4; do key KEYCODE_DPAD_DOWN; done
key KEYCODE_DPAD_CENTER
key KEYCODE_DPAD_DOWN
wait_for 'Halina Reijn'
capture search
key KEYCODE_DPAD_CENTER
wait_for 'Sintel'
capture person-library
key KEYCODE_BACK
key KEYCODE_BACK
wait_for 'Continue Watching'
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_CENTER
wait_for Resume
wait_for 'Sintel' content-desc
capture details
wait_for 'Trailer playing' content-desc
key KEYCODE_DPAD_UP
wait_for 'Trailer'
key KEYCODE_MEDIA_PAUSE
key KEYCODE_DPAD_RIGHT
capture trailer-transport
key KEYCODE_BACK
wait_for 'Resume'
wait_for 'About'
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_CENTER
wait_for 'Synopsis'
capture about
key KEYCODE_BACK
wait_for 'Resume'
key KEYCODE_BACK
wait_for 'Continue Watching'
adb shell dumpsys gfxinfo "$pkg" reset >/dev/null
for direction in RIGHT LEFT RIGHT LEFT; do key "KEYCODE_DPAD_$direction"; done
adb shell dumpsys gfxinfo "$pkg" framestats >"$out/emulator-framestats.txt"
key KEYCODE_DPAD_RIGHT
key KEYCODE_MENU
wait_for 'Play from Start'
key KEYCODE_DPAD_CENTER
wait_for 'Skip Intro'
key KEYCODE_DPAD_LEFT
wait_for 'Watch Intro' selected-text
capture intro
key KEYCODE_DPAD_CENTER
key KEYCODE_DPAD_RIGHT
wait_for 'Seek preview' content-desc
capture seek
key KEYCODE_BACK
key KEYCODE_MEDIA_PAUSE
wait_for 'Subtitles' content-desc
capture transport
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_CENTER
wait_for 'Subtitle Style'
key KEYCODE_BACK
key KEYCODE_BACK
adb shell uiautomator dump /sdcard/window.xml >/dev/null
adb shell cat /sdcard/window.xml >"$out/hidden-transport.xml"
! grep -Fq 'content-desc="Subtitles"' "$out/hidden-transport.xml" || exit 1
key KEYCODE_DPAD_DOWN
wait_for 'Subtitles' content-desc
key KEYCODE_MENU
wait_for 'Playback Info'
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_CENTER
wait_for 'Blend with Video'
key KEYCODE_DPAD_RIGHT
wait_for 'Apple TV'
capture subtitle-style
key KEYCODE_BACK
key KEYCODE_MENU
wait_for 'Playback Info'
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_CENTER
wait_for 'Apple TV'
key KEYCODE_BACK
key KEYCODE_BACK
key KEYCODE_BACK
wait_for 'Continue Watching'
key KEYCODE_MENU
wait_for 'Play from Start'
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_CENTER
wait_for 'Video & Playback'
capture settings
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_CENTER
wait_for 'Apple TV'
key KEYCODE_BACK
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_CENTER
wait_for 'Hide'
capture home-row-editor
key KEYCODE_BACK
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_CENTER
wait_for 'Sort Value'
capture under-posters
key KEYCODE_BACK
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_CENTER
wait_for 'Hide Movies'
key KEYCODE_DPAD_CENTER
wait_for 'Show Movies'
capture top-bar
key KEYCODE_BACK
key KEYCODE_BACK
wait_for 'Continue Watching'
! grep -Fq 'text="Movies"' "$out/window.xml" || { echo "Hidden movie shortcut still shown"; exit 1; }
key KEYCODE_MENU
wait_for 'Play from Start'
key KEYCODE_DPAD_CENTER
wait_for 'Skip Intro'
key KEYCODE_DPAD_CENTER
key KEYCODE_DPAD_RIGHT
wait_for 'After the opening'
key KEYCODE_DPAD_UP
wait_for 'First roll'
key KEYCODE_DPAD_CENTER
wait_for 'Watch Credits'
key KEYCODE_DPAD_LEFT
wait_for 'Watch Credits' selected-text
capture credits-first
key KEYCODE_DPAD_CENTER
key KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_UP
wait_for 'Last roll'
key KEYCODE_DPAD_CENTER
wait_for 'Next Episode'
capture credits-final
