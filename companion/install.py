#!/usr/bin/env python3
"""Register the already-built companion for this user's Chrome and Brave."""
from pathlib import Path
import argparse, hashlib, json, os, shutil, sys
p = argparse.ArgumentParser(); p.add_argument('--extension-id', action='append', default=[]); args = p.parse_args()
home = Path.home(); root = Path(__file__).resolve().parent.parent
state = home / 'Library/Application Support/Media Lens'; state.mkdir(parents=True, exist_ok=True, mode=0o700)
python = state / 'venv/bin/python'
if not python.is_file(): sys.exit('Create the companion venv and install yt-dlp[default] first. See README.md.')
ids = set(args.extension_id)
for source in [root]:
    digest = hashlib.sha256(str(source).encode()).hexdigest()[:32]
    ids.add(''.join(chr(ord('a')+int(c,16)) for c in digest))
for browser in ['BraveSoftware/Brave-Browser', 'Google/Chrome']:
    browser_root = home / 'Library/Application Support' / browser
    if not browser_root.is_dir(): continue
    for profile in browser_root.iterdir():
        for filename in ['Preferences', 'Secure Preferences']:
            f = profile/filename
            if not f.is_file(): continue
            try: entries = json.loads(f.read_text()).get('extensions',{}).get('settings',{})
            except (ValueError,OSError): continue
            for extension_id, entry in entries.items():
                path = entry.get('path')
                if path and Path(path).resolve() == root: ids.add(extension_id)
if not all(len(i)==32 and all('a'<=c<='p' for c in i) for i in ids): sys.exit('Invalid extension ID.')
existing = {}
try: existing = json.loads((state/'config.json').read_text())
except (OSError, ValueError): pass
for origin in existing.get('origins', []):
    if origin.startswith('chrome-extension://'):
        extension_id = origin.removeprefix('chrome-extension://').rstrip('/')
        if len(extension_id) == 32 and all('a' <= c <= 'p' for c in extension_id): ids.add(extension_id)
origins = [f'chrome-extension://{i}/' for i in sorted(ids)]
node = shutil.which('node'); ffmpeg = shutil.which('ffmpeg')
if not node or not ffmpeg: sys.exit('Node.js and FFmpeg must be installed first.')
shutil.copy2(root/'companion/host.py', state/'host.py')
config = {'origins':origins, 'ffmpeg':ffmpeg, 'node':node, 'output':existing.get('output') or str(home/'Downloads/Media Lens')}
(state/'config.json').write_text(json.dumps(config,indent=2)); os.chmod(state/'config.json',0o600)
launcher = state/'media-lens-host'; launcher.write_text('#!/bin/sh\nexec '+"'"+str(python).replace("'", "'\\''")+"' '"+str(state/'host.py').replace("'", "'\\''")+"' \"$@\"\n"); launcher.chmod(0o700)
manifest = {'name':'com.medialens.downloader','description':'Media Lens local video download engine','path':str(launcher),'type':'stdio','allowed_origins':origins}
for browser in ['BraveSoftware/Brave-Browser', 'Google/Chrome']:
    folder = home/'Library/Application Support'/browser/'NativeMessagingHosts'; folder.mkdir(parents=True,exist_ok=True)
    (folder/'com.medialens.downloader.json').write_text(json.dumps(manifest,indent=2))
print(json.dumps({'installed':True,'origins':origins,'output':config['output']},indent=2))
