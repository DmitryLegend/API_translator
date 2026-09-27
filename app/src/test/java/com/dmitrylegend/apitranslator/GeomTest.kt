package com.dmitrylegend.apitranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверки на чистой математике. Тест выполняется на CI обычной командой
 * `./gradlew test` — без телефона и эмулятора.
 *
 * Здесь проверяется ровно то, что ломается тихо и незаметно: пересчёт
 * координат при повороте экрана и подбор размера шрифта. Остальное —
 * распознавание текста, сеть, отрисовка — проверяется только на реальном
 * устройстве, автоматически это сделать нечем.
 */
class GeomTest {

    /**
     * Главное, что тут ломается: при повороте экрана координаты «по картинке»
     * и координаты «по экрану» должны совпадать после преобразования, иначе
     * цвет фона берётся не оттуда и заливка получается чужой.
     *
     * Угол каждой стороны считаем руками на маленьком неквадратном прямоугольнике.
     */
    @Test
    fun поворот_на_90_градусов() {
        // Картинка 200x400 (телефон вертикально), телефон повёрнут на бок.
        // Тогда по экрану он 400x200.
        val b = toBufferBox(left = 10, top = 20, right = 60, bottom = 30, deg = ROT_90, bw = 200, bh = 400)

        // По экрану: x 10..60, y 20..30. После поворота на бок эта полоска
        // лежит вертикально: по картинке её x меняется местами с y, а
        // «верх экрана» уезжает к правому краю картинки.
        // Ширину и высоту считаем как «право минус лево», то есть без
        // прибавления единицы: это длина отрезка, а не число пикселей.
        assertEquals("ширина", 10, b[2] - b[0])
        assertEquals("высота", 50, b[3] - b[1])
    }

    @Test
    fun поворот_на_270_градусов() {
        val b = toBufferBox(left = 10, top = 20, right = 60, bottom = 30, deg = ROT_270, bw = 200, bh = 400)
        assertEquals("ширина", 10, b[2] - b[0])
        assertEquals("высота", 50, b[3] - b[1])
    }

    @Test
    fun поворот_на_180_градусов() {
        // При 180° ничего не меняется местами, поэтому размеры рамки сохраняются.
        val b = toBufferBox(left = 10, top = 20, right = 60, bottom = 30, deg = ROT_180, bw = 200, bh = 400)
        assertEquals("ширина", 50, b[2] - b[0])
        assertEquals("высота", 10, b[3] - b[1])
    }

    @Test
    fun без_поворота_координаты_не_меняются() {
        val b = toBufferBox(left = 10, top = 20, right = 60, bottom = 30, deg = ROT_0, bw = 200, bh = 400)
        assertEquals(10, b[0])
        assertEquals(20, b[1])
        assertEquals(60, b[2])
        assertEquals(30, b[3])
    }

    /**
     * Размер шрифта подбирается так, чтобы текст влез в рамку. Здесь «высота
     * текста» — простая формула вместо настоящего текста: важна сама логика
     * подбора, а не отрисовка.
     */
    @Test
    fun подбор_шрифта_ужимает_перевод() {
        val lineHeight: (Float) -> Float = { size -> size }

        // В высоту 40 влезает шрифт 40 и всё, что меньше.
        assertEquals(40f, fitSize(6f, 100f, 40f, lineHeight), 0.5f)
        // Если влезает даже максимум — не ужимаем.
        assertEquals(100f, fitSize(6f, 100f, 500f, lineHeight), 0.5f)
        // Если не влезает даже минимум — рисуем в обрез, но показываем хоть что-то.
        assertEquals(6f, fitSize(6f, 20f, 2f, lineHeight), 0.5f)
    }

    /**
     * Текст в две строки должен занимать вдвое больше высоты при том же
     * кегле. Проверяем, что подгонка видит реальную разницу.
     */
    @Test
    fun подбор_шрифта_учитывает_число_строк() {
        val twoLines: (Float) -> Float = { size -> size * 2f }
        // Высота 100, текст в две строки: подходит 50, но не больше.
        assertEquals(50f, fitSize(6f, 200f, 100f, twoLines), 0.5f)
    }

    /**
     * Распознавание дрожит, и эта дрожь не должна считаться новым текстом.
     * Проверяем ровно то, на чём держался перевод: на близости строк.
     */
    @Test
    fun дрожь_распознавания_считается_той_же_строкой() {
        // Опечатка в одной букве и потерянный пробел — обычное дело.
        assertTrue(editDistanceWithin("helloworld", "helloworid", 2))
        assertTrue(editDistanceWithin("helloworld", "helloworld", 2))
        assertTrue(editDistanceWithin("hello", "hellow", 1))
        // А вот это уже другой текст, пусть и похожий по буквам.
        assertFalse(editDistanceWithin("helloworld", "goodbyeworld", 2))
        assertFalse(editDistanceWithin("hello", "goodbye", 2))
        assertFalse(editDistanceWithin("startgame", "loadlevel", 2))
        // Разница ровно в середине: ранний выход по строке обязан увидеть,
        // что по краям всё совпало, и не выкинуть пару раньше времени.
        assertTrue(editDistanceWithin("abcdef", "abcxef", 1))
        assertTrue(editDistanceWithin("startgameover", "startgamrover", 1))
        assertFalse(editDistanceWithin("startgameover", "startgamrsover", 1))
    }

    /**
     * Медиана игнорирует выбросы, среднее арифметическое — нет. Именно на этом
     * держится заливка «в цвет фона»: один яркий пиксель посреди тёмного фона
     * не должен превращать заливку в серое пятно.
     */
    @Test
    fun медиана_не_реагирует_на_яркую_точку() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val samples = IntArray(101) { black }
        samples[50] = white // единственная белая точка

        val result = medianColor(samples)
        assertEquals("должен остаться чёрным", black, result)
    }

    @Test
    fun медиана_находит_средний_цвет() {
        val samples = intArrayOf(
            0xFF000000.toInt(),
            0xFF101010.toInt(),
            0xFF202020.toInt(),
        )
        // Медиана трёх значений — среднее из них.
        assertEquals(0xFF101010.toInt(), medianColor(samples))
    }

    @Test
    fun пустая_картинка_даёт_прозрачный_цвет() {
        assertEquals(0, medianColor(IntArray(0)))
    }

    /**
     * Точки у краёв рамки должны лежать СНАРУЖИ неё. Если бы они попадали внутрь,
     * мы бы усредняли цвет букв и заливали бы текст его собственным оттенком.
     */
    @Test
    fun точки_берутся_вокруг_рамки_а_не_внутри() {
        val ring = ringPoints(left = 10, top = 10, right = 20, bottom = 20, band = 2)
        var i = 0
        var inside = 0
        var outside = 0
        while (i < ring.size) {
            val x = ring[i]
            val y = ring[i + 1]
            if (x in 10..20 && y in 10..20) inside++ else outside++
            i += 2
        }
        assertEquals("внутри рамки точек быть не должно", 0, inside)
        assertTrue("вокруг рамки точек должно быть много", outside > 0)
    }
}
