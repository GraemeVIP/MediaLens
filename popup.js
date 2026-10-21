const M = globalThis.MediaLens;
const $ = selector => document.querySelector(selector);
const FINAL = new Set(['complete', 'failed', 'cancelled']);
// Static icon markup only. Page text is always inserted with textContent.
const ICONS = {
  download: '<svg viewBox="0 0 24 24"><path d="M12 4v11m-5-5 5 5 5-5M5 20h14"/></svg>',
  check: '<svg viewBox="0 0 24 24"><path d="m5 12 5 5 9-10"/></svg>',
  video: '<svg viewBox="0 0 24 24"><rect x="3" y="6" width="13" height="12" rx="2"/><path d="m16 10 5-3v10l-5-3"/></svg>',
  image: '<svg viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="9" cy="10" r="2"/><path d="m21 16-5-5-9 9"/></svg>',
  search: '<svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/></svg>',
  warning: '<svg viewBox="0 0 24 24"><path d="M12 3 2 20h20L12 3z"/><path d="M12 10v4m0 3h.01"/></svg>',
  inbox: '<svg viewBox="0 0 24 24"><path d="M4 13h4l2 3h4l2-3h4M5 5h14l2 8v6H3v-6l2-8z"/></svg>'
};
const el = (tag, props = {}, ...children) => {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'icon') node.innerHTML = ICONS[value];
    else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? '' : value);
  }
  node.append(...children.flat().filter(child => child !== null && child !== undefined && child !== false && child !== ''));
  return node;
};

let tabId, page = null, videos = null, videoError = null, images = [], jobs = [], jobsShown = '';
let selected = new Set(), hideSmall = true, userPickedTab = false, currentTab = 'videos', scanRun = 0, toastTimer;
let videoLinks = [], selectedVideos = new Set(), linksLoading = false, linkError = null, bulkQuality = 'best', savingVideos = false, restoredSelection;

function persistSelection() {
  if (!page) return;
  chrome.storage.session.set({[`selection:${tabId}`]: {url: page.url, images: [...selected], videos: [...selectedVideos], quality: bulkQuality, panel: currentTab}}).catch(() => {});
}

async function send(type, data = {}) {
  const response = await chrome.runtime.sendMessage({type, tabId, ...data});
  if (!response?.ok) throw Object.assign(new Error(response?.error || 'Media Lens didn’t respond. Close this popup and try again.'), {update: Boolean(response?.update)});
  return response.result;
}
function toast(text) {
  const box = $('#toast'); box.textContent = text; box.hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => { box.hidden = true; }, 3500);
}
function state(icon, title, text, ...extra) {
  return el('div', {class: 'state'}, el('div', {class: 'badge-icon', icon}), el('h2', {}, title), text && el('p', {}, text), ...extra);
}

/* Tabs */
function showTab(name, byUser = false) {
  if (byUser) userPickedTab = true;
  currentTab = name;
  for (const button of document.querySelectorAll('.tabs button')) {
    const on = button.dataset.panel === name;
    button.setAttribute('aria-selected', String(on)); button.tabIndex = on ? 0 : -1;
  }
  for (const panel of ['videos', 'images', 'downloads']) $(`#panel-${panel}`).hidden = panel !== name;
  $('#panel-settings').hidden = true; $('.tabs').hidden = false;
  updateBar();
  if (byUser) persistSelection();
  if (name === 'downloads') { jobsShown = ''; renderJobs(); }
}

