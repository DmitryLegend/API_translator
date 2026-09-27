package com.dmitrylegend.apitranslator

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Единственный экран приложения: одна большая кнопка.
 *
 * Весь интерфейс собирается кодом прямо здесь, без файлов разметки. Для
 * двух элементов это короче, чем заводить отдельный layout-файл и класс для
 * его разбора, а заодно не тянет за собой библиотеку поддержки.
 */
class MainActivity : Activity() {

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var button: Button

    /** Что уже скопировано в буфер, чтобы не сбрасывать его каждую секунду. */
    private var copied: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = dp(24)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply {
            // Подпись DeepL, обязательная по их условиям использования
            // бесплатного API: нужно явно сказать, что перевод делает DeepL.
            text = "Перевод: DeepL"
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
        }

        button = Button(this).apply {
            setOnClickListener { toggle() }
        }

        root.addView(
            status,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(
            button,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = pad },
        )

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // Экран мог вернуться к нам из настроек разрешений — рисуем актуальное
        // состояние, а не то, что было при запуске.
        refresh()
        // Пока идёт перевод, обновляем счётчики раз в секунду: иначе цифры
        // на экране были бы из того момента, когда вы сюда зашли.
        main.postDelayed(ticker, 1000)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            if (TranslateService.isRunning) main.postDelayed(this, 1000)
        }
    }

    private fun refresh() {
        val running = TranslateService.isRunning
        val report = lastExitReport()
        val error = TranslateService.lastError(this)

        // Автокопирование: с телефона логи не достать, а вставить текст в чат
        // руками — лишняя ошибка. Копируем только когда есть что копировать,
        // и не на каждом обновлении, а один раз на запуск экрана.
        val text = buildString {
            if (running) {
                append("Идёт перевод экрана\n\n")
                append(TranslateService.debug)
            }
            if (error.isNotEmpty()) append("\n\nОШИБКА ЗАПУСКА:\n$error")
            if (report.isNotEmpty()) append("\n\nПРИЧИНА ПАДЕНИЯ ПРОЦЕССА:\n$report")
        }
        if (text.isNotEmpty() && text != copied) {
            copied = text
            copyToClipboard(text)
        }

        status.text = buildString {
            append("Перевод: DeepL")
            if (text.isNotEmpty()) append("\n\n").append(text)
            if (text.isNotEmpty()) append("\n\n(скопировано в буфер обмена)")
        }
        button.text = if (running) "Остановить" else "Запустить перевод"
    }

    private fun copyToClipboard(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText("API_translator", text))
    }

    /**
     * Почему процесс умер в прошлый раз.
     *
     * Нужно потому, что наш собственный `try` тут бессилен: если процесс
     * убивают насквозь — нативный краш, сигнал от системы, нехватка памяти —
     * код не успевает ничего записать, и приложение просто исчезает. Android
     * при этом сам запоминает причину и отдаёт её при следующем запуске
     * вместе с текстом падения.
     */
    private fun lastExitReport(): String {
        if (Build.VERSION.SDK_INT < 30) return ""
        val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return ""
        val info = runCatching {
            am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()
        }.getOrNull() ?: return ""
        val why = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH -> "обычный краш"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "нативный краш"
            ApplicationExitInfo.REASON_ANR -> "зависание (ANR)"
            ApplicationExitInfo.REASON_SIGNALED -> "убит сигналом " + info.status
            ApplicationExitInfo.REASON_LOW_MEMORY -> "кончилась память"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "пользователь закрыл"
            else -> "причина ${info.reason}, код ${info.status}"
        }
        val trace = runCatching {
            info.traceInputStream?.bufferedReader()?.use { it.readText() } ?: ""
        }.getOrDefault("")
        return "$why\n$trace".take(4000)
    }

    private fun toggle() {
        if (TranslateService.isRunning) {
            stopService(Intent(this, TranslateService::class.java))
        } else {
            start()
        }
        refresh()
    }

    /**
     * Проверяет разрешения по порядку и только потом просит захват экрана.
     * Каждое «не сработало» просто возвращает нас сюда же: пользователь нажимает
     * кнопку ещё раз. Такой подход выглядит глупо, но не требует хранить
     * состояние на все случаи.
     */
    private fun start() {
        if (BuildConfig.DEEPL_KEY.isBlank()) {
            status.text = "Перевод: DeepL\n\nКлюч DeepL не встроен в сборку.\n" +
                "Пересобери с DEEPL_API_KEY=... — инструкция в README."
            return
        }

        // Уведомление о фоновой работе. Без него на Android 13+ пользователь
        // ничего не увидит и решит, что приложение сломалось.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            return
        }

        // Право рисовать поверх других приложений выдаётся только в настройках
        // системы, из приложения его нельзя получить в одно касание.
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
            return
        }

        // Системный диалог «разрешить показывать экран в других приложениях».
        // Android требует его показывать через startActivityForResult, поэтому
        // здесь устаревший на вид API — это не недосмотр, а требование платформы.
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS) start()
    }

    // На Android 14+ у метода появилась вторая подпись, и старая на части
    // прошивок не вызывается. Но переопределение старой всё ещё обязательно,
    // иначе диалог захвата экрана вернётся в никуда.
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            // Пользователь передумал. Молча возвращаем кнопку в исходное
            // состояние.
            refresh()
            return
        }

        val (width, height) = screenSize()
        startForegroundService(
            Intent(this, TranslateService::class.java).apply {
                putExtra(TranslateService.EXTRA_RESULT_CODE, resultCode)
                putExtra(TranslateService.EXTRA_RESULT_DATA, data)
                putExtra(TranslateService.EXTRA_WIDTH, width)
                putExtra(TranslateService.EXTRA_HEIGHT, height)
            }
        )
        refresh()
    }

    /**
     * Размер экрана, в котором рисует оверлей.
     *
     * С Android 30 это делается одним способом, раньше — другим. Берём «родную»
     * (вертикальную) ориентацию: снимать будем в ней, а поворот обработает
     * TranslateService (см. Geom.kt).
     */
    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = windowManager.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val p = Point()
            windowManager.defaultDisplay.getRealSize(p)
            p.x to p.y
        }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()

    private companion object {
        const val REQ_CAPTURE = 10
        const val REQ_NOTIFICATIONS = 11
    }
}
