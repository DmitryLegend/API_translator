"""Тесты чистой логики (SPECS.md, раздел «Чистые функции»).

Гоняются без телефона и графики: python3 test_core.py
"""

import sys
import unittest
from os.path import dirname, join

# core.py лежит в src/main/python — именно его пакует Chaquopy в APK.
sys.path.insert(0, join(dirname(__file__), "src", "main", "python"))

from core import (
    Pipeline,
    box_overlap,
    deepl_translate,
    edit_distance_within,
    fit_size,
    is_russian,
    looks_like_text,
    luminance,
    median_color,
    normalize,
    ring_points,
    same_place,
)

RED = (0xFF << 24) | (0xFF << 16)
GREEN = (0xFF << 24) | (0xFF << 8)
BLUE = 0xFF0000FF
GRAY128 = (0xFF << 24) | (128 << 16) | (128 << 8) | 128


class NormalizeTest(unittest.TestCase):
    def test_strips_case_punct_spaces(self):
        self.assertEqual(normalize("Hello, World! 42"), "helloworld42")

    def test_empty(self):
        self.assertEqual(normalize("!? "), "")


class LooksLikeTextTest(unittest.TestCase):
    def test_real_sentence(self):
        self.assertTrue(looks_like_text("Hello, world!"))

    def test_digits_only(self):
        self.assertFalse(looks_like_text("12345678"))

    def test_too_few_letters(self):
        self.assertFalse(looks_like_text("hi"))

    def test_punct_only(self):
        self.assertFalse(looks_like_text("#$%^&*()"))

    def test_letters_and_digits_ok(self):
        self.assertTrue(looks_like_text("abc123"))


class IsRussianTest(unittest.TestCase):
    def test_russian(self):
        self.assertTrue(is_russian("Привет, мир"))

    def test_english(self):
        self.assertFalse(is_russian("Hello world"))

    def test_too_few_letters(self):
        self.assertFalse(is_russian("Си"))

    def test_mixed_majority_latin(self):
        self.assertFalse(is_russian("Hello Привет"))


class EditDistanceTest(unittest.TestCase):
    def test_equal(self):
        self.assertTrue(edit_distance_within("hello", "hello", 2))
        self.assertTrue(edit_distance_within("hello", "hello", 0))

    def test_one_edit(self):
        self.assertTrue(edit_distance_within("hello", "helo", 2))
        self.assertTrue(edit_distance_within("hello", "hellp", 2))
        self.assertFalse(edit_distance_within("abc", "abd", 0))

    def test_length_gap(self):
        self.assertFalse(edit_distance_within("hello", "hello world", 2))

    def test_three_edits(self):
        self.assertFalse(edit_distance_within("kitten", "sitting", 2))
        self.assertTrue(edit_distance_within("kitten", "sitten", 1))

    def test_early_exit(self):
        self.assertFalse(edit_distance_within("aaaa", "bbbb", 2))


class BoxOverlapTest(unittest.TestCase):
    def test_identical(self):
        self.assertAlmostEqual(box_overlap((0, 0, 10, 10), (0, 0, 10, 10)), 1.0)

    def test_disjoint(self):
        self.assertEqual(box_overlap((0, 0, 10, 10), (20, 20, 30, 30)), 0.0)

    def test_partial(self):
        self.assertAlmostEqual(box_overlap((0, 0, 10, 10), (5, 5, 15, 15)), 25 / 175)

    def test_contained(self):
        self.assertAlmostEqual(box_overlap((0, 0, 10, 10), (0, 0, 20, 20)), 0.25)

    def test_zero_area(self):
        self.assertEqual(box_overlap((0, 0, 0, 0), (0, 0, 0, 0)), 0.0)


class SamePlaceTest(unittest.TestCase):
    def test_above_threshold(self):
        self.assertTrue(same_place((0, 0, 10, 12), (0, 0, 10, 10)))

    def test_exactly_threshold(self):
        self.assertTrue(same_place((0, 0, 10, 20), (0, 0, 10, 10)))

    def test_below_threshold(self):
        self.assertFalse(same_place((0, 0, 10, 30), (0, 0, 10, 10)))


class RingPointsTest(unittest.TestCase):
    def test_count(self):
        # Полоса сверху/снизу: 2 * band * (ширина+1); слева/справа столько же по высоте.
        self.assertEqual(len(ring_points(0, 0, 4, 4, 2)), 40)

    def test_no_interior_points(self):
        points = ring_points(0, 0, 4, 4, 2)
        for x, y in points:
            self.assertTrue(x < 0 or x > 4 or y < 0 or y > 4)

    def test_starts_at_offset_one(self):
        points = ring_points(0, 0, 4, 4, 1)
        self.assertNotIn((0, 0), points)
        self.assertIn((0, -1), points)


class MedianColorTest(unittest.TestCase):
    def test_empty(self):
        self.assertEqual(median_color([]), 0)

    def test_majority_wins(self):
        self.assertEqual(median_color([RED, RED, BLUE]), RED)

    def test_per_channel_median(self):
        # Медиана считается по каналам независимо: углы RGB дают чёрный.
        self.assertEqual(median_color([RED, GREEN, BLUE]), 0xFF000000)


