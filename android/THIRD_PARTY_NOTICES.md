# Third-party notices (Android)

This notice covers the Android dependencies currently declared by MediaLens. It
records the dependencies checked on 2026-10-21. Keep the
full licence texts and the corresponding source available with any APK/AAB
release.

## youtubedl-android 0.18.1

The published coordinates are:

* `io.github.junkfood02.youtubedl-android:library:0.18.1`
* `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1`
* `io.github.junkfood02.youtubedl-android:common:0.18.1` (transitive)

The Maven POMs identify the project as GPL-3.0 and link to the upstream
repository and licence: [upstream source](https://github.com/yausername/youtubedl-android/tree/0.18.1),
[GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html),
[library POM](https://repo.maven.apache.org/maven2/io/github/junkfood02/youtubedl-android/library/0.18.1/library-0.18.1.pom),
and [FFmpeg POM](https://repo.maven.apache.org/maven2/io/github/junkfood02/youtubedl-android/ffmpeg/0.18.1/ffmpeg-0.18.1.pom).
The source tag is the signed annotated tag `0.18.1`, object
`864791ae478e5e32194722337286790829c0a689`, pointing to commit
[`d725d5c9a18c3a99a13ee0308bf78275dc310760`](https://github.com/yausername/youtubedl-android/commit/d725d5c9a18c3a99a13ee0308bf78275dc310760).

The tag's committed payload paths are:

* Python: `library/src/main/jniLibs/<abi>/libpython.so` and
  `libpython.zip.so`.
* QuickJS: `library/src/main/jniLibs/<abi>/libqjs.so`.
* FFmpeg/FFprobe: `ffmpeg/src/main/jniLibs/<abi>/libffmpeg.so`,
  `libffmpeg.zip.so`, and `libffprobe.so`.
* yt-dlp: `library/src/main/res/raw/ytdlp`.

The repository's `LICENSE` is GPL-3.0. Preserve that text and the upstream
source link; do not relabel it as GPL-3.0-or-later without an upstream basis.

## Embedded runtime notices

### FFmpeg and FFprobe

FFmpeg is LGPL-2.1-or-later by default, but the official project states that
using GPL components makes the whole FFmpeg build GPL. The [FFmpeg legal
page](https://ffmpeg.org/legal.html) requires corresponding source matching the
distributed libraries and an explanation of the configure/build command. The
candidate Termux recipe associated with this release labels FFmpeg GPL-3.0
and enables GPL/version-3 components; that association is not a confirmed
producer record. Include the applicable FFmpeg LGPL/GPL text and the complete
source and configuration for the exact `libffmpeg.zip.so` dependency set.

### CPython and Python packages

The runtime is Python and must retain the [Python Software Foundation License
Version 2](https://docs.python.org/3.12/license.html#terms-and-conditions-for-accessing-or-otherwise-using-python),
including its copyright notice and a summary of changes where applicable.
The tag contains only the native payload, not the producing Python source,
package manifest, or archive assembly record.

An upstream issue reports (without maintainer confirmation) Python 3.12.11,
Mutagen 1.47.0, and PyCryptodomex 3.23.0 in the archive. If those contents
are confirmed in the resolved AAR, also ship [Mutagen's GPL-2.0-or-later
notice](https://github.com/quodlibet/mutagen/blob/main/COPYING) and
[PyCryptodome's BSD-2-Clause/public-domain notices](https://github.com/Legrandin/pycryptodome).

### QuickJS

The QuickJS binary is inside `library`; it is not a separate upstream module
in tag `0.18.1`. The maintainer identified the embedded engine as
`quickjs-2025-04-26` in [PR #338](https://github.com/yausername/youtubedl-android/pull/338).
QuickJS is MIT-licensed; retain the [copyright and MIT notice](https://github.com/bellard/quickjs/blob/master/LICENSE)
for Fabrice Bellard and Charlie Gordon and link the exact source archive:
[`quickjs-2025-04-26.tar.xz`](https://bellard.org/quickjs/quickjs-2025-04-26.tar.xz).
The Android tag has no QuickJS source, patch, or native build command.

### yt-dlp

The tag updates the bundled `ytdlp` payload in commit
[`0611bd9bdb55093ebbeeedc03bc7325e3fb1f94f`](https://github.com/yausername/youtubedl-android/commit/0611bd9bdb55093ebbeeedc03bc7325e3fb1f94f)
(`yt-dlp` 2025.11.12). The upstream yt-dlp project publishes the
[Unlicense notice](https://github.com/yt-dlp/yt-dlp/blob/master/LICENSE).
The Android repository stores the payload as a binary resource, so verify the
exact embedded payload and any bundled third-party notices before claiming a
byte-for-byte yt-dlp source match.

## Source and binary distribution

For a source-only MediaLens distribution, retain this notice, the upstream
licence texts/links, exact dependency coordinates, and the pinned source tag.
For an APK/AAB, the AARs also distribute the native payloads above: provide
the corresponding source and exact build/assembly information for GPL-covered
parts, plus all applicable notices for Python, FFmpeg, QuickJS, yt-dlp and
transitive libraries. A dependency declaration by itself is not a
corresponding-source record.

## Release provenance gaps recorded

* `io.github.junkfood02.youtubedl-android:quickjs:0.18.1` has no Maven
  Central POM, AAR, Gradle module metadata, or source JAR at the checked URL;
  the tag has no `quickjs` Gradle module. The current app correctly receives
  QuickJS through `library`'s bundled `libqjs.so`; do not add a separate
  `quickjs` dependency unless upstream publishes one.
* The tag's [BUILD_PYTHON.md](https://raw.githubusercontent.com/yausername/youtubedl-android/0.18.1/BUILD_PYTHON.md)
  and [BUILD_FFMPEG.md](https://raw.githubusercontent.com/yausername/youtubedl-android/0.18.1/BUILD_FFMPEG.md)
  describe a generic Termux/Docker workflow. They do not identify the exact
  Termux checkout, image, patches, per-ABI manifests, or native assembly
  commands that produced the 0.18.1 payloads. Public Media Lens distribution
  currently includes Android source only while these inputs are resolved.
