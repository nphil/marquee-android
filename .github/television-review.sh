#!/usr/bin/env bash
# Runs on an Android TV emulator with the test-only Plex account. No real sign-in or server is used.
set -euo pipefail
out=app/build/tv-review
mkdir -p "$out"
apk=$(find app/build/outputs/apk/nonMinifiedRelease -name '*.apk' -print -quit)
pkg=$("$ANDROID_HOME/build-tools/37.0.0/aapt" dump badging "$apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
[[ $pkg == com.nphil.marquee.tv.review && $(adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || { echo "Offline review emulator required" >&2; exit 1; }
adb install -r "$apk"
wait_for() {
  local word=$1 attribute=${2:-text}
  for (( i=0; i<20; i++ )); do
    if adb shell uiautomator dump /sdcard/window.xml >/dev/null && adb shell cat /sdcard/window.xml >"$out/window.xml" && python3 - "$out/window.xml" "$attribute" "$word" <<'PY'
import sys, xml.etree.ElementTree as E
root = E.parse(sys.argv[1])
attr, word = sys.argv[2:]
labels = word.split("|")
def labelled(n):
    return any(c.get("text") in labels or c.get("content-desc") in labels for c in n.iter("node"))
if attr in ("selected-text", "select-right", "select-left", "select-down"):
    # Android projects Compose's selected button as a checked, clickable parent of its label.
    found = any((n.get("selected") == "true" or (n.get("checkable") == "true" and n.get("checked") == "true"))
                and labelled(n) for n in root.iter("node"))
elif attr in ("focused-text", "focus-down", "focus-right", "focus-up"):
    found = any(n.get("focused") == "true" and labelled(n) for n in root.iter("node"))
elif attr in ("focused-editor", "editor-down"):
    found = any(n.get("focused") == "true" and n.get("class") == "android.widget.EditText" and labelled(n)
                for n in root.iter("node"))
elif attr == "absent":
    found = not any(n.get("text") == word for n in root.iter("node"))
elif attr == "text-prefix":
    found = any(n.get("text", "").startswith(word) for n in root.iter("node"))
else:
    found = any(n.get(attr) == word for n in root.iter("node"))
raise SystemExit(not found)
PY
    then return 0; fi
    # The emulator launcher can interrupt a fresh app launch with its one-time Shop tour.
    if [[ $word == 'Browse the shelves' ]] && grep -Fq 'package="com.google.android.tvlauncher"' "$out/window.xml" &&
       grep -Fq 'text="Buy and rent movies on your TV"' "$out/window.xml" && grep -Fq 'text="Dismiss"' "$out/window.xml"; then
      key KEYCODE_BACK
      adb shell am start -W -n "$pkg/com.nphil.marquee.television.TelevisionActivity" >/dev/null
    fi
    case $attribute in
      focus-down|editor-down|select-down) key KEYCODE_DPAD_DOWN ;;
      focus-up) key KEYCODE_DPAD_UP ;;
      focus-right|select-right) key KEYCODE_DPAD_RIGHT ;;
      select-left) key KEYCODE_DPAD_LEFT ;;
    esac
    sleep 1
  done
  echo "Did not reach $word ($attribute)" >&2; return 1
}
key() { adb shell input keyevent "$1"; }
capture() { adb exec-out screencap -p >"$out/$1.png"; }
open_settings() {
  key KEYCODE_DPAD_RIGHT
  key KEYCODE_MENU
  wait_for 'Play from Start'
  key KEYCODE_DPAD_DOWN
  key KEYCODE_DPAD_CENTER
  wait_for 'Video & Playback'
}
hide_ime() {
  if adb shell dumpsys input_method | grep 'mInputShown=true' >/dev/null; then key KEYCODE_BACK; fi
}
seerr_address() {
  open_settings
  wait_for Seerr focus-down
  key KEYCODE_DPAD_CENTER
  wait_for Enabled focus-down
  key KEYCODE_DPAD_CENTER
  wait_for Address focused-editor
  adb shell input text seerr.local
  hide_ime
}
home_shortcut() {
  key KEYCODE_DPAD_UP
  wait_for Search select-left
  wait_for "$1" select-right
  key KEYCODE_DPAD_CENTER
}
verified_seerr_home() {
seerr_address
wait_for 'Plex Profile|API Key' focus-down
wait_for 'API Key' focus-right
key KEYCODE_DPAD_CENTER
wait_for 'API Key' editor-down
adb shell input text fixtureOnly12345678901234567890
hide_ime
wait_for 'Test Connection' focus-down
wait_for 'Connected · Seerr 2.0.0, signed in as Jordan.'
capture seerr-verified
key KEYCODE_BACK
key KEYCODE_BACK
wait_for 'Continue Watching'
}
journey() {
case "$1" in
watchlist)
capture home
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_CENTER
wait_for 'Sort & Filter'
key KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_CENTER
wait_for 'Spring'
wait_for 'On'
! grep -Fq 'text="Wing It!"' "$out/window.xml" || exit 1
capture watchlist-released
key KEYCODE_DPAD_CENTER
wait_for 'Wing It!'
key KEYCODE_DPAD_LEFT
key KEYCODE_DPAD_LEFT
key KEYCODE_DPAD_CENTER
wait_for 'Show'
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_CENTER
wait_for 'Caminandes'
! grep -Fq 'text="Wing It!"' "$out/window.xml" || exit 1
capture watchlist-shows
;;
series)
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_RIGHT
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
;;
library)
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_RIGHT
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
key KEYCODE_DPAD_LEFT
key KEYCODE_DPAD_CENTER
wait_for 'Big Buck Bunny'
capture library-watched
;;
source-title)
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_CENTER
wait_for A
key KEYCODE_DPAD_CENTER
key KEYCODE_DPAD_CENTER
wait_for 'Search other servers' select-down
key KEYCODE_DPAD_CENTER
wait_for 'Aardvark' select-down
key KEYCODE_DPAD_CENTER
wait_for 'Switch to Cabin'
capture source-confirm
wait_for 'Switch to Cabin' select-down
key KEYCODE_DPAD_CENTER
wait_for 'Aardvark: Cabin Edition'
capture source-title
;;
people)
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_CENTER
wait_for A
key KEYCODE_DPAD_CENTER
for i in {1..11}; do key KEYCODE_DPAD_RIGHT; done
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
;;
details)
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_CENTER
wait_for Resume
wait_for 'Sintel' content-desc
capture details
wait_for 'Trailer preview' content-desc
key KEYCODE_DPAD_UP
key KEYCODE_MEDIA_PAUSE
wait_for 'Sintel Trailer'
wait_for 'Paused · ' text-prefix
expected=$(python3 - "$out/window.xml" <<'PY'
import sys, xml.etree.ElementTree as E
line = next(n.get("text") for n in E.parse(sys.argv[1]).iter("node") if n.get("text", "").startswith("Paused · "))
at, total = line.removeprefix("Paused · ").split(" / ")
def seconds(clock):
    m, s = map(int, clock.split(":"))
    return 60*m + s
# The isolated 146-second clip has no hours and must have room for a full seek.
t = seconds(at) + 5
assert t < seconds(total)
print(f"Paused · {t//60}:{t%60:02d} / {total}")
PY
)
key KEYCODE_DPAD_RIGHT
wait_for "$expected"
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
;;
playback)
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
;;
settings)
open_settings
capture settings
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_CENTER
wait_for 'Blend with Video'
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
;;
account-services)
open_settings
for _ in 1 2 3 4 5 6 7; do key KEYCODE_DPAD_DOWN; done
key KEYCODE_DPAD_CENTER
wait_for Alex
wait_for Restricted
capture profiles
key KEYCODE_BACK
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_CENTER
wait_for 'Living Room'
capture servers
key KEYCODE_BACK
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_CENTER
wait_for Enabled
capture seerr-setup
key KEYCODE_BACK
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_CENTER
wait_for 'API Key'
capture bazarr-setup
key KEYCODE_BACK
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_CENTER
wait_for 'Test Connection'
wait_for 'SpeechDock'
! grep -Fq 'text="API Key"' "$out/window.xml" || exit 1
capture speechdock-setup
;;
restricted-profile)
open_settings
wait_for Profiles focus-down
key KEYCODE_DPAD_CENTER
wait_for Kids focus-down
key KEYCODE_DPAD_CENTER
wait_for 'Browse the shelves'
key KEYCODE_BACK
wait_for 'Continue Watching'
open_settings
wait_for Servers focus-down
key KEYCODE_DPAD_DOWN
wait_for 'Remote tour' focused-text
if grep -Eq 'text="(Integrations|Seerr|Bazarr|SpeechDock|Diagnostics)"' "$out/window.xml"; then exit 1; fi
capture restricted-settings
;;
seerr-permission)
seerr_address
wait_for 'Test Connection' focus-down
wait_for "Seerr doesn't let this Plex profile in." text-prefix
capture seerr-profile-denied
key KEYCODE_BACK
key KEYCODE_BACK
wait_for 'Continue Watching'
if grep -Fq 'text="Requests"' "$out/window.xml"; then exit 1; fi
;;
seerr-requests)
verified_seerr_home
home_shortcut Requests
wait_for 'My Requests'
# Entering Requests may restore focus to a Discover poster below the search field.
wait_for Back focus-up
wait_for 'Search movies and series' editor-down
adb shell input text Wing
hide_ime
wait_for 'Wing It!, 2023' focus-down
capture seerr-search
key KEYCODE_DPAD_CENTER
wait_for Request focus-down
key KEYCODE_DPAD_CENTER
wait_for 'Request Wing It!'
capture seerr-request-confirmation
wait_for Request focus-down
key KEYCODE_DPAD_CENTER
wait_for 'Pending · Requested by Jordan'
capture seerr-pending
;;
seerr-disable)
verified_seerr_home
wait_for Requests
home_shortcut Settings
wait_for Seerr focus-down
key KEYCODE_DPAD_CENTER
wait_for Enabled focus-down
key KEYCODE_DPAD_CENTER
key KEYCODE_BACK
key KEYCODE_BACK
wait_for 'Continue Watching'
key KEYCODE_DPAD_UP
wait_for Settings select-right
if grep -Fq 'text="Requests"' "$out/window.xml"; then exit 1; fi
capture seerr-disabled
;;
subtitle-replacement|subtitle-provider)
if [[ $1 == subtitle-provider ]]; then
  open_settings
  wait_for Bazarr focus-down
  key KEYCODE_DPAD_CENTER
  wait_for Address editor-down
  adb shell input text https://bazarr.local
  hide_ime
  wait_for 'API Key' editor-down
  adb shell input text fixtureOnly12345678901234567890
  hide_ime
  wait_for 'Test Connection' focus-down
  key KEYCODE_DPAD_CENTER
  wait_for 'Connected · Bazarr' text-prefix
  key KEYCODE_BACK
  key KEYCODE_BACK
  wait_for 'Continue Watching'
