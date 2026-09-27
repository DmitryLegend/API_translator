package com.dmitrylegend.apitranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Главная рабочая лошадка приложения. Живёт в фоне, пока пользователь не
 * выключит перевод, и делает вот что каждые ~0.7 секунды:
 *
 *   1. Берёт свежий кадр экрана
 *   2. Находит на нём текст и запоминает, где он стоит
 *   3. Проверяет, не переводили ли мы этот текст раньше
 *   4. Отправляет новое в DeepL пачкой и запоминает ответ
 *   5. Закрашивает оригинал цветом фона и рисует перевод поверх экрана
 *
 * Из-за шагов 2 и 4 перевод всегда отстаёт от картинки примерно на
 * 1–2 секунды. Это не баг — распознавание текста и обращение к сети просто
 * не мгновенные, и обойти это нельзя.
 */
class TranslateService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        /** Плотность виртуального экрана. На картинку не влияет вообще. */
        private const val DPI = 100

        private const val TAG = "Translator"

        /** На какой язык переводим. */
        const val TARGET_LANG = "RU"

        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "translator"
        const val VIRTUAL_DISPLAY = "translator"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000

        /**
         * Что происходит прямо сейчас — показывается на экране приложения.
         *
         * Нужно потому, что с телефона в Termux логи Android не достать вовсе:
         * система отдаёт только логи самого Termux. Единственный способ понять,
         * на каком шаге конвейера всё встало, — показать это человеку.
         */
        @Volatile
        var debug: String = "перевод ещё не запускался"

        /** Сколько раз DeepL ответил неудачей. Ненулевое — значит, сеть или ключ. */
        @Volatile
        var netErrors: Int = 0

        /** Последний ответ DeepL, чтобы на экране было видно код и текст ошибки. */
        @Volatile
        var lastNetError: String = ""

        private const val PREFS = "last"

        /**
         * Текст последней ошибки запуска.
         *
         * Лежит в настройках, а не в поле, специально: падение убивает процесс
         * вместе со всеми полями, а настройки переживают перезапуск, поэтому
         * экран приложения сможет показать, на чём именно всё упало.
         */
        fun lastError(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("error", "").orEmpty()

        /**
         * Пауза между кадрами. Распознавание занимает 200–400 мс, сеть ещё
         * 300–600 мс, так что быстрее полутора кадров в секунду всё равно не
         * выйдет, а батарея сядет заметно быстрее.
         */
        private const val SCAN_MS = 700L

        /**
         * Сколько раз подряд мы должны увидеть одну и ту же строку, прежде чем
         * отправить её в DeepL.
         *
         * Субтитры появляются и исчезают, а распознавание на границах букв
         * слегка «дрожит». Без этой проверки одна фраза улетела бы в API три-четыре
         * раза подряд, а на экране вместо перевода мерцал бы оригинал.
         *
         * Заодно это главная экономия лимита: 500 000 символов в месяц — не
         * бездонно.
         *
         * Трёх кадров, а не двух, по опыту: мусор от сжатого видео держится
         * на экране ровно столько, сколько длится кадр, и успевает пройти
         * проверку дважды, но не трижды.
         */
        private const val STABLE_SCANS = 3

        /** Строка, которой не видели [LINE_TTL_MS], считается ушедшей с экрана. */
        private const val LINE_TTL_MS = 8_000L

        /** Больше этого числа строк на экране не бывает; страховка от утечки. */
        private const val MAX_LINES = 60

        /** Знаки, которые встречаются в обычном тексте. Всё прочее — мусор. */
        private const val ALLOWED_PUNCT = " .,!?;:'\"()-%+\u2014\u2026"

        /** Блок длиннее — почти наверняка мусор распознавания. Не переводим. */
        private const val MAX_BLOCK_CHARS = 400

        /** Мельче — не текст, а иконки, цифры в часах, штрих-коды. */
        private const val MIN_BOX_PX = 26

        /** DeepL берёт максимум 50 строк за один запрос. Берём с запасом. */
        private const val BATCH = 40

        /** Насколько широкой полосой вокруг текста берём цвет фона. */
        private const val SAMPLE_BAND_PX = 3

        /**
         * Простое «работает или нет». Приложение одно и однопоточное по
         * экрану, поэтому связывать MainActivity с сервисом через
         * подключение смысла нет.
         */
        @Volatile
        var isRunning = false
            private set
    }

    /**
     * Известные строки на экране: что распознано, где стоит и как переведено.
     *
     * Заменило два разных хранилища (память переводов и счётчики
     * повторяемости), потому что они решали одну задачу и мешали друг другу:
     * перевод искался по точному совпадению строки, а распознавание дрожит, и
     * одна и та же фраза кадр за кадром приходит чуть иначе. Теперь строка
     * опознаётся по близости, вместе с рамкой и переводом, и не меняется.
     */
    private val lines = ArrayList<Line>()

    /**
     * Одна узнанная строка. Поток у конвейера один, синхронизация не нужна.
     */
    private class Line(
        /** Ключ для сравнения: [normalize] от текста, без пробелов и знаков. */
        val key: String,
        /** Текст в том виде, в каком его надо переводить. */
        val src: String,
        /**
         * Рамка берётся один раз, при первом появлении строки, и дальше не
         * трогается. Каждый кадр распознавание возвращает рамку на пару
         * пикселей иначе, и если брать её свежей, перевод будет прыгать по
         * экрану.
         */
        val box: Rect,
        /** Перевод. null, пока строка ещё не отправлена в DeepL. */
        var translation: String? = null,
        /** Сколько кадров подряд мы видели именно эту строку. */
        var seen: Int = 0,
        /** Когда видели в последний раз, миллисекунды [android.os.SystemClock]. */
        var lastSeen: Long = 0,
    )

    /**
     * Один поток на весь конвейер. Он и задаёт темп: пока заняты распознаванием
     * или сетью, следующий кадр не берётся, а устаревшие кадры Android тем
     * временем сам выбрасывает. Никакого пула потоков и синхронизации.
     */
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var recognizer: com.google.mlkit.vision.text.TextRecognizer
    private var reader: ImageReader? = null
    private var projection: MediaProjection? = null

    /** Захват экрана, который мы держим. Пересоздаётся при повороте. */
    private var virtual: VirtualDisplay? = null

    /** Размер последнего созданного захвата. */
    private var capW = 0
    private var capH = 0

    /** Как был повёрнут экран в момент, когда создан последний захват. */
    private var capDeg = ROT_0
    // Тип именно OverlayView, а не View: ниже мы вызываем его собственный
    // метод setItems, которого у обычного View нет.
    private var view: OverlayView? = null

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Повторное нажатие «Старт» при уже работающем переводе игнорируем.
        // Android может также дослать команду с пустым intent после того, как
        // убил процесс, — а без разрешения на захват экрана перезапускаться
        // всё равно не с чем.
        if (intent == null || running) return START_NOT_STICKY

        // Любое падение здесь убивает процесс целиком, и экран приложения
        // гаснет вместе с ним — а логи с телефона достать нечем. Поэтому
        // ловим всё и записываем текст ошибки в настройки: они переживут
        // перезапуск процесса, и MainActivity его покажет.
        return try {
            startCapture(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "Запуск не удался", t)
            saveError(t)
            stopSelf()
            START_NOT_STICKY
        }
    }

    /** Последняя ошибка запуска. Показывается на экране приложения. */
    private fun saveError(t: Throwable) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("error", t.stackTraceToString().take(1200))
            .apply()
    }

    private fun startCapture(intent: Intent): Int {
        // Старую ошибку стираем сразу: иначе на экране остался бы текст
        // предыдущего неудачного запуска и сбивал бы с толку.
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("error").apply()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = readResultData(intent)
        if (resultData == null) {
            Log.e(TAG, "Запуск без разрешения на захват экрана — нечего делать")
            stopSelf()
            return START_NOT_STICKY
        }

        // ПОРЯДОК ЭТИХ ДВУХ ВЫЗОВОВ КРИТИЧЕН на Android 14 и новее.
        //   1) сначала поднимаем фоновую работу с типом «захват экрана»,
        //   2) и только потом берём сам захват.
        // Наоборот — Android выбросит исключение. Разрешение пользователь уже
        // дал в диалоге, который вернулся в MainActivity.
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = manager.getMediaProjection(resultCode, resultData)
        if (proj == null) {
            Log.e(TAG, "Система не дала доступ к экрану")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = proj

        // На Android 15+ в статус-баре появляется чип «идёт захват экрана», и по
        // нему пользователь может в любой момент прекратить захват. Ловим это
        // и останавливаемся, иначе перевод навсегда зависнет поверх чужого
        // приложения.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "Пользователь прекратил захват экрана")
                stopSelf()
            }
        }, main)

        // Формат кадра — RGBA_8888, хотя распознаватель из готового кадра
        // берёт только JPEG и YUV: путь через YUV_420_888 на этом телефоне
        // роняет процесс нативно, сигналом 6, мимо любого try. RGBA мы
        // переводим в Bitmap сами, см. toBitmap.
        //
        // Сам захват создаёт ensureCapture() — он обязан следовать за
        // размером экрана, а размер меняется при повороте телефона.
        if (!ensureCapture()) {
            Log.e(TAG, "Android не дал создать захват экрана")
            stopSelf()
            return START_NOT_STICKY
        }


        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        showOverlay()
        running = true
        isRunning = true
        worker.execute { loop() }
        return START_NOT_STICKY
    }

    /**
     * Внутренние данные диалога захвата экрана читаются по-разному в разных
     * версиях Android: с 33-й нужно указывать класс, а старая перегрузка
     * помечена удалённой и на части прошивок падает.
     */
    @Suppress("DEPRECATION")
    private fun readResultData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

    /**
     * Основной цикл. Спит фиксированное время, а не «пока кадров нет»:
     * иначе на статичной картинке он крутился бы вхолостую и грел процессор.
     */
    private fun loop() {
        var frames = 0
        while (running) {
            // Телефон могли повернуть — пересоздаём захват под новый размер.
            if (!ensureCapture()) debug = "захват экрана не создаётся"

            val image = try {
                reader?.acquireLatestImage()
            } catch (t: Throwable) {
                Log.w(TAG, "Не удалось взять кадр", t)
                debug = "ошибка кадра: ${t.javaClass.simpleName}: ${t.message}"
                null
            }
            if (image != null) {
                frames++
                try {
                    process(image)
                } catch (t: Throwable) {
                    Log.w(TAG, "Кадр не обработан", t)
                    debug = "кадр #$frames, ошибка: ${t.javaClass.simpleName}: ${t.message}"
                } finally {
                    // Кадр обязательно закрываем. Забудешь — ImageReader
                    // упрётся в лимит картинок, и захват экрана встанет.
                    image.close()
                }
            } else {
                // Если эта строка висит — значит, картинка от Android не идёт
                // вовсе, и вопрос уже не в распознавании, а в захвате.
                debug = "кадров: $frames, картинка пока не приходит"
            }
            try {
                Thread.sleep(SCAN_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun process(image: Image) {
        // Насколько картинка в буфере повёрнута относительно экрана. Почти
        // всегда ноль: захват пересоздаётся под текущий размер, и буфер
        // совпадает с экраном. Ненулевое значение бывает лишь в один-два кадра
        // после поворота, пока пересоздание ещё не дошло. Разбор этой разницы —
        // в Geom.kt.
        val deg = ((rotationDegrees() - capDeg) % 360 + 360) % 360

        // Распознаватель ML Kit из кадра, взятого прямо с экрана, понимает
        // только JPEG и YUV. RGBA он берёт, только если сначала собрать из
        // кадра Bitmap.
        //
        // YUV_420_888 выглядел бы красивее (не надо копировать весь кадр), но
        // на этом телефоне он роняет процесс нативно, сигналом 6, то есть
        // мимо любого try — поэтому идём через Bitmap. Зато и взятие цвета
        // фона становится тривиальным: bitmap.getPixel вместо ручной сборки
        // цвета из плоскостей.
        val bitmap = try {
            toBitmap(image)
        } catch (t: Throwable) {
            Log.w(TAG, "Кадр не превратился в картинку", t)
            debug = "кадр в Bitmap не получился: ${t.javaClass.simpleName}: ${t.message}"
            return
        }

        try {
            processFrame(bitmap, deg)
        } finally {
            // Кадр 1080x2400 — это десяток мегабайт, раз в 0.7 секунды.
            // Без освобождения память забьётся очень быстро.
            bitmap.recycle()
        }
    }

    private fun processFrame(bitmap: Bitmap, deg: Int) {
        val now = SystemClock.elapsedRealtime()
        val result = try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, deg)))
        } catch (t: Throwable) {
            Log.w(TAG, "Распознавание не удалось", t)
            debug = "распознавание не удалось: ${t.javaClass.simpleName}: ${t.message}"
            return
        }

        var skippedGarbage = 0
        var skippedRussian = 0
        val found = ArrayList<Line>()

        for (block in result.textBlocks) {
            val box = block.boundingBox ?: continue
            val src = block.text.trim()
            if (src.isEmpty() || src.length > MAX_BLOCK_CHARS) continue
            if (box.width() < MIN_BOX_PX || box.height() < MIN_BOX_PX) continue

            // Не переводим то, что уже на русском.
            // Язык берём у первого слова первой строки блока — это единственное
            // место, где распознавание гарантированно его называет.
            val lang = block.lines.firstOrNull()?.elements?.firstOrNull()?.recognizedLanguage
            if (lang != null && lang.startsWith(TARGET_LANG, ignoreCase = true)) {
                skippedRussian++
                continue
            }

            // Мусор от сжатого видео. Лица, текстуры, буквы, наполовину
            // съеденные артефактами, — распознавание охотно выдаёт их за
            // строки. Если такое отправить в DeepL, на экране появится перевод
            // того, чего нет.
            if (!looksLikeText(src)) {
                skippedGarbage++
                continue
            }

            // Ищем не по точному совпадению, а по близости: «Неllo» должно
            // найти уже известное «Hello» и не породить ни нового запроса, ни
            // прыгающей рамки.
            val key = normalize(src)
            val line = findLine(key) ?: Line(key, src, Rect(box)).also { lines.add(it) }
            line.seen++
            line.lastSeen = now
            found.add(line)
        }

        // Забываем строки, ушедшие с экрана: субтитры сменились, и держать
        // старые переводы в памяти незачем.
        lines.removeAll { now - it.lastSeen > LINE_TTL_MS }
        if (lines.size > MAX_LINES) {
            lines.subList(0, lines.size - MAX_LINES).clear()
        }

        val toSend = found.filter { it.translation == null && it.seen == STABLE_SCANS }
        if (toSend.isNotEmpty()) translate(toSend)

        // Рисуем всё, что уже признано настоящим текстом. Пока перевод ещё не
        // пришёл, рамку всё равно закрашиваем, но пустым текстом: оригинал
        // исчезает сразу и не мельтешит, пока едет ответ от DeepL.
        val out = ArrayList<Item>(found.size)
        for (line in found) {
            if (line.seen < STABLE_SCANS) continue
            val box = line.box
            val rows = line.src.count { it == '\n' } + 1
            out.add(
                Item(
                    box = box,
                    text = line.translation ?: "",
                    color = sampleColor(bitmap, box, deg),
                    startSize = box.height() / rows.toFloat(),
                )
            )
        }

        Log.d(TAG, "найдено=${found.size} показано=${out.size} отправлено=${toSend.size}")

        // Показываем весь ход дела на экране приложения: с телефона логи не
        // достать, а «ничего не переводится» одинаково выглядит и при сети,
        // и при пустом распознавании.
        debug = "блоков всего ${result.textBlocks.size} (мусор $skippedGarbage, " +
            "русских $skippedRussian)\nстрок в работе: ${found.size}, показано: ${out.size}\n" +
            "ошибок сети: $netErrors" + if (lastNetError.isEmpty()) "" else "\n$lastNetError"

        // Перерисовываем оверлей только если что-то действительно изменилось.
        // Раньше мы дёргали setItems каждый кадр, и оверлей мигал даже тогда,
        // когда картинка была та же самая: список пересоздавался заново и
        // сравнить его было не с чем.
        if (out != shown) {
            shown = out
            // setItems трогает картинку на экране, поэтому только с главного потока.
            main.post { view?.setItems(out) }
        }
    }

    /** Что сейчас нарисовано на оверлее. Только рабочий поток. */
    private var shown: List<Item> = emptyList()

    /**
     * Ключ для сравнения строк: без пробелов, знаков и регистра.
     *
     * Так «Hello, world», «hello world» и «Неllo, wоrld» дают один ключ, и
     * распознавание перестаёт ронять перевод на каждую описку.
     */
    private fun normalize(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Ищет уже известную строку: сначала по точному ключу, затем по близости.
     *
     * Второй проход — то, ради чего всё затевалось. Без него дрожь
     * распознавания превращалась бы в тысячи одинаковых запросов к DeepL и в
     * рамку, прыгающую по экрану.
     */
    private fun findLine(key: String): Line? {
        if (key.isEmpty()) return null
        lines.firstOrNull { it.key == key }?.let { return it }
        for (l in lines) {
            if (editDistanceWithin(key, l.key, 2)) return l
        }
        return null
    }

    /**
     * Похоже ли это вообще на текст, который стоит переводить.
     *
     * Требования намеренно грубые: букв должно быть несколько, и они должны
     * идти подряд, без разнородного мусора между ними. Настоящая надпись
     * удовлетворяет этому всегда, а вот «8Зр|Зо», распознанное из лица или
     * из полосы сжатия, — нет.
     *
     * ponytail: грубая эвристика по символам. Настоящий фильтр один — стабильность
     * на [STABLE_SCANS] кадрах, мусор от сжатия не держится три кадра. Если после
     * этого всё ещё полезут выдуманные строки, смотреть в сторону ML Kit
     * TextRecognizerOptions с перечислением языков вместо подгонки порогов.
     */
    private fun looksLikeText(s: String): Boolean {
        if (s.count { it.isLetter() } < 3) return false
        val allowed = s.count { it.isLetterOrDigit() || it in ALLOWED_PUNCT }
        return allowed >= s.length * 3 / 4
    }

    /**
     * Угадывает цвет фона под текстом: берём точки по краю рамки и находим
     * «средний цвет», то есть медиану.
     *
     * Точки берутся снаружи рамки специально: внутри сами буквы, и медиана по
     * ним ответила бы «текст тёмный», а не «фон такого-то цвета».
     */
    private fun sampleColor(bitmap: Bitmap, box: Rect, deg: Int): Int {
        val b = toBufferBox(box.left, box.top, box.right, box.bottom, deg, bitmap.width, bitmap.height)
        val ring = ringPoints(b[0], b[1], b[2], b[3], SAMPLE_BAND_PX)
        val samples = ArrayList<Int>(ring.size / 2)
        var i = 0
        while (i < ring.size) {
            val x = ring[i]
            val y = ring[i + 1]
            if (x < 0 || y < 0 || x >= bitmap.width || y >= bitmap.height) {
                i += 2
                continue
            }
            samples.add(bitmap.getPixel(x, y))
            i += 2
        }
        return medianColor(samples.toIntArray())
    }

    /**
     * Превращает кадр экрана в обычную картинку.
     *
     * Android отдаёт кадр в памяти построчно, причём «строка» бывает чуть
     * шире самой картинки (для выравнивания), а байты внутри пикселя идут в
     * порядке R, G, B, A. Поэтому адрес каждого пикселя приходится считать
     * самому, а не брать готовым способом из буфера.
     */
    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height

        // Сначала копируем в обычный массив: поштучно дёргать ByteBuffer
        // миллионы раз дороже, чем один раз скопировать подряд.
        val needed = (h - 1) * rowStride + w * pixelStride
        val bytes = ByteArray(minOf(needed, buffer.remaining()))
        buffer.get(bytes, 0, bytes.size)

        val pixels = IntArray(w * h)
        var p = 0
        for (y in 0 until h) {
            var off = y * rowStride
            for (x in 0 until w) {
                val r = bytes[off].toInt() and 0xFF
                val g = bytes[off + 1].toInt() and 0xFF
                val b = bytes[off + 2].toInt() and 0xFF
                pixels[p++] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                off += pixelStride
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Переводит пачку строк и складывает переводы в память. Ошибки не
     * пробрасываем: при обрыве сети лучше молча не показать перевод, чем
     * уронить весь конвейер.
     */
    private fun translate(pending: List<Line>) {
        for (chunk in pending.chunked(BATCH)) {
            try {
                val body = JSONObject()
                    .put("text", JSONArray(chunk.map { it.src }))
                    .put("target_lang", TARGET_LANG)
                    // split_sentences=0 — «не делить на предложения». По
                    // умолчанию DeepL режет текст по точкам, и короткая надпись
                    // вроде "Start game." превратится в два перевода с разорванной
                    // структурой. Нам нужна цельная строка, а не литературный текст.
                    .put("split_sentences", "0")
                    .toString()

                // У бесплатного DeepL один адрес, у платного — другой. Ключ
                // сам подсказывает какой: у бесплатного в конце «:fx».
                val host = if (BuildConfig.DEEPL_FREE) "api-free" else "api"
                val connection = (URL("https://$host.deepl.com/v2/translate")
                    .openConnection() as HttpURLConnection)
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                // Заголовок авторизации у DeepL именно такой, не как у Bearer.
                connection.setRequestProperty(
                    "Authorization",
                    "DeepL-Auth-Key " + BuildConfig.DEEPL_KEY,
                )
                connection.outputStream.use { it.write(body.toByteArray()) }

                val code = connection.responseCode
                val stream: InputStream? =
                    if (code in 200..299) connection.inputStream else connection.errorStream
                val payload = stream?.bufferedReader()?.use { it.readText() } ?: ""

                if (code !in 200..299) {
                    // 403 — ключ не от того адреса, 429 — упёрлись в лимит,
                    // 456 — ключ сломан. Пишем и в лог, и на экран приложения:
                    // иначе пользователь видит просто «ничего не переводится».
                    Log.w(TAG, "DeepL ответил $code: ${payload.take(300)}")
                    netErrors++
                    lastNetError = "DeepL $code: ${payload.take(120)}"
                    continue
                }

                val translations = JSONObject(payload).getJSONArray("translations")
                for (i in chunk.indices) {
                    val translated = translations.getJSONObject(i).optString("text")
                    if (translated.isNotEmpty()) chunk[i].translation = translated
                }
                Log.i(TAG, "DeepL: ${chunk.size} строк, всего в работе ${lines.size}")
            } catch (t: Throwable) {
                Log.w(TAG, "Запрос к DeepL не прошёл", t)
                netErrors++
                lastNetError = "сеть: ${t.javaClass.simpleName}: ${t.message}"
            }
        }
    }

    /**
     * На сколько сейчас повёрнут экран. Спрашиваем через DisplayManager:
     // попытка узнать это из фоновой работы (а не из окна приложения) на
     * Android 11+ заканчивается ошибкой.
     */
    /**
     * Захват экрана ровно по текущему размеру экрана.
     *
     * Раньше размер брался один раз — при нажатии кнопки — и буфер намертво
     * запекался в него. Стоило повернуть телефон, и Android начинал впихивать
     * альбомную картинку в портретный буфер, добавив по краям чёрные поля.
     * Распознавание честно возвращало рамку для этой уменьшенной картинки, а
     * оверлей рисовал по её координатам — то есть мимо настоящего экрана.
     * Именно так перевод «наезжал» в горизонтальном положении.
     *
     * Теперь размер спрашивается каждый кадр, и при повороте захват
     * пересоздаётся. Буфер совпадает с экраном один в один: ни полей, ни
     * масштабирования, координаты рамок и координаты оверлея совпадают.
     *
     * Порядок важен: сначала создаём новый, и только потом закрываем старый.
     * Иначе мигновение неудачи оставит нас вообще без картинки.
     *
     * В Android 15+ у createVirtualDisplay восемь параметров: после dpi идёт
     * int flags, а поверхность идёт уже после него.
     * ponytail: вызов рассчитан на API 35+ (телефон на Android 16). Для
     * старых версий нужен обратный вызов через рефлексию — добавлять,
     * только если приложение пойдёт на Android 14 и ниже.
     */
    private fun ensureCapture(): Boolean {
        val proj = projection ?: return false
        val size = screenSize() ?: return false
        val deg = rotationDegrees()
        if (reader != null && size.first == capW && size.second == capH && deg == capDeg) {
            return true
        }
        val first = reader == null

        val newReader = ImageReader.newInstance(size.first, size.second, PixelFormat.RGBA_8888, 2)
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        val newVirtual = try {
            proj.createVirtualDisplay(
                VIRTUAL_DISPLAY, size.first, size.second, DPI, flags, newReader.surface, null, null,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Захват не создался", t)
            newReader.close()
            return false
        }

        reader?.close()
        virtual?.release()
        reader = newReader
        virtual = newVirtual
        capW = size.first
        capH = size.second
        capDeg = deg

        // Экран перевернулся или сменился размер — значит, всё найденное раньше
        // лежит теперь в других координатах. Забываем это, иначе перевод
        // на секунды зависнет в стороне от своего текста. На первом создании
        // захвата трогать нечего: пусто и так.
        if (!first) {
            lines.clear()
            shown = emptyList()
        }

        Log.i(TAG, "захват ${capW}x$capH, поворот $capDeg")
        return true
    }

    /**
     * Размер экрана в его нынешней ориентации — ровно те координаты, в которых
     * рисует оверлей. С Android 30 это maximumWindowMetrics, раньше — getRealSize.
     */
    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int>? {
        val wm = getSystemService(WindowManager::class.java) ?: return null
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val p = Point()
            wm.defaultDisplay.getRealSize(p)
            p.x to p.y
        }
    }

    private fun rotationDegrees(): Int {
        val manager = getSystemService(DisplayManager::class.java) ?: return ROT_0
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY) ?: return ROT_0
        return when (display.rotation) {
            Surface.ROTATION_90 -> ROT_90
            Surface.ROTATION_180 -> ROT_180
            Surface.ROTATION_270 -> ROT_270
            else -> ROT_0
        }
    }

    /**
     * Прозрачное окно поверх всего экрана.
     *
     * NOT_TOUCHABLE — обязательный флаг: без него окно перехватывало бы касания,
     * и управление в игре перестало бы работать. NOT_FOCUSABLE — чтобы не
     * забирать клавиатуру у приложения под нами.
     */
    private fun showOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        val overlay = OverlayView(this)
        (getSystemService(WINDOW_SERVICE) as WindowManager).addView(overlay, params)
        view = overlay
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Перевод экрана", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Перевод экрана")
            .setContentText("Идёт распознавание и перевод")
            // Взята иконка из самой системы, чтобы не тащить свою картинку в проект.
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()
    }

    override fun onDestroy() {
        running = false
        isRunning = false
        worker.shutdownNow()
        virtual?.release()
        virtual = null
        reader?.close()
        reader = null
        // Гасим именно в таком порядке: сначала перестаём читать кадры, потом
        // выключаем сам захват экрана. Иначе система может держать его живым.
        projection?.stop()
        projection = null
        if (this::recognizer.isInitialized) recognizer.close()
        view?.let { v ->
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) }
        }
        view = null
        super.onDestroy()
    }
}
