#!/usr/bin/env bash
set -euo pipefail

if [[ -f .bootstrap/project.b64 ]]; then
  base64 --decode .bootstrap/project.b64 > /tmp/project.tar.gz
  gzip -t /tmp/project.tar.gz
  tar -xzf /tmp/project.tar.gz
fi

SRC="app/src/main/java/io/github/zhanfg/sda"
mkdir -p "$SRC"
cat > "$SRC/SystemBarInsets.java" <<'JAVA'
package io.github.zhanfg.sda;

import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

final class SystemBarInsets {
    private SystemBarInsets() {}

    static void apply(View root) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT_WATCH) {
            root.setFitsSystemWindows(true);
            return;
        }
        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(baseLeft + left, baseTop + top,
                    baseRight + right, baseBottom + bottom);
            return insets;
        });
        root.requestApplyInsets();
    }
}
JAVA

python3 - <<'PY'
from pathlib import Path
import re

source = Path('app/src/main/java/io/github/zhanfg/sda/ModernMainActivity.java')
text = source.read_text(encoding='utf-8')

history_replacement = r'''    private View buildHistoryPage() {
        LinearLayout content = pageColumn();

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = button("刷新", false);
        refresh.setOnClickListener(v -> showPage(PAGE_HISTORY, false));
        Button directory = button("打开默认目录", false);
        directory.setOnClickListener(v -> openDirectory(null));
        toolbar.addView(refresh, new LinearLayout.LayoutParams(0, dp(48), 1f));
        toolbar.addView(spaceHorizontal(10));
        toolbar.addView(directory, new LinearLayout.LayoutParams(0, dp(48), 1f));
        content.addView(toolbar);

        List<HistoryItem> history = loadHistory();
        if (history.isEmpty()) {
            FrameLayout emptyHost = new FrameLayout(this);
            LinearLayout.LayoutParams hostParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            hostParams.topMargin = dp(12);
            content.addView(emptyHost, hostParams);

            LinearLayout empty = card(surface, 24);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(30), dp(24), dp(30));

            ImageView icon = new ImageView(this);
            icon.setImageResource(android.R.drawable.ic_menu_recent_history);
            icon.setColorFilter(textSecondary);
            empty.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));

            TextView title = text("暂无下载记录", 18, textPrimary, true);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, dp(14), 0, dp(6));
            empty.addView(title);

            TextView body = text("这里仅显示系统下载服务真实同步的数据。",
                    14, textSecondary, false);
            body.setGravity(Gravity.CENTER);
            body.setMaxWidth(dp(420));
            empty.addView(body);

            int available = Math.max(dp(280),
                    getResources().getDisplayMetrics().widthPixels - dp(32));
            int cardWidth = Math.min(available, dp(640));
            FrameLayout.LayoutParams emptyParams = new FrameLayout.LayoutParams(
                    cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            emptyHost.addView(empty, emptyParams);
            return content;
        }

        for (HistoryItem item : history) {
            content.addView(historyCard(item));
        }
        return scroll(content);
    }

    private View buildSettingsPage'''
pattern = re.compile(
    r'    private View buildHistoryPage\(\) \{.*?\n    \}\n\n    private View buildSettingsPage',
    re.S,
)
text, count = pattern.subn(history_replacement, text, count=1)
if count != 1:
    raise SystemExit('buildHistoryPage patch target not found')

