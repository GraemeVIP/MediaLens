# Third-party software

Media Lens original code and project assets are distributed under GPL-3.0-or-later; see LICENSE. The generated icon was created for this project using OpenAI image generation.

The macOS extension/companion source does not bundle Python, Node.js or FFmpeg. Users install those runtimes separately. The companion installs the declared yt-dlp Python package and its default dependencies into a local virtual environment.

- [yt-dlp](https://github.com/yt-dlp/yt-dlp): Unlicense for the Python source/wheel. Its default dependencies have separate licences, including GPL-2.0-or-later (mutagen), MPL-2.0 (certifi), MIT, BSD, Apache-2.0 and ISC. See [upstream dependency and licence details](https://github.com/yt-dlp/yt-dlp#licensing).
- [FFmpeg](https://ffmpeg.org/legal.html): LGPL/GPL depending on the installed build. Media Lens invokes the user's executable; it does not redistribute the Homebrew installation.
- [Python](https://docs.python.org/3/license.html): Python Software Foundation licence and incorporated notices.
- [Node.js](https://github.com/nodejs/node/blob/main/LICENSE): MIT and bundled third-party notices.
- Android bundles different runtime components; see [Android notices and corresponding source](android/THIRD_PARTY_NOTICES.md).

Naming a service in the supported-site list does not imply affiliation or endorsement.
