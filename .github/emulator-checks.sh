#!/usr/bin/env bash
# Runs inside the CI emulator (x86_64): the instrumented tests (the Swift bridge test, and the review
# screenshots), the baseline profile the release build ships, and cold starts of the release build. Results
# go to the job summary.
set -euo pipefail

gradle=(./gradlew --no-daemon -Pmarquee.abis=x86_64)
app=com.nphil.marquee
summary="${GITHUB_STEP_SUMMARY:-/dev/stdout}"

adb logcat -c
# The tests copy their review screenshots here, outside the app's own storage, so they outlive the uninstall
# that ends the Gradle run. It is emptied first so a pull holds only this run's.
review=/sdcard/Download/marquee-review
shots=app/build/review-screenshots
adb shell rm -rf "$review"
tests=0
"${gradle[@]}" :app:connectedDebugAndroidTest || tests=$?
# Pulled whether the tests passed or not, and never a failure of its own: the tests' result is the script's.
mkdir -p "$shots"
adb pull "$review/." "$shots" | tail -n 1 || true
count=$(find "$shots" -name '*.png' | wc -l)
[ "$count" -gt 0 ] || echo "::warning title=Review screenshots::the tests published none"
{
	echo "### Review screenshots"
	echo "$count images in the review-screenshots artifact. Emulator display: $(adb shell wm size | tr -d '\r' | paste -sd' ' -), $(adb shell wm density | tr -d '\r' | paste -sd' ' -)."
} | tee -a "$summary"
[ "$tests" -eq 0 ] || exit "$tests"
{
	echo "### Bridge test"
	echo '```'
	adb logcat -d -s MarqueeBridgeTest:I
	echo '```'
} | tee -a "$summary"

"${gradle[@]}" :app:generateReleaseBaselineProfile

"${gradle[@]}" :app:assembleRelease
adb uninstall "$app" > /dev/null 2>&1 || true
adb install -r "$(find app/build/outputs/apk/release -name '*.apk' | head -n1)"
# What a store install does: ProfileInstaller hands ART the baseline profile, then ART compiles it.
adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE "$app/androidx.profileinstaller.ProfileInstallReceiver"
adb shell am force-stop "$app"
adb shell cmd package compile -f -m speed-profile "$app"
{
	echo "### Cold start (release, x86_64 emulator)"
	echo '```'
	for _ in 1 2 3 4 5; do
		adb shell am force-stop "$app"
		sleep 2
		adb shell am start -W -n "$app/.MainActivity" | grep -E 'TotalTime|WaitTime'
	done
	echo '```'
} | tee -a "$summary"