settings_replacement = r'''    private View buildSettingsPage() {
        LinearLayout content = pageColumn();

        content.addView(sectionLabel("外观"));
        LinearLayout appearance = card(surface, 22);
        appearance.addView(uiStyleSelector());
        appearance.addView(divider());
        appearance.addView(switchRow("窗口模糊",
                "Android 12+ 对透明宿主后的原应用窗口进行模糊",
                "use_blur", Build.VERSION.SDK_INT >= 31));
        appearance.addView(divider());
        appearance.addView(infoRow("配色", Build.VERSION.SDK_INT >= 31
                ? "跟随系统动态色" : "青蓝固定主题"));
        content.addView(appearance);

        content.addView(sectionLabel("弹窗架构"));
        LinearLayout architecture = card(surface, 22);
        architecture.addView(infoRow("外层宿主", "Root 启动的透明 Activity"));
        architecture.addView(divider());
        architecture.addView(infoRow("Material 渲染器", "居中 PositionDialog 风格"));
        architecture.addView(divider());
        architecture.addView(infoRow("Miuix 渲染器", "底部 WindowBottomSheet 风格"));
        architecture.addView(divider());
        architecture.addView(infoRow("共享状态", "同一任务、同一确认结果"));
        content.addView(architecture);

        content.addView(sectionLabel("诊断"));
        LinearLayout diagnostics = card(surface, 22);
        diagnostics.addView(infoRow("模块入口", "API 102 · DownloadProvider"));
        diagnostics.addView(divider());
        diagnostics.addView(infoRow("特权启动桥", "io.github.zhanfg.sda.rootbridge"));
        diagnostics.addView(divider());
        diagnostics.addView(infoRow("核心作用域", "com.android.providers.downloads"));
        diagnostics.addView(divider());
        diagnostics.addView(infoRow("日志标签", "SysDownloadAccel / SysDownloadConfirm"));
        content.addView(diagnostics);

        Button clear = button("清除历史记录", false);
        clear.setTextColor(error);
        clear.setOnClickListener(v -> {
            int count = getContentResolver().delete(HistoryProvider.RECORDS_URI,
                    null, null);
            Toast.makeText(this, "已清除 " + count + " 条记录", Toast.LENGTH_SHORT).show();
        });
        content.addView(clear, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        return scroll(content);
    }

    private View uiStyleSelector() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(4), 0, dp(8));

        TextView title = text("界面风格", 16, textPrimary, true);
        container.addView(title);
        TextView description = text(
                "两套独立渲染器，共享下载状态；切换后下一次弹窗生效。",
                13, textSecondary, false);
        description.setPadding(0, dp(4), 0, dp(12));
        container.addView(description);

        String selected = preferences.getString("ui_style", "material");
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        Button material = button("Material 3", "material".equals(selected));
        Button miuix = button("Miuix", "miuix".equals(selected));
        material.setOnClickListener(v -> {
            preferences.edit().putString("ui_style", "material").apply();
            showPage(PAGE_SETTINGS, false);
        });
        miuix.setOnClickListener(v -> {
            preferences.edit().putString("ui_style", "miuix").apply();
            showPage(PAGE_SETTINGS, false);
        });
        choices.addView(material, new LinearLayout.LayoutParams(0, dp(48), 1f));
        choices.addView(spaceHorizontal(10));
        choices.addView(miuix, new LinearLayout.LayoutParams(0, dp(48), 1f));
        container.addView(choices);
        return container;
    }

    private View historyCard'''
settings_pattern = re.compile(
    r'    private View buildSettingsPage\(\) \{.*?\n    \}\n\n    private View historyCard',
    re.S,
)
text, count = settings_pattern.subn(settings_replacement, text, count=1)
if count != 1:
    raise SystemExit('buildSettingsPage patch target not found')
source.write_text(text, encoding='utf-8')

manifest = Path('app/src/main/AndroidManifest.xml')
xml = manifest.read_text(encoding='utf-8')
xml = xml.replace('android:name=".MainActivity"',
                  'android:name=".ModernMainActivity"', 1)
xml = xml.replace('android:name="io.github.zhanfg.sda.MainActivity"',
                  'android:name="io.github.zhanfg.sda.ModernMainActivity"', 1)

activity = '''<activity
            android:name=".SystemDownloadConfirmActivity"
            android:exported="true"
            android:excludeFromRecents="true"
            android:finishOnTaskLaunch="true"
            android:launchMode="singleInstance"
            android:noHistory="true"
            android:taskAffinity=""
            android:theme="@style/Theme.SDA.Translucent" />'''
activity_pattern = re.compile(
    r'<activity\s+android:name="\.SystemDownloadConfirmActivity".*?/>', re.S)
if activity_pattern.search(xml):
    xml = activity_pattern.sub(activity, xml, count=1)
else:
    xml = xml.replace('</application>', '        ' + activity + '\n    </application>', 1)

