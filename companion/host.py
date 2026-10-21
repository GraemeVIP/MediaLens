#!/usr/bin/env python3
"""Private native-messaging bridge. No network listener or shell execution. Browser sign-ins are read only when the user turns them on."""
from pathlib import Path
import concurrent.futures, contextlib, fcntl, itertools, json, os, re, signal, struct, subprocess, sys, threading, time, urllib.parse, uuid
VERSION = '2.2.0'
STATE = Path(os.environ.get('MEDIA_LENS_STATE_DIR', Path.home() / 'Library/Application Support/Media Lens'))
JOBS = STATE / 'jobs'
CONFIG = STATE / 'config.json'
FINAL = {'complete', 'failed', 'cancelled'}
PRIVATE = {'url', 'referer', 'hint', 'items', 'cookies'}
BROWSERS = {'brave', 'chrome'}
WRITE_LOCK = threading.Lock()
IMAGE_TYPES = {'image/jpeg': '.jpg', 'image/jpg': '.jpg', 'image/pjpeg': '.jpg', 'image/png': '.png', 'image/gif': '.gif', 'image/webp': '.webp',
               'image/avif': '.avif', 'image/svg+xml': '.svg', 'image/bmp': '.bmp', 'image/heic': '.heic', 'image/heif': '.heif',
               'image/tiff': '.tiff', 'image/x-icon': '.ico', 'image/vnd.microsoft.icon': '.ico', 'image/jxl': '.jxl'}
IMAGE_SUFFIXES = set(IMAGE_TYPES.values()) | {'.jpeg', '.jfif', '.tif'}
MAX_IMAGE_BYTES = 300 * 1024 * 1024
MAX_IMAGES = 1000
KEEP_JOBS = 150

def initialise():
    STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    JOBS.mkdir(exist_ok=True, mode=0o700)
    os.chmod(STATE, 0o700); os.chmod(JOBS, 0o700)

def config(): return json.loads(CONFIG.read_text())

def output_dir(): return Path(config()['output']).expanduser().resolve()

def valid_url(value):
    if not isinstance(value, str) or len(value) > 16384 or any(ord(c) < 32 for c in value): raise ValueError('Enter a valid http or https video URL.')
    u = urllib.parse.urlsplit(value)
    if u.scheme not in {'http', 'https'} or not u.hostname or u.username or u.password: raise ValueError('Enter a valid http or https video URL.')
    return value

def browser(value): return value if value in BROWSERS else None

def job_path(job_id):
    if not isinstance(job_id, str) or not re.fullmatch(r'[0-9a-f]{32}', job_id): raise ValueError('Invalid download ID.')
    return JOBS / (job_id + '.json')

def save(job):
    path = job_path(job['id']); temporary = path.with_suffix(f'.{os.getpid()}.{threading.get_ident()}.tmp')
    with temporary.open('w') as f:
        os.chmod(temporary, 0o600); json.dump(job, f, ensure_ascii=False)
    temporary.replace(path)

def load(job_id): return json.loads(job_path(job_id).read_text())

def public(job):
    item = {k: v for k, v in job.items() if k not in PRIVATE}
    if item.get('path'): item['file'] = Path(item['path']).name
    return item

def jobs():
    result = []
    for path in JOBS.glob('*.json'):
        try: result.append(public(json.loads(path.read_text())))
        except (ValueError, OSError): pass
    return sorted(result, key=lambda j: j.get('created', 0), reverse=True)

def prune():
    finished = [j for j in jobs() if j['status'] in FINAL]
    for job in finished[KEEP_JOBS:]:
        job_path(job['id']).unlink(missing_ok=True); (JOBS / (job['id'] + '.cancel')).unlink(missing_ok=True)

def clean_error(value):
    text = re.sub(r'\x1b\[[0-9;]*m', '', str(value)); text = re.sub(r'https?://\S+', '[media URL]', text)
    return text.replace('ERROR: ', '').strip()[:450] or 'No video was found. Press play on the video, wait a few seconds, then scan again.'

