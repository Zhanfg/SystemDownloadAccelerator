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
  app/src/main/java/io/github/zhanfg/sda/RangeProtocol.java \
  app/src/test/java/io/github/zhanfg/sda/RangeProtocolTest.java \
  app/src/test/java/io/github/zhanfg/sda/RangeHttpContractTest.java \
  app/src/main/java/io/github/zhanfg/sda/SafetyMigrationApplication.java \
  app/src/main/java/io/github/zhanfg/sda/RootAccess.java \
  app/src/main/resources/META-INF/xposed/java_init.list \
  app/src/main/resources/META-INF/xposed/module.prop \
  app/src/main/resources/META-INF/xposed/scope.list \
  alpha-module/module.prop \
  tools/alpha15-device-validation.sh \
  docs/ALPHA15-DEVICE-VALIDATION.md; do
  [ -s "$file" ] || fail "required file missing: $file"
done

sh -n tools/alpha15-device-validation.sh \
  || fail "Alpha 15 device validation helper has invalid shell syntax"

grep -Fq 'start [label]' tools/alpha15-device-validation.sh \
  || fail "device validation start mode missing"
grep -Fq 'finish [downloaded_file] [expected_sha256]' tools/alpha15-device-validation.sh \
  || fail "device validation finish/hash mode missing"
grep -Fq 'SDA-Alpha15-Validation-' tools/alpha15-device-validation.sh \
  || fail "device validation evidence archive output missing"
grep -Fq 'Fresh Range download' docs/ALPHA15-DEVICE-VALIDATION.md \
  || fail "fresh Range device acceptance case missing"
grep -Fq 'Resume from partial file' docs/ALPHA15-DEVICE-VALIDATION.md \
  || fail "resume device acceptance case missing"
grep -Fq 'VPN / proxy / cellular routing' docs/ALPHA15-DEVICE-VALIDATION.md \
  || fail "network-routing device acceptance case missing"

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
PY

grep -Fq 'versionName = "0.3.0-alpha15"' app/build.gradle.kts \
  || fail "Android versionName mismatch"
grep -Fq 'versionCode = 16' app/build.gradle.kts \
  || fail "Android versionCode mismatch"
grep -Fq 'testImplementation("junit:junit:4.13.2")' app/build.gradle.kts \
  || fail "Range invariant unit-test dependency missing"
grep -Fq 'minApiVersion=102' app/src/main/resources/META-INF/xposed/module.prop \
  || fail "libxposed minimum API mismatch"
grep -Fq 'targetApiVersion=102' app/src/main/resources/META-INF/xposed/module.prop \
  || fail "libxposed target API mismatch"

ENGINE=app/src/main/java/io/github/zhanfg/sda/ModuleMain.java
PROTOCOL=app/src/main/java/io/github/zhanfg/sda/RangeProtocol.java
TESTS=app/src/test/java/io/github/zhanfg/sda/RangeProtocolTest.java
HTTP_TESTS=app/src/test/java/io/github/zhanfg/sda/RangeHttpContractTest.java
BRIDGE=app/src/main/java/io/github/zhanfg/sda/RootUiBridgeProvider.java

grep -Fq 'class ParallelRangeInputStream extends InputStream' "$ENGINE" \
  || fail "functional bounded Range input stream is missing"
grep -Fq 'RequestSnapshot.capture(connection)' "$ENGINE" \
  || fail "original Range request snapshot is missing"
grep -Fq 'RangeProtocol.resolveBaseWindow' "$ENGINE" \
  || fail "resume alignment gate is not used by runtime preflight"
grep -Fq 'RangeProtocol.validateDestinationOffset' "$ENGINE" \
  || fail "local destination offset is not part of Range preflight"
grep -Fq 'Os.lseek(descriptor, 0L, OsConstants.SEEK_CUR)' "$ENGINE" \
  || fail "destination FD offset is not measured with lseek"
grep -Fq 'RangeProtocol.isStrongLastModified(modifiedMillis, responseDateMillis)' "$ENGINE" \
  || fail "runtime can use weak Last-Modified as If-Range validator"
grep -Fq 'RangeProtocol.chunkBounds' "$ENGINE" \
  || fail "tested chunk geometry is not used by runtime"
grep -Fq 'boolean enabled = false;' "$ENGINE" \
  || fail "preference IPC failure must remain fail-closed"
grep -Fq 'new File(context.getCacheDir(), "sda-range-spool")' "$ENGINE" \
  || fail "Range spool must live in DownloadProvider private cache"
