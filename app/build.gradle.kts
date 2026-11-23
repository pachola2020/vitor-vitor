plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.duascontas.pokeidle"
    compileSdk = 34

    defaultConfig {
        // Pacote separado para testar sem substituir o app que já está instalado.
        applicationId = "com.duascontas.pokeidle.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-test"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
