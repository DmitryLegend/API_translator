plugins {
    id("com.android.application") version "8.13.2"
    id("com.chaquo.python") version "17.2.0"
}

android {
    namespace = "com.dmitrylegend.apitranslator"
    compileSdk = 36

    defaultConfig {
        // Другой applicationId: обе версии (Kotlin и Python) стоят рядом.
        applicationId = "com.dmitrylegend.apitranslator.python"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
}

chaquopy {
    defaultConfig {
        version = "3.14"
        staticProxy("main", "service")
    }
}
