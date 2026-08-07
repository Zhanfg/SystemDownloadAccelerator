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
  app/lint.xml \
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

# The abandoned Alpha 12 engine wrote parallel HTTP workers directly into the
# DownloadProvider-owned destination FD. It must never return to the compiled tree.
[ ! -e app/src/main/java/io/github/zhanfg/sda/xposed/RealDownloadAcceleratorModule.java ] \
  || fail "legacy direct-write Range engine must remain outside the compiled source tree"
[ ! -e app/src/main/java/io/github/zhanfg/sda/xposed/DownloadConfirmationHook.java ] \
  || fail "legacy helper tied to the direct-write engine must remain quarantined"
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
    raise SystemExit('Range-engine settings migration application is missing')
if application.attrib.get(ns + 'allowBackup') != 'false':
    raise SystemExit('download history must not be included in Android backup')

activities = {
    item.attrib.get(ns + 'name'): item
    for item in application.findall('activity')
}
for name in ('.FirstRunSetupActivity', '.SystemDownloadConfirmActivity'):
    item = activities.get(name)
    if item is None:
        raise SystemExit(f'required one-shot activity missing: {name}')
    if item.attrib.get(ns + 'enableOnBackInvokedCallback') != 'false':
        raise SystemExit(f'{name} must retain the documented predictive-back opt-out')

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
    raise SystemExit('legacy direct-write Range engine must not be loaded')

lint_root = ET.parse('app/lint.xml').getroot()
issues = {item.attrib.get('id'): item for item in lint_root.findall('issue')}
if 'GestureBackNavigation' not in issues or 'WrongConstant' not in issues:
    raise SystemExit('documented compatibility lint scopes are missing')
PY

grep -Fq 'versionName = "0.3.0-alpha14"' app/build.gradle.kts \
  || fail "Android versionName mismatch"
grep -Fq 'versionCode = 15' app/build.gradle.kts \
  || fail "Android versionCode mismatch"
grep -Fq 'minApiVersion=102' app/src/main/resources/META-INF/xposed/module.prop \
  || fail "libxposed minimum API mismatch"
grep -Fq 'targetApiVersion=102' app/src/main/resources/META-INF/xposed/module.prop \
  || fail "libxposed target API mismatch"

ENGINE=app/src/main/java/io/github/zhanfg/sda/ModuleMain.java
MIGRATION=app/src/main/java/io/github/zhanfg/sda/SafetyMigrationApplication.java
UI=app/src/main/java/io/github/zhanfg/sda/ModernMainActivity.java
BRIDGE=app/src/main/java/io/github/zhanfg/sda/RootUiBridgeProvider.java

grep -Fq 'class ParallelRangeInputStream extends InputStream' "$ENGINE" \
  || fail "functional parallel Range input stream is missing"
grep -Fq 'Object result = chain.proceed(args);' "$ENGINE" \
  || fail "accelerated input is not handed back to the original system copy loop"
grep -Fq 'connection.setRequestProperty("Range", "bytes=" + start + "-" + end);' "$ENGINE" \
  || fail "worker HTTP Range request is missing"
grep -Fq 'connection.setRequestProperty("If-Range", strongEtag);' "$ENGINE" \
  || fail "strong validator If-Range protection is missing"
grep -Fq 'server rejected strict byte-range probe' "$ENGINE" \
  || fail "strict Range preflight is missing"
grep -Fq 'Math.min(preflight.threads, chunkCount)' "$ENGINE" \
  || fail "Range worker count is not bounded by planned chunks"
grep -Fq 'maxThreads = clamp(preferences.getInt("max_threads", 8), 2, 16);' "$ENGINE" \
  || fail "runtime worker hard cap must remain 16"
grep -Fq 'chunkSizeMb = clamp(preferences.getInt("chunk_size_mb", 16), 4, 64);' "$ENGINE" \
  || fail "bounded temporary chunk size gate is missing"
grep -Fq 'root.getUsableSpace() < spoolBudget + reserve' "$ENGINE" \
  || fail "temporary-spool free-space preflight is missing"
if grep -Fq 'Os.pwrite' "$ENGINE" || grep -Fq 'FileChannel positional write' "$ENGINE"; then
  fail "destination direct-write code must not exist in the active engine"
fi

grep -Fq 'ENGINE_MIGRATION_VERSION = 14' "$MIGRATION" \
  || fail "functional Range-engine migration version mismatch"
grep -Fq '.putBoolean("enabled", true)' "$MIGRATION" \
  || fail "functional Range engine is not enabled after Alpha 13 migration"
grep -Fq 'maxThreads = clamp(preferences.getInt("max_threads", 8), 2, 16);' "$MIGRATION" \
  || fail "migration does not clamp legacy 1024-thread setting"

grep -Fq '"report_engine_status".equals(method)' "$BRIDGE" \
  || fail "runtime Range telemetry bridge is missing"
grep -Fq 'Math.max(0, Math.min(16, extras.getInt("threads", 0)))' "$BRIDGE" \
  || fail "runtime telemetry thread value is not bounded"
grep -Fq 'preferences.getString("engine_status", "idle")' "$UI" \
  || fail "home page does not read actual engine runtime state"
grep -Fq '"max_threads", 8' "$UI" \
  || fail "UI maximum-thread default is not aligned to runtime"
grep -Fq 'value = Math.max(2, Math.min(16, value));' "$UI" \
  || fail "UI thread input is not limited to 2-16"
if grep -Fq '允许范围 1–1024' "$UI" || grep -Fq '"strict_range"' "$UI" \
  || grep -Fq '"auto_fallback"' "$UI"; then
  fail "obsolete configurable safety policy remains in the UI"
fi

grep -Fq 'new ProcessBuilder("su", "-c", "id -u")' \
  app/src/main/java/io/github/zhanfg/sda/RootAccess.java \
  || fail "root probe is no longer the fixed id -u command"
grep -Fq 'enforceDownloadsCaller();' "$BRIDGE" \
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
for source in \
  app/src/main/java/io/github/zhanfg/sda/DownloadConfirmActivity.java \
  app/src/main/java/io/github/zhanfg/sda/MainActivity.java; do
  grep -Fq 'data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)' "$source" \
    || fail "persisted URI flags are not strictly masked in $source"
done
if grep -R -n 'RealDownloadAcceleratorModule' \
  app/src/main app/proguard-rules.pro 2>/dev/null; then
  fail "legacy direct-write Range engine is still referenced by the compiled app"
fi
grep -Fq 'version=0.3.0-alpha14+diag1' alpha-module/module.prop \
  || fail "wrapper module version mismatch"
grep -Fq 'appVersionCode=15' alpha-module/module.prop \
  || fail "wrapper module app version mismatch"

echo "Direct-source validation passed. Functional bounded Range engine is present."
