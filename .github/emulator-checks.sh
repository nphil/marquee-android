#!/usr/bin/env bash
# Runs inside the CI emulator (x86_64): the Swift bridge test, the baseline profile the release build
# ships, and cold starts of the release build. Results go to the job summary.
set -euo pipefail

gradle=(./gradlew --no-daemon -Pmarquee.abis=x86_64)
app=com.nphil.marquee
summary="${GITHUB_STEP_SUMMARY:-/dev/stdout}"

adb logcat -c
"${gradle[@]}" :app:connectedDebugAndroidTest
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
