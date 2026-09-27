import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Ключ DeepL подставляет сборка на сервере GitHub из секрета репозитория.
// В код он не попадает и в историю коммитов не пишется.
//
// Собрать локально с ключом можно так:
//   DEEPL_API_KEY=xxx ./gradlew assembleDebug
val deeplKey = providers.environmentVariable("DEEPL_API_KEY").orNull
    ?: providers.gradleProperty("deeplKey").orNull
    ?: ""

// У DeepL два разных адреса: один для бесплатного ключа, другой для платного.
// Отличить их просто: у бесплатного ключ заканчивается на «:fx». Ошибёшься —
// получишь 403 от чужого адреса, поэтому проверяем здесь.
val deeplFreeHost = deeplKey.endsWith(":fx")

// На сервере ключ обязателен. Без этой проверки сборка молча прошла бы и
// выдала APK, который запускается, но ничего не переводит. Локально ключ
// можно не задавать — приложение честно скажет об этом на стартовом экране.
if (providers.environmentVariable("CI").isPresent && deeplKey.isBlank()) {
    error(
        "Секрет DEEPL_API_KEY не найден. Добавь его в " +
            "Settings -> Secrets and variables -> Actions -> New repository secret."
    )
}

android {
    namespace = "com.dmitrylegend.apitranslator"

    // 36 — это Android 16, ровно как на телефоне.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dmitrylegend.apitranslator"

        // 26 — это Android 8. В 2026 году покрывает всё живое с запасом.
        minSdk = 26

        // Намеренно 35, а не 36. Сборка против 36 ничего не меняет в
        // работе приложения, зато targetSdk 36 включает принудительные вещи
        // вроде «все окна в край экрана» и самые свежие правила для фоновой
        // работы. 35 даёт нужные нам ограничения Android 14/15, от которых
        // всё равно никуда не деться, и не добавляет лишнего.
        targetSdk = 35

        versionCode = 1
        versionName = "1.0"

        // Экранируем кавычки, потому что значение вставляется внутрь
        // сгенерированной Java-строки.
        buildConfigField("String", "DEEPL_KEY", "\"${deeplKey.replace("\"", "\\\"")}\"")
        buildConfigField("boolean", "DEEPL_FREE", deeplFreeHost.toString())
    }

    buildFeatures {
        // В AGP 8+ выключено по умолчанию. Нужно, чтобы положить туда ключ.
        buildConfig = true
    }

    buildTypes {
        release {
            // Без ужимания и обфускации: в приложении нет лишнего веса, который
            // стоило бы выкидывать, а библиотеки Google изнутри используют
            // рефлексию и с ней не уживаются.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Kotlin и Java обязаны собираться под одну и ту же версию, иначе сборка
    // падает с ошибкой про дублирующиеся данные классов. Импорт JvmTarget
    // в начале файла обязателен для этого блока.
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // Распознавание текста от Google.
    //
    // Вариант выбран вшитый в приложение, а не скачиваемый на лету:
    //   • работает сразу после установки, без первого запуска «подождите,
    //     качается модель 4 МБ»;
    //   • не зависит от версии Google Play Services на телефоне;
    //   • размер APK растёт на 4 МБ — для этого приложения неважно.
    //
    // Язык «latin» покрывает латиницу, кириллицу и греческий. Для китайского,
    // японского и корейского нужен отдельный модуль и ещё +4 МБ.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Единственная прочая зависимость. Сеть работает через встроенный
    // HttpURLConnection, разбор JSON — через встроенный org.json. Обе штуки
    // уже есть в Android, поэтому ни OkHttp, ни Retrofit, ни Moshi не нужны.
    testImplementation("junit:junit:4.13.2")
}