def friendly_error(value):
    """Turn engine errors into one plain sentence. The second value says whether an engine update may help."""
    text = clean_error(value); low = text.lower()
    if 'unsupported url' in low or 'no video formats found' in low or 'no video was found' in low:
        return 'No video was found here. Press play on the video, wait a few seconds, then press Scan again.', False
    if 'drm' in low: return 'This video is copy-protected (DRM), so it can’t be saved.', False
    if any(word in low for word in ('sign in', 'log in', 'login', 'logged-in', 'members', 'premium', 'private video', 'cookies')):
        return 'This video needs you to be signed in. Turn on “Use my browser sign-ins” in Settings, then try again.', False
    if 'not available in your country' in low or 'geo' in low and 'restrict' in low: return 'This video isn’t available in your country.', False
    if 'requested format is not available' in low: return 'That quality is no longer offered. Scan again and pick another.', False
    if '403' in low or 'forbidden' in low: return 'The site refused the download. Scan again and retry; some links expire quickly.', False
    if 'unable to extract' in low or 'please report this issue' in low or 'extractorerror' in low:
        return 'This site seems to have changed. Update the video engine in Settings, then try again.', True
    return text, False

class NothingFound(Exception):
    def __init__(self, message, update=False): super().__init__(message); self.update = update

class QuietLogger:
    def debug(self, *_): pass
    def info(self, *_): pass
    def warning(self, *_): pass
    def error(self, *_): pass

def options(referer=None, cookies=None):
    cfg = config()
    opts = {'quiet': True, 'no_warnings': True, 'logger': QuietLogger(), 'noplaylist': True,
            'cachedir': False, 'socket_timeout': 20, 'retries': 3, 'fragment_retries': 5,
            'extractor_retries': 2, 'concurrent_fragment_downloads': 4, 'ffmpeg_location': cfg['ffmpeg'],
            'js_runtimes': {'node': {'path': cfg['node']}}, 'nocheckcertificate': False,
            'allow_unplayable_formats': False,
            # Same resolution first; then formats QuickTime can play without conversion.
            'format_sort': ['res', 'vcodec:h264', 'acodec:aac', 'ext:mp4:m4a']}
    if referer: opts['http_headers'] = {'Referer': valid_url(referer)}
    if browser(cookies): opts['cookiesfrombrowser'] = (cookies,)
    return opts

def select_main_entry(entries, hint=None, video_id=None):
    entries = [e for e in itertools.islice(entries, 60) if isinstance(e, dict)]
    if not entries: raise ValueError('No video was found on this page.')
    if video_id:
        match = next((e for e in entries if str(e.get('id')) == video_id), None)
        if match: return match
    hint = hint if isinstance(hint, dict) else {}
    duration = float(hint.get('duration') or 0)
    source = hint.get('src')
    def score(entry):
        urls = [entry.get('url')] + [f.get(key) for f in entry.get('formats') or [] for key in ('url', 'manifest_url')]
        if source and source in urls: return 1000000
        length = float(entry.get('duration') or 0)
        if duration and length: return 100000 - abs(length-duration)
        return length
    return max(entries, key=score)

def resolve_main(ydl, url, hint=None, video_id=None, pick=True):
    info = ydl.extract_info(valid_url(url), download=False, process=False)
    if not pick and info.get('entries') is not None: raise NothingFound('This page is a list of videos, not one video.')
    for _ in range(5):
        if info.get('entries') is not None: info = select_main_entry(info['entries'], hint, video_id)
        info = ydl.process_ie_result(info, download=False)
        if info.get('entries') is None: break
    else: raise ValueError('This page did not identify a single video. Open the video’s own page and try again.')
    if video_id and str(info.get('id')) != video_id:
        raise ValueError('The video on this page changed. Open Media Lens again before downloading.')
    return info

def size_of(item, duration=0):
    size = item.get('filesize') or item.get('filesize_approx') or 0
    if not size and item.get('tbr') and duration: size = item['tbr'] * 125 * duration
    return int(size or 0)

