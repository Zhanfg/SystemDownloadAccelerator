#!/usr/bin/env bash
set -euo pipefail

SAVE=/tmp/sda-alpha12-sources
rm -rf "$SAVE"
mkdir -p "$SAVE"
cp app/src/main/java/io/github/zhanfg/sda/FirstRunSetupActivity.java "$SAVE/"
cp app/src/main/java/io/github/zhanfg/sda/RootAccess.java "$SAVE/"
cp app/src/main/java/io/github/zhanfg/sda/RootUiBridgeProvider.java "$SAVE/"

# Build the verified Alpha 11 baseline, then apply the audited onboarding/security layer.
bash ci/build-alpha11.sh

SRC=app/src/main/java/io/github/zhanfg/sda
cp "$SAVE/FirstRunSetupActivity.java" "$SRC/"
cp "$SAVE/RootAccess.java" "$SRC/"
cp "$SAVE/RootUiBridgeProvider.java" "$SRC/"

python3 - <<'PY'
from pathlib import Path
import re

# Manifest: first-run activity and only the special permission actually used.
manifest = Path('app/src/main/AndroidManifest.xml')
xml = manifest.read_text(encoding='utf-8')
permission = 'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS'
if permission not in xml:
    xml = xml.replace('<application',
        f'    <uses-permission android:name="{permission}" />\n\n    <application', 1)
activity = '''<activity
            android:name=".FirstRunSetupActivity"
            android:exported="false"
            android:resizeableActivity="true"
            android:theme="@style/Theme.SDA.Main" />'''
if '.FirstRunSetupActivity' not in xml:
    xml = xml.replace('</application>', '        ' + activity + '\n    </application>', 1)
manifest.write_text(xml, encoding='utf-8')

# Main UI: avoid a duplicate raw notification prompt, launch the explanatory first-run screen,
# and keep the permission checker accessible from Settings.
main = Path('app/src/main/java/io/github/zhanfg/sda/ModernMainActivity.java')
text = main.read_text(encoding='utf-8')
text = re.sub(
    r'\s*if \(Build\.VERSION\.SDK_INT >= 33\s*&& checkSelfPermission\(android\.Manifest\.permission\.POST_NOTIFICATIONS\)\s*!= android\.content\.pm\.PackageManager\.PERMISSION_GRANTED\) \{\s*requestPermissions\(new String\[\]\{android\.Manifest\.permission\.POST_NOTIFICATIONS\}, 901\);\s*\}\s*',
    '\n', text, count=1, flags=re.S)
needle = '        preferences = getSharedPreferences("module_settings", MODE_PRIVATE);\n'
insert = needle + '''        if (savedInstanceState == null
                && !preferences.getBoolean("first_run_setup_shown", false)) {
            startActivity(new Intent(this, FirstRunSetupActivity.class));
        }
'''
if 'first_run_setup_shown' not in text:
    if needle not in text:
        raise SystemExit('ModernMainActivity setup insertion point missing')
    text = text.replace(needle, insert, 1)
settings_needle = '        content.addView(diagnostics);\n'
settings_insert = settings_needle + '''
        content.addView(sectionLabel("权限与环境"));
        LinearLayout permissions = card(surface, 22);
        permissions.addView(infoRow("Root", "确认窗口的可靠启动权限"));
        permissions.addView(divider());
        permissions.addView(infoRow("通知", "实时活动、百分比和快速操作"));
        permissions.addView(divider());
        permissions.addView(infoRow("LSPosed 作用域", "com.android.providers.downloads"));
        Button permissionCheck = button("检查 Root 与权限", false);
        permissionCheck.setOnClickListener(v -> startActivity(
                new Intent(this, FirstRunSetupActivity.class).putExtra("manual", true)));
        LinearLayout.LayoutParams permissionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        permissionParams.topMargin = dp(12);
        permissions.addView(permissionCheck, permissionParams);
        content.addView(permissions);
'''
if '检查 Root 与权限' not in text:
    if settings_needle not in text:
        raise SystemExit('ModernMainActivity settings insertion point missing')
    text = text.replace(settings_needle, settings_insert, 1)
main.write_text(text, encoding='utf-8')

