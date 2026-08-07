package io.github.zhanfg.sda;

import android.app.Application;
import android.content.SharedPreferences;

/** Applies one-time Range-engine migrations before the settings UI is rendered. */
public final class SafetyMigrationApplication extends Application {
    private static final int ENGINE_MIGRATION_VERSION = 14;
    private static final String PREFS = "module_settings";
    private static final String MIGRATION_KEY = "range_engine_safety_migration";

    @Override
    public void onCreate() {
        super.onCreate();
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (preferences.getInt(MIGRATION_KEY, 0) < ENGINE_MIGRATION_VERSION) {
            int maxThreads = clamp(preferences.getInt("max_threads", 8), 2, 16);
            int initialThreads = clamp(preferences.getInt("initial_threads", 4), 2, maxThreads);
            int minimumSizeMb = clamp(preferences.getInt("min_size_mb", 32), 8, 4096);
            int chunkSizeMb = clamp(preferences.getInt("chunk_size_mb", 16), 4, 64);

            preferences.edit()
                    // Alpha 13 forcibly disabled the engine because it was only a pass-through.
                    // Alpha 14 restores a real fail-safe Range path, so enable it again once.
                    .putBoolean("enabled", true)
                    .putInt("max_threads", maxThreads)
                    .putInt("initial_threads", initialThreads)
                    .putInt("min_size_mb", minimumSizeMb)
                    .putInt("chunk_size_mb", chunkSizeMb)
                    .putInt(MIGRATION_KEY, ENGINE_MIGRATION_VERSION)
                    .apply();
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
