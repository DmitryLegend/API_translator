"""Мост Python→Java для колбэков: динамические прокси интерфейсов.

Модуль намеренно не входит в staticProxy: генератор статических прокси
сканирует только main и service, а динамические прокси — среда исполнения.
"""

from java import dynamic_proxy
from java.lang import Runnable
from android.view import View


class UiTask(dynamic_proxy(Runnable)):
    """Любое действие, которое надо исполнить на главном потоке."""

    def __init__(self, action):
        super().__init__()
        self._action = action

    def run(self):
        self._action()


class ClickHandler(dynamic_proxy(View.OnClickListener)):
    """Клик по кнопке: действие вместо целого класса слушателя."""

    def __init__(self, action):
        super().__init__()
        self._action = action

    def onClick(self, view):
        self._action()
