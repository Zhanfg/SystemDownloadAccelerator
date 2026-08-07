#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail() {
  echo "validation error: $*" >&2
  exit 1
}

for file in \
  build.gradle.kts \
  settings.gradle.kts \
  app/build.gradle.kts \
  app/src/main/AndroidManifest.xml \
  app/src/main/java/io/github/zhanfg/sda/ModuleMain.java \
  app/src/main/java/io/github/zhanfg/sda/SafetyMigrationApplication.java \
  app/src/main/java/io/github/zhanfg/sda/RootAccess.java \
  app/src/main/resources/META-INF/xposed/java_init.list \
  app/src/main/resources/META-INF/xposed/module.prop \
  app/src/main/resources/META-INF/xposed/scope.list \
  alpha-module/module.prop; do
  [ -s "$file" ] || fail "required file missing: $file"
done

[ ! -e app/src/main/java/io/github/zhanfg/sda/xposed/RealDownloadAcceleratorModule.java ] \
  || fail "unvalidated Range engine must remain outside the compiled source tree"
[ ! -d .bootstrap ] || fail "encoded bootstrap directory must not return"
if find . -maxdepth 1 -type f -name '.ci-*' -print -quit | grep -q .; then
  fail "temporary CI trigger files must not be committed"
fi
if find ci -maxdepth 1 -type f -name 'build-alpha*.sh' -print -quit | grep -q .; then
  fail "historical source-generation scripts must not return"
fi
if find .github/workflows -maxdepth 1 -type f ! -name 'build.yml' -print -quit | grep -q .; then
  fail "only the direct-source build workflow is allowed"
fi

python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET

manifest = Path('app/src/main/AndroidManifest.xml')
root = ET.parse(manifest).getroot()
ns = '{http://schemas.android.com/apk/res/android}'
permissions = {item.attrib[ns + 'name'] for item in root.findall('uses-permission')}
required = {
    'io.github.zhanfg.sda.permission.INTERNAL_BRIDGE',
    'android.permission.INTERNET',
    'android.permission.POST_NOTIFICATIONS',
    'android.permission.POST_PROMOTED_NOTIFICATIONS',
    'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
}
missing = required - permissions
if missing:
    raise SystemExit(f'missing permissions: {sorted(missing)}')
if 'android.permission.REQUEST_INSTALL_PACKAGES' in permissions:
    raise SystemExit('unused REQUEST_INSTALL_PACKAGES permission is forbidden')

declared = {
    item.attrib[ns + 'name']: item.attrib.get(ns + 'protectionLevel')
    for item in root.findall('permission')
}
if declared.get('io.github.zhanfg.sda.permission.INTERNAL_BRIDGE') != 'signature':
    raise SystemExit('internal bridge permission must be signature protected')

application = root.find('application')
if application is None:
    raise SystemExit('application element missing')
if application.attrib.get(ns + 'name') != '.SafetyMigrationApplication':
    raise SystemExit('fail-closed settings migration application is missing')
if application.attrib.get(ns + 'allowBackup') != 'false':
    raise SystemExit('download history must not be included in Android backup')

expected_providers = {
    '.HistoryProvider',
    '.RootUiBridgeProvider',
    '.DownloadLiveUpdateProvider',
}
providers = {
    item.attrib.get(ns + 'name'): item
    for item in application.findall('provider')
}
if set(providers) != expected_providers:
    raise SystemExit(f'unexpected provider set: {sorted(providers)}')
for name, item in providers.items():
    if item.attrib.get(ns + 'exported') != 'true':
        raise SystemExit(f'{name} must remain exported for DownloadProvider IPC')
    if item.attrib.get(ns + 'grantUriPermissions') != 'false':
        raise SystemExit(f'{name} must not grant URI permissions')

scope = [line.strip() for line in Path(
    'app/src/main/resources/META-INF/xposed/scope.list'
).read_text(encoding='utf-8').splitlines() if line.strip()]
if scope != ['com.android.providers.downloads']:
    raise SystemExit(f'unsafe LSPosed scope: {scope}')

entries = [line.strip() for line in Path(
    'app/src/main/resources/META-INF/xposed/java_init.list'
).read_text(encoding='utf-8').splitlines() if line.strip()]
expected_entries = [
    'io.github.zhanfg.sda.ModuleMain',
    'io.github.zhanfg.sda.xposed.SystemDownloadConfirmationModule',
    'io.github.zhanfg.sda.xposed.HistoryMirrorModule',
]
if entries != expected_entries:
    raise SystemExit(f'unsafe or unexpected libxposed entry list: {entries}')
if any('RealDownloadAcceleratorModule' in item for item in entries):
    raise SystemExit('unvalidated Range engine must not be loaded')
PY

grep -Fq 'versionName = "0.3.0-alpha13"' app/build.gradle.kts \
  || fail "Android versionName mismatch"
grep -Fq 'versionCode = 14' app/build.gradle.kts \
  || fail "Android versionCode mismatch"
grep -Fq 'minApiVersion=102' app/src/main/resources/META-INF/xposed/module.prop \
  || fail "libxposed minimum API mismatch"
grep -Fq 'targetApiVersion=102' app/src/main/resources/META-INF/xposed/module.prop \
  || fail "libxposed target API mismatch"
grep -Fq 'return chain.proceed();' app/src/main/java/io/github/zhanfg/sda/ModuleMain.java \
  || fail "system transfer return value is not preserved"
grep -Fq '.putBoolean("enabled", false)' \
  app/src/main/java/io/github/zhanfg/sda/SafetyMigrationApplication.java \
  || fail "legacy Range setting is not reset fail-closed"
grep -Fq 'new ProcessBuilder("su", "-c", "id -u")' \
  app/src/main/java/io/github/zhanfg/sda/RootAccess.java \
  || fail "root probe is no longer the fixed id -u command"
grep -Fq 'enforceDownloadsCaller();' \
  app/src/main/java/io/github/zhanfg/sda/RootUiBridgeProvider.java \
  || fail "root UI bridge caller validation missing"
grep -Fq 'enforceDownloadsCaller();' \
  app/src/main/java/io/github/zhanfg/sda/DownloadLiveUpdateProvider.java \
  || fail "live update caller validation missing"
for source in \
  app/src/main/java/io/github/zhanfg/sda/xposed/SystemDownloadConfirmationModule.java \
  app/src/main/java/io/github/zhanfg/sda/xposed/HistoryMirrorModule.java; do
  grep -Fq 'INTERNAL_BRIDGE_PERMISSION' "$source" \
    || fail "internal bridge permission missing from $source"
  grep -Fq 'context.registerReceiver(' "$source" \
    || fail "protected receiver registration missing from $source"
done
if grep -R -n 'RealDownloadAcceleratorModule' \
  app/src/main app/proguard-rules.pro 2>/dev/null; then
  fail "quarantined Range engine is still referenced by the compiled app"
fi
grep -Fq 'appVersionCode=14' alpha-module/module.prop \
  || fail "wrapper module app version mismatch"

echo "Direct-source validation passed."