def resolution_options(formats, duration=0):
    usable = [f for f in formats if not f.get('has_drm')]
    audio = [f for f in usable if f.get('vcodec') == 'none' and f.get('acodec') not in (None, 'none')]
    audio_size = max((size_of(f, duration) for f in audio), default=0)
    groups = {}
    for item in usable:
        if item.get('vcodec') in {'none', 'images'}: continue
        height = int(item.get('height') or 0)
        if not 0 < height <= 8640: continue
        width = int(item.get('width') or 0)
        side = min(width, height) if width else height  # Upright phone videos are labelled by their short side.
        size = size_of(item, duration)
        if size and item.get('acodec') == 'none': size += audio_size
        row = {'height': height, 'side': side, 'quality': f'height:{height}', 'label': f'{side}p', 'size': size,
               'approx': not item.get('filesize')}
        old = groups.get(side)
        if not old or size > old['size']: groups[side] = row
    rows = []
    for row in sorted(groups.values(), key=lambda r: r['side'], reverse=True):
        if rows and rows[-1]['side'] - row['side'] <= max(8, rows[-1]['side'] * 0.02): continue  # 1080 and 1072 are one choice.
        row['badge'] = '8K' if row['side'] >= 4320 else '4K' if row['side'] >= 2160 else '2K' if row['side'] >= 1440 else 'HD' if row['side'] >= 720 else ''
        rows.append(row)
    if not rows: rows = [{'height': 0, 'side': 0, 'quality': 'best', 'label': 'Best available', 'size': 0, 'approx': True, 'badge': ''}]
    if audio: rows.append({'height': 0, 'side': 0, 'quality': 'audio', 'label': 'Audio only', 'size': audio_size, 'approx': True, 'badge': ''})
    return rows

def media_urls(info):
    return {u for f in (info.get('formats') or [info]) for u in (f.get('url'), f.get('manifest_url')) if u}

def describe(info, source, url, referer=None):
    formats = info.get('formats') or [info]
    if not [f for f in formats if not f.get('has_drm')]: raise ValueError('This video is copy-protected (DRM), so it can’t be saved.')
    duration = float(info.get('duration') or 0)
    thumbnail = info.get('thumbnail') or ''
    with contextlib.suppress(ValueError): thumbnail = valid_url(thumbnail) if thumbnail else ''
    return {'title': str(info.get('title') or 'Untitled video')[:300], 'url': valid_url(url), 'referer': referer,
            'videoId': str(info.get('id') or ''), 'duration': duration, 'thumbnail': thumbnail if thumbnail.startswith('http') else '',
            'extractor': info.get('extractor_key') or info.get('extractor') or 'Generic', 'source': source,
            'site': info.get('webpage_url_domain') or urllib.parse.urlsplit(url).hostname or '',
            'live': bool(info.get('is_live')), 'qualities': resolution_options(formats, duration)}

def candidates_from(message):
    result, seen = [], set()
    for item in (message.get('candidates') or [])[:10]:
        if not isinstance(item, dict) or item.get('kind') not in {'stream', 'embed'}: continue
        with contextlib.suppress(ValueError):
            url = valid_url(item.get('url'))
            if url not in seen: seen.add(url); result.append({'url': url, 'kind': item['kind']})
    return result

def inspect(message):
    from yt_dlp import YoutubeDL
    url = valid_url(message.get('url')); cookies = browser(message.get('cookies'))
    hint = message.get('hint') if isinstance(message.get('hint'), dict) else None
    videos, errors, seen = [], [], set()
    try:
        # A channel or search page only counts as one video when a player is showing.
        with YoutubeDL(options(None, cookies) | {'skip_download': True}) as ydl: info = resolve_main(ydl, url, hint, pick=bool(hint))
        videos.append(describe(info, 'page' if info.get('extractor_key') == 'Generic' else 'site', url)); seen |= media_urls(info) | {url}
    except Exception as error: errors.append(error)
    def one(candidate):
        with YoutubeDL(options(url, cookies) | {'skip_download': True}) as ydl: return resolve_main(ydl, candidate['url'])
    todo = [c for c in candidates_from(message) if c['url'] not in seen]
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        futures = [(c, pool.submit(one, c)) for c in todo]
        for candidate, future in futures:  # Page order: a master playlist is requested before its variants.
            try: info = future.result(timeout=90)
            except Exception as error: errors.append(error); continue
            if candidate['url'] in seen or (videos and str(info.get('id')) in {v['videoId'] for v in videos if v['source'] == 'site'}): continue
            try: video = describe(info, candidate['kind'], candidate['url'], url)
            except Exception as error: errors.append(error); continue
            if 0 < video['duration'] < 10 and videos: continue  # Hover previews and adverts.
            videos.append(video); seen |= media_urls(info)
    if not videos:
        raise NothingFound(*friendly_error(errors[0] if errors else 'No video was found'))
    first, rest = videos[0], sorted(videos[1:], key=lambda v: v['duration'], reverse=True)
    # Retain the main-video fields while an already-open 2.1 popup awaits reload.
    return {**first, 'videos': [first, *rest][:8]}

