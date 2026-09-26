package dev.axymorrsen.systemdownloadaccelerator;

enum Scope {
    PROVIDER("com.android.providers.downloads"),
    DOWNLOADS_UI("com.android.providers.downloads.ui"),
    SYSTEM_UI("com.android.systemui");

    final String packageName;

    Scope(String packageName) {
        this.packageName = packageName;
    }

    static Scope fromPackage(String packageName) {
        for (Scope scope : values()) {
            if (scope.packageName.equals(packageName)) {
                return scope;
            }
        }
        return null;
    }
}
