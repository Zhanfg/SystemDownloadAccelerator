package de.robv.android.xposed;

import android.app.Application;

public final class AndroidAppHelper {
    private AndroidAppHelper() {
    }

    public static Application currentApplication() {
        throw new UnsupportedOperationException("compile-only stub");
    }
}
