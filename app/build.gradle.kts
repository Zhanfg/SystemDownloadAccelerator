plugins {
    id("com.android.application")
}

android {
    namespace = "dev.axymorrsen.systemdownloadaccelerator"
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "dev.axymorrsen.systemdownloadaccelerator"
        minSdk = 26
        targetSdk = 37
        versionCode = 13
        versionName = "0.4.0-alpha03"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    testImplementation("junit:junit:4.13.2")
}