/* Scanning */
async function scanPage(fresh = false) {
  const run = ++scanRun;
  $('#rescan').disabled = true; $('#rescan').classList.add('spinning');
  const finish = () => { if (run === scanRun) { $('#rescan').disabled = false; $('#rescan').classList.remove('spinning'); } };
  videos = null; videoLinks = []; linksLoading = true; linkError = null;
  renderVideosLoading(); renderLinks(); updateBar();
  try {
    const nextPage = await send('SCAN', {fresh});
    if (run !== scanRun) return;
    if (page && page.url !== nextPage.url) { selected.clear(); selectedVideos.clear(); }
    page = nextPage;
    if (restoredSelection?.url === page.url) {
      selected = new Set(restoredSelection.images || []); selectedVideos = new Set(restoredSelection.videos || []);
      bulkQuality = restoredSelection.quality || 'best';
      if (['videos', 'images', 'downloads'].includes(restoredSelection.panel)) showTab(restoredSelection.panel);
    }
    restoredSelection = null;
    $('#site').textContent = page.host;
    images = page.images;
    const known = new Set(images.map(i => i.url)); selected = new Set([...selected].filter(url => known.has(url)));
    renderImages();
  } catch (error) {
    if (run !== scanRun) return;
    videos = []; linksLoading = false; videoError = Object.assign(error, {unreadable: true}); images = []; renderVideos(); renderImages(); finish(); return;
  }
  await Promise.all([
    (async () => {
      try {
        const found = await send('LINKS');
        if (run !== scanRun) return;
        videoLinks = found;
        const known = new Set(found.map(v => v.url)); selectedVideos = new Set([...selectedVideos].filter(url => known.has(url)));
      } catch (error) { if (run === scanRun) linkError = error; }
      if (run === scanRun) { linksLoading = false; renderVideos(); }
    })(),
    (async () => {
      try { const found = await send('RESOLVE', {refresh: false}); if (run === scanRun) { videos = found; videoError = null; } }
      catch (error) { if (run === scanRun) { videos = []; videoError = error; } }
      if (run === scanRun) renderVideos();
    })()
  ]);
  if (run !== scanRun) return;
  renderVideos();
  if (!userPickedTab && !videoLinks.length && !videos.length && visibleImages().length && currentTab === 'videos') showTab('images');
  persistSelection();
  finish();
}

/* Videos */
function renderVideosLoading() {
  $('#count-videos').textContent = '';
  $('#single-videos').replaceChildren(el('div', {class: 'loading', 'aria-busy': 'true'},
    el('div', {class: 'skeleton hero'}), el('div', {class: 'skeleton line'}), el('div', {class: 'skeleton line short'}),
    el('p', {}, 'Looking for videos… this can take a few seconds.')));
}
function renderVideos() {
  renderLinks();
  const panel = $('#single-videos');
  $('#count-videos').textContent = (videoLinks.length || videos?.length) || '';
  if (videos === null) {
    if (videoLinks.length) panel.replaceChildren(el('p', {class: 'note'}, 'Checking the current player…'));
    return;
  }
  if (!videos.length && (videoLinks.length || linksLoading)) { panel.replaceChildren(); return; }
  if (!videos.length) {
    const error = videoError || linkError;
    if (error?.unreadable) return panel.replaceChildren(state('warning', 'Can’t read this page', error.message,
      el('div', {class: 'actions'}, el('button', {class: 'primary', type: 'button', onclick: () => scanPage(true)}, 'Try again'))));
    const actions = el('div', {class: 'actions'}, el('button', {class: 'primary', type: 'button', onclick: () => scanPage(true)}, 'Scan again'),
      error?.update && el('button', {class: 'secondary', type: 'button', onclick: () => { openSettings(); updateEngine(); }}, 'Update engine'));
    const tips = error?.update ? null : el('ol', {class: 'tips'}, el('li', {}, 'Press play on the video.'), el('li', {}, 'Wait a few seconds.'), el('li', {}, 'Press Scan again.'));
    return panel.replaceChildren(state('search', 'No video found yet', error?.message && !/^No video was found here/.test(error.message) ? error.message : 'Some sites only load the video after you press play.', tips, actions));
  }
  const [main, ...others] = videos;
  panel.replaceChildren(videoCard(main, true),
    others.length ? el('h3', {class: 'section-title'}, `Other videos on this page (${others.length})`) : null,
    ...others.map(video => videoCard(video, false)));
}

