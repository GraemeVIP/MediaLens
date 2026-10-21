/* Shared, dependency-free helpers for the page scanner and the popup. No page code is executed. */
globalThis.MediaLens = (() => {
  const IMAGE_EXT = new Set(["jpg", "jpeg", "jfif", "png", "gif", "webp", "avif", "bmp", "svg", "heic", "heif", "tif", "tiff", "jxl"]);
  const VIDEO_EXT = new Set(["mp4", "webm", "mov", "m4v", "mkv", "ogv", "flv"]);
  const httpUrl = (value, base) => {
    if (typeof value !== 'string' || !value.trim() || value.trim().startsWith('#')) return null;
    try {
      const u = new URL(value, base);
      return /^https?:$/.test(u.protocol) && !u.username && !u.password && u.href.length < 16384 ? u.href : null;
    } catch { return null; }
  };
  const extOf = url => {
    try { return (new URL(url).pathname.match(/\.([a-z0-9]{2,5})$/i)?.[1] || "").toLowerCase(); }
    catch { return ""; }
  };
  // Segments (.ts, .m4s, numbered fragments) are parts of a stream, never a video of their own.
  const isSegment = url => /\.(?:ts|m4s|aac|m4a|vtt|key)(?:[?#]|$)/i.test(url) || /(?:^|[/_.-])(?:seg|segment|frag|fragment|chunk)[-_]?\d+/i.test(url);
  const streamKind = url => {
    if (!url || isSegment(url)) return null;
    const ext = extOf(url);
    if (ext === "m3u8") return "hls";
    if (ext === "mpd") return "dash";
    return VIDEO_EXT.has(ext) ? "video" : null;
  };
  const looksLikeImage = url => IMAGE_EXT.has(extOf(url));
  // Picks the largest candidate from srcset strings such as "a.jpg 640w, b.jpg 1280w" or "a.jpg 1x, b.jpg 2x".
  const biggestFromSrcset = (sets, base) => {
    let best = null;
    for (const set of sets) {
      if (!set) continue;
      for (const part of String(set).split(/,\s*(?=[^,\s]+\s+\d+(?:\.\d+)?[wx](?:\s*,|\s*$))/)) {
        const [raw, descriptor = "1x"] = part.trim().split(/\s+/);
        const url = httpUrl(raw, base);
        const match = descriptor.match(/^(\d+(?:\.\d+)?)([wx])$/);
        if (!url || !match) continue;
        const score = match[2] === "w" ? Number(match[1]) : Number(match[1]) * 1000;
        if (!best || score > best.score) best = { url, score, width: match[2] === "w" ? Number(match[1]) : 0 };
      }
    }
    return best;
  };
  const filename = (value, fallback = "media") => {
    let name = String(value || fallback).normalize("NFC").replace(/[\x00-\x1f\x7f\\/:*?"<>|]/g, "-").replace(/^\.+/, "").trim();
    return name.slice(0, 180) || fallback;
  };
  const nameFromUrl = url => {
    try { return decodeURIComponent(new URL(url).pathname.split("/").filter(Boolean).pop() || "image"); }
    catch { return "image"; }
  };
  const formatBytes = n => {
    if (!Number.isFinite(n) || n <= 0) return "";
    const i = Math.min(3, Math.floor(Math.log(n) / Math.log(1024)));
    return `${(n / 1024 ** i).toLocaleString(undefined, { maximumFractionDigits: i > 1 ? 1 : 0 })} ${["B", "KB", "MB", "GB"][i]}`;
  };
  const duration = n => {
    if (!(n > 0)) return "";
    const h = Math.floor(n / 3600), m = Math.floor(n % 3600 / 60), s = String(Math.floor(n % 60)).padStart(2, "0");
    return h ? `${h}:${String(m).padStart(2, "0")}:${s}` : `${m}:${s}`;
  };
  return { httpUrl, extOf, isSegment, streamKind, looksLikeImage, biggestFromSrcset, filename, nameFromUrl, formatBytes, duration };
})();
