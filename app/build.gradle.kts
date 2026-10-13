plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.rotacard.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.rotacard.app"
        minSdk = 26
        targetSdk = 34
        val verCode = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 4
        versionCode = verCode
        versionName = "1.4 (build $verCode)"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        getByName("debug") {
            val ks = file("debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
}
dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.core:core-ktx:1.13.1")
}
