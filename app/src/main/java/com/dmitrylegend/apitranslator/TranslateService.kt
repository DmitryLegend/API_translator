package com.dmitrylegend.apitranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.LruCache
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
import java.nio.ByteBuffer
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
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"

        private const val TAG = "Translator"

        /** На какой язык переводим. */
        const val TARGET_LANG = "RU"

        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "translator"
        const val VIRTUAL_DISPLAY = "translator"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000

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
         * Заодно это главная экономия лимита: 1 000 000 символов в месяц — не
         * бездонно.
         */
        private const val STABLE_SCANS = 2

        /** Блок длиннее — почти наверняка мусор распознавания. Не переводим. */
        private const val MAX_BLOCK_CHARS = 400

        /** Мельче — не текст, а иконки, цифры в часах, штрих-коды. */
        private const val MIN_BOX_PX = 26

        /** DeepL берёт максимум 50 строк за один запрос. Берём с запасом. */
        private const val BATCH = 40

        /** Насколько широкой полосой вокруг текста берём цвет фона. */
        private const val SAMPLE_BAND_PX = 3

        /**
         * Сколько переводов помним. Названия кнопок и реплики героев
         * повторяются постоянно, и каждый повтор из памяти — это запрос,
         * до которого мы не дошли.
         */
        private const val CACHE_SIZE = 2000

        /**
         * Простое «работает или нет». Приложение одно и однопоточное по
         * экрану, поэтому связывать MainActivity с сервисом через
         * подключение смысла нет.
         */
        @Volatile
        var isRunning = false
            private set
    }

    /** Память переводов. Кто-то один её трогает — рабочий поток. */
    private val cache = LruCache<String, String>(CACHE_SIZE)

    /** Сколько кадров подряд каждая строка наблюдалась одинаковой. */
    private val stability = HashMap<String, Int>()

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

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = readResultData(intent)
        val width = intent.getIntExtra(EXTRA_WIDTH, 0)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 0)
        if (resultData == null || width <= 0 || height <= 0) {
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

        // Снимаем экран в «родной» ориентации (вертикально), даже если телефон
        // сейчас лежит на боку. Android сам развернёт картинку внутри буфера, а
        // мы скажем распознавателю угол поворота — и получим рамки сразу в
        // понятных координатах. Разбор этой разницы — в Geom.kt.
        val imgReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imgReader
        // В Android 15+ у createVirtualDisplay восемь параметров: после dpi идёт
        // int flags, а поверхность идёт уже после него. Флаги AUTO_MIRROR и
        // PRESENTATION — те же, что раньше были зашиты внутрь метода.
        // ponytail: вызов рассчитан на API 35+ (телефон на Android 16). Для
        // старых версий нужен обратный вызов через рефлексию — добавлять,
        // только если приложение пойдёт на Android 14 и ниже.
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        proj.createVirtualDisplay(VIRTUAL_DISPLAY, width, height, 100, flags, imgReader.surface, null, null)


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
        while (running) {
            val image = try {
                reader?.acquireLatestImage()
            } catch (t: Throwable) {
                Log.w(TAG, "Не удалось взять кадр", t)
                null
            }
            if (image != null) {
                try {
                    process(image)
                } catch (t: Throwable) {
                    Log.w(TAG, "Кадр не обработан", t)
                } finally {
                    // Кадр обязательно закрываем. Забудешь — ImageReader
                    // упрётся в лимит картинок, и захват экрана встанет.
                    image.close()
                }
            }
            try {
                Thread.sleep(SCAN_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun process(image: Image) {
        val deg = rotationDegrees()

        // Кадр отдаём распознавателю как есть, в Bitmap превращать незачем:
        // ML Kit умеет брать такую картинку напрямую, а для цвета фона нам
        // понадобится всего несколько сотен пикселей (см. sampleColor), а не
        // весь кадр целиком.
        val result = try {
            Tasks.await(recognizer.process(InputImage.fromMediaImage(image, deg)))
        } catch (t: Throwable) {
            Log.w(TAG, "Распознавание не удалось", t)
            return
        }

        val boxes = ArrayList<Rect>()
        val texts = ArrayList<String>()
        for (block in result.textBlocks) {
            val src = block.text.trim()
            val box = block.boundingBox
            if (src.isEmpty() || src.length > MAX_BLOCK_CHARS) continue
            if (box == null || box.width() < MIN_BOX_PX || box.height() < MIN_BOX_PX) continue

            // Не переводим то, что уже на русском.
            // Язык берём у первого слова первой строки блока — это единственное
            // место, где распознавание гарантированно его называет.
            val lang = block.lines.firstOrNull()?.elements?.firstOrNull()?.recognizedLanguage
            if (lang != null && lang.startsWith(TARGET_LANG, ignoreCase = true)) continue

            boxes.add(box)
            texts.add(src)
        }

        // Считаем строку готовой к отправке, только когда увидели её
        // STABLE_SCANS раз подряд. Проверка именно «== », а не «>= », заодно
        // убирает повторы: если одна строка попала в два блока, к моменту
        // второй прохода счётчик уже увеличен и второй раз в список не попадёт.
        val onScreen = HashSet(texts)
        val toSend = ArrayList<String>()
        for (t in texts) {
            if (cache.get(t) != null) continue
            val n = (stability[t] ?: 0) + 1
            stability[t] = n
            if (n == STABLE_SCANS) toSend.add(t)
        }
        // Забываем строки, ушедшие с экрана. Иначе при долгой прокрутке память
        // забьётся десятками тысяч ненужных ключей.
        stability.keys.toList().forEach { if (!onScreen.contains(it)) stability.remove(it) }

        if (toSend.isNotEmpty()) translate(toSend)

        // Рисуем всё, для чего перевод уже есть. Только что увиденные строки
        // в этом кадре не появятся — подставятся следующим, через 0.7–2 секунды.
        // Это и есть та самая задержка.
        val items = ArrayList<Item>(texts.size)
        for (i in texts.indices) {
            val dst = cache.get(texts[i]) ?: continue
            val box = boxes[i]
            val lines = texts[i].count { it == '\n' } + 1
            items.add(
                Item(
                    box = box,
                    text = dst,
                    color = sampleColor(image, box, deg),
                    startSize = box.height() / lines.toFloat(),
                )
            )
        }

        Log.d(TAG, "найдено=${texts.size} показано=${items.size} отправлено=${toSend.size}")
        // setItems трогает картинку на экране, поэтому только с главного потока.
        main.post { view?.setItems(items) }
    }

    /**
     * Угадывает цвет фона под текстом: берём точки по краю рамки и находим
     * «средний цвет», то есть медиану.
     *
     * Точки берутся снаружи рамки специально: внутри сами буквы, и медиана по
     * ним ответила бы «текст тёмный», а не «фон такого-то цвета».
     */
    private fun sampleColor(image: Image, box: Rect, deg: Int): Int {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val bw = image.width
        val bh = image.height

        val b = toBufferBox(box.left, box.top, box.right, box.bottom, deg, bw, bh)
        val ring = ringPoints(b[0], b[1], b[2], b[3], SAMPLE_BAND_PX)

        val samples = ArrayList<Int>(ring.size / 2)
        var i = 0
        while (i < ring.size) {
            val color = pixelAt(buffer, rowStride, pixelStride, ring[i], ring[i + 1], bw, bh)
            if (color != null) samples.add(color)
            i += 2
        }
        return medianColor(samples.toIntArray())
    }

    /**
     * Достаёт один пиксель из картинки.
     *
     * Читаем байты напрямую и собираем цвет вручную, а не через готовые
     * функции картинок: Bitmap у нас нет, а «строка» в памяти может быть чуть
     * шире, чем сама картинка (Android иногда добавляет выравнивание), так что
     * адрес пикселя приходится считать самим.
     */
    private fun pixelAt(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ): Int? {
        if (x < 0 || y < 0 || x >= w || y >= h) return null
        val offset = y * rowStride + x * pixelStride
        if (offset + 2 >= buffer.limit()) return null
        val r = buffer.get(offset).toInt() and 0xFF
        val g = buffer.get(offset + 1).toInt() and 0xFF
        val b = buffer.get(offset + 2).toInt() and 0xFF
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Переводит пачку строк и складывает переводы в память. Ошибки не
     * пробрасываем: при обрыве сети лучше молча не показать перевод, чем
     * уронить весь конвейер.
     */
    private fun translate(texts: List<String>) {
        for (chunk in texts.chunked(BATCH)) {
            try {
                val body = JSONObject()
                    .put("text", JSONArray(chunk))
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
                    // 456 — ключ сломан. Пишем в лог и идём дальше: через пару
                    // секунд тот же текст попробует уйти снова.
                    Log.w(TAG, "DeepL ответил $code: ${payload.take(300)}")
                    continue
                }

                val translations = JSONObject(payload).getJSONArray("translations")
                for (i in chunk.indices) {
                    val translated = translations.getJSONObject(i).optString("text")
                    if (translated.isNotEmpty()) cache.put(chunk[i], translated)
                }
                Log.i(TAG, "DeepL: ${chunk.size} строк, в памяти ${cache.size()}")
            } catch (t: Throwable) {
                Log.w(TAG, "Запрос к DeepL не прошёл", t)
            }
        }
    }

    /**
     * На сколько сейчас повёрнут экран. Спрашиваем через DisplayManager:
     // попытка узнать это из фоновой работы (а не из окна приложения) на
     * Android 11+ заканчивается ошибкой.
     */
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
