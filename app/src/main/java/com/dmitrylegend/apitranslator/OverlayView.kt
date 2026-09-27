package com.dmitrylegend.apitranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View

/**
 * Один кусочек перевода, готовый к отрисовке. Всё, что нужно для отрисовки,
 * посчитано заранее в [TranslateService] — в отдельном потоке, чтобы
 * [onDraw] (он выполняется на главном потоке) не тормозил картинку.
 */
data class Item(
    /** Где стоял оригинальный текст. Перевод рисуем ровно на этом месте. */
    val box: Rect,
    /** Сам перевод. Может содержать много строк, поэтому рисуется не drawText. */
    val text: String,
    /** Цвет фона вокруг оригинала — посчитали как медиану по точкам у краёв. */
    val color: Int,
    /**
     * С какого размера шрифта начинать подгонку: высота оригинала, делённая на
     * число строк в оригинале. Потом ужимаем вниз, потому что перевод почти
     * наверняка окажется длиннее оригинала.
     */
    val startSize: Float,
)

/**
 * Прозрачное окно поверх всего экрана. Закрашивает найденный текст цветом его
 * фона и рисует на этом месте перевод.
 *
 * Прозрачность тут обманчивая: View не заливает ничего целиком. Фон окна
 * прозрачный, а View рисует только маленькие прямоугольники там, где был
 * текст. Всё остальное содержимое экрана проходит насквозь нетронутым.
 */
class OverlayView(context: Context) : View(context) {

    private var items: List<Item> = emptyList()

    /** Закрашивает оригинал. Цвет берётся из [Item] перед каждой отрисовкой. */
    private val fillPaint = Paint().apply { style = Paint.Style.FILL }

    /**
     * Шрифт перевода, полужирный. Белый жирный текст на тёмном фоне читается
     * поверх видео заметно увереннее тонкого, а выбирать нам приходится вслепую:
     * про фон известно только то, что попало в медиану.
     */
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        isFakeBoldText = true
    }

    /** Вызывается с главного потока. */
    fun setItems(items: List<Item>) {
        this.items = items
        // Перерисовываем всегда: дешёвая проверка «а изменилось ли что-нибудь»
        // стоила бы дороже, чем сама перерисовка. Для ускорения есть
        // полноценный путь — рисовать прямо из готового Bitmap, но ради
        // пятнадцати прямоугольников это лишняя механика.
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for (item in items) {
            // Шаг 1. Убираем оригинал: заливаем его цветом фона.
            fillPaint.color = item.color
            canvas.drawRect(item.box, fillPaint)

            val layout = layoutFor(item) ?: continue

            // Шаг 2. Выбираем цвет букв, чтобы читалось.
            // Color.luminance — это «насколько цвет светлый», от 0 (чёрный)
            // до 1 (белый). Около 0.5 проходит граница, за которой светлый
            // текст на тёмном читается лучше. Точность тут не важна: мы
            // закрашиваем фон сами, а не пытаемся угадать чужой дизайн.
            textPaint.color = if (Color.luminance(item.color) < 0.45f) Color.WHITE else Color.BLACK

            // Шаг 3. Рисуем по центру рамки. Если перевод вышел короче
            // оригинала (так бывает с короткими словами), он встанет ровно
            // посередине, а не прилипнет к верху.
            val dy = (item.box.height() - layout.height) / 2f
            canvas.save()
            canvas.translate(item.box.left.toFloat(), item.box.top + dy)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    /**
     * Подгоняет перевод под рамку оригинала.
     *
     * Разрешаем тексту быть выше оригинала: русский текст в среднем длиннее
     * английского примерно в полтора раза. Если ужимать строго до исходной
     * высоты, субтитры превратятся в нечитаемую кашу. Лучше закрыть собой
     * кусочек картинки снизу, чем показать огрызки.
     */
    private fun layoutFor(item: Item): StaticLayout? {
        val width = item.box.width()
        if (width <= 0) return null

        val size = fitSize(MIN_TEXT_PX, item.startSize, item.box.height() * MAX_HEIGHT_GROWTH) { s ->
            build(item.text, width, s).height.toFloat()
        }
        return build(item.text, width, size)
    }

    private fun build(text: String, width: Int, size: Float): StaticLayout {
        textPaint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            // Выключаем служебные отступы сверху и снизу. С ними StaticLayout
            // добавляет несколько лишних пикселей, и подгонка по высоте едет.
            .setIncludePad(false)
            // Интерлиньяж чуть плотнее обычного: текст занимает меньше места
            // и реже задевает соседние строки экрана.
            .setLineSpacing(0f, 0.92f)
            .build()
    }

    private companion object {
        /** Мельче этого текст нечитаем. Упираемся сюда и рисуем в обрез. */
        const val MIN_TEXT_PX = 7f

        /** Во сколько раз перевод может оказаться выше оригинала. */
        const val MAX_HEIGHT_GROWTH = 1.6f
    }
}
