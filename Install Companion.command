#!/bin/zsh
set -e
cd -- "${0:A:h}"
ML_SUPPORT="$HOME/Library/Application Support/Media Lens"
ML_PYTHON="$(command -v python3)"
if [[ ! -x "$ML_SUPPORT/venv/bin/python" ]]; then
  "$ML_PYTHON" -m venv "$ML_SUPPORT/venv"
fi
"$ML_SUPPORT/venv/bin/python" -m pip install --disable-pip-version-check --upgrade --pre -r companion/requirements.txt
"$ML_PYTHON" companion/install.py
print 'Media Lens is ready. Reload the extension in Brave or Chrome.'
