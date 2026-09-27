import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Base64

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

// Подпись релиза. Ключ лежит в секретах репозитория, в репозиторий он не
// попадает никогда: без него никто не сможет выпустить обновление от вашего
// имени, даже если просто скачает APK.
//
// Gradle не умеет читать хранилище ключей прямо из строки, поэтому base64 из
// секрета сначала кладём во временный файл, а сам файл в сборку не копируем.
val ksB64 = providers.environmentVariable("RELEASE_KEYSTORE_BASE64").orNull
val ksPass = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
val ksAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
val keyPass = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
val signed = listOf(ksB64, ksPass, ksAlias, keyPass).none { it.isNullOrBlank() }

// Требовать секреты здесь нельзя: этот файл читается при любой сборке, в том
// числе черновой, которой подпись не нужна. Забытые секреты ловит сам
// релизный шаг в .github/workflows/build.yml — там проверяется и наличие
// секретов, и что готовый APK действительно подписан.

val keystore = if (signed) {
    val f = File(System.getProperty("java.io.tmpdir"), "release-keystore.p12")
    f.writeBytes(Base64.getDecoder().decode(ksB64))
    f
} else {
    null
}

android {
    namespace = "com.dmitrylegend.apitranslator"

    // 36 — это Android 16, ровно как на телефоне.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dmitrylegend.apitranslator"

        // 26 — это Android 8. В 2026 году покрывает всё живое с запасом.
        minSdk = 26

        // 36 обязателен, а не выбор: с 31 августа 2026 года Google Play не
        // принимает ни новое приложение, ни обновление, если targetSdk ниже
        // Android 16. То есть на 35 приложение просто не загрузится.
        //
        // Раньше здесь стояло 35 намеренно, чтобы не включать принудительные
        // вещи Android 16. Оказалось, что запрет на загрузку дороже лишней
        // возни: окно приложения и так центрировано и под статус-бар не
        // уезжает, а оверлей — системное окно, его правила не трогают.
        targetSdk = 36

        // versionCode обязан строго расти с каждой загрузкой в Play, иначе
        // магазин её отвергнет. Первой загрузке хватает единицы.
        versionCode = 1
        versionName = "1.0.2"

        // Экранируем кавычки, потому что значение вставляется внутрь
        // сгенерированной Java-строки.
        buildConfigField("String", "DEEPL_KEY", "\"${deeplKey.replace("\"", "\\\"")}\"")
        buildConfigField("boolean", "DEEPL_FREE", deeplFreeHost.toString())
    }

    buildFeatures {
        // В AGP 8+ выключено по умолчанию. Нужно, чтобы положить туда ключ.
        buildConfig = true
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
        release {
            // Подпись общая на все релизы, поэтому следующая версия ставится
            // поверх предыдущей, а не требует удаления.
            if (signed) signingConfig = signingConfigs.getByName("release")

            // Без ужимания и обфускации: в приложении нет лишнего веса, который
            // стоило бы выкидывать, а библиотеки Google изнутри используют
            // рефлексию и с ней не уживаются.
            //
            // ponytail: флаг можно включить одной строкой, но тогда размер APK
            // падает примерно вдвое, а поведение на телефоне меняется. Включать
            // стоит, только если размер стал важнее предсказуемости.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Kotlin и Java обязаны собираться под одну и ту же версию, иначе сборка
// падает с ошибкой про дублирующиеся данные классов. Этот блок стоит
// отдельно от android { }, потому что kotlin — это другое расширение, и
// внутри android оно не видно. Импорт JvmTarget в начале файла обязателен.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
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