else
  # open_settings already moved onto the Caminandes episode; Back preserves that tile.
  key KEYCODE_DPAD_RIGHT
fi
key KEYCODE_MENU
wait_for 'Play from Start'
wait_for 'Mark as watched'
wait_for 'Mark as unwatched'
capture partial-watch-menu
key KEYCODE_DPAD_CENTER
wait_for 'Skip Intro'
key KEYCODE_DPAD_LEFT
wait_for 'Watch Intro' selected-text
key KEYCODE_DPAD_CENTER
key KEYCODE_DPAD_RIGHT
wait_for 'Seek preview' content-desc
key KEYCODE_BACK
key KEYCODE_MEDIA_PAUSE
wait_for 'Subtitles' content-desc
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_CENTER
wait_for 'Subtitle Style'
if [[ $1 == subtitle-provider ]]; then
  wait_for 'Choose from Bazarr' select-down
  key KEYCODE_DPAD_CENTER
  wait_for 'Choose a subtitle'
  wait_for 'OpenSubtitles.com' text-prefix
  capture subtitle-provider-choices
  key KEYCODE_BACK
  wait_for 'Subtitle Style'
  return
fi
wait_for 'Find Subtitles' select-down
key KEYCODE_DPAD_CENTER
wait_for 'English subtitles on'
grep -Fq 'text="From Plex' "$out/window.xml"
capture subtitle-replacement
key KEYCODE_DPAD_CENTER
wait_for 'Remove a subtitle file…' select-down
key KEYCODE_DPAD_CENTER
wait_for 'Remove a subtitle file'
key KEYCODE_DPAD_CENTER
wait_for 'Keep file' selected-text
capture subtitle-keep-file
key KEYCODE_DPAD_CENTER
wait_for 'Subtitle Style'
;;
credits)
key KEYCODE_DPAD_RIGHT
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
# Keep the first roll onscreen while accessibility inspects the choice; the fixture's early marker lasts only 15 s.
key KEYCODE_MEDIA_PAUSE
key KEYCODE_DPAD_LEFT
wait_for 'Watch Credits' selected-text
capture credits-first
key KEYCODE_DPAD_CENTER
wait_for 'Watch Credits' absent
key KEYCODE_DPAD_RIGHT
wait_for 'Seek preview' content-desc
key KEYCODE_DPAD_UP
wait_for 'Last roll'
key KEYCODE_DPAD_CENTER
wait_for 'Next Episode'
capture credits-final
;;
esac
}

