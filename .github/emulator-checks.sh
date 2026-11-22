#!/usr/bin/env bash
# Runs inside the CI emulator (x86_64): the instrumented tests (the Swift bridge test, and the review screenshots), then
# the baseline profile the release build ships and cold starts of the release build. Results go to the job summary.
#
# The emulator process has exited mid-run in CI (adb offline, no output of its own). A phase that ends with the emulator
# gone is run again on a restarted emulator, up to three times. With the emulator still answering, a few failed tests
# run once more on their own, as its video decoding and UI timing have been flaky: one that passes then is a warning in
# the summary, and anything else is the code's, and final.
# The baseline profile is an optimisation, so when the emulator is lost on every try of its phase the build goes on
# without one and says so; lost tests would leave the code unchecked, and fail the run.
set -euo pipefail

gradle=(./gradlew --no-daemon -Pmarquee.abis=x86_64)
app=com.nphil.marquee
summary="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
attempts=3
rerun_limit=4
logs="${RUNNER_TEMP:-/tmp}/emulator-logs"
# What a phase returns when its last try ended with the emulator lost.
gone=75

# The tests that failed in the last Gradle run, one per line: `Class#method`, a tab, and the first line of why.
failed_tests() {
	python3 - << 'PY'
import glob
import xml.etree.ElementTree as ET

for path in glob.glob("app/build/outputs/androidTest-results/connected/**/TEST-*.xml", recursive=True):
    for case in ET.parse(path).getroot().iter("testcase"):
        failure = case.find("failure") if case.find("failure") is not None else case.find("error")
        if failure is not None:
            why = (failure.get("message") or failure.text or "").strip().splitlines()
            print(f'{case.get("classname")}#{case.get("name")}\t{why[0][:160] if why else ""}')
PY
}

# Runs the tests that failed once more, when only a few did. Succeeds only when every one of them passes.
rerun_failed_tests() {
	local failed count
	failed=$(failed_tests || true)
	count=$(printf '%s' "$failed" | grep -c . || true)
	{ [ "$count" -ge 1 ] && [ "$count" -le "$rerun_limit" ]; } || return 1
	echo "::warning title=Tests ran again::$count failed the first time"
	{
		echo "### Failed once, ran again"
		printf '%s\n' "$failed" | sed 's/^/- /; s/\t/: /'
	} | tee -a "$summary"
	"${gradle[@]}" :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=$(printf '%s\n' "$failed" | cut -f1 | paste -sd, -)"
}

run_tests() {
	adb logcat -c
	# The tests copy their review screenshots here, outside the app's own storage, so they outlive the uninstall
	# that ends the Gradle run. It is emptied first so a pull holds only this run's.
	local review=/sdcard/Download/marquee-review
	local shots=app/build/review-screenshots
	adb shell rm -rf "$review" || true
	rm -rf "$shots"
	local tests=0
	"${gradle[@]}" :app:connectedDebugAndroidTest || tests=$?
	if [ "$tests" -ne 0 ] && emulator_answers && rerun_failed_tests; then tests=0; fi
	# Pulled whether the tests passed or not, and never a failure of its own: the tests' result is the script's.
	mkdir -p "$shots"
	adb pull "$review/." "$shots" | tail -n 1 || true
	local count
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
}

run_profile() {
	# The Kotlin compile daemons the Gradle runs leave behind each hold up to the 3 GB Gradle gets, next to the
	# emulator's 4 GB, so this run compiles in the Gradle process instead.
	pkill -f KotlinCompileDaemon || true
	"${gradle[@]}" -Pkotlin.compiler.execution.strategy=in-process :app:generateReleaseBaselineProfile

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
}

emulator_answers() {
	timeout 20 adb shell true > /dev/null 2>&1
}

