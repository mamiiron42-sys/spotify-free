# yt-dlp for Android

A native Android front end embedding the upstream [`yt-dlp`](https://github.com/yt-dlp/yt-dlp) Python package with [Chaquopy](https://chaquo.com/chaquopy/). Paste one or more URLs (one per line) or share a link from another app; the app runs downloads in a foreground service and saves them to `Downloads/yt-dlp`.

## Features in this app

- Pinned upstream yt-dlp `2026.08.19`, with Python 3.11 embedded in the APK.
- Batch downloads from newline-separated URLs.
- Fragmented media downloads use bounded four-way segment concurrency and a larger transfer buffer; actual gains depend on the source and connection.
- Format choices: best single-file, up to 1080p/720p/480p, audio-only in its source format, or audio-only converted to MP3.
- Optional playlist downloads (disabled by default).
- M3U playlist import supports exact links to library files and remote HTTP(S) entries; `#EXTINF` titles, artist names, and durations are saved as metadata when present.
- Persistent job queue with configurable concurrency (1–10 downloads), per-job retry/pause/resume/cancel, and restart recovery.
- Optional Wi-Fi-only scheduling, queued-job priority, and retry-all controls.
- Separate Search, Library, Playlists, Player, and Queue tabs.
- Unlimited, on-device Recently Played history records each listen after playback starts and pages older entries on demand.
- YouTube track metadata and cover artwork are imported into the library when available; audio tags and artwork are embedded in MP3 conversions when supported.
- Import local audio files, browse/filter tracks by artist or album, create/reorder/share/import playlists, inspect track details, and manage downloaded files.
- Background audio playback with lock-screen/notification transport controls, audio-focus handling, seek, previous/next, shuffle/repeat, speed control, sleep timer, and a device-dependent equalizer.
- Foreground notification and in-app progress, including download pause/resume/cancel controls.
- Downloaded-file history; tap an entry to open it in another app.
- Format and playlist settings are remembered between launches.

## Build

Use JDK 17 or newer and Android SDK Platform 36. The checked-in executable Gradle wrapper pins Gradle 9.6.0; the project uses Android Gradle Plugin 9.4.1. Build the debug app with `./gradlew :app:assembleDebug` (the first build downloads the wrapper distribution, plugins, and pinned yt-dlp package). Alternatively, open the project in a compatible Android Studio release. The app targets Android 10+ (`arm64-v8a` or `x86_64`).

Run the Python downloader tests with `python3 -m unittest discover -s tests -p 'test_*.py'`. They use a fake yt-dlp engine, verify that `audio_mp3` selects `bestaudio` without configuring a yt-dlp postprocessor, and do not download or convert media. These tests do not exercise Kotlin, FFmpegKit, Android packaging, or native encoders.

### Validate MP3 conversion on Android

A successful Python test or Gradle build alone does not prove that MP3 conversion works on a device. For an end-to-end check, install and run the debug app on an Android 10+ emulator/device for a supported ABI (`arm64-v8a` or `x86_64`), choose **Audio only (MP3)**, and download a short URL containing audio you are authorized to save. Confirm the job completes, the resulting non-empty `.mp3` appears under `Downloads/yt-dlp`, and it plays in an audio player. Also try canceling during conversion if validating cancellation behavior. Check the job status/error details and Android Studio Logcat if conversion fails.

The conversion path intentionally downloads the `bestaudio` source without yt-dlp postprocessing, then invokes the bundled FFmpegKit Full native libraries with the `libmp3lame` encoder. MP3 audio encoding and optional cover embedding happen in a single FFmpeg pass where possible, avoiding an extra remux pass; when cover embedding fails, the conversion retries without the cover. The selected FFmpegKit artifact must include both `libmp3lame` and native binaries for the target ABI; this project currently packages `arm64-v8a` and `x86_64` only. Testing on another ABI or using an FFmpeg build without the LAME encoder is not covered by the Python tests and may fail at runtime.

## Limitations

This is an Android app around yt-dlp, not a rewrite or complete port of all yt-dlp CLI features and dependencies.

- The app bundles FFmpegKit Full 8.1.9 from `dev.ffmpegkit-maintained` (LGPL-3.0), adding its native libraries to each supported APK architecture. Audio-only MP3 conversion uses the documented `libmp3lame` encoder. The app still selects a single-file video format rather than merging separate video/audio streams; format merging is not yet exposed in the UI.
- No external JavaScript runtime is bundled. Some extractors—YouTube in particular—may need one for full functionality.
- Android or a device manufacturer's battery policy can still stop long-running background work. Interrupted active jobs are returned to the queue on the next app launch and resume from yt-dlp's partial files; explicitly paused jobs stay paused until resumed.
- YouTube metadata and cover art are best-effort and depend on extractor availability. Video downloads are not playable in the in-app audio player.
- M3U import recognizes local entries only when their URI exactly matches a library item, and accepts absolute remote HTTP(S) URLs. Remote entries are not fetched during import: optional `#EXTINF` title/artist/duration metadata is stored with the playlist item. Selecting a remote entry queues it for download; Play starts available local tracks, or queues remote entries when there are no local tracks. Relative paths and other remote URL schemes are not imported. Equalizer support depends on the Android device/audio effect implementation.
- Recently Played is stored only on the device and is not automatically pruned; its database usage grows with listening activity.
- Only HTTP(S) URLs are accepted. Use yt-dlp only for content you are authorized to save and follow the source site's terms.

## Licensing

The yt-dlp Python dependency is downloaded from PyPI during the build. yt-dlp is licensed under the Unlicense, with third-party components documented upstream. The bundled FFmpegKit AAR is distributed under LGPL-3.0 and includes third-party libraries with their own notices; review the upstream `LICENSE` and `THIRD-PARTY-NOTICES.txt`, and satisfy applicable LGPL source, relinking, and notice requirements before redistributing an APK. Chaquopy and Android build dependencies retain their own licenses. This is a dependency selected for MP3 encoding, not an endorsement or legal compliance determination.
# spotify-free
