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
        versionCode = 2
        versionName = "0.2.0-capture-test"
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
    implementation("androidx.webkit:webkit:1.12.1")
}
