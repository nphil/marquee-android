# TV release parity

The reference is the shipping Marquee webOS client (`rust-modules/screens/src`), not the handset or an earlier mock. A shared Android engine counts only when the TV remote can reach it and Back returns to the right place. Candidate controls below need an offline remote journey before release. Emulator frame timings do not establish device performance.

| LG surface | Android TV coverage | Remaining release checks or work |
| --- | --- | --- |
| Account and Home | Plex link code, server selection, Continue Watching, themed Home rails, remote focus and return | Profile switching including PIN; switch owned/shared servers without signing out; Home and top-bar configuration; tour |
| Library, search, collections | Search keyboard; paged titles; candidate adds Recommended, Collections, Genres, Unwatched and both sort directions | Combine filters, jump by letter, remember each view, cross-server search, alternate sources, favorites, display options and empty/error states |
| Watchlist and item menu | Candidate opens a paged account Watchlist and resolves a catalogue title against the current server before offering a local copy | Add/remove and Undo, sorting, unavailable titles and requests, watched status and other permitted item actions |
| Film, series and people | Resume/Start; season and episode browsing; candidate adds cast → filmography, bonus clips, related shelves and detail-hero trailers | Episode detail, About, versions, alternate copies, crew, poster/logo treatments, dedicated trailer transport and focus restoration |
| Player and transport | Audio/subtitle track selection, playback info, refresh-rate matching; candidate adds visible Watch/Skip Intro, pending seek with thumbnails and chapters, quality and speed selection | Verify accept/dismiss, seek cancellation and unavailable thumbnails; credits and Up Next outside the HUD, playback HUD layouts, picture/aspect options, sleep, A/V sync and diagnostics |
| Subtitle appearance | Candidate shares seven saved presets, text colour/opacity, size, font, weight, edge, background, softening and reveal with the player; remote editor has a rendered sample | Marquee preset; LG placement, nudge, line spacing, edge width, coloured/rounded backing, font coverage, authored ASS policy and exact scope for picture subtitles; verify with representative files |
| Services | Handset engines exist for Seerr, subtitle providers, speech services and diagnostics | Remote setup, enable/disable, request/search/status journey, subtitle correction, service failures and privacy; no fabricated service data. The proposed NAS discovery index is separate and parked |
| Platform behavior | Hardware decoding and Android display-rate APIs replace webOS playback services | Verify direct vs converted routes, restore display mode, focus and panel transitions on Onn. LG-only Magic Remote, native compositor and panel-care controls are not Android settings |

The emulator review uses an isolated fixture account and package. The local sideload package retains its original signing identity. No receipt from either is a substitute for the other.