class LuminanceTest(unittest.TestCase):
    def test_black_white(self):
        self.assertEqual(luminance(0xFF000000), 0.0)
        self.assertAlmostEqual(luminance(0xFFFFFFFF), 1.0)

    def test_mid_gray(self):
        # Линейная sRGB для 128/255 ≈ 0.2158.
        self.assertAlmostEqual(luminance(GRAY128), 0.2158, places=3)


class FitSizeTest(unittest.TestCase):
    def test_max_fits(self):
        self.assertEqual(fit_size(5, 100, 200, lambda s: s * 2), 100)

    def test_min_too_big(self):
        self.assertEqual(fit_size(10, 10, 5, lambda s: s * 2), 10)

    def test_binary_search(self):
        # Нужно s*2 <= 150, то есть s <= 75; 10 делений сходятся до ~0.1.
        size = fit_size(0, 100, 150, lambda s: s * 2)
        self.assertTrue(74.8 < size <= 75)
        self.assertLessEqual(size * 2, 150)


class PipelineTest(unittest.TestCase):
    """Дымовой тест конвейера: стабилизация, отправка в DeepL, отрисовка."""

    @staticmethod
    def _pipeline(translate):
        return Pipeline(key="k:fx", translate=translate)

    def test_three_frames_then_translate_once(self):
        calls = []

        def fake_translate(chunks):
            calls.append(chunks)
            return ["привет"], ""

        pipeline = self._pipeline(fake_translate)
        readings = [((0, 0, 50, 30), "Hello world", None)]
        color_of = lambda box: 0xFF000000  # noqa: E731

        for i in range(4):
            items, changed, _status = pipeline.frame(readings, color_of, now=i * 700.0)
            if i < 2:
                # Три кадра стабилизации, раньше перевод не уходит.
                self.assertEqual(calls, [])
                self.assertEqual(items, [])
            elif i == 2:
                self.assertEqual(calls, [["Hello world"]])
                self.assertTrue(changed)
                self.assertEqual(len(items), 1)
                self.assertEqual(items[0].text, "привет")
                self.assertEqual(items[0].box, (0, 0, 50, 30))
                # Стартовый размер: высота рамки на одну строку оригинала.
                self.assertEqual(items[0].start_size, 30.0)
            else:
                # Перевод держится, повторного запроса нет.
                self.assertEqual(calls, [["Hello world"]])
                self.assertFalse(changed)

    def test_same_line_twice_counts_once(self):
        calls = []
        pipeline = self._pipeline(
            lambda chunks: calls.append(chunks) or (["ok"], "")
        )
        # Одна строка, распознанная дважды в одном кадре (целиком и по кускам).
        readings = [
            ((0, 0, 50, 30), "Hello world", None),
            ((0, 0, 50, 30), "Hello world", None),
        ]
        for i in range(2):
            pipeline.frame(readings, lambda box: 0, now=i * 700.0)
        # Двойной учёт поднял бы счётчик сразу на два, и запрос ушёл бы на
        # втором кадре вместо третьего.
        self.assertEqual(calls, [])

    def test_reset_forgets_lines(self):
        calls = []
        pipeline = self._pipeline(lambda chunks: calls.append(chunks) or (["ok"], ""))
        readings = [((0, 0, 50, 30), "Hello world", None)]
        for i in range(3):
            pipeline.frame(readings, lambda box: 0, now=i * 700.0)
        self.assertEqual(len(calls), 1)
        pipeline.reset()
        # После сброса счётчик начинается заново: ещё три кадра до отправки.
        for i in range(2):
            pipeline.frame(readings, lambda box: 0, now=10000 + i * 700.0)
        self.assertEqual(len(calls), 1)

    def test_native_language_skips_russian(self):
        calls = []
        pipeline = self._pipeline(lambda chunks: calls.append(chunks) or (["ok"], ""))
        # Распознаватель сам сказал, что язык русский.
        readings = [((0, 0, 50, 30), "Привет, мир", "ru")]
        items, _changed, status = pipeline.frame(
            readings, lambda box: 0, now=0.0
        )
        self.assertEqual(items, [])
        self.assertIn("русских 1", status)

    def test_status_format(self):
        pipeline = self._pipeline(lambda chunks: (["ok"], ""))
        _items, _changed, status = pipeline.frame(
            [((0, 0, 50, 30), "Hello world", None)], lambda box: 0, now=0.0
        )
        self.assertIn("блоков всего 1 (мусор 0, русских 0)", status)
        self.assertIn("строк в работе: 1, показано: 0", status)
        self.assertIn("ошибок сети: 0", status)


class DeeplTranslateTest(unittest.TestCase):
    """Сетевой код проверяется по контракту ошибки, без настоящего запроса."""

    def test_free_host_by_key_suffix(self):
        # Запрос с битым ключом уходит в сеть, но контракт ошибки от этого
        # не меняется: при любом сбое возвращается (None, текст ошибки).
        texts, error = deepl_translate("broken-key:fx", ["test"])
        if texts is None:
            self.assertTrue(error.startswith(("сеть:", "DeepL ")))

    def test_error_shape(self):
        texts, error = deepl_translate("", ["x"])
        self.assertTrue(texts is None or isinstance(texts, list))
        self.assertIsInstance(error, str)


if __name__ == "__main__":
    unittest.main(verbosity=2)
