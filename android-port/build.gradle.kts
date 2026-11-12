import java.util.Base64

plugins {
    id("com.android.application") version "8.13.2"
    id("com.chaquo.python") version "17.2.0"
}

// Подпись из секретов CI — та же, что у Kotlin-версии. Debug-ключ на
// сборочных машинах GitHub свой у каждого запуска, поэтому без этого
// каждое обновление требовало бы удаления приложения.
val ksB64 = providers.environmentVariable("RELEASE_KEYSTORE_BASE64").orNull
val ksPass = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
val ksAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
val keyPass = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
val signed = listOf(ksB64, ksPass, ksAlias, keyPass).none { it.isNullOrBlank() }

val keystore = if (signed) {
    val f = File(System.getProperty("java.io.tmpdir"), "release-keystore.p12")
    f.writeBytes(Base64.getDecoder().decode(ksB64))
    f
} else {
    null
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

    signingConfigs {
        if (signed) {
            create("release") {
                storeFile = keystore
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = keyPass
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            if (signed) signingConfig = signingConfigs.getByName("release")
        }
        release {
            if (signed) signingConfig = signingConfigs.getByName("release")
        }
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
