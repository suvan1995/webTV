plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.webtv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.webtv"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }
    signingConfigs {
        // Fixed key so every build installs over the previous one as an update.
        create("webtv") {
            storeFile = file("webtv-release.jks")
            storePassword = "webtvpass"
            keyAlias = "webtv"
            keyPassword = "webtvpass"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("webtv")
        }
        debug { signingConfig = signingConfigs.getByName("webtv") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
}
