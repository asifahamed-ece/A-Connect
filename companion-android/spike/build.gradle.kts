plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * The clipboard privilege spike.
 *
 * This is a throwaway measurement tool, kept out of the shipping app on
 * purpose: it writes to the clipboard and it registers an input method, and
 * neither belongs in an application a user installs on a real phone. It has
 * its own application id so it can live on the device next to the app.
 */
android {
    namespace = "com.aplusconnect.companion.spike"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aplusconnect.companion.spike"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
}
