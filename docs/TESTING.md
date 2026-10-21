# Verification

## Browser extension 2.2.0

- 10 JavaScript tests and 17 native-host Python tests pass; static checks cover syntax, popup assets, minimum text sizes and scoped extension permissions.
- Integrated local fixtures verify two ticked channel videos download while an unticked third does not; videos retain audio.
- Full-size, responsive, lazy-loaded and background image downloads preserve fixture bytes. Separate batches do not overwrite each other.
- HLS quality selection saves the requested 180p video with audio; DASH audio and cancel/retry were exercised.
- YouTube live metadata for the public 19-second “Me at the zoo” sample resolves; no YouTube video was fetched in that check.
- The user reloaded the extension in Brave and confirmed its appearance. Automated extension-management access was unavailable.

## Named site support

Pornhub, xHamster, XVideos, XNXX, RedGifs, X/Twitter, YouTube and Vimeo have dedicated extractors in the installed yt-dlp engine. Offline synthetic URL-shape checks select each expected extractor. This proves extractor availability; it does not prove that every live page currently downloads. Adult-site media has not been fetched for these release checks.

## Android 0.1.0 beta

- Signed release and debug builds compile; Android lint passes with no errors.
- Android 15 ARM64 emulator: direct video download matches source SHA256; HLS 180p output contains video and audio; audio extraction produces an M4A audio stream; direct PNG downloads, cancel/retry and service shutdown work.
- Samsung Galaxy S23 Ultra (SM-S918B), Android 16/API36, 4096-byte pages: signed beta installs, direct video matches source SHA256, direct PNG downloads, and the HLS quality choice saves 320×180 video with audio through MediaStore.
- A second Share intent updates the already-open app. Engine update from the official upstream succeeds on the phone.
- Tests use small generated fixtures. The Android public release currently contains source, not the APK; see Android README for binary-provenance and 16 KB limitations.