grep -Fq 'ACTIVE_SPOOL_DIRS.contains(absolute)' "$ENGINE" \
  || fail "stale spool cleanup can delete an active session"
grep -Fq 'ACTIVE_SPOOL_DIRS.add(sessionDir)' "$ENGINE" \
  || fail "active spool sessions are not registered"
grep -Fq 'synchronized (preflight.configureLock)' "$ENGINE" \
  || fail "worker configuration calls are not serialized"
grep -Fq 'if (match != null) return null;' "$ENGINE" \
  || fail "ambiguous fallback hook discovery is not fail-closed"
grep -Fq 'Object result = chain.proceed(args);' "$ENGINE" \
  || fail "accelerated input is not handed back to the original system copy loop"
grep -Fq 'connection.setRequestProperty("Range", "bytes=" + start + "-" + end);' "$ENGINE" \
  || fail "worker HTTP Range request is missing"
if grep -Fq 'Os.pwrite' "$ENGINE" || grep -Fq 'FileChannel positional write' "$ENGINE"; then
  fail "destination direct-write code must not exist in the active engine"
fi

grep -Fq 'STRONG_LAST_MODIFIED_GAP_MS = 60_000L' "$PROTOCOL" \
  || fail "conservative strong Last-Modified threshold missing"
grep -Fq 'resume Range request downgraded to HTTP 200' "$PROTOCOL" \
  || fail "HTTP 200 resume downgrade gate missing"
grep -Fq 'resume offset mismatch' "$PROTOCOL" \
  || fail "206 request/response alignment gate missing"
grep -Fq 'bounded original Range response mismatch' "$PROTOCOL" \
  || fail "bounded Range response-alignment gate missing"
grep -Fq 'bounded original Range does not reach resource end' "$PROTOCOL" \
  || fail "bounded partial-window rejection gate missing"
grep -Fq 'base Content-Range does not reach resource end' "$PROTOCOL" \
  || fail "partial base 206 expansion gate missing"
grep -Fq 'destination offset mismatch' "$PROTOCOL" \
  || fail "destination FD mismatch gate missing"
grep -Fq 'original Range request state unavailable' "$PROTOCOL" \
  || fail "unknown Range request state is not fail-closed"

grep -Fq 'freshHttp200IsAcceptedOnlyWhenNoRangeWasRequested' "$TESTS" \
  || fail "fresh/resume HTTP 200 invariant test missing"
grep -Fq 'resumed206MustMatchOpenEndedOriginalRequest' "$TESTS" \
  || fail "open-ended resume alignment invariant test missing"
grep -Fq 'boundedResumeRequestIsAcceptedOnlyWhenItReachesEof' "$TESTS" \
  || fail "bounded EOF-resume compatibility test missing"
grep -Fq 'resumed206MustReachResourceEnd' "$TESTS" \
  || fail "partial base 206 rejection test missing"
grep -Fq 'destinationOffsetMustMatchResolvedResumeWindow' "$TESTS" \
  || fail "local destination offset invariant test missing"
grep -Fq 'lastModifiedMustBeStrongEnoughForIfRange' "$TESTS" \
  || fail "strong Last-Modified If-Range invariant test missing"
grep -Fq 'schedulerNeverExceedsChunksOrHardLimit' "$TESTS" \
  || fail "scheduler bound test missing"
grep -Fq 'parallelRangesReassembleOriginalBytes' "$HTTP_TESTS" \
  || fail "end-to-end parallel HTTP Range reconstruction test missing"
grep -Fq 'ifRangeMismatchForcesWholeBodyResponseAndRuntimeFallback' "$HTTP_TESTS" \
  || fail "If-Range downgrade/fallback contract test missing"

grep -Fq '"report_engine_status".equals(method)' "$BRIDGE" \
  || fail "runtime Range telemetry bridge is missing"
grep -Fq 'new ProcessBuilder("su", "-c", "id -u")' \
  app/src/main/java/io/github/zhanfg/sda/RootAccess.java \
  || fail "root probe is no longer the fixed id -u command"
grep -Fq 'enforceDownloadsCaller();' "$BRIDGE" \
  || fail "root UI bridge caller validation missing"

grep -Fq 'version=0.3.0-alpha15+diag1' alpha-module/module.prop \
  || fail "wrapper module version mismatch"
grep -Fq 'appVersionCode=16' alpha-module/module.prop \
  || fail "wrapper module app version mismatch"

echo "Direct-source validation passed. Alpha 15 request/response/FD-aligned Range invariants, strong validators, JVM HTTP contract tests and device validation tooling are present."