# A missing trailer must not discard the independent credits, settings or library evidence.
failed=0
if ! /usr/bin/python3 .github/television-captions.py "$out" "$pkg"; then
  echo "::error::TV caption placement failed"; failed=1
fi
for name in watchlist series library people source-title details playback settings account-services restricted-profile seerr-permission seerr-requests seerr-disable subtitle-replacement subtitle-provider credits; do
  set +e
  (
    set -e
    trap 'if [ "$?" -ne 0 ]; then capture "failure-$name"; cp "$out/window.xml" "$out/failure-$name.xml"; adb logcat -b crash -d >"$out/crash-$name.txt"; fi' EXIT
    adb shell pm clear "$pkg" >/dev/null
    adb shell am start -W -n "$pkg/com.nphil.marquee.television.TelevisionActivity" >/dev/null
    wait_for 'Browse the shelves'
    if [[ $name == watchlist ]]; then
      key KEYCODE_DPAD_RIGHT
      wait_for 'Return to the picture'
      key KEYCODE_DPAD_LEFT
      wait_for 'Browse the shelves'
      capture remote-tour
      for _ in 1 2 3 4 5; do key KEYCODE_DPAD_CENTER; done
      wait_for 'Switch profiles'
      key KEYCODE_DPAD_CENTER
      wait_for 'Continue Watching'
      adb shell am force-stop "$pkg"
      adb shell am start -W -n "$pkg/com.nphil.marquee.television.TelevisionActivity" >/dev/null
    else
      key KEYCODE_BACK
    fi
    wait_for 'Continue Watching'
    journey "$name"
  )
  status=$?
  set -e
  if ((status != 0)); then echo "::error::TV journey failed: $name"; failed=1; fi
done
exit "$failed"