LIST_EXTRACTOR = re.compile(r'User|Playlist|Channel|Tab|Search|Pornstar|Model|Categor|Tag|Collection|Album|Series|Show|Feed|Creator|Profile|List|Subscription|Favorite', re.I)
_video_extractors = None

def is_video_page(url):
    """True when the engine has dedicated support for this single-video address (no network request)."""
    global _video_extractors
    if re.search(r'\.(?:mp4|webm|mov|m4v|mkv|ogv|m3u8|mpd)(?:[?#]|$)', url, re.I): return True
    if _video_extractors is None:
        from yt_dlp.extractor import gen_extractor_classes
        _video_extractors = [ie for ie in gen_extractor_classes() if ie.ie_key() != 'Generic' and not LIST_EXTRACTOR.search(ie.ie_key())]
    return any(ie.suitable(url) for ie in _video_extractors)

def match_links(message):
    result = []
    for url in (message.get('urls') or [])[:400]:
        with contextlib.suppress(ValueError):
            if is_video_page(valid_url(url)): result.append(url)
    return {'urls': result}

def start_many(message):
    quality = str(message.get('quality', 'best')); quality_selector(quality)
    items = message.get('items') or []
    if not isinstance(items, list) or len(items) > 100: raise ValueError('Choose up to 100 videos at a time.')
    if not items: raise ValueError('Choose at least one video.')
    labels = {'best': 'Best', 'audio': 'Sound only'}
    prepared, seen = [], set()
    for item in items:
        if not isinstance(item, dict): raise ValueError('Choose a valid video.')
        job = video_job({'url': item.get('url'), 'title': item.get('title'), 'quality': quality, 'label': labels.get(quality, f'Up to {quality}p'),
                         'referer': message.get('referer'), 'cookies': message.get('cookies')})
        if job['url'] not in seen:
            seen.add(job['url']); prepared.append(job)
    return queue_many(prepared)

def quality_selector(quality):
    if quality == 'best': return 'bv*+ba/b'
    if quality == 'audio': return 'ba/b'
    if re.fullmatch(r'height:[0-9]{1,4}', str(quality)):
        height = int(quality.split(':')[1])
        if not 0 < height <= 8640: raise ValueError('Invalid video resolution.')
        return f'bv*[height={height}]+ba/b[height={height}]/bv*[height={height}]'
    if str(quality) in {'2160', '1440', '1080', '720', '480', '360', '240'}: return f'bv*[height<=?{quality}]+ba/b[height<=?{quality}]'
    raise ValueError('Choose one of the available quality settings.')

def ensure_worker():
    with (STATE / 'worker.lock').open('a') as lock:
        try: fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError: return
        fcntl.flock(lock, fcntl.LOCK_UN)
    subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '--worker'], stdin=subprocess.DEVNULL,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)

def queue(job):
    return queue_many([job])[0]

