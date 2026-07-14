#!/usr/bin/env bash
set -euo pipefail

# Preserve V9 sources because the legacy bootstrap archive intentionally contains an older baseline.
SAVE=/tmp/sda-alpha9-sources
rm -rf "$SAVE"
mkdir -p "$SAVE/ui" "$SAVE/xposed"
cp app/src/main/java/io/github/zhanfg/sda/DownloadLiveUpdateProvider.java "$SAVE/"
cp app/src/main/java/io/github/zhanfg/sda/SystemDownloadConfirmActivity.java "$SAVE/"
cp app/src/main/java/io/github/zhanfg/sda/ui/AdaptiveWindowInfo.java "$SAVE/ui/"
cp app/src/main/java/io/github/zhanfg/sda/ui/DownloadUiRenderer.java "$SAVE/ui/"
cp app/src/main/java/io/github/zhanfg/sda/ui/DownloadUiState.java "$SAVE/ui/"
cp app/src/main/java/io/github/zhanfg/sda/ui/MaterialExpressiveDownloadRenderer.java "$SAVE/ui/"
cp app/src/main/java/io/github/zhanfg/sda/ui/MiuixDownloadRenderer.java "$SAVE/ui/"
cp app/src/main/java/io/github/zhanfg/sda/xposed/HistoryMirrorModule.java "$SAVE/xposed/"
cp app/src/main/java/io/github/zhanfg/sda/xposed/SystemDownloadConfirmationModule.java "$SAVE/xposed/"

# Reuse the complete Alpha 8 bootstrap and UI setup, then replace the V9-specific layer.
bash ci/build-alpha8.sh

SRC=app/src/main/java/io/github/zhanfg/sda
mkdir -p "$SRC/ui" "$SRC/xposed"
cp "$SAVE/DownloadLiveUpdateProvider.java" "$SRC/"
cp "$SAVE/SystemDownloadConfirmActivity.java" "$SRC/"
cp "$SAVE/ui/"*.java "$SRC/ui/"
cp "$SAVE/xposed/HistoryMirrorModule.java" "$SRC/xposed/"
cp "$SAVE/xposed/SystemDownloadConfirmationModule.java" "$SRC/xposed/"

python3 - <<'PY'
from pathlib import Path
import re

manifest = Path('app/src/main/AndroidManifest.xml')
xml = manifest.read_text(encoding='utf-8')
for permission in [
    'android.permission.POST_NOTIFICATIONS',
    'android.permission.POST_PROMOTED_NOTIFICATIONS',
]:
    if permission not in xml:
        xml = xml.replace('<application',
            f'    <uses-permission android:name="{permission}" />\n\n    <application', 1)

activity = '''<activity
            android:name=".SystemDownloadConfirmActivity"
            android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation|density|uiMode"
            android:excludeFromRecents="true"
            android:exported="true"
            android:finishOnTaskLaunch="true"
            android:launchMode="singleInstance"
            android:noHistory="true"
            android:resizeableActivity="true"
            android:taskAffinity=""
            android:theme="@style/Theme.SDA.Translucent" />'''
xml = re.sub(r'<activity\s+android:name="\.SystemDownloadConfirmActivity".*?/>',
             activity, xml, count=1, flags=re.S)

provider = '''<provider
            android:name=".DownloadLiveUpdateProvider"
            android:authorities="io.github.zhanfg.sda.liveupdate"
            android:exported="true"
            android:grantUriPermissions="false" />'''
if 'io.github.zhanfg.sda.liveupdate' not in xml:
    xml = xml.replace('</application>', '        ' + provider + '\n    </application>', 1)
manifest.write_text(xml, encoding='utf-8')

main = Path('app/src/main/java/io/github/zhanfg/sda/ModernMainActivity.java')
text = main.read_text(encoding='utf-8')
needle = '        preferences = getSharedPreferences("module_settings", MODE_PRIVATE);\n'
insert = needle + '''        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 901);
        }
'''
if 'requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}' not in text:
    if needle not in text:
        raise SystemExit('ModernMainActivity notification permission insertion point missing')
    text = text.replace(needle, insert, 1)
main.write_text(text, encoding='utf-8')
PY

cat >> app/proguard-rules.pro <<'EOF'
-keep class io.github.zhanfg.sda.DownloadLiveUpdateProvider { public *; }
-keep class io.github.zhanfg.sda.ui.AdaptiveWindowInfo { *; }
-keep class io.github.zhanfg.sda.ui.AdaptiveWindowInfo$* { *; }
EOF

cat > gradle.properties <<'EOF'
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
android.suppressUnsupportedCompileSdk=36
EOF

cat > app/build.gradle.kts <<'EOF'
plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.zhanfg.sda"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "io.github.zhanfg.sda"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.2.7-alpha9"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core:1.19.0")
    compileOnly("io.github.libxposed:api:102.0.0")
}
EOF

sdkmanager "platforms;android-36" "build-tools;35.0.0" >/dev/null
rm -rf app/build dist

gradle --no-daemon :app:assembleDebug :app:assembleRelease

for apk in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/release/app-release.apk; do
  unzip -p "$apk" META-INF/xposed/java_init.list | grep -qx \
    'io.github.zhanfg.sda.xposed.SystemDownloadConfirmationModule'
  unzip -p "$apk" META-INF/xposed/module.prop | grep -qx 'targetApiVersion=102'
  test "$(unzip -p "$apk" META-INF/xposed/scope.list | tr -d '\r\n')" = \
    'com.android.providers.downloads'
  unzip -l "$apk" | grep -q 'classes.dex'
done

mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/SystemDownloadAccelerator-0.2.7-alpha9-debug.apk
cp app/build/outputs/apk/release/app-release.apk dist/SystemDownloadAccelerator-0.2.7-alpha9.apk
(
  cd dist
  sha256sum *.apk > SHA256SUMS.txt
)
