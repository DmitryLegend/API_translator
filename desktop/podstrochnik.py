#!/usr/bin/env python3
"""Подстрочник — перевод того, что сейчас на экране компьютера.

Отдельное приложение, а не порт мобильного: там захват экрана делает
Android, здесь — mss, и он берёт весь экран целиком, как и просили.
Дальше ровно та же цепочка: распознавание, отсев мусора, стабилизация,
DeepL, оверлей поверх.

Один файл, потому что одна программа. Настройки — ~/.config/podstrochnik.json.

Сборка: .github/workflows/desktop.yml
"""

import json
import os
import queue
import sys
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

import mss
from PySide6.QtCore import Qt, QTimer, Signal, QObject
from PySide6.QtGui import QColor, QFont, QPainter, QPen
from PySide6.QtWidgets import (
    QApplication,
    QInputDialog,
    QLabel,
    QMessageBox,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

# --- Настройки конвейера -----------------------------------------------------
# Значения те же, что в мобильном приложении, и по той же причине: мусор от
# сжатия видео держится на экране ровно столько, сколько длится кадр, и
# проходит проверку дважды, но не трижды. Меньше — начинаются выдуманные
# строки, больше — ощутимая задержка.

SCAN_MS = 700           # пауза между кадрами
STABLE_SCANS = 3        # столько кадров подряд нужно, чтобы отправить в DeepL
LINE_TTL_MS = 4_000     # не видели столько — строка ушла с экрана
MAX_LINES = 60          # страховка от утечки
SAME_PLACE = 0.5        # порог перекрытия рамок, см. same_place()
ALLOWED_PUNCT = " .,!?;:'\"()-%+—…"
MIN_BOX_PX = 26
TARGET_LANG = "RU"
DEEPL_URL = "https://api-free.deepl.com/v2/translate"
HOTKEY = "F8"

CONFIG = Path(
    os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")
) / "podstrochnik.json"


# --- Перенесённая из мобильного приложения математика ------------------------
# Логика строк уже отлажена и проверена тестами в
# app/src/test/.../GeomTest.kt. Копия нужна потому, что проект на Python и
# общих модулей у него нет; расхождение с мобильной версией придётся держать
# в голове.


def normalize(s: str) -> str:
    """Ключ для сравнения строк: без пробелов, знаков и регистра."""
    return "".join(c for c in s.lower() if c.isalnum())


def edit_distance_within(a: str, b: str, limit: int) -> bool:
    """Отличаются ли строки не более чем на limit правок."""
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
    return previous[len(b)] <= limit


def box_overlap(a, b) -> float:
    """Насколько две рамки (left, top, right, bottom) описывают одно место."""
    w = min(a[2], b[2]) - max(a[0], b[0])
    h = min(a[3], b[3]) - max(a[1], b[1])
    if w <= 0 or h <= 0:
        return 0.0
    shared = w * h
    area_a = (a[2] - a[0]) * (a[3] - a[1])
    area_b = (b[2] - b[0]) * (b[3] - b[1])
    union = area_a + area_b - shared
    return shared / union if union > 0 else 0.0


def same_place(a, b) -> bool:
    """Тот же кусок экрана ли это.

    Главная правка сегодняшнего дня по итогам жалобы «перевод прошлого текста
    остаётся и накладывается». Рамка у строки запоминается один раз, значит
    строка, найденная по похожему тексту в другом месте экрана, забрала бы
    чужую рамку и залила бы старым переводом новое место.
    """
    return box_overlap(a, b) >= SAME_PLACE


def looks_like_text(s: str) -> bool:
    """Похоже ли это вообще на текст.

    Требования намеренно грубые: букв несколько, и идут подряд, без
    разнородного мусора. «8Зр|Зо», распознанное из лица, этому не отвечает.

    ponytail: грубая эвристика. Настоящий фильтр один — стабильность на
    STABLE_SCANS кадрах.
    """
    if sum(c.isalpha() for c in s) < 3:
        return False
    allowed = sum(c.isalnum() or c in ALLOWED_PUNCT for c in s)
    return allowed >= len(s) * 3 // 4


# --- Настройки ---------------------------------------------------------------


@dataclass
class Config:
    deepl_key: str = ""
    # Зеркало меняется одной строкой в конфиге, если DeepL недоступен.
    # Pro-ключ ходит на api.deepl.com и в заголовке, а не в теле.
    deepl_url: str = DEEPL_URL
    use_auth_header: bool = False

    @property
    def path(self) -> Path:
        return CONFIG

    def load(self) -> "Config":
        try:
            raw = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return self
        self.deepl_key = raw.get("deepl_key", self.deepl_key)
        self.deepl_url = raw.get("deepl_url", self.deepl_url)
        self.use_auth_header = bool(raw.get("use_auth_header", False))
        return self

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(
            json.dumps(
                {
                    "deepl_key": self.deepl_key,
                    "deepl_url": self.deepl_url,
                    "use_auth_header": self.use_auth_header,
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )


# --- DeepL -------------------------------------------------------------------


@dataclass
class Reply:
    text: str
    ok: bool
    detail: str = ""


def deep_l(cfg: Config, chunks: list[str]) -> Reply:
    """Переводит список строк одним запросом.

    Состояние по нему держится честно, а не в сообщении, потому что с телефона
    логи не достать, а «ничего не переводится» одинаково выглядит и при сети,
    и при пустом распознавании.
    """
    body = json.dumps(
        {"target_lang": TARGET_LANG, "text": chunks}
    ).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if cfg.use_auth_header:
        headers["Authorization"] = f"DeepL-Auth-Key {cfg.deepl_key}"
    else:
        body = json.dumps(
            {"auth_key": cfg.deepl_key, "target_lang": TARGET_LANG, "text": chunks}
        ).encode("utf-8")

    req = urllib.request.Request(cfg.deepl_url, data=body, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:300]
        return Reply("", False, f"DeepL вернул {e.code}: {detail}")
    except (urllib.error.URLError, TimeoutError, ValueError) as e:
        return Reply("", False, f"сеть не ответила: {e}")

    out = [t.get("text", "") for t in data.get("translations", [])]
    if len(out) != len(chunks):
        return Reply("", False, f"ответ на {len(chunks)} строк, а пришло {len(out)}")
    return Reply("\n".join(out), True)


# --- Распознавание -----------------------------------------------------------


class Recognizer:
    """Обёртка над RapidOCR.

    Модель качается при первом запуске и дальше лежит на диске, интернет
    нужен только один раз.
    """

    def __init__(self) -> None:
        from rapidocr_onnxruntime import RapidOCR

        self.ocr = RapidOCR()

    def read(self, image) -> list[tuple[tuple[int, int, int, int], str, float]]:
        """Возвращает (рамка, текст, уверенность)."""
        out = []
        for box, text, score in self.ocr(image) or []:
            if score < 0.5:
                continue
            xs = [int(p[0]) for p in box]
            ys = [int(p[1]) for p in box]
            out.append(((min(xs), min(ys), max(xs), max(ys)), text, score))
        return out


# --- Конвейер ----------------------------------------------------------------


@dataclass
class Line:
    key: str
    src: str
    box: tuple
    translation: str | None = None
    seen: int = 0
    last_seen: float = 0.0


@dataclass
class Item:
    box: tuple
    text: str
    size: float
    seen: int = field(default=0)


class Pipeline:
    """Захват, распознавание, стабилизация, перевод — по одному потоку.

    Ровно как в мобильном приложении: пока заняты, следующий кадр не берётся.
    Никакого пула потоков и синхронизации.
    """

    def __init__(self, cfg: Config, out: "queue.Queue[tuple[str, object]]") -> None:
        self.cfg = cfg
        self.out = out
        self.lines: list[Line] = []
        self.running = False
        self.thread: threading.Thread | None = None
        self.recognizer: Recognizer | None = None
        self.mss = None
        self.errors = 0

    def start(self) -> None:
        if self.running:
            return
        self.running = True
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.thread.start()

    def stop(self) -> None:
        self.running = False

    def _loop(self) -> None:
        self.out.put(("status", "захват экрана, первый запуск может занять время"))
        try:
            self.recognizer = Recognizer()
        except Exception as e:  # noqa: BLE001 — на экране это просто текст
            self.out.put(("status", f"распознавание не запустилось: {e}"))
            return
        try:
            self.mss = mss.mss()
        except Exception as e:  # noqa: BLE001
            self.out.put(("status", f"захват экрана недоступен: {e}"))
            return

        while self.running:
            started = time.monotonic()
            try:
                self._frame()
            except Exception as e:  # noqa: BLE001
                self.errors += 1
                self.out.put(("status", f"сбой в конвейере: {e}"))
            delay = SCAN_MS / 1000 - (time.monotonic() - started)
            if delay > 0:
                time.sleep(delay)

    def _frame(self) -> None:
        now = time.monotonic() * 1000
        mon = self.mss.monitors[1]
        shot = np_array(self.mss.grab(mon))
        readings = self.recognizer.read(shot)

        found: list[Line] = []
        garbage = 0
        for box, src, _score in readings:
            src = src.strip()
            if not src or len(src) > 400:
                continue
            if box[2] - box[0] < MIN_BOX_PX or box[3] - box[1] < MIN_BOX_PX:
                continue
            if not looks_like_text(src):
                garbage += 1
                continue
            # Уже на русском переводить нечего. Язык определяем по
            # алфавиту: RapidOCR, в отличие от ML Kit, язык не отдаёт.
            if is_russian(src):
                continue

            key = normalize(src)
            line = self._find(key, box)
            if line is None:
                line = Line(key, src, box)
                self.lines.append(line)
            if line in found:
                continue
            line.seen += 1
            line.last_seen = now
            found.append(line)

        self.lines = [l for l in self.lines if now - l.last_seen <= LINE_TTL_MS]
        if len(self.lines) > MAX_LINES:
            self.lines = self.lines[-MAX_LINES:]

        todo = [l for l in found if l.translation is None and l.seen == STABLE_SCANS]
        if todo:
            # Склеиваем в один запрос: строк на экране мало, а лимит DeepL
            # считается по запросам.
            reply = deep_l(self.cfg, [l.src for l in todo])
            if reply.ok:
                parts = reply.text.split("\n")
                if len(parts) == len(todo):
                    for line, text in zip(todo, parts):
                        line.translation = text
                else:
                    self.out.put(
                        ("status", f"DeepL вернул {len(parts)} строк на {len(todo)}")
                    )
            else:
                self.errors += 1
                self.out.put(("status", reply.detail))

        items = []
        for line in found:
            if not line.translation:
                continue
            if any(same_place(i.box, line.box) for i in items):
                continue
            rows = line.src.count("\n") + 1
            items.append(
                Item(
                    box=line.box,
                    text=line.translation,
                    size=(line.box[3] - line.box[1]) / rows,
                )
            )

        self.out.put(
            (
                "items",
                (
                    items,
                    f"найдено={len(found)} показано={len(items)} "
                    f"в очереди={len(todo)} мусор={garbage} ошибок сети={self.errors}",
                ),
            )
        )

    def _find(self, key: str, box: tuple) -> Line | None:
        for line in self.lines:
            if not same_place(line.box, box):
                continue
            if line.key == key or edit_distance_within(key, line.key, 2):
                return line
        return None


def is_russian(s: str) -> bool:
    """Похоже ли, что строка уже на русском.

    Считаем кириллицу: если букв больше половины и почти все они кириллические,
    переводить нечего.
    """
    letters = [c for c in s if c.isalpha()]
    if len(letters) < 3:
        return False
    cyr = sum("Ѐ" <= c <= "ӿ" for c in letters)
    return cyr / len(letters) > 0.7


def np_array(shot):
    """mss отдаёт наш собственный буфер, numpy нужен чтобы распознаватель его съел."""
    import numpy as np

    return np.frombuffer(shot.raw, dtype=np.uint8).reshape(shot.height, shot.width, 4)


# --- Оверлей -----------------------------------------------------------------


class Overlay(QWidget):
    """Прозрачное окно поверх всего, что переводим.

    Идёт по всему экрану, а не по окну видео: так и просили, и тогда не надо
    угадывать, в каком окне идёт фильм.
    """

    def __init__(self) -> None:
        super().__init__(None)
        self.items: list[Item] = []
        self.setWindowFlags(
            Qt.WindowType.Tool
            | Qt.WindowType.FramelessWindowHint
            | Qt.WindowType.WindowStaysOnTopHint
            | Qt.WindowType.WindowTransparentForInput
        )
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents)
        self.setFocusPolicy(Qt.FocusPolicy.NoFocus)

    def show_full(self) -> None:
        screen = QApplication.primaryScreen()
        self.setGeometry(screen.geometry())
        self.raise_()
        self.show()

    def set_items(self, items: list[Item]) -> None:
        self.items = items
        self.update()

    def paintEvent(self, _event) -> None:  # noqa: N802 — так зовётся метод Qt
        p = QPainter(self)
        for item in self.items:
            left, top, right, bottom = item.box
            p.fillRect(left, top, right - left, bottom - top, QColor(0, 0, 0, 170))
            font = QFont()
            font.setPixelSize(max(12, int(item.size)))
            p.setFont(font)
            p.setPen(QPen(QColor("white")))
            p.drawText(
                left + 2,
                top + 2,
                right - left - 4,
                bottom - top - 4,
                int(Qt.AlignmentFlag.AlignLeft) | int(Qt.AlignmentFlag.AlignVCenter),
                item.text,
            )


# --- Окно управления ---------------------------------------------------------


class Bridge(QObject):
    """Передача из рабочего потока в графический.

    Qt трогать из чужого потока нельзя, а из конвейера напрямую не выйдет.
    """

    items = Signal(object, str)
    status = Signal(str)


class Panel(QWidget):
    def __init__(self, cfg: Config) -> None:
        super().__init__()
        self.cfg = cfg
        self.setWindowTitle("Подстрочник")

        self.status = QLabel("не запущено")
        self.status.setWordWrap(True)
        self.toggle = QPushButton("Запустить")
        self.settings = QPushButton("Ключ DeepL")
        self.quit = QPushButton("Выход")

        layout = QVBoxLayout(self)
        layout.addWidget(QLabel("F8 — запуск и остановка"))
        layout.addWidget(self.status)
        layout.addWidget(self.toggle)
        layout.addWidget(self.settings)
        layout.addWidget(self.quit)

        self.bridge = Bridge()
        self.bridge.items.connect(self.on_items)
        self.bridge.status.connect(self.on_status)

        self.queue: queue.Queue = queue.Queue()
        self.overlay = Overlay()
        self.pipeline = Pipeline(cfg, self.queue)
        self.timer = QTimer(self)
        self.timer.timeout.connect(self.drain)
        self.timer.start(80)

        self.toggle.clicked.connect(self.start_stop)
        self.settings.clicked.connect(self.edit_key)
        self.quit.clicked.connect(self.shutdown)
        QTimer.singleShot(0, self.start_shortcut)

    def start_shortcut(self) -> None:
        from PySide6.QtGui import QKeySequence, QShortcut

        QShortcut(QKeySequence(HOTKEY), self, activated=self.start_stop)

    def drain(self) -> None:
        """Разбирает очередь рабочего потока. На таймере, раз в 80 мс."""
        while True:
            try:
                kind, payload = self.queue.get_nowait()
            except queue.Empty:
                return
            if kind == "items":
                items, text = payload
                self.bridge.items.emit(items, text)
            else:
                self.bridge.status.emit(str(payload))

    def on_items(self, items: list[Item], text: str) -> None:
        self.overlay.set_items(items)
        self.status.setText(text)

    def on_status(self, text: str) -> None:
        self.status.setText(text)

    def ask_key(self) -> bool:
        text, ok = QInputDialog.getText(
            self,
            "Ключ DeepL",
            "Вставьте ключ DeepL. Он сохранится в\n"
            + str(self.cfg.path)
            + "\n\nБез ключа перевода не будет.",
        )
        if ok and text.strip():
            self.cfg.deepl_key = text.strip()
            self.cfg.save()
            return True
        return False

    def edit_key(self) -> None:
        if self.ask_key():
            self.status.setText("ключ сохранён")

    def start_stop(self) -> None:
        if self.pipeline.running:
            self.pipeline.stop()
            self.toggle.setText("Запустить")
            self.overlay.set_items([])
            return
        if not self.cfg.deepl_key and not self.ask_key():
            return
        self.toggle.setText("Стоп")
        self.overlay.show_full()
        self.pipeline.start()

    def shutdown(self) -> None:
        self.pipeline.stop()
        QApplication.quit()

    def closeEvent(self, event) -> None:  # noqa: N802
        self.shutdown()
        event.accept()


def main() -> int:
    cfg = Config().load()
    app = QApplication(sys.argv)
    panel = Panel(cfg)
    panel.show()
    if not cfg.deepl_key:
        panel.status.setText("нужен ключ DeepL — нажмите «Ключ DeepL»")
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
