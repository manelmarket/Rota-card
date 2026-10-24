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
        versionName = "2.9 (build $verCode)"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Assinatura. Se a chave propria existir (variaveis de ambiente, vindas dos Secrets do GitHub),
    // ela e usada. Senao, continua a chave de depuracao do repositorio — ver README, "Assinatura".
    val releaseStore = System.getenv("ROTACARD_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
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
        if (releaseStore != null) {
            create("own") {
                storeFile = releaseStore
                storePassword = System.getenv("ROTACARD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ROTACARD_KEY_ALIAS")
                keyPassword = System.getenv("ROTACARD_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (releaseStore != null) signingConfig = signingConfigs.getByName("own")
        }
    }
    testOptions {
        // as classes do Android viram "stubs" que devolvem valores padrao nos testes da JVM
        unitTests.isReturnDefaultValues = true
    }
}
dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
