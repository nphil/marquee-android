# Marquee for Android

A native Plex client for Android tablets and phones: the Android sibling of
[Marquee for iPhone](https://github.com/nphil/marquee-ios). It shares the iPhone app's Swift core
(MarqueeCore, in the `core` submodule) and draws its own Material 3 Expressive interface in Jetpack
Compose.

Plan and status: [docs/PLAN.md](docs/PLAN.md).

Settings offers Soft, Hard and Blur headers, and an option to hide scroll indicators without
hiding the Library’s A–Z rail. Under Posters sets the title, details and library sort
value separately for each Plex profile.

## AirPlay video

The Android sender uses [Airkast 0.6.0](https://github.com/aivanyuk/airkast/tree/0.6.0)
under Apache 2.0. The receiver must advertise AirPlay 2 video, allow the sender on its network,
and reach the Plex server. Marquee supports direct H.264/AAC MP4 playback; it does not send
Plex's HLS conversions because LG's AirPlay web player requires cross-origin headers on
the playlist and segments. Subtitles and audio-track switching are not available on this file path.

[Airkast's hardware checks](https://github.com/aivanyuk/airkast/blob/0.6.0/docs/compatibility.md#checked)
cover an LG OLED CX with or without a PIN and a Mac set to allow AirPlay from the same network.
AirPlay 1 devices, audio-only receivers, and receivers restricted to the owner's Apple Account
are not supported. Apple TV and other AirPlay 2 displays need hardware verification.

The older Connect SDK sender uses legacy video commands; the LG CX does not accept them.
Physical acceptance needs an Android device, an LG CX, and a test Plex server with an
unprotected H.264/AAC MP4. It checks discovery, the receiver's on-screen PIN, actual video
at the resume position, seek, pause and disconnect back to the phone.

## Android TV

The television build uses the same Plex account, library and playback code as the phone app.
Its separate Android TV launcher (`com.nphil.marquee.tv`) uses the remote: arrows browse,
Select opens a title, holding Select opens options, and Back returns to the previous screen.
Sign-in uses a code at plex.tv/link. The TV uses Android hardware decoding first, FFmpeg for
additional audio formats and MPEG-2, and Plex conversion for incompatible direct-play streams.

During playback, Up shows the timeline and Down opens options. Playback Info shows the actual
route, decoder, startup time and dropped frames. When Android TV permits non-seamless frame-rate
matching, playback requests the film rate at the current resolution and releases it on exit.

Home highlights the new selection immediately and waits 240 ms before loading its preview.
The 5% card lift uses the [Android TV focus treatment](https://developer.android.com/design/ui/tv/guides/styles/focus-system);
the shelves and options drawer ease to a stop without delaying the next remote press.

The Onn 4K Pro runs 32-bit Android. The TV package includes both ARM runtimes; the handset's default 64-bit build cannot be installed on the Onn. With the Android Swift SDK and NDK set up:

```sh
bash ffmpeg/build.sh arm64-v8a armeabi-v7a
./gradlew :app:assembleTelevision -Pmarquee.abis=arm64-v8a,armeabi-v7a
```

## Install

Add this repository to [Obtainium](https://github.com/ImranR98/Obtainium):
`https://github.com/nphil/marquee-android`. Each push to `main` publishes a signed APK as a release.

## Clone

```sh
git clone --recurse-submodules https://github.com/nphil/marquee-android.git
```
