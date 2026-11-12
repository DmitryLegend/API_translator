"""Служба перевода: захват экрана, распознавание, конвейер, показ.

Всё тяжёлое — на рабочем потоке; служба лишь держит процесс живым.
Порядок запуска важен: сначала startForeground (иначе Android 14+
убивает процесс за захват без уведомления), потом getMediaProjection.

Разбор кадра (ImageReader → Bitmap) написан вручную, потому что у
Chaquopy нет обёрток над JNI-классами оригинала.
"""

import threading
import time
import traceback

from java import jint, jvoid, jarray, static_proxy, Override
from java.nio import ByteBuffer
from android import R
from android.app import Notification, NotificationChannel, NotificationManager, Service
from android.content import Context, Intent
from android.content.pm import ServiceInfo
from android.graphics import Bitmap, PixelFormat, Point
from android.hardware.display import DisplayManager
from android.media import ImageReader
from android.media.projection import MediaProjection, MediaProjectionManager
from android.os import Build, Handler, IBinder, Looper, SystemClock
from android.util import Log
from android.view import Display, Surface
from com.google.android.gms.tasks import Tasks
from com.google.mlkit.vision.common import InputImage
from com.google.mlkit.vision.text import TextRecognition
from com.google.mlkit.vision.text.latin import TextRecognizerOptions

from core import Pipeline, SCAN_MS, SAMPLE_BAND_PX, median_color, ring_points
from keys import DEEPL_KEY
from overlay import Overlay

TAG = "Translator"
NOTIFICATION_ID = 1
CHANNEL_ID = "translator"
VIRTUAL_DISPLAY = "translator"
DPI = 100
PREFS = "last"

STATE = {"running": False, "debug": "перевод ещё не запускался"}

SERVICE_CLASS = "com.dmitrylegend.apitranslator.TranslateService"
EXTRA_RESULT_CODE = "resultCode"
EXTRA_RESULT_DATA = "resultData"


def _first_language(block):
    """Язык первого элемента строки; назвать его распознаватель не обязан."""
    try:
        return (
            block.getLines().get(0).getElements().get(0).getRecognizedLanguage()
        )
    except BaseException:
        return None


class StopCallback(static_proxy(MediaProjection.Callback)):
    """Пользователь нажал «остановить трансляцию» — гасим службу."""

    def __init__(self, service):
        super().__init__()
        self._service = service

    @Override(jvoid, [])
    def onStop(self):
        Log.i(TAG, "Пользователь прекратил захват экрана")
        self._service.stopSelf()


