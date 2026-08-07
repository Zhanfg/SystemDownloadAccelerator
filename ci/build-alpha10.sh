#!/usr/bin/env bash
set -euo pipefail

# Build the full Alpha 9 responsive Live Update baseline first.
bash ci/build-alpha9.sh

python3 - <<'PY'
from pathlib import Path

manifest = Path('app/src/main/AndroidManifest.xml')
xml = manifest.read_text(encoding='utf-8')
receiver = '''<receiver
            android:name=".LiveUpdateDismissReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="io.github.zhanfg.sda.action.DISMISS_COMPLETED_LIVE_UPDATE" />
            </intent-filter>
        </receiver>'''
if 'io.github.zhanfg.sda.action.DISMISS_COMPLETED_LIVE_UPDATE' not in xml:
    xml = xml.replace('</application>', '        ' + receiver + '\n    </application>', 1)
manifest.write_text(xml, encoding='utf-8')

build = Path('app/build.gradle.kts')
text = build.read_text(encoding='utf-8')
text = text.replace('versionCode = 10', 'versionCode = 11')
text = text.replace('versionName = "0.2.7-alpha9"', 'versionName = "0.2.8-alpha10"')
build.write_text(text, encoding='utf-8')
PY

cat >> app/proguard-rules.pro <<'EOF'
-keep class io.github.zhanfg.sda.LiveUpdateDismissReceiver { public *; }
EOF