def queue_many(prepared):
    # Native requests run on several threads. Reserve capacity for the whole
    # selection before saving any jobs or waking the worker.
    with (STATE / 'queue.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if len([j for j in jobs() if j['status'] not in FINAL]) + len(prepared) > 100:
            raise ValueError('The queue is full. Let some downloads finish first.')
        for job in prepared:
            job.update(id=uuid.uuid4().hex, created=time.time(), status='queued', percent=0, downloadedBytes=0, totalBytes=0, speed=0, eta=None)
            if job.get('kind') == 'images': job['folder'] += f' [{job["id"][:8]}]'
        saved = []
        try:
            for job in prepared: save(job); saved.append(job['id'])
        except OSError:
            for job_id in saved: job_path(job_id).unlink(missing_ok=True)
            raise
        prune()
    ensure_worker()
    return [public(job) for job in prepared]

def video_job(message):
    url = valid_url(message.get('url')); quality = str(message.get('quality', 'best')); quality_selector(quality)
    return {'kind': 'video', 'url': url, 'referer': valid_url(message['referer']) if message.get('referer') else None,
                  'quality': quality, 'label': str(message.get('label') or '')[:40], 'videoId': str(message.get('videoId') or '')[:300],
                  'hint': message.get('hint') if isinstance(message.get('hint'), dict) else None, 'cookies': browser(message.get('cookies')),
                  'title': str(message.get('title') or 'Video')[:300]}

def start(message): return queue(video_job(message))

def safe_name(value, fallback='image', limit=120):
    name = re.sub(r'[\x00-\x1f\x7f\\/:*?"<>|]+', '-', str(value or '')).strip().strip('.').strip()
    return name[:limit].strip() or fallback

def start_images(message):
    items = []
    for item in (message.get('items') or [])[:MAX_IMAGES]:
        if not isinstance(item, dict): continue
        with contextlib.suppress(ValueError):
            url = valid_url(item.get('url'))
            if len(url) <= 4096: items.append({'url': url, 'name': safe_name(item.get('name'))[:100], 'done': False})
    if not items: raise ValueError('Choose at least one image.')
    folder = safe_name(message.get('folder'), 'Images', 100)
    return queue({'kind': 'images', 'items': items, 'count': len(items), 'saved': 0, 'failedCount': 0, 'folder': folder,
                  'referer': valid_url(message['referer']) if message.get('referer') else None, 'cookies': browser(message.get('cookies')),
                  'title': f'{len(items)} image{"s" if len(items) != 1 else ""} · {folder}'[:300]})

def remove_partials(job):
    """Cancelled downloads leave no half-written files behind."""
    with contextlib.suppress(OSError):
        if job.get('kind') == 'images':
            folder = output_dir() / 'Images' / job.get('folder', '')
            for path in folder.glob(f'*.{job["id"]}.part'): path.unlink(missing_ok=True)
            with contextlib.suppress(OSError): folder.rmdir()  # Only succeeds when nothing was saved.
            return
        tag = f'[{job["id"][:8]}]'
        for path in output_dir().iterdir():
            name = path.name
            if tag in name and (name.endswith(('.part', '.ytdl', '.temp')) or '.part-Frag' in name or '.temp.' in name
                                or re.search(re.escape(tag) + r'\.f[0-9A-Za-z_-]+\.', name)):
                path.unlink(missing_ok=True)

def run_images(job, cancel):
    from yt_dlp import YoutubeDL
    from yt_dlp.networking import Request
    folder = output_dir() / 'Images' / job['folder']; folder.mkdir(parents=True, exist_ok=True)
    referer = job.get('referer'); lock = threading.Lock(); taken = set(); failures = []
    def target(item, content_type):
        suffix = IMAGE_TYPES.get(content_type) or Path(urllib.parse.urlsplit(item['url']).path).suffix.lower()
        suffix = '.jpg' if suffix in {'.jpeg', '.jfif'} else suffix if suffix in IMAGE_SUFFIXES else '.jpg'
        stem = safe_name(Path(item['name']).stem if Path(item['name']).suffix.lower() in IMAGE_SUFFIXES else item['name'])
        with lock:
            for n in range(1, 10000):
                path = folder / (f'{stem}{suffix}' if n == 1 else f'{stem} ({n}){suffix}')
                if path not in taken and not path.exists(): taken.add(path); return path
        raise RuntimeError('Too many files with this name.')
    def fetch(ydl, item):
        if cancel.exists(): raise InterruptedError('Download cancelled.')
        headers = {'Referer': referer} if referer else {}
        with contextlib.closing(ydl.urlopen(Request(item['url'], headers=headers))) as response:
            content_type = (response.headers.get('Content-Type') or '').split(';')[0].strip().lower()
            if content_type and not content_type.startswith('image/') and content_type not in {'application/octet-stream', 'binary/octet-stream'}:
                raise ValueError('This link is not an image.')
            path = target(item, content_type); partial = path.with_name(path.name + f'.{job["id"]}.part'); size = 0
            try:
                with partial.open('wb') as f:
                    while chunk := response.read(262144):
                        size += len(chunk)
                        if size > MAX_IMAGE_BYTES: raise ValueError('This image is too large.')
                        if cancel.exists(): raise InterruptedError('Download cancelled.')
                        f.write(chunk)
                if not size: raise ValueError('The image was empty.')
                partial.replace(path)
            finally: partial.unlink(missing_ok=True)
        return path, size
    todo = [item for item in job['items'] if not item.get('done')]
    job.update(status='downloading', failedCount=0, error=''); save(job)
    with YoutubeDL(options(referer, job.get('cookies'))) as ydl, concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        futures = {pool.submit(fetch, ydl, item): item for item in todo}
        for future in concurrent.futures.as_completed(futures):
            item = futures[future]
            try: path, size = future.result()
            except InterruptedError:
                for other in futures: other.cancel()
                raise
            except Exception as error: failures.append(clean_error(error)); continue
            item['done'] = True
            with lock:
                job['saved'] = sum(1 for i in job['items'] if i.get('done')); job['downloadedBytes'] += size
                job['percent'] = min(99, job['saved'] * 100 / job['count']); save(job)
    if cancel.exists(): raise InterruptedError('Download cancelled.')
    job['failedCount'] = len(failures)
    if not job['saved']: raise RuntimeError(failures[0] if failures else 'No images could be saved.')
    job.update(status='complete', percent=100, path=str(folder), speed=0, eta=0,
               error=f'{len(failures)} of {job["count"]} couldn’t be saved. Press Retry to try those again.' if failures else '')

def run_video(job, cancel):
    from yt_dlp import YoutubeDL
    last_write = [0.0]; output_files = []; parts = [0]
    def progress(data):
        if cancel.exists(): raise InterruptedError('Download cancelled.')
        now = time.monotonic()
        if data['status'] == 'finished': parts[0] += 1
        if now - last_write[0] < 0.45 and data['status'] != 'finished': return
        last_write[0] = now
        total = data.get('total_bytes') or data.get('total_bytes_estimate') or 0; downloaded = data.get('downloaded_bytes') or 0
        job.update(status='downloading', part=parts[0] + 1, downloadedBytes=downloaded,
                   totalBytes=total, speed=data.get('speed') or 0, eta=data.get('eta'), percent=min(99, downloaded * 100 / total) if total else 0)
        save(job)
    def postprocess(data):
        if data.get('status') == 'started' and job['status'] != 'processing': job['status'] = 'processing'; save(job)
    output = output_dir(); output.mkdir(parents=True, exist_ok=True)
    opts = options(job.get('referer'), job.get('cookies'))
    opts.update({'format': quality_selector(job['quality']), 'windowsfilenames': True,
                 'outtmpl': str(output / ('%(title).150B [' + job['id'][:8] + '].%(ext)s')),
                 'merge_output_format': 'mp4/mkv', 'progress_hooks': [progress], 'postprocessor_hooks': [postprocess],
                 'post_hooks': [lambda name: output_files.append(name)]})
    if job['quality'] == 'audio': opts['postprocessors'] = [{'key': 'FFmpegExtractAudio', 'preferredcodec': 'm4a'}]
    job['status'] = 'analysing'; save(job)
    with YoutubeDL(opts) as ydl:
        info = resolve_main(ydl, job['url'], job.get('hint'), job.get('videoId') or None)
        if info.get('is_live'): raise ValueError('Live streams can’t be saved yet.')
        info = ydl.process_ie_result(info, download=True)
    if cancel.exists(): raise InterruptedError('Download cancelled.')
    candidates = [Path(p) for p in output_files if Path(p).is_file()]
    if not candidates: candidates = [p for p in output.iterdir() if p.is_file() and f'[{job["id"][:8]}]' in p.name and not p.name.endswith(('.part', '.ytdl', '.temp'))]
    if not candidates: raise RuntimeError('The video finished but the saved file could not be located.')
    path = max(candidates, key=lambda p: p.stat().st_size).resolve()
    if output not in path.parents: raise RuntimeError('The output path was outside the download folder.')
    job.update(status='complete', percent=100, path=str(path), title=info.get('title') or job['title'],
               downloadedBytes=path.stat().st_size, totalBytes=path.stat().st_size, speed=0, eta=0)

def run_job(job_id):
    job = load(job_id); cancel = JOBS / (job_id + '.cancel')
    def cancelled(*_): raise InterruptedError('Download cancelled.')
    signal.signal(signal.SIGTERM, cancelled)
    try:
        if cancel.exists(): cancelled()
        (run_images if job.get('kind') == 'images' else run_video)(job, cancel)
    except BaseException as error:
        stopped = cancel.exists() or isinstance(error, InterruptedError)
        job.update(status='cancelled' if stopped else 'failed', speed=0,
                   error='' if stopped else friendly_error(error)[0])
        if stopped: remove_partials(job)
    finally: job['finished'] = time.time(); save(job)

def worker():
    with (STATE / 'worker.lock').open('w') as lock:
        try: fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError: return
        active = {}; idle = time.monotonic()
        for item in jobs():
            if item['status'] in {'analysing', 'downloading', 'processing'}:
                job = load(item['id']); job.update(status='failed', error='The download was interrupted. Press Retry to start it again.'); save(job)
        while True:
            for job_id, process in list(active.items()):
                if (JOBS / (job_id + '.cancel')).exists() and process.poll() is None:
                    with contextlib.suppress(ProcessLookupError): os.killpg(process.pid, signal.SIGTERM)
                if process.poll() is not None:
                    job = load(job_id)
                    if job['status'] not in FINAL and job['status'] != 'queued':
                        stopped = (JOBS / (job_id + '.cancel')).exists()
                        job.update(status='cancelled' if stopped else 'failed', error='' if stopped else 'The download stopped unexpectedly. Press Retry to start it again.')
                        if stopped: remove_partials(job)
                        save(job)
                    del active[job_id]
            waiting = sorted([j for j in jobs() if j['status'] == 'queued'], key=lambda j: j['created'])
            for item in waiting[:max(0, 3 - len(active))]:
                if (JOBS / (item['id'] + '.cancel')).exists():
                    job = load(item['id']); job['status'] = 'cancelled'; save(job); continue
                job = load(item['id']); job['status'] = 'analysing'; save(job)
                active[item['id']] = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '--job', item['id']],
                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            if active or waiting: idle = time.monotonic()
            elif time.monotonic() - idle > 15: return
            time.sleep(0.4)

