#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
from pathlib import Path
path = Path('ci/build-alpha12.sh')
text = path.read_text(encoding='utf-8')
text = text.replace('@style/Theme.SDA.Main', '@android:style/Theme.Material.Light.NoActionBar')
path.write_text(text, encoding='utf-8')
PY
exec bash ci/build-alpha12.sh
