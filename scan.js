/* Runs in each readable frame of the tab after the toolbar click. Reads the page; never changes it. The last value is returned to the extension. */
(() => {
  const M = globalThis.MediaLens;
  const base = document.baseURI;
  const box = globalThis.__mediaLensScan ||= { streams: new Map(), watching: false };
  const noteStream = (raw, size = 0, how = "Network", forced = null) => {
    const url = M.httpUrl(raw, base);
    if (!url) return;
    const kind = M.streamKind(url) || forced;
    if (!kind) return;
    const old = box.streams.get(url);
    box.streams.set(url, { url, kind, size: Math.max(size || 0, old?.size || 0), how: old?.how === "Player" ? "Player" : how, order: old?.order ?? box.streams.size });
  };
  // Streams load after the page does (often only after pressing play). From the first scan on, keep watching for them.
  if (!box.watching) {
    box.watching = true;
    try { performance.setResourceTimingBufferSize(6000); } catch { /* Keep the default buffer. */ }
    try { new PerformanceObserver(list => list.getEntries().forEach(e => noteStream(e.name, e.encodedBodySize))).observe({ type: "resource", buffered: true }); }
    catch { /* Older browser: the snapshot below still works. */ }
  }
  for (const entry of performance.getEntriesByType("resource")) noteStream(entry.name, entry.encodedBodySize);

  let hint = null, bestArea = 0;
  for (const video of document.querySelectorAll("video")) {
    const rect = video.getBoundingClientRect();
    const src = video.currentSrc || video.src || "";
    if (/^https?:/.test(src)) noteStream(src, 0, "Player", "video");
    video.querySelectorAll("source[src]").forEach(s => noteStream(s.src, 0, "Player", "video"));
    const area = rect.width * rect.height * (video.paused ? 1 : 4);
    if (rect.width > 120 && rect.height > 70 && area > bestArea) {
      bestArea = area;
      hint = { src: /^https?:/.test(src) ? src : "", blob: src.startsWith("blob:"), duration: Number.isFinite(video.duration) ? video.duration : 0,
        width: video.videoWidth || 0, height: video.videoHeight || 0, area: Math.round(area) };
    }
  }
  for (const meta of document.querySelectorAll('meta[property^="og:video"], meta[name^="og:video"], meta[name="twitter:player:stream"]')) {
    if (/(?:url|video|stream)$/i.test(meta.getAttribute("property") || meta.getAttribute("name") || "")) noteStream(meta.content, 0, "Page", "video");
  }

  const embeds = new Set();
  for (const frame of document.querySelectorAll("iframe[src]")) {
    const rect = frame.getBoundingClientRect(), url = M.httpUrl(frame.src, base);
    if (url && rect.width >= 240 && rect.height >= 135) embeds.add(url);
  }
  // Player settings and structured data are read as text, never run.
  let scanned = 0;
  for (const script of document.scripts) {
    const text = script.textContent;
    if (!text || text.length > 1500000 || scanned + text.length > 5000000) continue;
    scanned += text.length;
    if (script.type === "application/ld+json") {
      try {
        const walk = value => {
          if (Array.isArray(value)) return value.forEach(walk);
          if (!value || typeof value !== "object") return;
          if (typeof value.contentUrl === "string") noteStream(value.contentUrl, 0, "Page", "video");
          if (typeof value.embedUrl === "string") { const url = M.httpUrl(value.embedUrl, base); if (url) embeds.add(url); }
          Object.values(value).forEach(walk);
        };
        walk(JSON.parse(text));
      } catch { /* Not valid JSON. */ }
    }
    const normalised = text.replace(/\\u002[fF]|\\\//g, "/").replace(/\\u0026/g, "&").replace(/&amp;/g, "&");
    for (const match of normalised.matchAll(/https?:\/\/[^\s"'<>\\`]+?\.(?:m3u8|mpd|mp4|webm)(?:\?[^\s"'<>\\`]*)?(?=["'\s<>\\`]|$)/gi)) noteStream(match[0], 0, "Player settings");
  }

  const images = new Map();
  const addImage = (raw, { thumb = "", width = 0, height = 0, how = "Image", alt = "" } = {}) => {
    const url = M.httpUrl(raw, base);
    if (!url) return;
    const old = images.get(url);
    const cleanThumb = M.httpUrl(thumb, base) || old?.thumb || url;
    images.set(url, { url, thumb: old?.thumb || cleanThumb, width: Math.max(width || 0, old?.width || 0), height: Math.max(height || 0, old?.height || 0),
      how: old?.how || how, name: M.nameFromUrl(url), alt: String(old?.alt || alt || "").slice(0, 200) });
  };
  const LAZY = ["data-src", "data-lazy-src", "data-original", "data-lazy", "data-url", "data-full", "data-full-src", "data-large", "data-large-src", "data-hi-res", "data-zoom-image", "data-zoom-src", "data-big", "data-image"];
  for (const img of document.images) {
    const w = img.naturalWidth, h = img.naturalHeight;
    const shown = M.httpUrl(img.currentSrc || img.src, base);
    const lazy = LAZY.map(name => M.httpUrl(img.getAttribute(name), base)).find(Boolean);
    const sets = [img.getAttribute("srcset"), img.getAttribute("data-srcset"),
      ...[...(img.closest("picture")?.querySelectorAll("source") || [])].map(s => s.getAttribute("srcset") || s.getAttribute("data-srcset"))];
    const biggest = M.biggestFromSrcset(sets, base);
    if (w && h && w <= 3 && h <= 3 && !lazy && !biggest) continue; // Keep lazy images with tracking-pixel placeholders.
    const link = img.closest("a[href]")?.href;
    const full = (link && M.looksLikeImage(link) && M.httpUrl(link, base)) || lazy && M.looksLikeImage(lazy) && lazy || biggest?.url || lazy || shown;
    if (!full) continue;
    const same = full === shown;
    addImage(full, { thumb: shown || lazy || full, width: same ? w : biggest?.url === full ? biggest.width : 0,
      height: same ? h : biggest?.url === full && biggest.width && w ? Math.round(h * biggest.width / w) : 0,
      how: full === link ? "Full-size link" : same ? "Image" : "Larger version", alt: img.alt });
  }
  for (const a of document.querySelectorAll("a[href]")) if (M.looksLikeImage(a.href) && !images.has(a.href)) addImage(a.href, { how: "Link" });
  for (const meta of document.querySelectorAll('meta[property="og:image"], meta[property="og:image:url"], meta[name="twitter:image"], link[rel="image_src"]')) {
    addImage(meta.content || meta.href, { how: "Page preview" });
  }
  for (const video of document.querySelectorAll("video[poster]")) addImage(video.poster, { how: "Video poster" });
  for (const image of document.querySelectorAll("svg image")) addImage(image.getAttribute("href") || image.getAttribute("xlink:href"), { how: "Image" });
  let checked = 0;
  for (const element of document.querySelectorAll("body *")) {
    if (++checked > 8000) break;
    const style = getComputedStyle(element).backgroundImage;
    if (!style || style === "none" || !style.includes("url(")) continue;
    const rect = element.getBoundingClientRect();
    if (rect.width < 60 || rect.height < 60) continue;
    for (const m of style.matchAll(/url\(\s*(?:"([^"]*)"|'([^']*)'|([^)]*))\s*\)/gi)) addImage((m[1] ?? m[2] ?? m[3] ?? "").trim(), { how: "Background" });
  }

  // Video cards on channel, search and playlist pages: a link around a thumbnail. The helper later keeps only real video pages.
  const links = new Map();
  const here = location.href.split("#")[0];
  for (const a of document.querySelectorAll("a[href]")) {
    const url = M.httpUrl(a.href.split("#")[0], base);
    if (!url || url === here || links.size >= 400) continue;
    const img = a.querySelector("img");
    const rect = (img || a).getBoundingClientRect();
    if (!img || rect.width < 80 || rect.height < 45) continue;
    const card = a.closest("article, li, ytd-rich-item-renderer, ytd-video-renderer, ytd-grid-video-renderer, ytd-playlist-video-renderer, [class*='item' i], [class*='card' i], [class*='video' i], [class*='thumb' i]") || a.parentElement;
    const texts = [...(card?.querySelectorAll("a[href]") || [])].filter(other => other.href.split("#")[0] === url)
      .map(other => other.getAttribute("title") || other.textContent.replace(/\s+/g, " ").trim()).filter(text => text && !/^(?:\d{1,2}:)?\d{1,2}:\d{2}$/.test(text));
    const title = [a.getAttribute("title"), ...texts.sort((x, y) => y.length - x.length), img.alt, a.getAttribute("aria-label")].find(t => t && t.trim().length > 2) || "";
    const duration = (card?.textContent || "").match(/\b(?:\d{1,2}:)?\d{1,2}:\d{2}\b/)?.[0] || "";
    const thumb = M.httpUrl(img.currentSrc || img.src, base) || LAZY.map(name => M.httpUrl(img.getAttribute(name), base)).find(Boolean) || "";
    const old = links.get(url);
    if (!old) links.set(url, { url, title: title.trim().slice(0, 300), duration, thumb });
    else if (!old.title && title) old.title = title.trim().slice(0, 300);
  }

  return {
    top: window === window.top, url: location.href, title: document.title, hint, links: [...links.values()],
    streams: [...box.streams.values()].sort((a, b) => a.order - b.order).slice(0, 60),
    embeds: [...embeds].slice(0, 6),
    images: [...images.values()].slice(0, 1500)
  };
})();
