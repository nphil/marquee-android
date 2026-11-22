#!/bin/bash
#
# Copyright (C) 2019 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Derived from Media3's libraries/decoder_ffmpeg/src/main/jni/build_ffmpeg.sh and CMakeLists.txt. Changed: FFmpeg is
# downloaded at a pinned version and checksum, built as shared libraries from an out-of-tree copy for the ABIs the app
# ships, and the JNI library is compiled here.
#
# Builds FFmpeg's audio decoders, LGPL only, as shared libraries, and the JNI library Media3 loads them through:
#
#   ffmpeg/build.sh arm64-v8a x86_64
#
# Needs the Android NDK in $ANDROID_NDK_HOME (CI uses r30), a host C compiler, make, curl and xz. The libraries are
# written to ffmpeg/src/main/jniLibs/<abi>/, where :ffmpeg packages them. An app built without them runs and has
# no FFmpeg decoders.
#
# The LGPL lets FFmpeg's part be replaced by a rebuilt one, so FFmpeg is linked as shared libraries (libavcodec.so,
# libavutil.so, libswresample.so) and not into libffmpegJNI.so. Nothing here passes --enable-gpl, --enable-version3 or
# --enable-nonfree, and the script stops unless configure reports "LGPL version 2.1 or later".
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=versions.properties
. "$here/versions.properties"

# Media3's FfmpegLibrary reaches ac3, eac3, dca, truehd, flac, opus, vorbis, alac, pcm_alaw and pcm_mulaw by MIME
# type. The rest (mlp and the other PCM formats) are built too, though no MIME type of Media3's reaches them yet.
decoders=(
  ac3 eac3 dca truehd mlp flac opus vorbis alac
  pcm_s16le pcm_s16be pcm_s24le pcm_s24be pcm_s32le pcm_s32be pcm_f32le pcm_f64le pcm_u8
  pcm_alaw pcm_mulaw pcm_bluray pcm_dvd
)

die() {
  echo "build.sh: $*" >&2
  exit 1
}

[ "$#" -gt 0 ] || die "usage: build.sh <abi>...  (arm64-v8a, x86_64)"
ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -d "$toolchain" ] || die "set ANDROID_NDK_HOME to a Linux x86_64 Android NDK (now: '$ndk')"

work="$here/.build"
tarball="$work/ffmpeg-$FFMPEG_VERSION.tar.xz"
src="$work/ffmpeg-$FFMPEG_VERSION"

fetch() {
  mkdir -p "$work"
  if ! { [ -f "$tarball" ] && echo "$FFMPEG_SHA256  $tarball" | sha256sum -c --status; }; then
    curl -fsSL --retry 3 -o "$tarball" "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz"
    echo "$FFMPEG_SHA256  $tarball" | sha256sum -c --status || {
      rm -f "$tarball"
      die "the FFmpeg $FFMPEG_VERSION download does not match the SHA-256 in versions.properties"
    }
  fi
  if [ ! -e "$src/.unpacked" ]; then
    rm -rf "$src"
    mkdir "$src"
    # --no-same-owner: as root (a CI container, a worker) tar would try to give the files the tarball's uid and fail.
    tar -xJf "$tarball" -C "$src" --strip-components=1 --no-same-owner
    touch "$src/.unpacked"
  fi
}

