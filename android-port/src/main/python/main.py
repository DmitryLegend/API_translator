"""Экран управления: запуск, остановка и диагностика перевода.

Телефон не даёт логов, поэтому всё, что пошло не так, показывается текстом
на экране и копируется в буфер обмена: его можно вставить в письмо или чат.
"""

# noinspection PyUnresolvedReferences
# Модули java/android/com.google существуют только внутри APK (Chaquopy),
# поэтому IDE не может их разрезолвить — это не ошибка.
from java import jint, jvoid, jarray, static_proxy, Override
from java.io import BufferedReader, InputStreamReader
from java.lang import String
from android import Manifest
from android.app import Activity
from android.content import ClipData, Context, Intent
from android.content.pm import PackageManager
from android.graphics import Color
from android.net import Uri
from android.os import Build, Bundle, Handler, Looper
from android.provider import Settings
from android.util import TypedValue
from android.view import Gravity, ViewGroup
from android.widget import Button, LinearLayout, TextView

from interop import ClickHandler, UiTask
from keys import DEEPL_KEY
from service import EXTRA_RESULT_CODE, EXTRA_RESULT_DATA, PREFS, SERVICE_CLASS, STATE

REQ_CAPTURE = 10
REQ_NOTIFICATIONS = 11
ATTRIBUTION = "Перевод: DeepL"