if 'io.github.zhanfg.sda.history' not in xml:
    xml = xml.replace('</application>', '''
        <provider
            android:name=".HistoryProvider"
            android:authorities="io.github.zhanfg.sda.history"
            android:exported="true" />
    </application>''', 1)

root_provider = '''<provider
            android:name=".RootUiBridgeProvider"
            android:authorities="io.github.zhanfg.sda.rootbridge"
            android:exported="true"
            android:grantUriPermissions="false" />'''
root_pattern = re.compile(
    r'<provider\s+android:name="\.RootUiBridgeProvider".*?/>', re.S)
if root_pattern.search(xml):
    xml = root_pattern.sub(root_provider, xml, count=1)
elif 'io.github.zhanfg.sda.rootbridge' not in xml:
    xml = xml.replace('</application>', '        ' + root_provider + '\n    </application>', 1)
manifest.write_text(xml, encoding='utf-8')
PY

META="app/src/main/resources/META-INF/xposed"
mkdir -p "$META"
cat > "$META/java_init.list" <<'EOF'
io.github.zhanfg.sda.xposed.RealDownloadAcceleratorModule
io.github.zhanfg.sda.xposed.SystemDownloadConfirmationModule
io.github.zhanfg.sda.xposed.HistoryMirrorModule
EOF
printf '%s\n' 'com.android.providers.downloads' > "$META/scope.list"
cat > "$META/module.prop" <<'EOF'
minApiVersion=102
targetApiVersion=102
staticScope=false
autoHotReload=false
EOF

cat >> app/proguard-rules.pro <<'EOF'
-keep class io.github.zhanfg.sda.ModernMainActivity { public *; }
-keep class io.github.zhanfg.sda.SystemDownloadConfirmActivity { public *; }
-keep class io.github.zhanfg.sda.HistoryProvider { public *; }
-keep class io.github.zhanfg.sda.RootUiBridgeProvider { public *; }
-keep class io.github.zhanfg.sda.ui.** { *; }
-keep class io.github.zhanfg.sda.xposed.RealDownloadAcceleratorModule { public *; }
-keep class io.github.zhanfg.sda.xposed.RealDownloadAcceleratorModule$* { *; }
-keep class io.github.zhanfg.sda.xposed.SystemDownloadConfirmationModule { public *; }
-keep class io.github.zhanfg.sda.xposed.SystemDownloadConfirmationModule$* { *; }
-keep class io.github.zhanfg.sda.xposed.HistoryMirrorModule { public *; }
-keep class io.github.zhanfg.sda.xposed.HistoryMirrorModule$* { *; }
EOF

cat > build.gradle.kts <<'EOF'
plugins {
    id("com.android.application") version "8.7.3" apply false
}
EOF
cat > settings.gradle.kts <<'EOF'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "SystemDownloadAccelerator"
include(":app")
EOF
cat > gradle.properties <<'EOF'
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
android.useAndroidX=false
android.nonTransitiveRClass=true
EOF
cat > app/build.gradle.kts <<'EOF'
plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.zhanfg.sda"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "io.github.zhanfg.sda"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.2.6-alpha8"
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
    compileOnly("io.github.libxposed:api:102.0.0")
}
EOF

sdkmanager "platforms;android-35" "build-tools;35.0.0" >/dev/null
gradle --no-daemon :app:assembleDebug :app:assembleRelease

for apk in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/release/app-release.apk; do
  unzip -p "$apk" META-INF/xposed/java_init.list | grep -qx \
    'io.github.zhanfg.sda.xposed.SystemDownloadConfirmationModule'
  unzip -p "$apk" META-INF/xposed/module.prop | grep -qx 'targetApiVersion=102'
  test "$(unzip -p "$apk" META-INF/xposed/scope.list | tr -d '\r\n')" = \
    'com.android.providers.downloads'
done

mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/SystemDownloadAccelerator-0.2.6-alpha8-debug.apk
cp app/build/outputs/apk/release/app-release.apk dist/SystemDownloadAccelerator-0.2.6-alpha8.apk
(
  cd dist
  sha256sum *.apk > SHA256SUMS.txt
)