class TranslateService(
    static_proxy(Service, package="com.dmitrylegend.apitranslator")
):

    _projection = None
    _reader = None
    _virtual = None
    _overlay = None
    _recognizer = None
    _pipeline = None
    _main = None
    _running = False
    _cap_w = 0
    _cap_h = 0
    _cap_deg = 0

    @Override(IBinder, [Intent])
    def onBind(self, intent):
        return None

    @Override(jint, [Intent, jint, jint])
    def onStartCommand(self, intent, flags, start_id):
        if intent is None:
            self.stopSelf()
            return Service.START_NOT_STICKY
        if self._running:
            return Service.START_NOT_STICKY
        try:
            self._start_capture(intent)
        except BaseException as e:
            Log.w(TAG, "Запуск не удался: %s" % e)
            self._save_error()
            self.stopSelf()
        return Service.START_NOT_STICKY

    @Override(jvoid, [])
    def onDestroy(self):
        Service.onDestroy(self)
        self._running = False
        STATE["running"] = False
        STATE["debug"] = "перевод остановлен"
        if self._virtual is not None:
            self._virtual.release()
            self._virtual = None
        if self._reader is not None:
            self._reader.close()
            self._reader = None
        if self._projection is not None:
            self._projection.stop()
            self._projection = None
        if self._recognizer is not None:
            self._recognizer.close()
            self._recognizer = None
        if self._overlay is not None:
            self._overlay.close()
            self._overlay = None

    def _start_capture(self, intent):
        self._prefs().edit().remove("error").apply()
        result_code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        result_data = self._read_result_data(intent)
        if result_data is None:
            Log.e(TAG, "Экран не захвачен: от Activity не пришёл resultData")
            self.stopSelf()
            return
        self._main = Handler(Looper.getMainLooper())
        self._go_foreground(self._notification())
        manager = self.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
        projection = manager.getMediaProjection(result_code, result_data)
        if projection is None:
            Log.e(TAG, "getMediaProjection вернул null")
            self.stopSelf()
            return
        self._projection = projection
        projection.registerCallback(StopCallback(self), self._main)
        self._recognizer = TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )
        self._overlay = Overlay(self)
        self._overlay.show()
        if not self._ensure_capture():
            Log.e(TAG, "Захват экрана не создаётся")
            self.stopSelf()
            return
        self._pipeline = Pipeline(DEEPL_KEY)
        self._running = True
        STATE["running"] = True
        STATE["debug"] = "перевод запущен"
        threading.Thread(target=self._loop, daemon=True).start()

    def _go_foreground(self, notification):
        if Build.VERSION.SDK_INT >= 29:
            self.startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        else:
            self.startForeground(NOTIFICATION_ID, notification)

    def _read_result_data(self, intent):
        if Build.VERSION.SDK_INT >= 33:
            return intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent)
        return intent.getParcelableExtra(EXTRA_RESULT_DATA)

    def _loop(self):
        while self._running:
            try:
                self._frame()
            except BaseException as e:
                STATE["debug"] = "сбой в конвейере: %s: %s" % (type(e).__name__, e)
            time.sleep(SCAN_MS / 1000.0)

    def _frame(self):
        if not self._ensure_capture():
            STATE["debug"] = "захват экрана не создаётся"
            return
        image = None
        try:
            image = self._reader.acquireLatestImage()
        except BaseException as e:
            STATE["debug"] = "ошибка кадра: %s: %s" % (type(e).__name__, e)
            return
        if image is None:
            STATE["debug"] = "кадров нет: картинка ещё не пришла"
            return
        try:
            self._process(image)
        finally:
            image.close()

    def _process(self, image):
        deg = ((self._rotation_degrees() - self._cap_deg) % 360 + 360) % 360
        bitmap = None
        try:
            bitmap = self._to_bitmap(image)
        except BaseException as e:
            STATE["debug"] = "кадр в Bitmap не получился: %s: %s" % (
                type(e).__name__,
                e,
            )
            return
        try:
            self._process_frame(bitmap, deg)
        finally:
            bitmap.recycle()

    def _to_bitmap(self, image):
        """RGBA-кадр в Bitmap: чтение с учётом rowStride, запись одним куском."""
        plane = image.getPlanes()[0]
        buffer = plane.getBuffer()
        row_stride = plane.getRowStride()
        pixel_stride = plane.getPixelStride()
        w = image.getWidth()
        h = image.getHeight()
        needed = (h - 1) * row_stride + w * pixel_stride
        raw = jarray(jbyte)(needed)
        buffer.get(raw)
        data = bytes(raw)
        # Строки кадра идут с хвостом-набивкой до rowStride: выбрасываем его.
        packed = b"".join(
            data[y * row_stride : y * row_stride + w * pixel_stride]
            for y in range(h)
        )
        frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buf = ByteBuffer.allocateDirect(w * h * 4)
        buf.put(packed)
        buf.rewind()
        frame.copyPixelsFromBuffer(buf)
        return frame

    def _process_frame(self, bitmap, deg):
        now = SystemClock.elapsedRealtime()
        try:
            result = Tasks.await_(
                self._recognizer.process(InputImage.fromBitmap(bitmap, deg))
            )
        except BaseException as e:
            STATE["debug"] = "распознавание не удалось: %s: %s" % (
                type(e).__name__,
                e,
            )
            return
        readings = []
        for block in result.getTextBlocks():
            box = block.getBoundingBox()
            if box is None:
                continue
            src = block.getText().strip()
            readings.append(
                ((box.left, box.top, box.right, box.bottom), src, _first_language(block))
            )
        items, changed, status = self._pipeline.frame(
            readings, lambda b: self._sample_color(bitmap, b), now
        )
        STATE["debug"] = status
        if changed:
            self._overlay.draw(items)

    def _sample_color(self, bitmap, box):
        left, top, right, bottom = box
        points = ring_points(left, top, right, bottom, SAMPLE_BAND_PX)
        w = bitmap.getWidth()
        h = bitmap.getHeight()
        samples = [
            bitmap.getPixel(x, y) for x, y in points if 0 <= x < w and 0 <= y < h
        ]
        return median_color(samples)

    def _ensure_capture(self):
        projection = self._projection
        if projection is None:
            return False
        size = self._screen_size()
        if size is None:
            return False
        deg = self._rotation_degrees()
        if (
            self._reader is not None
            and size == (self._cap_w, self._cap_h)
            and deg == self._cap_deg
        ):
            return True
        first = self._reader is None
        w, h = size
        new_reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        flags = (
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        )
        try:
            new_virtual = projection.createVirtualDisplay(
                VIRTUAL_DISPLAY, w, h, DPI, flags, new_reader.getSurface(), None, None
            )
        except BaseException as e:
            Log.w(TAG, "Виртуальный дисплей не создался: %s" % e)
            new_reader.close()
            return False
        if self._virtual is not None:
            self._virtual.release()
        if self._reader is not None:
            self._reader.close()
        self._reader = new_reader
        self._virtual = new_virtual
        self._cap_w, self._cap_h, self._cap_deg = w, h, deg
        self._overlay.resize(w, h)
        if not first:
            self._pipeline.reset()
        return True

    def _screen_size(self):
        wm = self.getSystemService(Context.WINDOW_SERVICE)
        if Build.VERSION.SDK_INT >= 30:
            bounds = wm.getMaximumWindowMetrics().getBounds()
            return (bounds.width(), bounds.height())
        point = Point()
        wm.getDefaultDisplay().getRealSize(point)
        return (point.x, point.y)

    def _rotation_degrees(self):
        manager = self.getSystemService(Context.DISPLAY_SERVICE)
        display = manager.getDisplay(Display.DEFAULT_DISPLAY)
        if display is None:
            return 0
        rotation = display.getRotation()
        return {
            Surface.ROTATION_0: 0,
            Surface.ROTATION_90: 90,
            Surface.ROTATION_180: 180,
            Surface.ROTATION_270: 270,
        }[rotation]

    def _notification(self):
        manager = self.getSystemService(Context.NOTIFICATION_SERVICE)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Перевод экрана", NotificationManager.IMPORTANCE_LOW
            )
        )
        return (
            Notification.Builder(self, CHANNEL_ID)
            .setContentTitle("Перевод экрана")
            .setContentText("Идёт распознавание и перевод")
            .setSmallIcon(R.drawable.ic_menu_search)
            .build()
        )

    def _save_error(self):
        text = traceback.format_exc()[:1200]
        self._prefs().edit().putString("error", text).apply()

    def _prefs(self):
        return self.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
