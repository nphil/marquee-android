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
  NatePad acceptance covers Home and Library flings, sidebar toggles and opening a title. Compare the
  same gestures with warm artwork. `ui window.frames` records the display rate at report time, missed
  rendering deadlines and the slowest UI frame; the display rate alone is not the app's frame rate.
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
  decode (DTS, TrueHD and similar, read from `MediaCodecList`) goes to an LGPL-only FFmpeg build
  (`ffmpeg/`: Media3's FFmpeg audio extension over separate shared libraries), which decodes AC3,
  E-AC3, DTS, TrueHD, FLAC, Opus, Vorbis and ALAC to PCM; the platform's decoder is always tried
  first. A tablet whose codec list has no MPEG-2 decoder decodes MPEG-2 video with the same build's
  `mpeg2video` (Marquee's own Media3 renderer in `ffmpeg/`: slice-threaded decoding, a motion-adaptive
  deinterlacer that shows each field of an interlaced frame as a picture, and OpenGL drawing onto the
  player's surface); where there is a decoder, MediaCodec keeps the stream. Audio neither can decode is
  declared unsupported, so Core's route has Plex convert only the audio and copy the video (a direct
  stream). Passthrough to a receiver stays Media3's own where one
  is attached. Subtitles: SRT sidecars
  as on iPhone, a cue starting `{\an8}` at the top, and the file's own text tracks; which audio and
  subtitle play is Core's `TrackRules`. The app draws the lines itself (Media3's `SubtitleView` over the
  picture, the player's own one hidden) in the profile's subtitle style; a file's own font sizes never
  override it. The server never burns a subtitle in for this player: picture subtitles (PGS,
  VobSub) are listed as not shown on the tablet. VideoEngine's on-device remux is not used: Android
  needs no repackaging.
- **UI.** Compose with Material 3 Expressive. `NavigationSuiteScaffold` gives a rail on tablets and a bar
  on phones; list-detail and supporting-pane layouts on wide windows; shared-element transitions and
  predictive back.
- **Performance.** Baseline profiles, R8, stable list keys, derived state for anything ticking, video on
  a `SurfaceView`. A macrobenchmark in CI measures frame timing on scrolling and the main transitions,
  and a regression fails the build.
- **Icon.** The iPhone app's mark as an adaptive icon with a monochrome layer, so Android's themed icons
  tint it.
- **Icons.** In-app icons are Material Symbols, Rounded style, converted from the SVGs to 24 dp vector
  drawables in `res/drawable/ic_*.xml` (viewport 960, white fill). They take their colour from the theme
  where they are drawn, never from the file; a filled variant for a selected or on state is a second
  file (`ic_home_filled`). `material-icons-extended` is not used, which would add thousands of unused
  icons to the APK. Where the iPhone uses an SF Symbol, the nearest Material Symbol stands in.
- **The M.** The launcher icon's M is redrawn as four flat slabs in `ui/shell/MarqueeMark.kt` for the
  tablet sidebar's header: the slabs pull apart and settle each time the sidebar opens or closes, and
  take their face colours from the theme's accent and warning colours. The launcher icon keeps the
  iPhone's colours.

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
   - Plex address: Core keeps the address that last worked. `AppSession.preferLocal` asks it to look for a nearer one
     when the app comes to the front and when the default network changes (settled for a second), as the iPhone does
     on a network change, so a tablet that comes home leaves the remote address without waiting for a failed
     request. Leaving home is left to Core's failover on the next failed request.
2. **Browse.** Home with its rows and layout editor (Continue Watching with rewatches), libraries,
   detail pages with trailers and theme music, seasons and episodes, search, watchlist, Seerr requests.
   - Browse bridge: `bridge/Sources/MarqueeBridge/BrowseCalls.swift` serves Home (rows, rewatch cards, the
     last Home from disk), library pages and letters, search, the plex.tv watchlist and snapshot removal;
     its header lists the calls and `bridge/Tests/MarqueeBridgeTests` runs them against a scripted server
     in CI. The profile's Home layout is applied by the app, so the bridge sends rows without `shown`.
   - Detail: the `item.*` calls (`DetailCalls.swift`) serve a title, its seasons and episodes, extras,
     related shelves, watched marks and watchlist changes. `DetailModel` loads the item first and the rest
     in parallel. Pages are Navigation 3 routes (`ui/AppNavigation.kt`); the poster-to-hero shared element
     runs only from the poster that was tapped, and the page's hero is on screen from the first frame,
     drawing that poster's cached picture until its own has loaded, so an open is one flight whenever the
     title's answer arrives. The tracks sheet is read-only and there is no Play or
     Resume until the player. An episode row swipes from the start edge to mark it watched or unwatched, as
     on the iPhone; the check and the More menu do the same.
   - Deleting a title is two bridge calls, so the title can leave every list at once. `detail.delete` asks the
     Plex server alone and returns a ticket; `MediaDeletions` then records the deletion and runs
     `detail.cleanUp` in the background: the Watchlist, Radarr or Sonarr (removed with its files and added to
     the import exclusion list) and Seerr (the media record and its requests), each only where set up in
     Settings › Integrations, each answering for itself. A toast says what was done and what was not asked; a
     step that failed or could not match the title is an alert hosted by the app, because the page is gone by
     then, with Retry for the failed ones. Radarr and Sonarr keep their address and key in the secret store.
   - The instrumented tests run `DetailBridgeTest` (the calls) and `DetailScreenTest` (the pages, with
     the app's own session restored onto the fake server) against the iPhone's FakePlex fixtures.
     `BackAnimationTest` films a page closing frame by frame.
   - The baseline profile is recorded against the `nonMinifiedRelease` build, the one build with
     `src/nonMinifiedRelease`. Its manifest names `ProfilingApplication` through the `marqueeApplication`
     placeholder; that application serves the fixtures from a fake server and writes the fixture account
     into the secret store on every start, so the build is always signed in and the generators need no
     sign-in step. `DetailProfileGenerator` opens a movie and a show from Home, swipes an episode and
     opens it; the debug and release builds contain none of it.
3. **Player.** Routes from Core, Auto quality with the link probe and Save Data, the track rules from
   Core (English subtitles on by default; original audio, or the English dub with its own subtitle for
   anime; a Tracks menu of On, Off, Style and one Advanced row), Bazarr and SpeechDock, subtitle style,
   Skip Intro, credits and Up Next, background play (a media session and a foreground service keep the sound
   going with the screen off, and the player keeps the last keyframe so the picture comes back without a
   reload), playback info, the format badge, stats for nerds, seek-bar previews (the
   server's thumbnail index, or frames the app takes itself) and the `Diag` trail (route reasons by name,
   chosen tracks, stalls, the server's own account of a conversion; never URLs or tokens). Small-player modes:
   - **Picture in Picture** outside the app: resizable by the system; Android draws its controls, so the
     app adds remote actions (play/pause, back and forward 10 s, Skip Intro, Next Episode) up to the
     device's limit. Home enters it from the full screen or from the mini bar, and coming back restores
     the same form.
   - **In-app mini player** while browsing: a slim bar above the tab bar (at the foot of the content beside
     the sidebar on tablets) with the live picture, the title (season and episode for a show), play and
     pause, close and a thin progress line in the theme's accent. Swiping the player down, or its minimise
     button, docks it; tapping the bar or swiping it up expands it with no reload, as one player and one
     picture serve both. Swiping the bar sideways stops it. Live TV minimises the same way. A phone
     held on its side by the player turns upright on minimise. The player is one session above the back
     stack, so pages open over it; a trailer hides the bar and pauses the title.
   - **Free-form window** on tablets: the whole player, controls and menus included, in a resizable
     floating window (`resizeableActivity`, layouts that hold at small window sizes).
4. **The rest.** Downloads, Live TV (guide, DVR, direct from tuner), casting, linked phones, the
   LumaTV remote, glasses, the HomeLabber quality map.
   - Big Screen (Settings > Integrations, off by default) follows the iPhone's v0.1.70 plugin: a connected external
     display (DisplayManager, shown through `Presentation`) gets Marquee's own 1080-high canvas, with the player's
     picture, Subtitles/Audio options and Next Episode, and the phone becomes the remote (touchpad, hold-to-scrub,
     tilt, haptic cues). Per-display answers are remembered; Mirror leaves the system's mirroring alone.
   - Linked phones are built (`link/`, `ui/link/`, Settings › Linked Phones): Core's bridge owns the code, keys,
     handshakes and messages exactly as on the iPhone; Android owns mDNS, the Keystore-sealed saved phones, the
     player controls it answers as the driving phone, and the remote it shows for a linked phone. Not carried: the
     iPhone's eyes-free touch surface for a linked phone, and what a phone driving glasses answers it (input, focus,
     haptic cues), so a linked phone gets arrow buttons. Untested on two physical phones.
   - Plex Live TV uses Core's lineup, schedule, tuner sessions and stream cleanup. The guide uses a pinned
     channel/time grid when the content pane has room, and a Now & Next list at narrower widths or larger
     type sizes. The player has a Quick Guide alongside the picture in wide windows and below it otherwise.
     Direct from Tuner in Video & Playback allows an attached-LAN HDHomeRun stream, with Core choosing
     the route and Plex handling unsupported streams or a direct read/decoder failure. LumaTV refusals
     stay visible, and the player releases the tuner for Plex recordings or when it stops. Android 17
     requires local-network permission. Direct streams do not have a rewind buffer on Android; Plex
     playback can rewind within the server's live window. DVR rules and scheduled airings use Plex's
     recording templates; Recorded opens the completed DVR version in the normal player. Before tuning direct,
     Android asks the tablet's decoders for what the tuner sends (the lineup's codecs for an HDHomeRun, MPEG-2
     and AC-3 for a LumaTV; MPEG-2 counts as decodable on a tablet with no decoder of its own, through the
     software renderer) and goes to Plex when one is missing; a stream the renderers or a decoder then refuse
     is remembered for the run, and a tuner whose stream cannot connect or times out is not tried again for a
     minute, as on the iPhone. Each fallback to Plex logs its cause and the stream's tracks.
   - LumaTV Remote is opt-in. A TV can pair by QR link or a code on the local network. It opens from a round
     button beside the profile button (sidebar footer on a tablet, following the sidebar's width; Home header
     on a phone) or from the Mini Bar above the pages, which stays reachable above a minimised playback's bar;
     Settings › LumaTV Remote picks Button by Profile (default; a saved floating choice becomes it) or Mini Bar.
     There is no floating button. The panel zooms out of the button, can be moved and resized, and lays its
     controls out for the shape. Standby, offline and unpaired states replace the pad with the notice and its
     one button. Holding an empty part of the panel edits which controls show. The pad is a D-pad, a joystick
     or a touchpad. Pairing and encrypted commands use the same LumaRemoteCore as iOS. Not yet device-tested.
   - Chromecast and AirPlay are separate per-device opt-ins under Integrations. The player has one receiver chooser.
     A failed handoff leaves local playback running; returning from a receiver opens the title paused at its last position.
     Offline downloads stay on this device.
   - Enhance Picture (Settings › Video & Playback, off by default) follows the iPhone's names, defaults and log lines
     (`enhance.state`, `enhance-probe`, `enhance.options`, the stats screen's Enhance line). The title player runs Media3
     video effects: a Lanczos (or bicubic) upscale to the display, then one finishing pass: Deblock (smooths 8x8 block
     seams), Noise Reduction (small edge-preserving filter), deband, a sharpen held to the local range, for Line art an
     edge steepening, and Vivid (local contrast and a colour lift favouring dull colours), in the app's own GLSL. The
     strengths follow VideoEngine 3.0.3: Sharpen 0.5 is the old 1.0 and 1.0 is twice that, the deband threshold is
     doubled; the new three are off by default. Automatic starts at Lanczos only for sources of 720 lines or fewer and
     takes bicubic above that. SDR only: HDR and Dolby Vision, tunnelling, the software MPEG-2 renderer and
     protected video report unavailable and play unchanged. It is inactive in picture-in-picture, in the mini bar, in the
     background and on a glasses display, and it pauses on dropped frames, thermal status severe and Battery Saver, then
     returns. Differences from the iPhone: Lanczos-2 with a bicubic rung under it (fewer taps per frame), a windowed
     governor, the chain stays on a paused picture, and turning the setting on mid-title restarts the title once, since
     Media3 builds its effect pipeline only as the player prepares. The GPU cost on the tablet and on an onn-class box is
     reasoned, not measured; the shaders have not been compiled or run on a device.
   - A stall writes `player-stall` at once and again 4.5 s later with `again=yes` if the player is still buffering (the
     iPhone's line name): buffered ahead and in total, loading, state, play-when-ready, suppression, the last load error's
     type and HTTP status (never an address), the bandwidth estimate and dropped frames.
   - The iPhone's early read of the next episode (`prewarm`) is not ported: the tablet's players read through a plain
     OkHttp data source with no cache, and the next episode's address and headers exist only after Core's `startPlay`
     opens a server session, so bytes read at the Up Next card would be fetched again. Media3's preload manager needs that
     same session first. Only a connection already open to the server would be saved, and the title playing keeps one.
   - Enhance Picture is also in the player's More menu (title player only, hidden while casting): Off, Automatic or
     Line art, and with it the Sharpen, Deband, Noise Reduction, Deblock and Vivid sliders, saved in the same profile
     setting Settings uses. A slider applies live (also on a paused picture, which is drawn again by seeking to where it
     is); a new scaler restarts the title once. When the title cannot be enhanced the row shows the reason and the
     controls are off. A line says so when the video is already as tall as the view. While a slider is dragged the panel,
     the other rows and the player controls hide, leaving that slider in a small capsule, and the picture splits (left
     half as the player draws it, right half enhanced) with a divider and "Original" / "Enhanced" labels; letting go logs
     `enhance.options` with every strength. With a screen reader that explores by touch nothing hides. While the page is
     open a pause for dropped frames does not hold the chain back; heat and Battery Saver still do.
   - Text from picture subtitles (the iPhone's on-device recognition of PGS and VobSub) is not ported. Picture cues never
     reach the tablet's subtitle hook, Core marks embedded picture subtitles in-stream only when a route draws them, the
     menu's options are Core's, and cast senders have no text path.
5. **Android extras** (section 6).

## 5. Verification

- Kotlin unit tests and MarqueeCore's tests run in CI; Core's tests also run on an Android emulator.
- Screenshot tests at phone and tablet sizes, in light and dark, against FakePlex fixtures from `core`.
- Frame-timing benchmark as above.
- **Downloads fixtures.** `PlexOriginPolicyTest` covers the same-origin rule for Plex credentials.
  `AppSessionIdentityTest` covers an interrupted profile switch, a blank owner, the first legacy
  launch offline and recovery on reconnect, and a verified offline relaunch. Unverified records
  stay on disk and never join another profile. `DownloadIdentityPersistenceTest` checks that a
  deleted file leaves its title identity and pending progress behind; `DownloadWatchSyncTests`
  covers key reuse, a resume retry after a watched acknowledgment, and newer viewing on another
  device. These checks use synthetic Plex replies and are separate from device acceptance.
- Acceptance on the Xiaomi Pad 8 Pro only, with its receipt recorded; phone hardware is listed as
  untested.

## 6. Android extras

- Picture-in-Picture actions (above), the in-app mini player and the free-form window player.
- Background playback with full system media controls.
- Home-screen widgets (Continue Watching, Up Next), app shortcuts.
- Downloads through the system's background work scheduler, surviving restarts.
- Later: a Google TV build from the same code.

### Backup & Sync

Settings › Backup & Sync, with the iPhone's rows and words (its DESIGN §8.13). Core makes, seals and opens the
file (`backup.*` bridge calls); Kotlin (`backup/`) decides what goes in and applies a restore.

- The file holds the settings, the integrations and their keys, and the paired TVs. The Plex sign-in, the
  client identifier, Seerr's session, linked phones and caches are not in it; anything added later is in until
  listed out in `BackupScope`.
- Restore is all or nothing: every value is checked first, secrets are written first, and a failure puts back
  what was touched. An iPhone's backup applies only its integrations, and an Android backup is read by an
  iPhone the same way. A restore ends with Close Marquee, because running models hold the old values.
- The sealing key is in the Keystore and does not leave the device, so a backup opens elsewhere, or on an
  iPhone, only with the recovery passphrase (12–256 characters). Automatic backup cannot be turned on without
  one, and removing it stops automatic backup.
- Back Up Now and Restore use the system's document pickers. Automatic backup keeps one file in a folder
  chosen with the folder picker (a persisted grant, so Drive and network providers work), 20 s after a change
  and when Marquee leaves the foreground, and keeps the previous copy beside it.
- Needs a device: the pickers and the persisted folder grant on Drive and a network share, opening a
  `.marqueebackup` from a file manager, the Keystore keyring, the write when the app is stopped, the restore
  and restart, and a restore both ways between an iPhone and this app.

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
