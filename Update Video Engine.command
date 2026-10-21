#!/bin/zsh
set -e
ML_PYTHON="$HOME/Library/Application Support/Media Lens/venv/bin/python"
if [[ ! -x "$ML_PYTHON" ]]; then
  print 'Run Install Companion.command first.'
  exit 1
fi
"$ML_PYTHON" -m pip install --disable-pip-version-check --no-cache-dir --pre --upgrade 'yt-dlp[default]'
print 'Engine updated. Restart Brave or Chrome to use it.'
