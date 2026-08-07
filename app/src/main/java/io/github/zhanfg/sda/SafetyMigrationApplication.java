package io.github.zhanfg.sda;

import android.app.Application;
import android.content.SharedPreferences;

/** Applies one-time fail-closed migrations before any activity renders module settings. */
public final class SafetyMigrationApplication extends Application {
    private static final int SAFETY_MIGRATION_VERSION = 13;
    private static final String PREFS = "module_settings";
    private static final String MIGRATION_KEY = "range_engine_safety_migration";

    @Override
    public void onCreate() {
        super.onCreate();
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (preferences.getInt(MIGRATION_KEY, 0) < SAFETY_MIGRATION_VERSION) {
            preferences.edit()
                    .putBoolean("enabled", false)
                    .putInt(MIGRATION_KEY, SAFETY_MIGRATION_VERSION)
                    .apply();
        }
    }
}
