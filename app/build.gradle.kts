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
        versionCode = 14
        versionName = "0.3.0-alpha13"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Alpha candidates use the debug key so CI can produce an installable APK.
            // A public stable release must replace this with a protected release key.
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
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

dependencies {
    // 1.17.0 is the API 36 generation and contains Android 16 Live Update APIs.
    // Core 1.18+ requires API 36.1, while 1.19 also requires AGP 9.1.
    implementation("androidx.core:core:1.17.0")
    compileOnly("io.github.libxposed:api:102.0.0")
}
