# Media Lens for Android

A standalone native downloader: **paste a link or Share → Media Lens**, find the media, choose a quality and download it to your phone. Python, yt-dlp, QuickJS and FFmpeg run locally inside the app. No Mac, companion server, Termux or separately installed downloader is needed.

- Best video with audio, maximum-resolution choices and M4A audio extraction.
- Direct PNG, JPEG, WebP, GIF and other image links.
- Background downloads with file sizes, estimated stream totals, live Mbps speed, time remaining, cancellation and retry.
- Completed files in **Downloads / Media Lens**, accessible in your usual Files app.
- Private on-device history; no account, analytics or broad storage permission.

The browser extension's channel checkbox selection and page-image scanner remain desktop features. On Android, share one media-page link at a time; up to ten downloads can queue, running one at a time.

## Beta status

**0.1.0 beta · Android 10+ · ARM64 / ARMv7 / x86_64 · 4 KB-page devices.**

The app is available as source for local builds. A signed private beta has been built for device testing. Public APK redistribution is held until the bundled native runtime has a complete matching-source and licence package; the desktop extension release is available now.

The FFmpeg dependency has a [known upstream 16 KB-page issue](https://github.com/yausername/youtubedl-android/issues/365). Video operations detect affected devices and report the limitation instead of starting the incompatible runtime. This beta is not a Google Play release. Site support follows yt-dlp; login/age-gated, private and DRM content may be unavailable. Android does not import your desktop browser's cookies.

Use **Settings → Update engine** before trying sites that have changed since the bundled engine was built. Updates come directly from the official yt-dlp stable release.

Stream downloads use eight parallel fragment connections. A DNS fallback handles failed xHamster CDN lookups without changing the phone's VPN or DNS settings; see [privacy details](../docs/PRIVACY.md).

## Build

Requires JDK 17 and an Android SDK with platform 36. Gradle downloads dependencies from Maven Central and Google's repository. Set `ANDROID_HOME` to your SDK or create an untracked `local.properties` containing `sdk.dir=/your/sdk/path`.

```sh
cd android
./gradlew assembleDebug lintDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Building locally installs upstream dependencies into your own APK. Review [the native component notices and source status](THIRD_PARTY_NOTICES.md) before redistributing a binary.

For an unsigned release, run `./gradlew assembleRelease`. To sign your own build, set `MEDIA_LENS_SIGNING_PROPERTIES` to a private properties file with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Keep the key and properties outside Git. The publisher's private beta key is stored outside this repository so later builds can update it.

## Implementation

Java Activity, foreground download service, private JSON history and Android MediaStore. No backend. yt-dlp/FFmpeg via [youtubedl-android 0.18.1](https://github.com/yausername/youtubedl-android/tree/0.18.1). Minimum Android 29, target/compile 36. Android 13+ requests notification permission; denying it does not block a user-initiated download. No access to the rest of your photo library is required.
