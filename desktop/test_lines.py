#!/usr/bin/env python3
"""Проверка логики строк. Запуск: python3 desktop/test_lines.py

Импортируется podstrochnik целиком, поэтому нужен Python с PySide6 и mss.
На CI это выполняется на линуксовом раннере, где зависимости уже стоят.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from podstrochnik import (  # noqa: E402
    ALLOWED_PUNCT,
    box_overlap,
    edit_distance_within,
    is_russian,
    looks_like_text,
    normalize,
    same_place,
)

checks = 0


def check(ok: bool, what: str) -> None:
    global checks
    assert ok, what
    checks += 1


# Ключ убирает пробелы, знаки и регистр, но не буквы: «Hello, world!» и
# «hello  world» это одна и та же строка, а мусор между ними — нет.
check(normalize("Hello, world!") == "helloworld", "пунктуация и регистр")
check(normalize("hello  world") == "helloworld", "пробелы и регистр")
check(normalize("") == "", "пустая строка")

# Дрожь распознавания не должна ронять строку и не должна плодить запросы.
check(edit_distance_within("helloworld", "helloworle", 2), "одна опечатка")
check(edit_distance_within("helloworld", "helloworlds", 2), "лишняя буква")
check(not edit_distance_within("helloworld", "hello", 2), "разные слова")

# Совпадение рамок: дребезг в пару пикселей — то же место, соседние строки —
# разные, и касание углами не считается пересечением.
check(box_overlap((0, 0, 20, 20), (0, 0, 20, 20)) == 1.0, "рамка с собой")
check(box_overlap((0, 0, 10, 10), (10, 10, 20, 20)) == 0.0, "касание углом")
check(abs(box_overlap((0, 0, 20, 20), (10, 0, 30, 20)) - 200 / 600) < 1e-9, "треть")

# Главная проверка сегодняшнего дня: тот же текст в другом месте экрана —
# это другая строка, иначе перевод прошлой фразы висит поверх новой.
check(same_place((100, 1200, 1400, 1240), (102, 1198, 1398, 1242)), "дребезг")
check(not same_place((100, 1200, 1400, 1240), (100, 600, 1400, 640)), "другое место")
check(not same_place((100, 100, 900, 140), (100, 150, 900, 190)), "соседние строки")

# Мусор от сжатия: три четверти знаков должны быть буквами, цифрами или
# обычной пунктуацией.
check(looks_like_text("Hello there, friend"), "настоящая строка")
check(not looks_like_text("ab"), "две буквы мало")
junk = "8Зр|Зо=~^"
check(
    not looks_like_text(junk),
    f"мусор прошёл: нужно {len(junk) * 3 // 4}, "
    f"есть {sum(c.isalnum() or c in ALLOWED_PUNCT for c in junk)}",
)

# Русский переводить нечего.
check(is_russian("Привет, как дела"), "русский")
check(not is_russian("Hello there, friend"), "английский")

print(f"в порядке: {checks} проверок")