function renderLinks() {
  const holder = $('#linked-videos');
  if (!videoLinks.length) { holder.replaceChildren(); updateBar(); return; }
  const allSelected = () => videoLinks.slice(0, 100).every(video => selectedVideos.has(video.url));
  const selectionLabel = () => allSelected() ? 'Select none' : videoLinks.length > 100 ? 'Select first 100' : 'Select all';
  const selectAll = el('button', {class: 'text-button', type: 'button', onclick: () => {
    if (allSelected()) selectedVideos.clear();
    else selectedVideos = new Set(videoLinks.slice(0, 100).map(video => video.url));
    persistSelection(); renderLinks();
  }}, selectionLabel());
  const quality = el('select', {id: 'batch-quality', 'aria-label': 'Quality for selected videos'});
  for (const [value, text] of [['best','Best available'],['2160','Up to 4K'],['1080','Up to 1080p'],['720','Up to 720p'],['480','Up to 480p'],['audio','Sound only']]) {
    quality.append(el('option', {value}, text));
  }
  quality.value = bulkQuality;
  quality.addEventListener('change', () => { bulkQuality = quality.value; persistSelection(); });
  holder.replaceChildren(
    el('div', {class: 'toolbar'}, el('strong', {}, `${videoLinks.length} videos on this page`), selectAll),
    el('p', {class: 'channel-help'}, 'Tick the videos you want. Scroll the page to load more, then scan again.'),
    el('label', {class: 'batch-options'}, 'Save quality', quality),
    el('div', {class: 'channel-list'}, videoLinks.map(video => {
      const input = el('input', {type: 'checkbox', checked: selectedVideos.has(video.url), 'aria-label': `Select ${video.title || 'video'}`});
      input.addEventListener('change', () => {
        if (input.checked && selectedVideos.size >= 100) { input.checked = false; toast('Choose up to 100 videos at a time.'); return; }
        if (input.checked) selectedVideos.add(video.url); else selectedVideos.delete(video.url);
        selectAll.textContent = selectionLabel();
        persistSelection(); updateBar();
      });
      return el('label', {class: 'channel-video'}, input,
        video.thumb ? el('img', {src: video.thumb, alt: '', loading: 'lazy', referrerpolicy: 'no-referrer', onerror: e => e.target.hidden = true}) : null,
        el('span', {}, el('strong', {}, video.title || 'Untitled video'), video.duration && el('span', {class: 'meta'}, video.duration)));
    })), el('p', {class: 'channel-help'}, 'Downloads run three at a time and continue after you close this popup.'));
  updateBar();
}
function thumbnail(video) {
  return video.thumbnail ? el('img', {src: video.thumbnail, alt: '', loading: 'lazy', referrerpolicy: 'no-referrer', onerror: event => event.target.remove()}) : null;
}
function videoCard(video, open) {
  const time = M.duration(video.duration);
  const count = video.qualities.filter(q => q.quality !== 'audio').length;
  const where = {site: video.site, page: video.site, stream: 'Found while playing', embed: 'Embedded player'}[video.source] || video.site;
  const meta = [where, video.live ? 'Live' : `${count} ${count === 1 ? 'quality' : 'qualities'}`].filter(Boolean).join(' · ');
  const list = video.live ? el('p', {class: 'note'}, 'This is a live stream. Live streams can’t be saved yet.')
    : el('ul', {class: 'qualities'}, video.qualities.map((quality, index) => qualityRow(video, quality, index === 0)));
  if (open) {
    return el('article', {class: 'video'},
      el('div', {class: 'thumb'}, thumbnail(video), time && el('span', {class: 'time'}, time)),
      el('div', {class: 'video-body'}, el('h2', {title: video.title}, video.title), el('p', {class: 'meta'}, meta)), list);
  }
  return el('details', {class: 'video'},
    el('summary', {class: 'compact'}, thumbnail(video) || el('span', {class: 'blank'}),
      el('div', {}, el('h2', {title: video.title}, video.title), el('p', {class: 'meta'}, [time, meta].filter(Boolean).join(' · ')))), list);
}
function qualityRow(video, quality, first) {
  const audio = quality.quality === 'audio';
  const size = quality.size ? `${quality.approx ? '~' : ''}${M.formatBytes(quality.size)}` : '';
  const button = el('button', {class: 'download', type: 'button', 'aria-label': `Download ${quality.label}`}, el('span', {icon: 'download'}), 'Save');
  button.addEventListener('click', async event => {
    if (!event.isTrusted) return;
    button.disabled = true;
    try {
      await send('DOWNLOAD_VIDEO', {key: video.key, quality: quality.quality});
      button.classList.add('done'); button.replaceChildren(el('span', {icon: 'check'}), 'Started');
      toast(`Saving ${audio ? 'the sound' : quality.label}. Follow it in Downloads.`);
      refreshJobs();
      setTimeout(() => { button.disabled = false; button.classList.remove('done'); button.replaceChildren(el('span', {icon: 'download'}), 'Save'); }, 4000);
    } catch (error) { button.disabled = false; toast(error.message); }
  });
  return el('li', {class: `quality${first && !audio ? ' first' : ''}`},
    el('span', {class: 'label'}, audio ? 'Sound only' : quality.label),
    first && !audio ? el('span', {class: 'tag best'}, 'Best') : quality.badge ? el('span', {class: 'tag'}, quality.badge) : audio ? el('span', {class: 'tag'}, 'M4A') : null,
    el('span', {class: 'size'}, size), button);
}

