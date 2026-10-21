plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.medoed.vagdiag"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "ru.medoed.vagdiag"
        minSdk = 23
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0-alpha"
    }
}

dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
}