# Stops the build if the libraries are not what the licence page says they are.
check() {
  local abi="$1" dest="$2" lib dynamic needed
  for lib in "$dest"/*.so; do
    dynamic="$("$toolchain/llvm-readelf" -d "$lib")"
    needed="$(grep -E '\(NEEDED\)|\(SONAME\)' <<<"$dynamic" || true)"
    # A NEEDED entry such as libavcodec.so.60 would name a file the APK does not contain.
    ! grep -qE '\.so\.[0-9]|c\+\+_shared' <<<"$needed" || die "$abi: $(basename "$lib") names a library the APK lacks: $needed"
    ! grep -q '(TEXTREL)' <<<"$dynamic" || die "$abi: $(basename "$lib") has text relocations, which Android refuses to load"
    # Devices with 16 KB memory pages load a library only if its segments are aligned to 16 KB.
    [ -z "$("$toolchain/llvm-readelf" -lW "$lib" | awk '$1 == "LOAD" && $NF != "0x4000"')" ] ||
      die "$abi: $(basename "$lib") is not aligned for 16 KB pages"
  done
  for lib in libavcodec libavutil libswresample; do
    grep -q "Shared library: \[$lib.so\]" <<<"$("$toolchain/llvm-readelf" -d "$dest/libffmpegJNI.so")" ||
      die "$abi: libffmpegJNI.so does not load $lib.so"
  done
  "$toolchain/llvm-nm" -D --defined-only "$dest/libffmpegJNI.so" | grep -q ' JNI_OnLoad$' ||
    die "$abi: libffmpegJNI.so does not export JNI_OnLoad"
  [ "$("$toolchain/llvm-nm" -D --defined-only "$dest/libavcodec.so" | grep -c ' ff_' || true)" = 0 ] ||
    die "$abi: libavcodec.so exports FFmpeg's internals"
}

build() {
  local abi="$1" arch cpu triple d
  local -a extra=() enable=()
  case "$abi" in
    arm64-v8a)
      arch=aarch64
      cpu=armv8-a
      triple=aarch64-linux-android
      ;;
    x86_64)
      # Only the emulator runs this ABI; skipping the assembly saves needing nasm.
      arch=x86_64
      cpu=x86-64
      triple=x86_64-linux-android
      extra=(--disable-asm)
      ;;
    *) die "no build for ABI '$abi' (arm64-v8a, x86_64)" ;;
  esac
  local cc="$toolchain/$triple$ANDROID_API-clang" cxx="$toolchain/$triple$ANDROID_API-clang++"
  local out="$work/$abi" prefix="$work/$abi/prefix"
  for d in "${decoders[@]}"; do enable+=("--enable-decoder=$d"); done

  echo "== FFmpeg $FFMPEG_VERSION for $abi"
  rm -rf "$out"
  mkdir -p "$out"
  (
    cd "$out"
    # configure links each library with its own version script, so only the public API (av_*, avcodec_*, swr_*, ...)
    # is exported; check() asserts it. --disable-iconv because --disable-autodetect still probes the libc's iconv.
    "$src/configure" \
      --prefix="$prefix" \
      --target-os=android --enable-cross-compile --arch="$arch" --cpu="$cpu" \
      --cc="$cc" --cxx="$cxx" --nm="$toolchain/llvm-nm" --ar="$toolchain/llvm-ar" \
      --ranlib="$toolchain/llvm-ranlib" --strip="$toolchain/llvm-strip" \
      --enable-shared --disable-static --enable-pic --optflags=-O2 --disable-debug \
      --extra-ldsoflags="-Wl,-z,defs -Wl,-z,max-page-size=16384 -Wl,-z,relro -Wl,-z,now" \
      --disable-everything --disable-autodetect --disable-iconv --disable-network --disable-doc --disable-programs \
      --disable-avdevice --disable-avformat --disable-avfilter --disable-swscale --disable-postproc \
      --disable-symver --disable-v4l2-m2m --disable-vulkan --enable-swresample \
      "${extra[@]}" "${enable[@]}"
  ) 2>&1 | tee "$out/configure.log"

  grep -qx 'License: LGPL version 2.1 or later' "$out/configure.log" ||
    die "$abi: configure did not report an LGPL-only build"
  local built wanted
  built="$(sed -n 's/^#define CONFIG_\([A-Z0-9_]*\)_DECODER 1$/\1/p' "$out/config_components.h" | tr 'A-Z' 'a-z' | sort | paste -sd' ')"
  wanted="$(printf '%s\n' "${decoders[@]}" | sort | paste -sd' ')"
  [ "$built" = "$wanted" ] || die "$abi: the decoders built differ from the list in build.sh: $built"

  make -C "$out" -j"$(nproc)"
  make -C "$out" install-libs install-headers >"$out/install.log" || {
    tail -n 20 "$out/install.log" >&2
    die "$abi: make install failed"
  }

  # The JNI code uses nothing from the C++ library, so none is linked; the NDK's default, libc++_shared.so, would have
  # to ship with the app.
  "$cxx" -shared -fPIC -std=c++11 -O2 -Wall -fvisibility=hidden -fvisibility-inlines-hidden -fno-exceptions -fno-rtti \
    -nostdlib++ -I"$prefix/include" "$here/src/main/jni/ffmpeg_jni.cc" -o "$out/libffmpegJNI.so" \
    -L"$prefix/lib" -lavcodec -lswresample -lavutil -llog \
    -Wl,-soname,libffmpegJNI.so -Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,-z,relro -Wl,-z,now

  local dest="$here/src/main/jniLibs/$abi"
  rm -rf "$dest"
  mkdir -p "$dest"
  cp "$prefix/lib/libavutil.so" "$prefix/lib/libswresample.so" "$prefix/lib/libavcodec.so" "$out/libffmpegJNI.so" "$dest/"
  "$toolchain/llvm-strip" --strip-unneeded "$dest"/*.so
  check "$abi" "$dest"
  ls -l "$dest"
}

fetch
for abi in "$@"; do
  build "$abi"
done