/* Images */
const visibleImages = () => images.filter(i => !hideSmall || !(i.width && i.height && Math.max(i.width, i.height) < 200));
function renderImages() {
  const list = visibleImages(), grid = $('#image-grid'), holder = $('#images-state');
  $('#count-images').textContent = list.length || '';
  grid.replaceChildren(...list.map(tile));
  $('.toolbar').hidden = !images.length;
  holder.replaceChildren(
    !images.length ? state('image', 'No images found', page ? 'This page has no images Media Lens can save.' : 'Scanning this page…')
      : !list.length ? state('image', 'Only small images here', 'Turn off “Hide small” to see icons and thumbnails.') : '');
  $('#select-all').textContent = list.length && list.slice(0, 1000).every(i => selected.has(i.url)) ? 'Select none' : list.length > 1000 ? 'Select first 1000' : 'Select all';
  updateBar();
}
function tile(image) {
  const button = el('button', {class: 'tile', type: 'button', 'aria-label': `Select ${image.alt || image.name || 'image'}`, 'aria-pressed': String(selected.has(image.url)), title: image.alt || image.name});
  const picture = el('img', {src: image.thumb, alt: image.alt || '', loading: 'lazy', decoding: 'async', referrerpolicy: 'no-referrer'});
  const dims = el('span', {class: 'dims'}, image.width && image.height ? `${image.width}×${image.height}` : '');
  picture.addEventListener('error', () => button.classList.add('broken'));
  picture.addEventListener('load', () => { if (!dims.textContent && image.thumb === image.url) dims.textContent = `${picture.naturalWidth}×${picture.naturalHeight}`; });
  button.append(picture, el('span', {class: 'check', icon: 'check'}), dims);
  button.addEventListener('click', () => {
    if (!selected.has(image.url) && selected.size >= 1000) { toast('Choose up to 1000 images at a time.'); return; }
    if (selected.has(image.url)) selected.delete(image.url); else selected.add(image.url);
    button.setAttribute('aria-pressed', String(selected.has(image.url)));
    persistSelection();
    const list = visibleImages(); $('#select-all').textContent = list.slice(0, 1000).every(i => selected.has(i.url)) ? 'Select none' : list.length > 1000 ? 'Select first 1000' : 'Select all';
    updateBar();
  });
  return button;
}
function updateBar() {
  const show = currentTab === 'images' && !$('#panel-images').hidden && visibleImages().length > 0;
  $('#image-bar').hidden = !show;
  const count = selected.size;
  $('#selected-label').textContent = count ? `${count} selected` : 'Tap images to select';
  $('#save-images').disabled = !count;
  $('#save-images').textContent = count ? `Download ${count}` : 'Download';
  $('#video-bar').hidden = currentTab !== 'videos' || $('#panel-videos').hidden || !videoLinks.length;
  $('#selected-videos-label').textContent = selectedVideos.size ? `${selectedVideos.size} selected` : 'Tick videos to select';
  $('#save-videos').disabled = !selectedVideos.size || savingVideos;
  $('#save-videos').textContent = savingVideos ? 'Starting…' : selectedVideos.size ? `Download ${selectedVideos.size}` : 'Download';
}

