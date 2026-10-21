// Scans only the tab the toolbar popup was opened on. The local helper finds the video and saves files.
const HOST = 'com.medialens.downloader';
const popupUrl = chrome.runtime.getURL('popup.html');
const pages = new Map();
const pending = new Map();
const TIMEOUTS = {inspect: 150000, update: 660000, start_images: 30000};
let port, requestId = 0, idleTimer;
const webUrl = value => { try { const u = new URL(value); return /^https?:$/.test(u.protocol) && !u.username && !u.password ? u.href : null; } catch { return null; } };
const cookies = async () => {
  const prefs = await chrome.storage.local.get({cookies: false, cookieBrowser: 'brave'});
  return prefs.cookies ? (prefs.cookieBrowser === 'chrome' ? 'chrome' : 'brave') : null;
};
function disconnect() { clearTimeout(idleTimer); try { port?.disconnect(); } catch { /* Already closed. */ } port = null; }
function native(command, data = {}) {
  return new Promise((resolve, reject) => {
    clearTimeout(idleTimer);
    if (!port) {
      port = chrome.runtime.connectNative(HOST);
      const connection = port;
      port.onMessage.addListener(message => {
        const request = pending.get(message.id); if (!request) return;
        clearTimeout(request.timer); pending.delete(message.id);
        if (message.ok) request.resolve(message.result);
        else request.reject(Object.assign(new Error(message.error || 'The video could not be read.'), {update: Boolean(message.update)}));
        if (!pending.size) idleTimer = setTimeout(disconnect, 30000);
      });
      port.onDisconnect.addListener(() => {
        if (port !== connection) return;
        const detail = chrome.runtime.lastError?.message; port = null;
        const text = /not found/i.test(detail || '') ? 'The Media Lens helper isn’t installed. Run “Install Companion.command”.' : 'The Media Lens helper stopped. Try again.';
        for (const request of pending.values()) { clearTimeout(request.timer); request.reject(new Error(text)); }
        pending.clear();
      });
    }
    const id = ++requestId;
    pending.set(id, {resolve, reject, timer: setTimeout(() => {
      pending.delete(id); reject(new Error('This took too long. Try again.'));
      if (!pending.size) disconnect();
    }, TIMEOUTS[command] || 20000)});
    try { port.postMessage({id, command, ...data}); }
    catch (error) { clearTimeout(pending.get(id)?.timer); pending.delete(id); reject(error); disconnect(); }
  });
}
async function scan(tabId, fresh = true) {
  const tab = await chrome.tabs.get(tabId), url = webUrl(tab.url);
  if (!url) throw new Error('Open a website, then click Media Lens.');
  let frames;
  const files = ['shared.js', 'scan.js'];
  try { frames = await chrome.scripting.executeScript({target: {tabId, allFrames: true}, files}); }
  catch {
    try { frames = await chrome.scripting.executeScript({target: {tabId}, files}); }
    catch { throw new Error('Media Lens can’t read this page. Click its toolbar icon again on the page.'); }
  }
  if (webUrl((await chrome.tabs.get(tabId)).url) !== url) throw new Error('The page changed. Press Scan again.');
  const results = frames.map(f => f.result).filter(Boolean);
  const top = results.find(r => r.top) || results[0] || {};
  const streams = new Map(), images = new Map(), embeds = new Set(), links = new Map();
  let hint = null;
  for (const result of results) {
    for (const l of result.links || []) if (!links.has(l.url)) links.set(l.url, l);
    for (const s of result.streams || []) if (!streams.has(s.url)) streams.set(s.url, s);
    for (const i of result.images || []) if (!images.has(i.url)) images.set(i.url, i);
    for (const e of result.embeds || []) embeds.add(e);
    if (result.hint && (!hint || result.hint.area > hint.area)) hint = result.hint;
  }
  // The visible player first, then playlists, then the largest files. Small network files are often previews or segments.
  const rank = s => (s.url === hint?.src ? 0 : 10) + (s.how === 'Player' ? 0 : s.kind === 'video' ? 4 : 2);
  const ordered = [...streams.values()].sort((a, b) => rank(a) - rank(b) || b.size - a.size || a.order - b.order);
  const hasPlaylist = ordered.some(s => s.kind !== 'video');
  const candidates = ordered.filter(s => !(hasPlaylist && s.kind === 'video' && s.how === 'Network' && s.size < 5e6));
  const page = {url, title: top.title || tab.title || new URL(url).hostname, host: new URL(url).hostname.replace(/^www\./, ''),
    hint, streams: candidates, embeds: [...embeds].filter(e => e !== url), images: [...images.values()], links: [...links.values()], videoLinks: null, time: Date.now(), videos: null, keys: new Map()};
  const old = pages.get(tabId);
  if (!fresh && old?.url === url && old.videos) Object.assign(page, {videos: old.videos, keys: old.keys, videosTime: old.videosTime});
  pages.set(tabId, page);
  return page;
}
async function currentPage(tabId) {
  const tab = await chrome.tabs.get(tabId), page = pages.get(tabId);
  if (!page || page.url !== webUrl(tab.url)) throw new Error('The page changed. Press Scan again.');
  return page;
}
async function resolveVideos(tabId, refresh) {
  let page = refresh ? await scan(tabId) : await currentPage(tabId);
  if (page.videos && Date.now() - page.videosTime < 120000) return page.videos;
  const candidates = [...page.streams.slice(0, 6).map(s => ({url: s.url, kind: 'stream'})), ...page.embeds.slice(0, 3).map(url => ({url, kind: 'embed'}))];
  const result = await native('inspect', {url: page.url, hint: page.hint, candidates, cookies: await cookies()});
  if (pages.get(tabId) !== page || await currentPage(tabId) !== page) throw new Error('The page changed. Press Scan again.');
  page.keys = new Map();
  page.videos = result.videos.map(video => {
    const key = crypto.randomUUID(); page.keys.set(key, video);
    const {url, referer, videoId, ...shown} = video;
    return {key, ...shown};
  });
  page.videosTime = Date.now();
  return page.videos;
}
const jobId = value => { if (!/^[a-f0-9]{32}$/.test(value || '')) throw new Error('Invalid download.'); return value; };
async function handle(message) {
  const tabId = Number(message.tabId);
  const needsTab = ['SCAN', 'RESOLVE', 'LINKS', 'DOWNLOAD_VIDEO', 'DOWNLOAD_IMAGES', 'DOWNLOAD_LINKS'].includes(message.type);
  if (needsTab && (!Number.isInteger(tabId) || tabId < 0)) throw new Error('Open Media Lens on a web page.');
  switch (message.type) {
    case 'SCAN': {
      const page = await scan(tabId, Boolean(message.fresh));
      return {url: page.url, title: page.title, host: page.host, images: page.images, streams: page.streams.length, blob: Boolean(page.hint?.blob)};
    }
    case 'RESOLVE': return resolveVideos(tabId, Boolean(message.refresh));
    case 'LINKS': {
      const page = await currentPage(tabId);
      if (!page.links.length) return [];
      const {urls} = await native('match_links', {urls: page.links.map(l => l.url)});
      if (await currentPage(tabId) !== page) throw new Error('The page changed. Press Scan again.');
      const known = new Set(urls);
      page.videoLinks = page.links.filter(l => known.has(l.url));
      return page.videoLinks;
    }
    case 'DOWNLOAD_LINKS': {
      const page = await currentPage(tabId), wanted = new Set(Array.isArray(message.urls) ? message.urls : []);
      const items = (page.videoLinks || []).filter(l => wanted.has(l.url)).slice(0, 100).map(l => ({url: l.url, title: l.title || 'Video'}));
      if (!items.length) throw new Error('Tick at least one video.');
      if (wanted.size > 100) throw new Error('Choose up to 100 videos at a time.');
      if (!['best', '2160', '1440', '1080', '720', '480', '360', '240', 'audio'].includes(String(message.quality || 'best'))) throw new Error('Choose one of the listed qualities.');
      return native('start_many', {items, quality: String(message.quality || 'best'), referer: page.url, cookies: await cookies()});
    }
    case 'DOWNLOAD_VIDEO': {
      const page = await currentPage(tabId), video = page.keys.get(message.key);
      if (!video) throw new Error('The page changed. Press Scan again.');
      const choice = video.qualities.find(q => q.quality === message.quality);
      if (!choice) throw new Error('Choose one of this video’s qualities.');
      if (video.live) throw new Error('Live streams can’t be saved yet.');
      const mainPlayer = ['site', 'page'].includes(video.source);
      return native('start', {url: video.url, referer: video.referer, title: video.title, quality: choice.quality, label: choice.label,
        videoId: video.videoId, hint: mainPlayer ? page.hint : null, cookies: await cookies()});
    }
    case 'DOWNLOAD_IMAGES': {
      const page = await currentPage(tabId), wanted = new Set(Array.isArray(message.urls) ? message.urls : []);
      if (wanted.size > 1000) throw new Error('Choose up to 1000 images at a time.');
      const items = page.images.filter(i => wanted.has(i.url)).slice(0, 1000).map(i => ({url: i.url, name: i.name}));
      if (!items.length) throw new Error('Choose at least one image.');
      return native('start_images', {items, referer: page.url, folder: `${page.host} - ${page.title}`.slice(0, 100), cookies: await cookies()});
    }
    case 'JOBS': return native('jobs');
    case 'CANCEL': return native('cancel', {jobId: jobId(message.jobId)});
    case 'RETRY': return native('retry', {jobId: jobId(message.jobId)});
    case 'REVEAL': return native('reveal', {jobId: jobId(message.jobId)});
    case 'CLEAR': return native('clear');
    case 'OPEN_FOLDER': return native('open_folder');
    case 'STATUS': return native('status');
    case 'UPDATE_ENGINE': {
      const result = await native('update');
      disconnect(); // The next request starts a helper that uses the new engine.
      for (const page of pages.values()) page.videos = null;
      return result;
    }
  }
  throw new Error('Unknown Media Lens command.');
}
chrome.tabs.onRemoved.addListener(tabId => { pages.delete(tabId); chrome.storage.session.remove(`selection:${tabId}`).catch(() => {}); });
chrome.tabs.onUpdated.addListener((tabId, change) => { if (change.url && pages.get(tabId)?.url !== change.url) pages.delete(tabId); });
chrome.runtime.onMessage.addListener((message, sender, reply) => {
  if (sender.id !== chrome.runtime.id || sender.url?.split(/[?#]/)[0] !== popupUrl) return;
  if (!message || typeof message !== 'object') return;
  handle(message).then(result => reply({ok: true, result}), error => reply({ok: false, error: error.message, update: Boolean(error.update)}));
  return true;
});