class MainActivity(
    static_proxy(Activity, package="com.dmitrylegend.apitranslator")
):

    _status = None
    _button = None
    _main = None
    _ticker = None
    _copied = ""
    _hint = ""

    @Override(jvoid, [Bundle])
    def onCreate(self, state):
        # Без вызова базового onCreate Android убивает активность
        # (SuperNotCalledException); у статического прокси это ручная работа.
        Activity.onCreate(self, state)
        pad = self._dp(24)
        root = LinearLayout(self)
        root.setOrientation(LinearLayout.VERTICAL)
        root.setGravity(Gravity.CENTER)
        root.setPadding(pad, pad, pad, pad)
        self._status = TextView(self)
        self._status.setGravity(Gravity.CENTER)
        self._status.setTextColor(Color.DKGRAY)
        self._status.setText(ATTRIBUTION)
        root.addView(
            self._status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        self._button = Button(self)
        params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = pad
        root.addView(self._button, params)
        self.setContentView(root)
        self._main = Handler(Looper.getMainLooper())
        self._button.setOnClickListener(ClickHandler(self._toggle))
        self._refresh()

    @Override(jvoid, [])
    def onResume(self):
        Activity.onResume(self)
        self._ticker = UiTask(self._refresh_tick)
        self._main.postDelayed(self._ticker, 1000)

    @Override(jvoid, [])
    def onPause(self):
        Activity.onPause(self)
        if self._ticker is not None:
            self._main.removeCallbacks(self._ticker)
            self._ticker = None

    @Override(jvoid, [jint, jint, Intent], modifiers="protected")
    def onActivityResult(self, request_code, result_code, data):
        Activity.onActivityResult(self, request_code, result_code, data)
        if request_code != REQ_CAPTURE:
            return
        if result_code != Activity.RESULT_OK or data is None:
            self._refresh()
            return
        intent = Intent()
        intent.setClassName(self, SERVICE_CLASS)
        # jint обязателен: обычное Python-число Chaquopy кладёт как long,
        # а getIntExtra со стороны службы читает только Integer — код
        # превращался в 0, и getMediaProjection возвращал null.
        intent.putExtra(EXTRA_RESULT_CODE, jint(result_code))
        intent.putExtra(EXTRA_RESULT_DATA, data)
        self.startForegroundService(intent)
        self._refresh()

    @Override(jvoid, [jint, jarray(String), jarray(jint)])
    def onRequestPermissionsResult(self, request_code, permissions, results):
        Activity.onRequestPermissionsResult(self, request_code, permissions, results)
        if request_code == REQ_NOTIFICATIONS:
            self._start()

    def _toggle(self):
        if STATE["running"]:
            self.stopService(Intent().setClassName(self, SERVICE_CLASS))
        else:
            self._start()
        self._refresh()

    def _start(self):
        if not DEEPL_KEY:
            self._hint = (
                "Ключ DeepL не встроен: пересоберите APK, задав секрет "
                "DEEPL_API_KEY в настройках Actions."
            )
            self._refresh()
            return
        if Build.VERSION.SDK_INT >= 33:
            permission = Manifest.permission.POST_NOTIFICATIONS
            if (
                self.checkSelfPermission(permission)
                != PackageManager.PERMISSION_GRANTED
            ):
                self.requestPermissions([permission], REQ_NOTIFICATIONS)
                return
        if not Settings.canDrawOverlays(self):
            self.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + self.getPackageName()),
                )
            )
            return
        manager = self.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
        self.startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE)

    def _refresh_tick(self):
        if self._ticker is None:
            return
        self._refresh()
        self._main.postDelayed(self._ticker, 1000)

    def _refresh(self):
        running = STATE["running"]
        problems = []
        error = self._last_error()
        if error:
            problems.append("ОШИБКА ЗАПУСКА:\n" + error)
        report = self._exit_report()
        if report:
            problems.append("ПРИЧИНА ПАДЕНИЯ ПРОЦЕССА:\n" + report)
        problem = "\n\n".join(problems)
        if problem and problem != self._copied:
            self._copied = problem
            self._copy(problem)
        text = ""
        if running:
            text = "Идёт перевод экрана\n" + STATE["debug"]
        if self._hint:
            text += ("\n\n" if text else "") + self._hint
        if problem:
            text += ("\n\n" if text else "") + problem
        self._status.setText(
            ATTRIBUTION
            + (
                "\n\n" + text + "\n\n(скопировано в буфер обмена)"
                if text
                else ""
            )
        )
        self._button.setText("Остановить" if running else "Запустить перевод")

    def _copy(self, text):
        try:
            clipboard = self.getSystemService(Context.CLIPBOARD_SERVICE)
            clipboard.setPrimaryClip(ClipData.newPlainText("Подстрочник", text))
        except BaseException:
            pass

    def _last_error(self):
        prefs = self.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("error", "") or ""

    def _exit_report(self):
        if Build.VERSION.SDK_INT < 30:
            return ""
        from android.app import ApplicationExitInfo

        am = self.getSystemService(Context.ACTIVITY_SERVICE)
        try:
            infos = am.getHistoricalProcessExitReasons(self.getPackageName(), 0, 1)
        except BaseException:
            return ""
        if infos is None or infos.isEmpty():
            return ""
        info = infos.get(0)
        # Обычные закрытия и убийства кэш-процессов — не падение, не показываем:
        # 0 UNKNOWN, 1 EXIT_SELF, 9 USER_REQUESTED, 10 USER_STOPPED,
        # 12 INIT_EXEC, 15 FREEZER, 16 STOPPED_BY_REDIRECT.
        if info.getReason() in (0, 1, 9, 10, 12, 15, 16):
            return ""
        # Отчёт показываем один раз: иначе старое падение (история
        # процесса переживает переустановку) висит на экране вечно.
        prefs = self.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        stamp = info.getTimestamp()
        if stamp <= prefs.getLong("exit_seen", 0):
            return ""
        prefs.edit().putLong("exit_seen", stamp).apply()
        try:
            why = {
                ApplicationExitInfo.REASON_CRASH: "падение (crash)",
                ApplicationExitInfo.REASON_CRASH_NATIVE: "падение нативного кода",
                ApplicationExitInfo.REASON_ANR: "система убила: не отвечал (ANR)",
                ApplicationExitInfo.REASON_LOW_MEMORY: "не хватило памяти",
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: "не запустился",
                ApplicationExitInfo.REASON_PERMISSION_DENIED:
                    "система убила: не хватило прав",
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                    "система убила: слишком много ресурсов",
                ApplicationExitInfo.REASON_DEPENDENCY_DIED: "умер процесс-спутник",
                ApplicationExitInfo.REASON_SIGNALED: "убит сигналом",
            }.get(info.getReason())
        except BaseException:
            why = None
        if why is None:
            why = (info.getDescription() or "").strip()
        if not why:
            why = "система убила процесс, код " + str(info.getReason())
        trace = ""
        try:
            stream = info.getTraceInputStream()
            if stream is not None:
                reader = BufferedReader(InputStreamReader(stream))
                trace = "\n".join(iter(reader.readline, ""))
                reader.close()
        except BaseException:
            trace = ""
        return (why + "\n" + trace)[:4000]

    def _dp(self, value):
        return int(
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                self.getResources().getDisplayMetrics(),
            )
        )