/* Downloads */
async function refreshJobs() {
  try { jobs = await send('JOBS'); } catch { return; }
  const active = jobs.filter(job => !FINAL.has(job.status)).length;
  $('#count-downloads').textContent = active || '';
  if (currentTab === 'downloads' && !$('#panel-downloads').hidden) renderJobs();
}
function jobStatus(job) {
  const bytes = n => M.formatBytes(n);
  if (job.status === 'queued') return 'Waiting to start';
  if (job.status === 'analysing') return 'Getting ready…';
  if (job.status === 'processing') return 'Joining video and sound…';
  if (job.status === 'cancelled') return 'Cancelled';
  if (job.status === 'failed') return job.error || 'Failed';
  if (job.kind === 'images') {
    if (job.status === 'complete') return [`${job.saved} saved`, job.failedCount ? `${job.failedCount} failed` : ''].filter(Boolean).join(' · ');
    return `${job.saved || 0} of ${job.count} saved`;
  }
  if (job.status === 'complete') return ['Saved', job.label, bytes(job.totalBytes)].filter(Boolean).join(' · ');
  const part = job.part > 1 ? 'Sound' : '';
  return [part, job.percent ? `${Math.round(job.percent)}%` : 'Downloading', job.totalBytes ? `${bytes(job.downloadedBytes)} of ${bytes(job.totalBytes)}` : ''].filter(Boolean).join(' · ');
}
function jobRow(job) {
  const active = !FINAL.has(job.status), failed = job.status === 'failed';
  const act = (label, type) => el('button', {type: 'button', onclick: async () => {
    try { await send(type, {jobId: job.id}); refreshJobs(); } catch (error) { toast(error.message); }
  }}, label);
  const actions = active ? [act('Cancel', 'CANCEL')]
    : job.status === 'complete' ? [act('Show in Finder', 'REVEAL'), job.failedCount ? act('Retry failed', 'RETRY') : null]
    : [act('Retry', 'RETRY')];
  const progress = active ? (job.percent > 0 ? el('progress', {max: '100', value: String(job.percent)}) : el('progress', {})) : null;
  return el('div', {class: 'job'},
    el('span', {class: `job-icon${job.status === 'complete' ? ' done' : failed ? ' bad' : ''}`, icon: job.kind === 'images' ? 'image' : 'video'}),
    el('div', {class: 'job-main'}, el('strong', {title: job.title}, job.title),
      el('span', {class: `job-status${failed ? ' bad' : ''}`}, jobStatus(job)), progress),
    el('div', {class: 'job-actions'}, actions));
}
function renderJobs() {
  const signature = JSON.stringify(jobs.map(j => [j.id, j.status, Math.round(j.percent || 0), j.saved, j.downloadedBytes, j.error]));
  if (signature === jobsShown) return;
  jobsShown = signature;
  const active = jobs.filter(job => !FINAL.has(job.status)).sort((a, b) =>
    Number(a.status === 'queued') - Number(b.status === 'queued') || a.created - b.created);
  const shown = [...active, ...jobs.filter(job => FINAL.has(job.status)).slice(0, 40)];
  $('#job-list').replaceChildren(...(shown.length ? shown.map(jobRow) : [state('inbox', 'No downloads yet', 'Videos and images you save will appear here.')]));
  $('#clear-finished').hidden = !jobs.some(job => FINAL.has(job.status));
}

/* Settings */
async function openSettings() {
  for (const panel of ['videos', 'images', 'downloads']) $(`#panel-${panel}`).hidden = true;
  $('.tabs').hidden = true; $('#image-bar').hidden = true; $('#video-bar').hidden = true; $('#panel-settings').hidden = false;
  $('#use-cookies').checked = (await chrome.storage.local.get({cookies: false})).cookies;
  $('#cookie-browser').value = (await chrome.storage.local.get({cookieBrowser: 'brave'})).cookieBrowser;
  try {
    const status = await send('STATUS');
    const age = status.engineAgeDays;
    $('#engine-info').textContent = `Version ${status.engine.split('.').slice(0, 3).join('.')}` +
      (age === null ? '' : age > 30 ? ` · ${age} days old — update recommended` : age > 1 ? ` · ${age} days old` : ' · up to date');
  } catch (error) { $('#engine-info').textContent = error.message; }
}
async function updateEngine() {
  const button = $('#update-engine');
  button.disabled = true; button.textContent = 'Updating…';
  $('#engine-info').textContent = 'Getting the latest engine. This can take a minute.';
  try {
    const result = await send('UPDATE_ENGINE');
    $('#engine-info').textContent = `Version ${result.engine.split('.').slice(0, 3).join('.')} · up to date`;
    toast('Video engine updated. Scanning again.');
    scanPage(true);
  } catch (error) { $('#engine-info').textContent = error.message; }
  button.disabled = false; button.textContent = 'Update';
}

