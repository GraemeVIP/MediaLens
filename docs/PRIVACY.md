# Privacy

Media Lens has no backend, accounts, advertising or analytics. Downloads and job history stay on the device. Source sites receive normal network requests from your device; their own privacy policies apply.

The browser extension scans the active page only after you invoke it. Native messaging connects the popup to the local companion. Browser-cookie access is an explicit setting, off by default; credentials and browser profiles are not included in this repository or releases.

The companion downloads dependencies from Python package services. Engine updates contact upstream yt-dlp/GitHub services. Android includes its own runtime and contacts GitHub only when you request an engine update.

Desktop files are in Downloads/Media Lens and job data is in Library/Application Support/Media Lens. Android stores history privately, excludes it from Android backups and writes completed files to Downloads/Media Lens using MediaStore. Uninstalling the app removes its private history; completed downloads remain in your Downloads folder. Sharing a link to Media Lens fills the input; downloading requires your tap.

Do not attach private URLs, cookies, tokens or personal downloaded media to public issues.