# Confirmation setting was visible in UI but previously ignored by the hook.
confirm = Path('app/src/main/java/io/github/zhanfg/sda/xposed/SystemDownloadConfirmationModule.java')
text = confirm.read_text(encoding='utf-8')
text = text.replace(
    'if (context == null || shouldBypass(snapshot)) return result;',
    'if (context == null || shouldBypass(snapshot) || !confirmationEnabled()) return result;', 1)
method_needle = '''    private boolean shouldBypass(ContentValues values) {
        String source = values.getAsString("uri");
        return source == null || source.isEmpty();
    }
'''
method_insert = method_needle + '''
    private boolean confirmationEnabled() {
        try {
            return getRemotePreferences("module_settings")
                    .getBoolean("confirmation_enabled", true);
        } catch (Throwable ignored) {
            return true;
        }
    }
'''
if 'private boolean confirmationEnabled()' not in text:
    if method_needle not in text:
        raise SystemExit('Confirmation preference insertion point missing')
    text = text.replace(method_needle, method_insert, 1)
confirm.write_text(text, encoding='utf-8')

# Protect exported notification controls with an unguessable per-download token.
live = Path('app/src/main/java/io/github/zhanfg/sda/DownloadLiveUpdateProvider.java')
text = live.read_text(encoding='utf-8')
if 'java.security.SecureRandom' not in text:
    text = text.replace('import java.util.Locale;\n',
                        'import java.security.SecureRandom;\nimport java.util.Locale;\n', 1)
text = text.replace(
    '    private static final long SUCCESS_VISIBLE_MS = 6_000L;\n',
    '    private static final long SUCCESS_VISIBLE_MS = 6_000L;\n'
    '    private static final String CONTROL_PREFS = "live_update_control_tokens";\n'
    '    private static final SecureRandom CONTROL_RANDOM = new SecureRandom();\n', 1)
call_needle = '''        if ("cancel".equals(method)) {
            long id = extras == null ? -1L : extras.getLong("download_id", -1L);
            if (id >= 0) cancelNotification(providerContext(), id);
            return Bundle.EMPTY;
        }
'''
call_insert = call_needle + '''        if ("verify_control".equals(method)) {
            long id = extras == null ? -1L : extras.getLong("download_id", -1L);
            String supplied = extras == null ? null : extras.getString("control_token");
            String expected = id < 0 ? null : providerContext()
                    .getSharedPreferences(CONTROL_PREFS, Context.MODE_PRIVATE)
                    .getString(Long.toString(id), null);
            Bundle result = new Bundle();
            result.putBoolean("valid", constantTimeEquals(expected, supplied));
            return result;
        }
'''
if '"verify_control".equals(method)' not in text:
    if call_needle not in text:
        raise SystemExit('Live control verifier insertion point missing')
    text = text.replace(call_needle, call_insert, 1)
text = text.replace(
    '.putExtra("command", command);',
    '.putExtra("command", command)\n'
    '                .putExtra("control_token", controlToken(context, id));', 1)
text = text.replace(
    '        SAMPLES.remove(id);\n    }\n\n    private static PendingIntent dismissPendingIntent',
    '        SAMPLES.remove(id);\n'
    '        removeControlToken(context, id);\n'
    '    }\n\n'
    '    private static String controlToken(Context context, long id) {\n'
    '        android.content.SharedPreferences preferences = context.getSharedPreferences(\n'
    '                CONTROL_PREFS, Context.MODE_PRIVATE);\n'
    '        String key = Long.toString(id);\n'
    '        String token = preferences.getString(key, null);\n'
    '        if (token != null && token.length() >= 32) return token;\n'
    '        byte[] bytes = new byte[24];\n'
    '        CONTROL_RANDOM.nextBytes(bytes);\n'
    '        StringBuilder value = new StringBuilder(bytes.length * 2);\n'
    '        for (byte item : bytes) value.append(String.format(Locale.ROOT, "%02x", item & 0xff));\n'
    '        token = value.toString();\n'
    '        preferences.edit().putString(key, token).commit();\n'
    '        return token;\n'
    '    }\n\n'
    '    private static void removeControlToken(Context context, long id) {\n'
    '        context.getSharedPreferences(CONTROL_PREFS, Context.MODE_PRIVATE)\n'
    '                .edit().remove(Long.toString(id)).apply();\n'
    '    }\n\n'
    '    private static boolean constantTimeEquals(String expected, String supplied) {\n'
    '        if (expected == null || supplied == null || expected.length() != supplied.length()) return false;\n'
    '        int difference = 0;\n'
    '        for (int index = 0; index < expected.length(); index++) {\n'
    '            difference |= expected.charAt(index) ^ supplied.charAt(index);\n'
    '        }\n'
    '        return difference == 0;\n'
    '    }\n\n'
    '    private static PendingIntent dismissPendingIntent', 1)
