# Marquee for Android: plan

Every feature of Marquee for iPhone, plus what Android allows beyond it, in a native Android app. The
iPhone app's design document, `core/docs/DESIGN.md`, is the feature specification; this file records
how the Android app meets it and what differs.

## Goals

- **Parity, then more.** Every iPhone feature, and Android-only extras (section 6).
- **Tablet first, good on phones.** The owner's Xiaomi Pad 8 Pro is the only test device. Phone layouts
  are checked with emulator screenshots and in a phone-width split-screen window on the tablet; phone
  hardware stays untested.
- **Native and themed.** Material 3 Expressive components, shapes and motion, on phones and tablets alike
  (Google's large-screen guidance: navigation rail, side-by-side panes). Colour and type come from
  Marquee's themes (`core/Resources/themes.json`), so every iPhone theme exists here, plus a Wallpaper
  theme from Android's dynamic colour.
- **Smooth at the panel's refresh rate.** No dropped frames in scrolling, transitions or the player.
- **Robust video and audio.** Direct play of what the device can decode, including MKV, with the server
  converting only what it cannot.
- **Installed and updated with Obtainium** from this repo's signed GitHub releases.

## 1. Architecture

```
app/        Kotlin, Jetpack Compose, Material 3 Expressive, Media3
bridge/     Swift package MarqueeBridge: MarqueeCore behind a small JNI surface
core/       git submodule: nphil/marquee-ios, pinned; MarqueeCore and the shared docs
```

- **Shared brain.** MarqueeCore (Plex client, route and quality rules, markers, tracks, subtitles,
  downloads, Live TV guide, Seerr, link and diagnostics rules) is compiled for Android with the official
  Swift SDK for Android and called from Kotlin. The bridge is coarse: a few calls per screen load or
  playback event, values passed as JSON, always off the main thread. Nothing in the bridge runs per frame.
- **Plex requests.** Core sends requests through its `PlexTransport` seam. On Android the transport is
  OkHttp, supplied from Kotlin, so requests use Android's certificate store, HTTP/2 and network callbacks.
- **Player.** Media3 ExoPlayer plays MKV, MP4 and the server's HLS directly. Audio the device cannot
  decode (DTS, TrueHD and similar) goes through Media3's FFmpeg decoder extension; passthrough to a
  receiver where one is attached. Subtitles: SRT, ASS/SSA, WebVTT, PGS and VobSub in the player; sidecar
  files as on iPhone. VideoEngine's on-device remux is not used: Android needs no repackaging.
- **UI.** Compose with Material 3 Expressive. `NavigationSuiteScaffold` gives a rail on tablets and a bar
  on phones; list-detail and supporting-pane layouts on wide windows; shared-element transitions and
  predictive back.
- **Performance.** Baseline profiles, R8, stable list keys, derived state for anything ticking, video on
  a `SurfaceView`. A macrobenchmark in CI measures frame timing on scrolling and the main transitions,
  and a regression fails the build.
- **Icon.** The iPhone app's mark as an adaptive icon with a monochrome layer, so Android's themed icons
  tint it.

## 2. Working with the iPhone app

- Both apps are maintained together. A change to shared behaviour lands in MarqueeCore in `marquee-ios`
  first, with its tests, and this repo then moves the `core` pin.
- A feature is done when it works on both, or the difference is recorded here with its reason.
- Video engine work stays in `nphil/videoengine`; this app does not depend on it.
- The trailer and theme-music rules in `app/.../playback/` (`TrailerAutoplay`, `ThemeMusic`, `ThemeFade`)
  mirror MarqueeCore's; they are small enough to port, so a change lands in Core first and is copied here.

## 3. Release

- Every push to `main` builds the Swift libraries and the APK, runs the tests and publishes a signed APK
  as GitHub release `vX.Y.Z`, which Obtainium tracks.
- The release key is in the repository secrets `ANDROID_KEYSTORE_BASE64` and
  `ANDROID_KEYSTORE_PASSWORD`; losing it means no in-place updates.
- The release APK carries arm64-v8a only; x86_64 builds exist for the CI emulator.

## 4. Phases

Each phase ends with a release installed on the tablet.

1. **Foundations.** Project, Swift bridge build, CI and signed releases, icon, theme engine and theme
   picker, adaptive app shell, sign-in, server and profile choice; diagnostics and Report a Problem
   (the iPhone's DESIGN §8.12) in the release right after.
   - Diagnostics: `bridge/Sources/MarqueeDiagnostics` runs the iPhone's log (redaction, journal, live ntfy
     batches, reports) behind the `diag.*` calls, with its Swift tests run in CI. The app side is
     `diagnostics/` (the `Diag` facade, Logcat, the crash handler, last run's exit reasons, the request
     log) and Settings › Diagnostic Logs, with the Report a Problem sheet and the floating button.
2. **Browse.** Home with its rows and layout editor (Continue Watching with rewatches), libraries,
   detail pages with trailers and theme music, seasons and episodes, search, watchlist, Seerr requests.
   - Browse bridge: `bridge/Sources/MarqueeBridge/BrowseCalls.swift` serves Home (rows, rewatch cards, the
     last Home from disk), library pages and letters, search, the plex.tv watchlist and snapshot removal;
     its header lists the calls and `bridge/Tests/MarqueeBridgeTests` runs them against a scripted server
     in CI. The profile's Home layout is applied by the app, so the bridge sends rows without `shown`.
   - Detail: the `item.*` calls (`DetailCalls.swift`) serve a title, its seasons and episodes, extras,
     related shelves, watched marks and watchlist changes. `DetailModel` loads the item first and the rest
     in parallel. Pages are Navigation 3 routes (`ui/AppNavigation.kt`); the poster-to-hero shared element
     runs only from the poster that was tapped. The tracks sheet is read-only and there is no Play or
     Resume until the player. Episode rows have no swipe actions; their menu has the same ones.
   - The instrumented tests run `DetailBridgeTest` (the calls) and `DetailScreenTest` (the pages, with
     the app's own session restored onto the fake server) against the iPhone's FakePlex fixtures. The
     baseline profile still records the start only: the detail screens need a signed-in session, and the
     profile generator runs in another process with no way to give the app one.
3. **Player.** Routes from Core, Auto quality with the link probe and Save Data, track and subtitle
   rules, Bazarr and SpeechDock, subtitle style, Skip Intro, credits and Up Next, background play,
   playback info, format badge, stats for nerds. Three small-player modes:
   - **Picture in Picture** outside the app: resizable by the system; Android draws its controls, so the
     app adds remote actions (play/pause, back and forward 10 s, Skip Intro, Next Episode) up to the
     device's limit.
   - **In-app mini player** while browsing: drawn by the app with the full controls and seek bar in the
     theme, dragged to any corner, resized, swiped away, tapped to expand.
   - **Free-form window** on tablets: the whole player, controls and menus included, in a resizable
     floating window (`resizeableActivity`, layouts that hold at small window sizes).
4. **The rest.** Downloads, Live TV (guide, DVR, direct from tuner), Chromecast, linked phones, the
   LumaTV remote, glasses, the HomeLabber quality map.
5. **Android extras** (section 6).

## 5. Verification

- Kotlin unit tests and MarqueeCore's tests run in CI; Core's tests also run on an Android emulator.
- Screenshot tests at phone and tablet sizes, in light and dark, against FakePlex fixtures from `core`.
- Frame-timing benchmark as above.
- Acceptance on the Xiaomi Pad 8 Pro only, with its receipt recorded; phone hardware is listed as
  untested.

## 6. Android extras

- Picture-in-Picture actions (above), the in-app mini player and the free-form window player.
- Background playback with full system media controls.
- Home-screen widgets (Continue Watching, Up Next), app shortcuts.
- Downloads through the system's background work scheduler, surviving restarts.
- Later: a Google TV build from the same code.

## 7. Facts measured

- 2026-11-21: MarqueeCore builds unchanged for `aarch64-unknown-linux-android28` (Swift 6.4.1 with its
  Android SDK, NDK r30). The SDK's setup script stops on the bundle's dangling `clang` link and runs once
  that link is removed. Linking needs the native build system and `-Xlinker -L` to the SDK's
  `swift_static-aarch64/android` directory for `_CFURLSessionInterface`.
- The Swift runtime, Foundation (ICU alone 40 MB stripped) and Core come to about 30 MB zipped per ABI.
  The 0.1.0 release APK (arm64-v8a only, R8, baseline profile) is 31,927,921 bytes.
- CI emulator (API 34, x86_64): the Swift libraries load in 15 ms and Core's connection probe runs
  through the bridge (a refused port in 15 ms, a local server in 24 ms). Cold start to first frame
  (`am start -W`, profile compiled) 604–629 ms over five launches, median 613 ms. Not yet run on the
  tablet.
- Compose 1.13 needs compileSdk 37.1 or later, so the app builds against `android-37.2`
  (`compileSdkMinor = 2`). AGP 9 makes unit tests for the debug build type only.
- 2026-11-21: Footnote text in Settings read too thin on the tablet at Regular weight, so the body styles
  (`bodyLarge`, `bodyMedium`, `bodySmall`) use Medium app-wide. Not yet looked at on the tablet since.
