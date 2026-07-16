plugins {
    id("com.android.library")
}

android {
    namespace = "de.robv.android.xposed.stubs"
    compileSdk = 35

    defaultConfig {
        minSdk = 27
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