# Also remove the token after the completion-delay runnable fires.
text = text.replace(
    '            DISMISS_RUNNABLES.remove(id);\n',
    '            DISMISS_RUNNABLES.remove(id);\n            removeControlToken(context, id);\n', 1)
live.write_text(text, encoding='utf-8')

history = Path('app/src/main/java/io/github/zhanfg/sda/xposed/HistoryMirrorModule.java')
text = history.read_text(encoding='utf-8')
verify_needle = '''                long id = intent.getLongExtra("download_id", -1L);
                String command = intent.getStringExtra("command");
                if (id < 0 || command == null) return;
'''
verify_insert = verify_needle + '''                String controlToken = intent.getStringExtra("control_token");
                Bundle verification = new Bundle();
                verification.putLong("download_id", id);
                verification.putString("control_token", controlToken);
                Bundle verified;
                try {
                    verified = context.getContentResolver().call(
                            LIVE_UPDATE_URI, "verify_control", null, verification);
                } catch (Throwable error) {
                    log(Log.WARN, TAG, "Unable to verify notification control", error);
                    return;
                }
                if (verified == null || !verified.getBoolean("valid", false)) {
                    log(Log.WARN, TAG, "Rejected unauthenticated download control for id=" + id);
                    return;
                }
'''
if 'Rejected unauthenticated download control' not in text:
    if verify_needle not in text:
        raise SystemExit('History control verification insertion point missing')
    text = text.replace(verify_needle, verify_insert, 1)
history.write_text(text, encoding='utf-8')

build = Path('app/build.gradle.kts')
text = build.read_text(encoding='utf-8')
text = text.replace('versionCode = 12', 'versionCode = 13')
text = text.replace('versionName = "0.2.9-alpha11"', 'versionName = "0.3.0-alpha12"')
build.write_text(text, encoding='utf-8')
PY

cat >> app/proguard-rules.pro <<'EOF'
-keep class io.github.zhanfg.sda.FirstRunSetupActivity { public *; }
-keep class io.github.zhanfg.sda.RootAccess { public *; }
-keep class io.github.zhanfg.sda.RootAccess$* { *; }
EOF

rm -rf app/build dist
gradle --no-daemon :app:assembleDebug :app:assembleRelease

for apk in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/release/app-release.apk; do
  unzip -p "$apk" META-INF/xposed/java_init.list | grep -qx \
    'io.github.zhanfg.sda.xposed.SystemDownloadConfirmationModule'
  unzip -p "$apk" META-INF/xposed/module.prop | grep -qx 'targetApiVersion=102'
  test "$(unzip -p "$apk" META-INF/xposed/scope.list | tr -d '\r\n')" = \
    'com.android.providers.downloads'
  unzip -l "$apk" | grep -q 'classes.dex'
  "$ANDROID_HOME/build-tools/35.0.0/aapt" dump xmltree "$apk" AndroidManifest.xml \
    | grep -q 'FirstRunSetupActivity'
  "$ANDROID_HOME/build-tools/35.0.0/aapt" dump permissions "$apk" \
    | grep -q 'android.permission.POST_NOTIFICATIONS'
done

mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/SystemDownloadAccelerator-0.3.0-alpha12-debug.apk
cp app/build/outputs/apk/release/app-release.apk dist/SystemDownloadAccelerator-0.3.0-alpha12.apk
(
  cd dist
  sha256sum *.apk > SHA256SUMS.txt
)
