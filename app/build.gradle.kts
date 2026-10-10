plugins {
    id("com.android.application")
}

android {
    namespace = "dev.surfacespeaker.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.surfacespeaker.lab03"
        minSdk = 31
        targetSdk = 35
        versionCode = 13
        versionName = "0.13.0"
    }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}