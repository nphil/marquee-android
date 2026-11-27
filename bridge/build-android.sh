#!/usr/bin/env bash
# Builds libMarqueeBridge.so for one Android ABI and puts it, with every Swift runtime library it
# loads and the NDK's libc++_shared.so, stripped, in <out>/<abi>/. Gradle's buildBridge task runs it.
#
#   bridge/build-android.sh arm64-v8a app/build/generated/bridge/jniLibs
#
# Needs swift 6.4.1 on PATH, the swift-6.4.1-RELEASE_android Swift SDK and ANDROID_NDK_HOME (NDK r30).
set -euo pipefail

abi="$1"
out="$2"
case "$abi" in
	arm64-v8a) arch=aarch64 ;;
	armeabi-v7a) arch=armv7 ;;
	x86_64) arch=x86_64 ;;
	*) echo "build-android.sh: no bridge build for ABI $abi" >&2; exit 1 ;;
esac

sdk_id=swift-6.4.1-RELEASE_android
here="$(cd "$(dirname "$0")" && pwd)"
# SwiftPM's default place for Swift SDKs, unless SWIFT_SDKS_PATH names another (CI does).
sdks="${SWIFT_SDKS_PATH:-$HOME/.swiftpm/swift-sdks}"
sdk_root="$sdks/$sdk_id.artifactbundle/swift-android"
runtime="$sdk_root/swift-resources/usr/lib/swift-$arch/android"
llvm="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point at NDK r30}/toolchains/llvm/prebuilt/linux-x86_64"

# API 28 is the oldest the Swift SDK's Foundation supports; the app's minSdk is above it.
swift_build=(swift build --package-path "$here" --build-system native --swift-sdks-path "$sdks" --swift-sdk "$sdk_id"
	--triple "$arch-unknown-linux-android28" -c release
	-Xlinker -L"$sdk_root/swift-resources/usr/lib/swift_static-$arch/android")
"${swift_build[@]}"
bin="$("${swift_build[@]}" --show-bin-path)"

dest="$out/$abi"
rm -rf "$dest"
mkdir -p "$dest"
cp "$bin/libMarqueeBridge.so" "$dest/"

# Follow DT_NEEDED from the bridge: what the Swift SDK ships is copied, libc++_shared.so comes from
# the NDK, and everything else (libc, libm, libdl, liblog) is part of Android.
queue=("$dest/libMarqueeBridge.so")
while ((${#queue[@]})); do
	lib="${queue[0]}"
	queue=("${queue[@]:1}")
	while read -r needed; do
		[[ -e "$dest/$needed" ]] && continue
		if [[ -e "$runtime/$needed" ]]; then
			cp "$runtime/$needed" "$dest/"
			queue+=("$dest/$needed")
		elif [[ "$needed" == libc++_shared.so ]]; then
			libdir="$arch-linux-android"
			[[ "$abi" != armeabi-v7a ]] || libdir=arm-linux-androideabi
			cp "$llvm/sysroot/usr/lib/$libdir/libc++_shared.so" "$dest/"
		fi
	done < <("$llvm/bin/llvm-readelf" --needed-libs "$lib" | sed -n 's/^ *\(lib[^ ]*\.so\)$/\1/p')
done

"$llvm/bin/llvm-strip" --strip-unneeded "$dest"/*.so