# What the host looked like when the emulator went, for whoever reads the log. The logs are kept for the artifact.
host_state() {
	touch "$logs/lost"
	echo "::group::Host after the emulator was lost"
	uptime
	free -m
	df -h / | tail -n 1
	pgrep -fa 'qemu-system|emulator' | cut -c1-160 || true
	sudo dmesg -T 2> /dev/null | tail -n 15 || true
	# A kill that is not the kernel's (systemd-oomd, a script) is noted in the journal, not in dmesg.
	sudo journalctl --since '-15 min' --no-pager 2> /dev/null | grep -i -E 'oom|killed|qemu|emulator' | tail -n 15 | cut -c1-200 || true
	ls /tmp/android-runner 2> /dev/null || true
	echo "::endgroup::"
}

# The guest's own log, from now on. The emulator leaves no message of its own when it goes.
capture_logcat() {
	mkdir -p "$logs"
	nohup adb logcat -b all -v threadtime > "$logs/logcat-$1.txt" 2>&1 &
}

# The host every 5 s while the checks run: memory, the biggest processes, and whether the emulator's process is there.
watch_host() {
	mkdir -p "$logs"
	(
		while sleep 5; do
			echo "$(date +%T) qemu=$(pgrep -f qemu-system | head -n1 || true) $(free -m | awk 'NR==2 {print "used=" $3 " available=" $7}') $(ps -eo rss=,comm= --sort=-rss | head -n 3 | tr -s ' ' | tr '\n' ';')"
		done
	) >> "$logs/host.txt" 2>&1 &
	watcher=$!
}

# Starts the AVD again with the flags the runner gave it, and waits until it has booted. Its data is wiped: after the
# emulator was killed, rm answered "Bad address" for the screenshots the first run had left on the card.
revive_emulator() {
	local sdk
	sdk=$(dirname "$(dirname "$(command -v adb)")")
	adb kill-server > /dev/null 2>&1 || true
	pkill -f 'qemu-system|emulator/emulator' || true
	sleep 2
	# shellcheck disable=SC2086
	nohup bash -c '"$@"; echo "emulator exited with status $?"' _ "$sdk/emulator/emulator" -port 5554 -avd test -wipe-data -no-snapshot $EMULATOR_OPTIONS > "$logs/emulator-again-$1.log" 2>&1 &
	timeout 120 adb wait-for-device
	timeout 300 bash -c 'until [ "$(adb shell getprop sys.boot_completed 2> /dev/null | tr -d "\r")" = 1 ]; do sleep 2; done'
	adb shell input keyevent 82
	for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
		adb shell settings put global "$scale" 0.0
	done
}

# Runs a phase in a shell of its own, so that its `set -e` holds, and again on a restarted emulator when that is what failed.
phase() {
	local name=$1 attempt status
	for attempt in $(seq 1 "$attempts"); do
		status=0
		bash "$0" --run "$name" || status=$?
		[ "$status" -ne 0 ] || return 0
		emulator_answers && return "$status"
		echo "::warning title=Emulator lost::the emulator stopped answering in the $name phase (attempt $attempt of $attempts)"
		host_state
		[ "$attempt" -lt "$attempts" ] || return "$gone"
		revive_emulator "$attempt"
		capture_logcat "again-$attempt"
	done
}

case "${1:-}" in
	--run) "run_$2" ;;
	*)
		echo "Host: $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | xargs), $(nproc) cores"
		capture_logcat first
		watch_host
		phase tests
		profile=0
		phase profile || profile=$?
		kill "$watcher" 2> /dev/null || true
		# Kept only when the emulator was lost: the artifact is for finding out why.
		[ -e "$logs/lost" ] || rm -rf "$logs"
		if [ "$profile" -eq "$gone" ]; then
			echo "::warning title=No baseline profile::the emulator was lost on every try of the profile phase; this build has none"
			echo "Baseline profile: not generated, the emulator was lost on every try. This build ships without one." >> "$summary"
		elif [ "$profile" -ne 0 ]; then
			exit "$profile"
		fi
		;;
esac
