"""Чистая логика «Подстрочника»: без единой строчки Android.

Специально без зависимостей от платформы — поэтому весь этот файл покрывается
тестами (test_core.py) на обычном компьютере и в Termux, без телефона.
Поведение описано в SPECS.md; написано методом чистой комнаты.
"""

import json
import time
import urllib.error
import urllib.request
from dataclasses import dataclass

# --- Константы конвейера (SPECS.md, раздел «Константы») ----------------------

SCAN_MS = 700
STABLE_SCANS = 3
LINE_TTL_MS = 4000
MAX_LINES = 60
SAME_PLACE = 0.5
ALLOWED_PUNCT = " .,!?;:'\"()-%+\u2014\u2026"
MAX_BLOCK_CHARS = 400
MIN_BOX_PX = 26
BATCH = 40
SAMPLE_BAND_PX = 3
TARGET_LANG = "RU"
CONNECT_TIMEOUT_MS = 10_000
READ_TIMEOUT_MS = 15_000
MIN_TEXT_PX = 7.0
MAX_HEIGHT_GROWTH = 1.6
FIT_STEPS = 10


def normalize(text: str) -> str:
    """Ключ строки: без регистра, пробелов и знаков — только буквы и цифры.

    Так дрожь распознавания («Неllo» вместо «Hello») не порождает новую строку.
    """
    return "".join(c for c in text.lower() if c.isalnum())


def looks_like_text(text: str) -> bool:
    """Грубый фильтр мусора: букв несколько, разнородных знаков мало.

    Настоящий фильтр один — стабильность на STABLE_SCANS кадрах.
    """
    if sum(c.isalpha() for c in text) < 3:
        return False
    allowed = sum(c.isalnum() or c in ALLOWED_PUNCT for c in text)
    return allowed >= len(text) * 3 // 4


def is_russian(text: str) -> bool:
    """Строка считается русской, если буквы почти все кириллические.

    Замена на случай, когда распознаватель не называет язык сам: переводить
    русский на русский незачем, лимит не тратится.
    """
    letters = [c for c in text if c.isalpha()]
    if len(letters) < 3:
        return False
    cyr = sum("\u0400" <= c <= "\u04ff" for c in letters)
    return cyr / len(letters) > 0.7


def edit_distance_within(a: str, b: str, limit: int) -> bool:
    """Отличаются ли строки не более чем на limit правок (вставка/удаление/замена).

    Таблица хранится полосами — только две строки. Выход раньше срока, когда
    даже лучший элемент строки уехал за лимит: строка идёт слева направо,
    ниже уже не опуститься.
    """
    if a == b:
        return True
    if abs(len(a) - len(b)) > limit:
        return False
    previous = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        current = [i]
        best = i
        for j in range(1, len(b) + 1):
            cost = 0 if a[i - 1] == b[j - 1] else 1
            current.append(
                min(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            )
            best = min(best, current[j])
        if best > limit:
            return False
        previous = current
    return previous[-1] <= limit


def box_overlap(a: tuple, b: tuple) -> float:
    """Насколько две рамки (left, top, right, bottom) описывают одно место.

    Отношение общей площади к площади объединения: 0 — не касаются,
    1 — совпали до пикселя.
    """
    w = min(a[2], b[2]) - max(a[0], b[0])
    h = min(a[3], b[3]) - max(a[1], b[1])
    if w <= 0 or h <= 0:
        return 0.0
    shared = w * h
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - shared
    return shared / union if union > 0 else 0.0


def same_place(a: tuple, b: tuple) -> bool:
    """Тот же кусок экрана ли это: рамка могла уехать — это уже другое место."""
    return box_overlap(a, b) >= SAME_PLACE


def ring_points(left: int, top: int, right: int, bottom: int, band: int) -> list:
    """Точки полосой снаружи рамки, для взятия цвета фона.

    Снаружи, а не внутри: внутри рамки сами буквы, и медиана по ним ответила
    бы «текст тёмный», а не «фон такого-то цвета». Отступ начинается с 1:
    на самой границе рамки тоже буквы. Точки за кадром отбрасывает вызывающая
    сторона.
    """
    points = []
    for i in range(1, band + 1):
        for x in range(left, right + 1):
            points.append((x, top - i))
            points.append((x, bottom + i))
    for i in range(1, band + 1):
        for y in range(top, bottom + 1):
            points.append((left - i, y))
            points.append((right + i, y))
    return points


def median_color(pixels: list) -> int:
    """Цвет фона: медиана по каждому каналу, не среднее.

    Один яркий пиксель (блик, курсор) уводит среднее в свою сторону, медиану
    — не сдвигает вовсе. Пустой вход — чёрный.
    """
    if not pixels:
        return 0
    rs = sorted((p >> 16) & 0xFF for p in pixels)
    gs = sorted((p >> 8) & 0xFF for p in pixels)
    bs = sorted(p & 0xFF for p in pixels)
    m = len(pixels) // 2
    return (0xFF << 24) | (rs[m] << 16) | (gs[m] << 8) | bs[m]


def luminance(color: int) -> float:
    """Относительная яркость ARGB-цвета: линейная sRGB, 0 — чёрный, 1 — белый."""
    def channel(v: int) -> float:
        v /= 255.0
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4

    r = (color >> 16) & 0xFF
    g = (color >> 8) & 0xFF
    b = color & 0xFF
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)


