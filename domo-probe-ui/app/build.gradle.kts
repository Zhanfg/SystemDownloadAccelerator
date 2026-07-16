plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.zhanfg.sda.domo"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.zhanfg.sda.domo"
        minSdk = 27
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2-probe"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(project(":xposed-stubs"))
}
