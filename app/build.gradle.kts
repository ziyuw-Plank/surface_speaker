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
        versionCode = 12
        versionName = "0.12.0"
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