def fit_size(min_size: float, max_size: float, max_height: float, height_of) -> float:
    """Наибольший размер из [min_size, max_size], при котором текст влезает.

    height_of обязан расти с размером — тогда можно делить диапазон пополам,
    а не перебирать. Если влезает даже минимум — вернётся минимум: текст
    обрежется снизу, но показать почти всё лучше, чем не показать ничего.
    """
    if max_size <= min_size:
        return min_size
    if height_of(max_size) <= max_height:
        return max_size
    lo, hi = min_size, max_size
    for _ in range(FIT_STEPS):
        mid = (lo + hi) / 2
        if height_of(mid) <= max_height:
            lo = mid
        else:
            hi = mid
    return lo


# --- Данные ------------------------------------------------------------------


@dataclass(eq=False)
class Line:
    """Строка, которая держится на экране. Сравнение — по принадлежности."""

    key: str
    src: str
    box: tuple
    translation: str | None = None
    seen: int = 0
    last_seen: float = 0.0


@dataclass
class Item:
    """Готовый к отрисовке перевод: всё посчитано заранее, вне главного потока."""

    box: tuple
    text: str
    color: int
    start_size: float


# --- Перевод -----------------------------------------------------------------


def deepl_translate(key: str, chunks: list):
    """Переводит пачку строк. Возвращает (список переводов, текст ошибки).

    Ошибки не пробрасываются: при обрыве сети лучше молча не показать
    перевод, чем уронить весь конвейер.
    """
    # У бесплатного ключа суффикс «:fx» — он ходит на другой адрес.
    host = "api-free" if key.endswith(":fx") else "api"
    body = json.dumps(
        {"text": chunks, "target_lang": TARGET_LANG, "split_sentences": "0"}
    ).encode("utf-8")
    request = urllib.request.Request(
        f"https://{host}.deepl.com/v2/translate",
        data=body,
        headers={
            "Content-Type": "application/json",
            # Заголовок у DeepL именно такой, не как у Bearer.
            "Authorization": f"DeepL-Auth-Key {key}",
        },
    )
    try:
        with urllib.request.urlopen(
            request, timeout=(CONNECT_TIMEOUT_MS + READ_TIMEOUT_MS) / 1000
        ) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            detail = e.read().decode("utf-8", "replace")[:120]
        except Exception:
            detail = ""
        finally:
            # Не закрытый ответ уезжает в GC с ResourceWarning (Python 3.14).
            e.close()
        return None, f"DeepL {e.code}: {detail}"
    except Exception as e:
        return None, f"сеть: {type(e).__name__}: {e}"
    return [t.get("text", "") for t in payload.get("translations", [])], ""


