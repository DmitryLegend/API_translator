"""Оверлей: прозрачное окно ImageView поверх всего экрана.

Рисование делается в Bitmap на рабочем потоке; главный поток только
подменяет bitmap через setImageBitmap. Собственный подкласс View не
используется: у статического прокси Chaquopy конструктор с аргументами
недоступен, а View требует View(Context), — зато ImageView штатный.

Прозрачность обманчива: фон окна пустой, рисуются только маленькие
прямоугольники там, где был текст. Остальная картинка проходит насквозь.
"""

# noinspection PyUnresolvedReferences
# Модули java/android существуют только внутри APK (Chaquopy),
# поэтому IDE не может их разрезолвить — это не ошибка.
from java import dynamic_proxy
from java.lang import Runnable
from android.content import Context
from android.graphics import Bitmap, Canvas, Color, Paint, PixelFormat
from android.os import Handler, Looper
from android.text import Layout, StaticLayout, TextPaint
from android.util import Log
from android.view import WindowManager
from android.widget import ImageView

from core import MAX_HEIGHT_GROWTH, MIN_TEXT_PX, fit_size, luminance

TAG = "Translator"


class _SetImage(dynamic_proxy(Runnable)):
    def __init__(self, view, bitmap):
        super().__init__()
        self._view = view
        self._bitmap = bitmap

    def run(self):
        self._view.setImageBitmap(self._bitmap)


class Overlay:
    """Владелец окна, bitmap и рисования.

    Всё, кроме show/close, вызывается с рабочего потока; трогать окно
    может только главный поток.
    """

    def __init__(self, service) -> None:
        self._service = service
        self._handler = Handler(Looper.getMainLooper())
        self._view = None
        self._bitmap = None
        self._canvas = None
        # Закрашивает оригинал. Цвет берётся из элемента перед отрисовкой.
        self._fill_paint = Paint()
        self._fill_paint.setStyle(Paint.Style.FILL)
        # Шрифт перевода, полужирный: белый жирный текст читается поверх
        # видео увереннее тонкого, а фон известен только из медианы.
        self._text_paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        self._text_paint.setFakeBoldText(True)

    def show(self) -> None:
        # NOT_TOUCHABLE обязателен: без него окно перехватывало бы касания,
        # и управление в игре перестало бы работать. NOT_FOCUSABLE — чтобы
        # не забирать клавиатуру у приложения под нами.
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        self._view = ImageView(self._service)
        self._service.getSystemService(Context.WINDOW_SERVICE).addView(
            self._view, params
        )

    def resize(self, width: int, height: int) -> None:
        """Новый размер экрана: пересоздаём bitmap, старое содержимое — прочь."""
        self._bitmap = Bitmap.createBitmap(
            width, height, Bitmap.Config.ARGB_8888
        )
        self._canvas = Canvas(self._bitmap)
        if self._view is not None:
            self._handler.post(_SetImage(self._view, self._bitmap))

    def draw(self, items: list) -> None:
        """Рисует все элементы и показывает результат. Рабочий поток."""
        self._bitmap.eraseColor(0)
        for item in items:
            left, top, right, bottom = item.box
            width = right - left
            if width <= 0:
                continue
            self._fill_paint.setColor(item.color)
            self._canvas.drawRect(left, top, right, bottom, self._fill_paint)

            size = fit_size(
                MIN_TEXT_PX,
                item.start_size,
                (bottom - top) * MAX_HEIGHT_GROWTH,
                lambda s: self._layout(item.text, width, s).getHeight(),
            )
            layout = self._layout(item.text, width, size)
            # Светлый текст на тёмном читается лучше, и наоборот: порог
            # грубый, фон мы закрашиваем сами, чужой дизайн не угадываем.
            self._text_paint.setColor(
                Color.WHITE if luminance(item.color) < 0.45 else Color.BLACK
            )
            dy = ((bottom - top) - layout.getHeight()) / 2.0
            self._canvas.save()
            self._canvas.translate(left, top + dy)
            layout.draw(self._canvas)
            self._canvas.restore()
        self._handler.post(_SetImage(self._view, self._bitmap))

    def _layout(self, text: str, width: int, size: float):
        self._text_paint.setTextSize(size)
        return (
            StaticLayout.Builder.obtain(text, 0, len(text), self._text_paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            # Служебные отступы сверху и снизу добавляют лишние пиксели,
            # и подгонка по высоте едет.
            .setIncludePad(False)
            # Интерлиньяж чуть плотнее: текст занимает меньше места.
            .setLineSpacing(0.0, 0.92)
            .build()
        )

    def close(self) -> None:
        if self._view is None:
            return
        try:
            self._service.getSystemService(Context.WINDOW_SERVICE).removeView(
                self._view
            )
        except BaseException as e:
            Log.w(TAG, "Оверлей не снялся: %s" % e)
        self._view = None
