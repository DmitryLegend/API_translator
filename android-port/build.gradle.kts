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

// Версия берётся из тега: v1.0.4 → versionName 1.0.4, versionCode 10404.
// Сборка по пушу в main помечается -dev, чтобы черновик отличался от релиза
// и на телефоне, и в диагностике.
val tag = providers.environmentVariable("GITHUB_REF_NAME").orNull.orEmpty()
val ver = if (tag.startsWith("v")) tag.removePrefix("v") else "1.0.0"
val vparts = (ver.split(".") + listOf("0", "0", "0")).take(3).map { it.toIntOrNull() ?: 0 }

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
        versionCode = vparts[0] * 10000 + vparts[1] * 100 + vparts[2]
        versionName = if (tag.startsWith("v")) ver else "$ver-dev"

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
