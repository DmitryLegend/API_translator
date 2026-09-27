package com.dmitrylegend.apitranslator

import android.Manifest
import android.app.Activity
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
        // Ход конвейера и последняя ошибка запуска выводятся прямо на экран.
        // С телефона логи Android не достать, так что это единственное место,
        // где видно, на чём именно всё остановилось.
        val error = TranslateService.lastError(this)
        status.text = buildString {
            append("Перевод: DeepL")
            if (running) {
                append("\n\nИдёт перевод экрана\n\n")
                append(TranslateService.debug)
            }
            if (error.isNotEmpty()) {
                append("\n\nОШИБКА ЗАПУСКА:\n")
                append(error)
            }
        }
        button.text = if (running) "Остановить" else "Запустить перевод"
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