# --- Конвейер ----------------------------------------------------------------


class Pipeline:
    """Обработка одного кадра: фильтры, строки, перевод, элементы отрисовки.

    Захват, распознавание и цвет подставляются снаружи — этот класс не знает
    ничего о платформе и полностью покрыт тестами.
    """

    def __init__(self, key: str, translate=None) -> None:
        self.key = key
        self._translate = translate or (lambda chunks: deepl_translate(key, chunks))
        self.lines: list = []
        self.errors = 0
        self.last_error = ""
        self.shown: list = []

    def reset(self) -> None:
        """Экран сменил размер или поворот: всё найденное — в чужих координатах."""
        self.lines.clear()
        self.shown = []

    def frame(self, readings: list, color_of, now: float):
        """Один кадр. readings — [(рамка, текст, язык | None)].

        color_of(рамка) → цвет фона под ней; now — миллисекунды времени.
        Возвращает (элементы, изменилось_ли, статусная строка).
        """
        found = []
        garbage = 0
        russian = 0
        for box, src, lang in readings:
            src = src.strip()
            if not src or len(src) > MAX_BLOCK_CHARS:
                continue
            if box[2] - box[0] < MIN_BOX_PX or box[3] - box[1] < MIN_BOX_PX:
                continue
            # Язык называет распознаватель; если не назвал — эвристика.
            if (lang.upper().startswith(TARGET_LANG) if lang is not None
                    else is_russian(src)):
                russian += 1
                continue
            if not looks_like_text(src):
                garbage += 1
                continue

            key = normalize(src)
            line = self._find(key, box)
            if line is None:
                line = Line(key, src, box)
                self.lines.append(line)
            # Та же строка в кадре иногда приходит дважды: целиком и по кускам.
            # Второй раз не считается, иначе счётчик прыгает сразу на два.
            if line in found:
                continue
            line.seen += 1
            line.last_seen = now
            found.append(line)

        self.lines = [l for l in self.lines if now - l.last_seen <= LINE_TTL_MS]
        if len(self.lines) > MAX_LINES:
            del self.lines[: len(self.lines) - MAX_LINES]

        todo = [l for l in found if l.translation is None and l.seen == STABLE_SCANS]
        for chunk in (todo[i : i + BATCH] for i in range(0, len(todo), BATCH)):
            texts, error = self._translate([l.src for l in chunk])
            if texts is None:
                self.errors += 1
                self.last_error = error
                continue
            for line, text in zip(chunk, texts):
                if text:
                    line.translation = text

        items = []
        for line in found:
            if not line.translation:
                continue
            # Пересекающиеся рамки рисуем одну, большую: обе заливки легли бы
            # друг на друга, и верхняя затёрла бы нижний текст.
            if any(same_place(i.box, line.box) for i in items):
                continue
            rows = line.src.count("\n") + 1
            items.append(
                Item(
                    box=line.box,
                    text=line.translation,
                    color=color_of(line.box),
                    start_size=(line.box[3] - line.box[1]) / rows,
                )
            )

        changed = items != self.shown
        self.shown = items
        status = (
            f"блоков всего {len(readings)} (мусор {garbage}, русских {russian})\n"
            f"строк в работе: {len(found)}, показано: {len(items)}\n"
            f"ошибок сети: {self.errors}"
            + ("" if not self.last_error else f"\n{self.last_error}")
        )
        return items, changed, status

    def _find(self, key: str, box: tuple):
        if not key:
            return None
        for line in self.lines:
            if line.key == key and same_place(line.box, box):
                return line
        for line in self.lines:
            if same_place(line.box, box) and edit_distance_within(key, line.key, 2):
                return line
        return None
