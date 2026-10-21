package ru.medoed.vagdiag

import android.app.Activity
import android.app.PendingIntent
import android.app.ProgressDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ClipData
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import android.widget.Toast
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Experimental KW1281 tester. USB enumeration and serial I/O use the real adapter;
 * ECU transactions remain unverified until tested with a physical vehicle and cable.
 */
class MainActivity : Activity() {
    private lateinit var usbManager: UsbManager
    private lateinit var status: TextView
    private lateinit var deviceInfo: TextView
    private lateinit var logView: TextView
    private lateinit var connectButton: Button
    private lateinit var readButton: Button
    private lateinit var scanButton: Button
    private var controllerScanButton: Button? = null
    private lateinit var mainScroll: ScrollView
    private lateinit var quickAccessAnchor: View
    private lateinit var diagnosticAnchor: View
    private lateinit var logAnchor: View
    private lateinit var dtcButton: Button
    private lateinit var groupsButton: Button
    private lateinit var adaptButton: Button
    private lateinit var shareLogButton: Button
    private lateinit var groupInput: EditText
    private var kwClient: Kw1281Client? = null
    private var busyCount = 0
    private var progressDialog: ProgressDialog? = null
    private lateinit var diagnosticSettings: DiagnosticSettings
    private var port: UsbSerialPort? = null
    private var connectedDevice: UsbDevice? = null
    private val io = Executors.newSingleThreadExecutor()
    private val actionUsbPermission = "ru.medoed.vagdiag.USB_PERMISSION"

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != actionUsbPermission) return
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) openDevice(device)
            else appendLog("USB permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        diagnosticSettings = DiagnosticSettings.load(this)
        buildUi()
        val filter = IntentFilter(actionUsbPermission)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(permissionReceiver, filter)
        }
        appendLog("VAG Diag Android 0.7.0-alpha started")
        appendLog("Target: Golf IV 1.6 16V BCB / USB KKL CH340")
        appendLog("No ECU data is fabricated. Diagnostic protocol support is not yet validated.")
        refreshDevices()
    }

    private fun buildUi() {
        val navy = 0xFF082B4C.toInt()
        val blue = 0xFF075CB5.toInt()
        val pale = 0xFFF0F5FA.toInt()
        val ink = 0xFF153653.toInt()
        val muted = 0xFF647C91.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pale)
        }
        val scroll = ScrollView(this)
        mainScroll = scroll
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(22))
        }

        // Dark VAG-style top bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(navy, 0, 0, 0, 0)
        }
        val brandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.vag_diag_logo)
            contentDescription = "Логотип VAG Diag Android"
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(12) }
        }
        brandRow.addView(logo)
        val brandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandText.addView(TextView(this).apply {
            text = "VAG Diag Android"; textSize = 21f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
        })
        brandText.addView(TextView(this).apply {
            text = "GOLF IV · 1.6 16V BCB"; textSize = 12f; setTextColor(0xFFB9D2E7.toInt())
        })
        brandRow.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f))
        val menuButton = TextView(this).apply {
            text = "☰"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            contentDescription = "Открыть меню"
            isClickable = true
            isFocusable = true
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { showNavigationMenu(this) }
        }
        brandRow.addView(menuButton, LinearLayout.LayoutParams(dp(44), dp(48)))
        header.addView(brandRow)
        status = TextView(this).apply {
            text = "● Адаптер не подключён"; textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFD6D6.toInt()); setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(0xFF173D5D.toInt(), 12, 12, 12, 12)
        }
        header.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        deviceInfo = TextView(this).apply {
            text = "USB KKL · CH340 · OTG"; textSize = 12f; setTextColor(0xFFB9D2E7.toInt())
            setPadding(dp(2), dp(8), dp(2), 0)
        }
        header.addView(deviceInfo)
        root.addView(header)

        val carBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(0xFFFFFFFF.toInt(), 16, 16, 16, 16)
        }
        val carPicture = ImageView(this).apply {
            setImageResource(R.drawable.golf_car_illustration)
            contentDescription = "Иллюстрация автомобиля Volkswagen Golf IV"
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(126), dp(76)).apply { rightMargin = dp(12) }
        }
        carBanner.addView(carPicture)
        val carCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        carCopy.addView(TextView(this).apply {
            text = "ТВОЙ АВТОМОБИЛЬ"; textSize = 10f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF0874C9.toInt())
        })
        carCopy.addView(TextView(this).apply {
            text = "Volkswagen Golf IV"; textSize = 17f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ink)
        })
        carCopy.addView(TextView(this).apply {
            text = "1.6 16V · BCB · K-line"; textSize = 12f; setTextColor(muted)
        })
        carBanner.addView(carCopy, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(carBanner, matchWrap())

        content.addView(TextView(this).apply {
            text = "Volkswagen Golf IV"; textSize = 20f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ink); setPadding(dp(2), dp(16), dp(2), 0)
        })
        content.addView(TextView(this).apply {
            text = "2001 · Бензин · ME7.5.10 (ожидаемый тип, будет уточнён по ЭБУ)"
            textSize = 12f; setTextColor(muted); setPadding(dp(2), dp(3), dp(2), dp(12))
        })

        connectButton = button("Подключить USB KKL", true) { refreshDevices() }
        content.addView(connectButton, matchWrap())
        content.addView(button("Отключить адаптер", false) { disconnectAdapter() }, matchWrap())

        quickAccessAnchor = section("БЫСТРЫЙ ДОСТУП")
        content.addView(quickAccessAnchor)
        // Two-column VAG-style tiles. Each tile opens a real implemented action or clearly reports that it is unavailable.
        val row1 = tileRow()
        row1.addView(tile("▦", "Список блоков", "Опрос KW1281") { scanControllers() }, tileParams())
        row1.addView(tile("⚠", "Ошибки DTC", "Двигатель · адрес 01") {
            runDiagnostic("Подключение и чтение DTC") { client -> client.connectAuto(diagnosticSettings.ecuAddress, diagnosticSettings.baudRate, diagnosticSettings.startBaudRate, fastInit = diagnosticSettings.fastInit); client.readFaults() }
        }, tileParams())
        content.addView(row1, matchWrap())
        val row2 = tileRow()
        row2.addView(tile("⌁", "Измерения", "Группы и сырые данные") {
            val g = groupInput.text.toString().trim().toIntOrNull() ?: 1
            runDiagnostic("Группа $g") { client -> client.readGroup(g) }
        }, tileParams())
        row2.addView(tile("☷", "Адаптации", "Пока недоступны") { unsupported("Адаптации") }, tileParams())
        content.addView(row2, matchWrap())
        val row3 = tileRow()
        row3.addView(tile("⚙", "Базовые установки", "Не реализованы") { unsupported("Базовые установки") }, tileParams())
        row3.addView(tile("≡", "Журнал", "Сохранить / отправить") { shareLog() }, tileParams())
        content.addView(row3, matchWrap())

        content.addView(section("ДИАГНОСТИКА ДВИГАТЕЛЯ"))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(0xFFFFFFFF.toInt(), 16, 16, 16, 16)
        }
        panel.addView(TextView(this).apply {
            text = "Блок 01 — Двигатель"; textSize = 16f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ink)
        })
        panel.addView(TextView(this).apply {
            text = "Сначала установи соединение с USB-адаптером, затем попробуй подключиться к ЭБУ."
            textSize = 12f; setTextColor(muted); setPadding(0, dp(4), 0, dp(10))
        })
        panel.addView(button("⚙ Настройки подключения", false) { showDiagnosticSettings() }, matchWrap())
        scanButton = button(connectLabel(), true) {
            runDiagnostic("Автоопределение ЭБУ") { client ->
                val connection = client.connectAuto(diagnosticSettings.ecuAddress, diagnosticSettings.baudRate, diagnosticSettings.startBaudRate, fastInit = diagnosticSettings.fastInit)
                val identification = try { client.readIdentification() } catch (e: Exception) { "Идентификация не прочитана: ${e.message}" }
                "$connection\nИдентификация ЭБУ:\n$identification"
            }
        }
        panel.addView(scanButton, matchWrap())
        panel.addView(button("Прочитать идентификацию ЭБУ", false) {
            runDiagnostic("Идентификация") { client -> client.readIdentification() }
        }, matchWrap())
        dtcButton = button("Прочитать ошибки двигателя", false) {
            runDiagnostic("Чтение DTC") { client -> client.readFaults() }
        }
        panel.addView(dtcButton, matchWrap())
        panel.addView(button("Очистить ошибки…", false) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Очистить ошибки ЭБУ?")
                .setMessage("Сначала сохрани ошибки и данные диагностики. Очистка удаляет сохранённые DTC, но не устраняет неисправность.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Очистить") { _, _ -> runDiagnostic("Очистка DTC") { client -> client.clearFaults() } }
                .show()
        }, matchWrap())
        groupInput = EditText(this).apply {
            hint = "Номер группы (например, 001)"; setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText("001")
            textSize = 14f; setTextColor(ink); setHintTextColor(muted)
            background = rounded(0xFFF5F8FB.toInt(), 10, 10, 10, 10)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        panel.addView(groupInput, matchWrap())
        groupsButton = button("Прочитать измерительную группу", true) {
            val group = groupInput.text.toString().trim().toIntOrNull()
            if (group == null || group !in 0..255) Toast.makeText(this, "Укажи группу от 0 до 255", Toast.LENGTH_SHORT).show()
            else runDiagnostic("Группа $group") { client -> client.readGroup(group) }
        }
        panel.addView(groupsButton, matchWrap())
        diagnosticAnchor = panel
        content.addView(panel, matchWrap())

        val warning = TextView(this).apply {
            text = "Важно: версия экспериментальная. Инициализация линии работает как в Васе: fast-init (Start Baud 9600) или slow-init (5 бод). Измерения пока отображаются в сыром виде. Адаптации и базовые установки не реализованы. Совместимость CH340/OTG и связь с ЭБУ не подтверждены на реальном автомобиле."
            textSize = 12f; setTextColor(0xFF80520B.toInt()); setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(0xFFFFF0D2.toInt(), 12, 12, 12, 12)
        }
        content.addView(warning, matchWrap())
        logAnchor = section("ЖУРНАЛ ДИАГНОСТИКИ")
        content.addView(logAnchor)
        logView = TextView(this).apply {
            textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFFDDE7EF.toInt()); setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(0xFF0B2238.toInt(), 12, 12, 12, 12); text = "Ожидание действий…\n"
        }
        content.addView(logView, matchWrap())
        shareLogButton = button("Поделиться журналом", false) { shareLog() }
        content.addView(shareLogButton, matchWrap())
        content.addView(TextView(this).apply {
            text = "VAG Diag Android 0.2 · бесплатный проект"; textSize = 11f
            setTextColor(muted); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4))
        })
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    /** Выпадающий список «как галка у Васи»: выбор из нескольких значений вместо цифрового поля. */
    private fun dropdownField(fields: LinearLayout, label: String, options: List<Pair<String, Int>>, selectedValue: Int): () -> Int {
        fields.addView(TextView(this).apply {
            text = label; textSize = 13f; setTextColor(0xFF153653.toInt()); setPadding(0, dp(7), 0, dp(3))
        })
        val spinnerLabel = TextView(this).apply {
            textSize = 15f; setTextColor(0xFF075CB5.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = rounded(0xFFF5F8FB.toInt(), 10, 10, 10, 10)
        }
        var selectedIndex = options.indexOfFirst { it.second == selectedValue }.coerceAtLeast(0)
        fun render() { spinnerLabel.text = options[selectedIndex].first + "     \u25BE" }
        render()
        spinnerLabel.setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            options.forEachIndexed { i, (text, _) -> popup.menu.add(0, i, i, text) }
            popup.setOnMenuItemClickListener { item ->
                selectedIndex = item.itemId; render(); true
            }
            popup.show()
        }
        fields.addView(spinnerLabel, LinearLayout.LayoutParams(-1, -2))
        return { options[selectedIndex].second }
    }

    private fun showDiagnosticSettings() {
        val current = diagnosticSettings
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(4))
        }
        fun field(label: String, value: Int, inputType: Int = android.text.InputType.TYPE_CLASS_NUMBER): EditText {
            fields.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(0xFF153653.toInt())
                setPadding(0, dp(7), 0, dp(3))
            })
            return EditText(this).apply {
                setSingleLine(true)
                this.inputType = inputType
                setText(value.toString())
                textSize = 15f
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }.also { fields.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        }
        val address = field("Адрес ЭБУ (двигатель обычно 01)", current.ecuAddress)
        // Vasya Diagnost-style advanced options (labels mirror his "Protocol settings" dialog).
        // Start Baud — как два варианта у Васи: 5 = slow-init, 9600 = fast-init.
        val startBaud = dropdownField(fields, "Start Baud — режим инициализации (как в Васе)",
            listOf("5 — slow-init (классический KW1281)" to 5, "9600 — fast-init (рекомендуется для CH340)" to 9600),
            current.startBaudRate)
        val baud = dropdownField(fields, "Скорость после инициализации",
            listOf("Авто (подбор по адресу, как в Васе)" to 0, "10400" to 10400, "9600" to 9600, "4800" to 4800),
            current.baudRate)
        val response = field("Тайм-аут ответа, мс", current.responseTimeoutMs)
        val init = field("Тайм-аут инициализации, мс", current.initTimeoutMs)
        val echoDelay = field("Echo Delay — пауза вокруг эха/ACK, мс (как в Васе: 0)", current.echoDelayMs)
        val blkDelay = field("Blk Int — задержка между блоками, мс (как в Васе: 55)", current.blockDelayMs)
        val intDelay = field("Char Int — межбайтовая задержка в блоке, мс (как в Васе: 1)", current.interByteDelayMs)
        val charIntDelay = field("Delay Char int — доп. пауза между символами, мс (как в Васе: 1)", current.charInterDelayMs)
        val debug = dropdownField(fields, "Debug Level",
            listOf("0 — выключен" to 0, "1 — байты TX/RX" to 1, "2 — максимально подробно" to 2),
            current.debugLevel)
        val retries = field("Init retry times — повторов инициализации при отсутствии ответа (1..5)", current.initRetryTimes)
        fields.addView(TextView(this).apply {
            text = "Параметры повторяют Васю Диагност. Рекомендация для USB KKL на CH340: Start Baud 9600 (fast-init) — Вася с таким кабелем тоже работает через break-импульс, а не 5 бод. Скорость «Авто» подбирается по адресу блока: 01/15/17/25/35/36 → сначала 10400, остальные → сначала 9600."
            textSize = 11f
            setTextColor(0xFF647C91.toInt())
            setPadding(0, dp(10), 0, dp(4))
        })
        val scroll = ScrollView(this).apply { addView(fields) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Настройки подключения")
            .setView(scroll)
            .setNeutralButton("По умолчанию") { _, _ ->
                diagnosticSettings = VasyaDefaults.vasyaSettings(diagnosticSettings.ecuAddress)
                diagnosticSettings.save(this)
                kwClient = null
                scanButton.text = connectLabel()
                appendLog("Настройки сброшены по умолчанию (как в Васе): ${diagnosticSettings.describe()}")
                Toast.makeText(this, "Настройки по умолчанию восстановлены", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val a = address.text.toString().toIntOrNull()
                        val sb = startBaud()
                        val b = baud()
                        val r = response.text.toString().toIntOrNull()
                        val i = init.text.toString().toIntOrNull()
                        val echo = echoDelay.text.toString().toIntOrNull()
                        val blk = blkDelay.text.toString().toIntOrNull()
                        val inter = intDelay.text.toString().toIntOrNull()
                        val charInt = charIntDelay.text.toString().toIntOrNull()
                        val dbgLvl = debug()
                        val rt = retries.text.toString().toIntOrNull()
                        val error = when {
                            a == null || a !in 0..127 -> "Адрес должен быть от 0 до 127"
                            r == null || r !in 100..10000 -> "Тайм-аут ответа: 100–10000 мс"
                            i == null || i !in 1000..15000 -> "Тайм-аут инициализации: 1000–15000 мс"
                            echo == null || echo !in 0..100 -> "Echo delay: 0–100 мс"
                            blk == null || blk !in 0..1000 -> "Blk Int: 0–1000 мс"
                            inter == null || inter !in 0..100 -> "Char Int: 0–100 мс"
                            charInt == null || charInt !in 0..100 -> "Delay Char int: 0–100 мс"
                            rt == null || rt !in 1..5 -> "Повторов инициализации: 1–5"
                            else -> null
                        }
                        if (error != null) {
                            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        diagnosticSettings = DiagnosticSettings(
                            ecuAddress = a!!, baudRate = b, responseTimeoutMs = r!!, initTimeoutMs = i!!,
                            startBaudRate = sb, echoDelayMs = echo!!, blockDelayMs = blk!!,
                            interByteDelayMs = inter!!, charInterDelayMs = charInt!!,
                            debugLevel = dbgLvl, initRetryTimes = rt!!,
                            protocolMode = if (sb >= 1000) 2 else 1
                        )
                        diagnosticSettings.save(this)
                        kwClient = null
                        scanButton.text = connectLabel()
                        appendLog("Настройки сохранены: ${diagnosticSettings.describe()}")
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun showNavigationMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Главная")
        popup.menu.add(0, 2, 1, "Быстрый доступ")
        popup.menu.add(0, 3, 2, "Диагностика двигателя")
        popup.menu.add(0, 4, 3, "Журнал диагностики")
        popup.menu.add(0, 5, 4, "О приложении")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> mainScroll.smoothScrollTo(0, 0)
                2 -> mainScroll.post { mainScroll.smoothScrollTo(0, quickAccessAnchor.top) }
                3 -> mainScroll.post { mainScroll.smoothScrollTo(0, diagnosticAnchor.top) }
                4 -> mainScroll.post { mainScroll.smoothScrollTo(0, logAnchor.top) }
                5 -> android.app.AlertDialog.Builder(this)
                    .setTitle("VAG Diag Android")
                    .setMessage("""Бесплатное экспериментальное приложение для диагностики автомобилей VAG через USB KKL.

Автомобиль: Volkswagen Golf IV 1.6 16V BCB.
Адаптер: USB KKL / CH340.

Поддержка протокола и связь с ЭБУ требуют проверки на реальном автомобиле. Адаптации и базовые установки пока не реализованы.""")
                    .setPositiveButton("Понятно", null)
                    .show()
            }
            true
        }
        popup.show()
    }

    /** Заголовок кнопки подключения с текущим режимом инициализации — видно «как в Васе». */
    private fun connectLabel(): String = "Подключиться к ЭБУ · адрес %02X · %s".format(
        diagnosticSettings.ecuAddress, if (diagnosticSettings.fastInit) "fast-init 9600" else "slow-init 5 бод"
    )

    private fun section(text: String) = TextView(this).apply {
        this.text = text; textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(0xFF58738B.toInt()); setPadding(dp(2), dp(18), dp(2), dp(8))
    }

    private fun button(label: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; minHeight = dp(46)
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else 0xFF174568.toInt())
        background = rounded(if (primary) 0xFF0864B8.toInt() else 0xFFE0EAF3.toInt(), 10, 10, 10, 10)
        setPadding(dp(12), dp(5), dp(12), dp(5))
        setOnClickListener { action() }
    }

    private fun tileRow() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun tileParams() = LinearLayout.LayoutParams(0, dp(104), 1f).apply {
        marginEnd = dp(7); bottomMargin = dp(2)
    }
    private fun tile(symbol: String, title: String, subtitle: String, action: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(8), dp(9), dp(8), dp(8))
            background = rounded(0xFFFFFFFF.toInt(), 14, 14, 14, 14)
            isClickable = true; isFocusable = true; setOnClickListener { action() }
        }
        box.addView(TextView(this).apply {
            text = symbol; textSize = 24f; gravity = Gravity.CENTER; setTextColor(0xFF075CB5.toInt())
        })
        box.addView(TextView(this).apply {
            text = title; textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setTextColor(0xFF153653.toInt())
        })
        box.addView(TextView(this).apply {
            text = subtitle; textSize = 10f; gravity = Gravity.CENTER; setTextColor(0xFF647C91.toInt())
        })
        return box
    }

    private fun rounded(color: Int, tl: Int, tr: Int, br: Int, bl: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadii = floatArrayOf(dp(tl).toFloat(), dp(tl).toFloat(), dp(tr).toFloat(), dp(tr).toFloat(),
                dp(br).toFloat(), dp(br).toFloat(), dp(bl).toFloat(), dp(bl).toFloat())
        }

    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun refreshDevices() {
        try {
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            if (drivers.isEmpty()) {
                deviceInfo.text = "USB-serial адаптер не найден. Подключи KKL через OTG и разреши доступ."
                appendLog("No supported USB serial devices found")
                status.text = "● Адаптер не подключён"
                return
            }
            val driver = drivers.firstOrNull { d ->
                val id = d.device.vendorId
                // CH340 commonly uses VID 1A86, PID 7523/5523. Library driver detection is authoritative.
                id == 0x1A86 || d.ports.isNotEmpty()
            } ?: drivers.first()
            val dev = driver.device
            deviceInfo.text = "Найден USB-адаптер: VID %04X · PID %04X\nУстройство: %s".format(Locale.US, dev.vendorId, dev.productId, dev.deviceName)
            appendLog("Found USB device VID=%04X PID=%04X".format(Locale.US, dev.vendorId, dev.productId))
            if (usbManager.hasPermission(dev)) openDevice(dev) else {
                val intent = Intent(actionUsbPermission).setPackage(packageName)
                val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
                val permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags)
                usbManager.requestPermission(dev, permissionIntent)
                appendLog("Requesting USB permission…")
            }
        } catch (e: Exception) {
            appendLog("USB scan error: ${e.message}")
            Toast.makeText(this, "Ошибка USB: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openDevice(device: UsbDevice) {
        showBusy("USB KKL", "Открываю порт адаптера…")
        io.execute {
            try {
                closePort()
                val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                    ?: throw IOException("Нет драйвера USB-Serial для этого устройства")
                runOnUiThread { updateBusy("Запрашиваю USB-соединение…") }
                val connection = usbManager.openDevice(device) ?: throw IOException("Не удалось открыть USB-устройство")
                val serial = driver.ports.firstOrNull() ?: throw IOException("У адаптера нет serial-порта")
                runOnUiThread { updateBusy("Инициализация UART…") }
                serial.open(connection)
                // Стартовая скорость порта — просто настройка UART; режим инициализации ЭБУ
                // (fast/slow) выбирается при подключении по настройкам «как в Васе».
                serial.setParameters(VasyaProtocol.FAST_INIT_BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                VasyaProtocol.resetCache()
                port = serial
                kwClient = null
                connectedDevice = device
                runOnUiThread {
                    hideBusy()
                    status.text = "● USB-адаптер подключён"; status.setTextColor(0xFF237A45.toInt())
                    deviceInfo.text = "USB открыт: VID %04X · PID %04X\nГотов к подключению · режим: %s".format(Locale.US, device.vendorId, device.productId, if (diagnosticSettings.fastInit) "fast-init 9600 (как в Васе)" else "slow-init 5 бод")
                    appendLog("USB serial port opened; UART configured 10400 8N1")
                    appendLog("Адаптер готов. Связь с ЭБУ ещё не установлена — нажми «Подключить ЭБУ двигателя».")
                }
            } catch (e: Exception) {
                runOnUiThread { hideBusy(); appendLog("Open failed: ${e.message}"); status.text = "● Не удалось открыть адаптер"; status.setTextColor(0xFF9B2C2C.toInt()) }
            }
        }
    }

    private fun readRaw() {
        val serial = port
        if (serial == null) { appendLog("Connect the adapter first"); return }
        io.execute {
            try {
                val buffer = ByteArray(256)
                val n = serial.read(buffer, 500)
                val line = if (n <= 0) "No bytes received (no ECU command was sent)" else buffer.take(n).joinToString(" ") { "%02X".format(it) }
                runOnUiThread { appendLog("RX: $line") }
            } catch (e: Exception) { runOnUiThread { appendLog("Read failed: ${e.message}") } }
        }
    }

    /**
     * Busy/progress overlay: a spinner dialog with the current protocol stage, so long
     * slow-init/handshake waits visibly look like work in progress instead of a freeze.
     * Must be called on the UI thread (runOnUiThread / onCreate paths already do).
     */
    private fun showBusy(title: String, message: String) {
        busyCount++
        if (progressDialog == null) {
            progressDialog = ProgressDialog(this).apply {
                isIndeterminate = true
                setProgressStyle(ProgressDialog.STYLE_SPINNER)
                setCancelable(false)
            }
        }
        progressDialog?.apply {
            setTitle(title)
            setMessage(message)
            if (!isShowing) show()
        }
    }

    /** Updates the dialog text from the IO thread; silently ignores updates when hidden. */
    private fun updateBusy(message: String) {
        runOnUiThread {
            if (busyCount > 0) progressDialog?.setMessage(message)
        }
    }

    private fun hideBusy() {
        busyCount--
        if (busyCount <= 0) {
            busyCount = 0
            try { progressDialog?.dismiss() } catch (_: Exception) { }
            progressDialog = null
        }
    }

    /** Создаёт клиент с текущими настройками; initMode передаётся единообразно (как в Васе). */
    private fun newClient(serial: UsbSerialPort, stagePrefix: String? = null): Kw1281Client = Kw1281Client(
        serial,
        { message -> runOnUiThread { appendLog(message) } },
        responseTimeoutMs = diagnosticSettings.responseTimeoutMs,
        initTimeoutMs = diagnosticSettings.initTimeoutMs,
        echoDelayMs = diagnosticSettings.echoDelayMs,
        blockDelayMs = diagnosticSettings.blockDelayMs,
        interByteDelayMs = diagnosticSettings.interByteDelayMs,
        charInterDelayMs = diagnosticSettings.charInterDelayMs,
        debugLevel = diagnosticSettings.debugLevel,
        initRetryTimes = diagnosticSettings.initRetryTimes,
        onStage = { stage -> updateBusy(if (stagePrefix != null) "$stagePrefix $stage" else stage) }
    )

    private fun runDiagnostic(label: String, action: (Kw1281Client) -> String) {
        val serial = port
        if (serial == null) { appendLog("Сначала подключи USB KKL") ; return }
        showBusy(label, "Открываю соединение с ЭБУ…")
        io.execute {
            try {
                val client = kwClient ?: newClient(serial).also { kwClient = it }
                val result = action(client)
                runOnUiThread {
                    hideBusy()
                    appendLog("$label — результат:\n$result")
                    Toast.makeText(this, "$label: операция завершена", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                val cause = e.cause
                val detail = buildString {
                    append(e.javaClass.name)
                    e.message?.let { append(": ").append(it) }
                    if (cause != null) {
                        append("; причина: ").append(cause.javaClass.name)
                        cause.message?.let { append(": ").append(it) }
                    }
                }
                // Keep the full stack trace in the in-app log: the exception type alone
                // cannot identify which USB-serial API/driver operation failed.
                val trace = android.util.Log.getStackTraceString(e)
                runOnUiThread {
                    hideBusy()
                    appendLog("$label — ошибка: $detail")
                    appendLog(trace)
                    Toast.makeText(this, "Ошибка диагностики: $detail", Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    /** Tries a conservative set of common VAG addresses using KW1281 only.
     * Failure is reported as “not detected via KW1281”, never as proof the module is absent.
     */
    private fun scanControllers() {
        val serial = port
        if (serial == null) { appendLog("Сначала подключи USB KKL"); return }
        val targets = listOf(
            Triple(0x01, "Двигатель", 0), Triple(0x02, "АКПП", 0),
            Triple(0x03, "ABS", 0), Triple(0x08, "Климат", 0),
            Triple(0x15, "Подушки безопасности", 0), Triple(0x17, "Приборная панель", 0),
            Triple(0x35, "Центральный замок", 0), Triple(0x46, "Комфорт", 0),
            Triple(0x56, "Магнитола", 0)
        )
        controllerScanButton?.isEnabled = false
        showBusy("Список блоков", "Опрашиваю адрес 01 (1 из ${targets.size})…")
        io.execute {
            val found = mutableListOf<String>()
            val missed = mutableListOf<String>()
            for ((index, target) in targets.withIndex()) {
                val (address, label, baud) = target
                runOnUiThread { updateBusy("Опрашиваю «$label» · адрес %02X (${index + 1} из ${targets.size})…".format(address)) }
                try {
                    try { kwClient?.disconnect() } catch (_: Exception) { }
                    kwClient = null
                    val client = newClient(serial, "«$label» (${index + 1}/${targets.size}):")
                    val handshake = client.connectAuto(address, baud, diagnosticSettings.startBaudRate, fastInit = diagnosticSettings.fastInit)
                    kwClient = client
                    val identification = try { client.readIdentification() } catch (e: Exception) { "Идентификация не прочитана: ${e.message}" }
                    found += "%02X — %s\n%s\n%s".format(address, label, identification, handshake)
                    try { client.disconnect() } catch (_: Exception) { }
                    kwClient = null
                } catch (e: Exception) {
                    missed += "%02X — %s: %s".format(address, label, e.message ?: e.javaClass.simpleName)
                    try { kwClient?.disconnect() } catch (_: Exception) { }
                    kwClient = null
                }
            }
            runOnUiThread {
                hideBusy()
                appendLog("Начинаю последовательный опрос ${targets.size} адресов KW1281 (скорость подбирается по адресу, как в Васе). Это может занять несколько минут.")
                appendLog("Результат опроса KW1281: успешно установлено соединений ${found.size} из ${targets.size}.")
                if (found.isNotEmpty()) appendLog("ОТВЕТИЛИ:\n" + found.joinToString("\n\n"))
                appendLog("НЕ ОТВЕТИЛИ ПО KW1281 (это не доказывает отсутствие блока):\n" + missed.joinToString("\n"))
                controllerScanButton?.isEnabled = true
            }
        }
    }

    private fun disconnectAdapter() {
        showBusy("USB KKL", "Закрываю порт адаптера…")
        io.execute {
            closePort()
            runOnUiThread {
                hideBusy()
                status.text = "● Адаптер отключён"
                status.setTextColor(0xFF9B2C2C.toInt())
                deviceInfo.text = "USB-порт закрыт"
                appendLog("USB-адаптер отключён")
            }
        }
    }

    private fun shareLog() {
        val content = if (::logView.isInitialized) logView.text.toString() else "Журнал пуст"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "VAG Diag Android — журнал диагностики")
            putExtra(Intent.EXTRA_TEXT, content)
            clipData = ClipData.newPlainText("VAG diagnostic log", content)
        }
        startActivity(Intent.createChooser(intent, "Отправить журнал диагностики"))
    }

    private fun unsupported(feature: String) {
        appendLog("$feature: not implemented; no command sent")
        Toast.makeText(this, "$feature пока не реализовано", Toast.LENGTH_SHORT).show()
    }

    private fun appendLog(message: String) {
        if (!::logView.isInitialized) return
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logView.append("[$stamp] $message\n")
    }

    private fun closePort() {
        VasyaProtocol.resetCache()
        try { kwClient?.disconnect() } catch (_: Exception) {}
        kwClient = null
        try { port?.close() } catch (_: Exception) {}
        port = null
        connectedDevice = null
    }

    override fun onDestroy() {
        try { unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
        io.execute { closePort() }
        io.shutdown()
        super.onDestroy()
    }
}
