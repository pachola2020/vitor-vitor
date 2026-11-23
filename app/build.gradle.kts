plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.duascontas.pokeidle"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // Pacote separado para testar sem substituir o app que já está instalado.
        applicationId = "com.duascontas.pokeidle.assistant.v2"
        minSdk = 28
        targetSdk = 34
        versionCode = 39
        versionName = "0.5.34-background-cookie-candidate" // Move blocking cookie persistence off the UI thread
    }

    signingConfigs {
        create("ciDebug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("ciDebug")
        }
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