/* Wiring */
for (const button of document.querySelectorAll('.tabs button')) {
  button.addEventListener('click', () => showTab(button.dataset.panel, true));
  button.addEventListener('keydown', event => {
    if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return;
    const all = [...document.querySelectorAll('.tabs button')], next = all[(all.indexOf(button) + (event.key === 'ArrowRight' ? 1 : all.length - 1)) % all.length];
    next.focus(); showTab(next.dataset.panel, true);
  });
}
$('#rescan').addEventListener('click', () => scanPage(true));
$('#open-settings').addEventListener('click', openSettings);
$('#close-settings').addEventListener('click', () => showTab(currentTab));
$('#update-engine').addEventListener('click', updateEngine);
$('#use-cookies').addEventListener('change', async event => {
  await chrome.storage.local.set({cookies: event.target.checked});
  toast(event.target.checked ? 'Sign-ins on. Scan again to use them.' : 'Sign-ins off.');
});
$('#cookie-browser').addEventListener('change', async event => {
  await chrome.storage.local.set({cookieBrowser: event.target.value});
  toast('Browser saved. Scan again to use its sign-ins.');
});
$('#hide-small').addEventListener('change', event => { hideSmall = event.target.checked; chrome.storage.local.set({hideSmall}); renderImages(); });
$('#select-all').addEventListener('click', () => {
  const list = visibleImages().slice(0, 1000), all = list.every(i => selected.has(i.url));
  if (!all) selected.clear();
  for (const image of list) all ? selected.delete(image.url) : selected.add(image.url);
  persistSelection(); renderImages();
});
$('#save-images').addEventListener('click', async event => {
  if (!event.isTrusted || !selected.size) return;
  const button = $('#save-images'); button.disabled = true;
  try {
    await send('DOWNLOAD_IMAGES', {urls: [...selected]});
    toast(`Saving ${selected.size} image${selected.size === 1 ? '' : 's'}. Follow it in Downloads.`);
    selected.clear(); persistSelection(); renderImages(); showTab('downloads', true); refreshJobs();
  } catch (error) { toast(error.message); updateBar(); }
});
$('#save-videos').addEventListener('click', async event => {
  if (!event.isTrusted || savingVideos || !selectedVideos.size) return;
  savingVideos = true; updateBar();
  try {
    const started = await send('DOWNLOAD_LINKS', {urls: [...selectedVideos], quality: bulkQuality});
    selectedVideos.clear(); persistSelection(); renderLinks(); showTab('downloads', true);
    toast(`${started.length} video${started.length === 1 ? '' : 's'} added to Downloads.`); await refreshJobs();
  } catch (error) { toast(error.message); }
  finally { savingVideos = false; updateBar(); }
});
for (const id of ['#open-folder', '#settings-folder']) $(id).addEventListener('click', () => send('OPEN_FOLDER').catch(error => toast(error.message)));
$('#clear-finished').addEventListener('click', async () => { await send('CLEAR').catch(() => {}); refreshJobs(); });

(async () => {
  hideSmall = (await chrome.storage.local.get({hideSmall: true})).hideSmall;
  $('#hide-small').checked = hideSmall;
  const [tab] = await chrome.tabs.query({active: true, currentWindow: true});
  tabId = tab?.id;
  restoredSelection = (await chrome.storage.session.get(`selection:${tabId}`))[`selection:${tabId}`];
  renderImages();
  refreshJobs(); setInterval(refreshJobs, 1500);
  scanPage(false);
})();
