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
        versionCode = 10
        versionName = "0.5.5-analysis-test"
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