def engine_status():
    import yt_dlp.version
    version = yt_dlp.version.__version__; age = None
    if match := re.match(r'(\d{4})\.(\d{1,2})\.(\d{1,2})', version):
        age = max(0, int((time.time() - time.mktime((int(match[1]), int(match[2]), int(match[3]), 0, 0, 0, 0, 0, -1))) // 86400))
    cfg = config()
    return {'version': VERSION, 'engine': version, 'engineAgeDays': age, 'ffmpeg': Path(cfg['ffmpeg']).is_file(), 'output': cfg['output']}

def update_engine():
    command = [sys.executable, '-m', 'pip', 'install', '--disable-pip-version-check', '--no-cache-dir', '--pre', '--upgrade', 'yt-dlp[default]']
    result = subprocess.run(command, capture_output=True, text=True, timeout=600, stdin=subprocess.DEVNULL)
    if result.returncode: raise RuntimeError('The update failed. Check your internet connection and try again.')
    check = subprocess.run([sys.executable, '-c', 'import yt_dlp.version as v; print(v.__version__)'], capture_output=True, text=True, timeout=60)
    return {'engine': check.stdout.strip(), 'restart': True}

def dispatch(message):
    command = message.get('command')
    if command == 'status': return engine_status()
    if command == 'inspect': return inspect(message)
    if command == 'start': return start(message)
    if command == 'start_images': return start_images(message)
    if command == 'start_many': return start_many(message)
    if command == 'match_links': return match_links(message)
    if command == 'jobs':
        if any(j['status'] == 'queued' for j in jobs()): ensure_worker()
        return jobs()
    if command == 'cancel':
        job = load(message.get('jobId'))
        if job['status'] not in FINAL:
            (JOBS / (job['id'] + '.cancel')).touch(mode=0o600)
            if job['status'] == 'queued': job['status'] = 'cancelled'; save(job)
        return {'ok': True}
    if command == 'retry':
        job = load(message.get('jobId'))
        if job['status'] not in {'failed', 'cancelled'} and not (job['status'] == 'complete' and job.get('failedCount')):
            raise ValueError('Only failed or cancelled downloads can be retried.')
        (JOBS / (job['id'] + '.cancel')).unlink(missing_ok=True)
        job.update(status='queued', percent=0, speed=0, error='', created=time.time())
        save(job); ensure_worker(); return {'ok': True}
    if command == 'reveal':
        job = load(message.get('jobId')); path = Path(job.get('path', '')).resolve(); output = output_dir()
        if job['status'] != 'complete' or output not in path.parents or not path.exists(): raise ValueError('This saved file is no longer in the download folder.')
        subprocess.Popen(['/usr/bin/open', '-R', str(path)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL); return {'ok': True}
    if command == 'open_folder':
        output = output_dir(); output.mkdir(parents=True, exist_ok=True)
        subprocess.Popen(['/usr/bin/open', str(output)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL); return {'ok': True}
    if command == 'clear':
        for job in jobs():
            if job['status'] in FINAL:
                job_path(job['id']).unlink(missing_ok=True); (JOBS / (job['id'] + '.cancel')).unlink(missing_ok=True)
        return {'ok': True}
    if command == 'update': return update_engine()
    raise ValueError('Unknown Media Lens request.')

def read_exact(stream, size):
    data = b''
    while len(data) < size:
        chunk = stream.read(size - len(data))
        if not chunk: return None
        data += chunk
    return data

def native_main():
    if len(sys.argv) < 2 or sys.argv[1] not in config()['origins']: return 1
    def reply(message):
        try: payload = {'id': message.get('id'), 'ok': True, 'result': dispatch(message)}
        except NothingFound as error: payload = {'id': message.get('id'), 'ok': False, 'error': str(error), 'update': error.update}
        except Exception as error: payload = {'id': message.get('id'), 'ok': False, 'error': friendly_error(error)[0]}
        encoded = json.dumps(payload, ensure_ascii=False).encode()
        if len(encoded) > 900000: encoded = json.dumps({'id': message.get('id'), 'ok': False, 'error': 'Too many results. Open the video’s own page and scan again.'}).encode()
        with WRITE_LOCK:
            with contextlib.suppress(BrokenPipeError):
                sys.stdout.buffer.write(struct.pack('<I', len(encoded)) + encoded); sys.stdout.buffer.flush()
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        while True:
            header = read_exact(sys.stdin.buffer, 4)
            if header is None: return 0
            size = struct.unpack('<I', header)[0]
            if size > 4000000: return 1
            body = read_exact(sys.stdin.buffer, size)
            if body is None: return 1
            try:
                message = json.loads(body)
                if not isinstance(message, dict): return 1
                pool.submit(reply, message)
            except (ValueError, UnicodeError): return 1

if __name__ == '__main__':
    initialise()
    if sys.argv[1:2] == ['--worker']: worker()
    elif sys.argv[1:2] == ['--job']: run_job(sys.argv[2])
    else: sys.exit(native_main())
