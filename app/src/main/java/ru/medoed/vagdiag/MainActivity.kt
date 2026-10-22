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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.File
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
    private lateinit var settingsButton: Button
    private lateinit var addressField: EditText
    private lateinit var mainScroll: ScrollView
    private lateinit var quickAccessAnchor: View
    private lateinit var diagnosticAnchor: View
    private lateinit var logAnchor: View
    private lateinit var groupInput: EditText
    private var kwClient: Kw1281Client? = null
    private var busyCount = 0
    private var progressDialog: ProgressDialog? = null
    private lateinit var diagnosticSettings: DiagnosticSettings
    private var port: UsbSerialPort? = null
    private var connectedDevice: UsbDevice? = null
    private val io = Executors.newSingleThreadExecutor()
    private val actionUsbPermission = "ru.medoed.vagdiag.USB_PERMISSION"

    // ===== Журнал в файл (чтобы можно было отправить домой без телефона у машины) =====
    private val fileStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fileNameStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private var currentLogFile: File? = null
    /** true, пока приложение активно — пишет журнал в экран AND файл; false при фоне — только файл. */
    private var uiActive = true

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
        pruneOldLogs()
        buildUi()
        val filter = IntentFilter(actionUsbPermission)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(permissionReceiver, filter)
        }
        appendLog("VCDS-KW1281 1.3.0-alpha started")
        appendLog("Vehicle   : Golf IV 1.6 16V BCB / Interface: USB KKL CH340")
        appendLog("KW1281 only. No fabricated ECU data; validation on real vehicle required.")
        val logFile = currentLogFile
        if (logFile != null) {
            appendLog("Журнал пишется в файл: ${logFile.name} — «Save log» внизу отправит его целиком, даже если экран чистили или приложение закрывали.")
        } else {
            appendLog("ВНИМАНИЕ: журнал НЕ сохраняется в файл (нет хранилища). Кнопка «Save log» отправит то, что видно на экране.")
        }
        refreshDevices()
    }

    override fun onResume() {
        super.onResume()
        uiActive = true
    }

    override fun onPause() {
        uiActive = false
        super.onPause()
    }

    /**
     * VCDS-стиль: тёмный «терминальный» интерфейс с зелёным моноширинным текстом.
     * Верхняя панель — статус соединения (как «Testers / Vehicle / Mode» в VCDS),
     * ниже — выбор блока (Select Control Module), функциональные кнопки в ряд
     * (Read Measuring Blocks / Read Fault Codes / Clear Fault Codes / Basic Settings /
     * Identification / Prototype Coding) и большое окно вывода (Output window).
     */
    private fun buildUi() {
        val bg = 0xFF0A0A0A.toInt()          // почти чёрный фон, как окно VCDS
        val panel = 0xFF101010.toInt()       // панели чуть светлее
        val green = 0xFF00E000.toInt()       // основной зелёный текст
        val brightGreen = 0xFF6AFF6A.toInt() // подсветка активной кнопки
        val dimGreen = 0xFF008F3A.toInt()    // второстепенный текст
        // (amber зарезервирован под будущие предупреждения в стиле VCDS)
        val red = 0xFFFF5F5F.toInt()         // ошибка/не активно
        val mono = android.graphics.Typeface.MONOSPACE

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        // ===== Верхняя строка статуса (аналог шапки VCDS) =====
        val headRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        headRow.addView(TextView(this).apply {
            text = "VCDS-KW1281"; textSize = 17f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(green)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val menuButton = TextView(this).apply {
            text = "[Меню]"
            textSize = 14f; typeface = mono; gravity = Gravity.CENTER
            setTextColor(green); isClickable = true; isFocusable = true
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { showNavigationMenu(this) }
        }
        headRow.addView(menuButton)
        root.addView(headRow)

        status = TextView(this).apply {
            text = "K-TESTER : NOT CONNECTED"; textSize = 13f; typeface = mono
            setTextColor(red); setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        root.addView(status, matchWrap())
        deviceInfo = TextView(this).apply {
            text = "VEHICLE   : Golf IV 1.6 16V BCB (ME7.5.10?)"; textSize = 12f; typeface = mono
            setTextColor(dimGreen); setPadding(dp(6), 0, dp(6), dp(4))
        }
        root.addView(deviceInfo, matchWrap())

        // ===== Кнопки адаптера =====
        val adapterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        connectButton = vcdsButton("Connect", green) { refreshDevices() }
        adapterRow.addView(connectButton, vcdsBtnParams())
        adapterRow.addView(vcdsButton("Disconnect", green) { disconnectAdapter() }, vcdsBtnParams())
        root.addView(adapterRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

        // ===== Выбор адреса блока (Select Control Module) =====
        val addrRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addrRow.addView(TextView(this).apply {
            text = "Adresse:"; textSize = 14f; typeface = mono; setTextColor(green)
            setPadding(0, 0, dp(8), 0)
        })
        addressField = EditText(this).apply {
            setText("%02X".format(diagnosticSettings.ecuAddress)); setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            filters = arrayOf(android.text.InputFilter.LengthFilter(2))
            textSize = 16f; typeface = mono; setTextColor(brightGreen); setHintTextColor(dimGreen)
            gravity = Gravity.CENTER
            background = rounded(panel, 4, 4, 4, 4)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        addrRow.addView(addressField, LinearLayout.LayoutParams(dp(72), -2).apply { marginEnd = dp(8) })
        addrRow.addView(TextView(this).apply {
            text = "(01 Motronic · 03 ABS · 15 Airbag · 17 Panel · 08 Climat…)"; textSize = 10f
            typeface = mono; setTextColor(dimGreen)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(addrRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(2) })

        settingsButton = vcdsButton("Protocol settings…", dimGreen) { showDiagnosticSettings() }
        root.addView(settingsButton, matchWrap())

        // ===== Функциональные кнопки, как нижний ряд окон VCDS =====
        fun fnRow(vararg labels: Pair<String, () -> Unit>) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            labels.forEach { (label, action) ->
                val btn = vcdsButton(label, green, action = action)
                btn.setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> (v as Button).setTextColor(0xFF6AFF6A.toInt())
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                            (v as Button).setTextColor(green)
                    }
                    false
                }
                row.addView(btn, vcdsBtnParams())
            }
            root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        fnRow(
            "Autoscan" to { scanControllers() },
            "Read Measuring Block" to { readMeasuringBlock() }
        )
        fnRow(
            "Read Fault Codes" to {
                runDiagnostic("Чтение DTC") { client -> ensureSession(client); client.readFaults() }
            },
            "Clear Fault Codes" to { confirmClearFaults() }
        )
        fnRow(
            "Basic Settings" to {
                runDiagnostic("Базовые установки") { client -> ensureSession(client); client.readBasicSettings() }
            },
            "Identification" to {
                runDiagnostic("Идентификация") { client -> ensureSession(client); client.readIdentification() }
            }
        )

        // Номер группы для измерений — отдельная строка ввода, как поле в окне VCDS
        val groupRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        groupRow.addView(TextView(this).apply {
            text = "Group:"; textSize = 14f; typeface = mono; setTextColor(green)
            setPadding(0, 0, dp(8), 0)
        })
        groupInput = EditText(this).apply {
            setText("001"); setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(3))
            textSize = 16f; typeface = mono; setTextColor(brightGreen)
            gravity = Gravity.CENTER
            background = rounded(panel, 4, 4, 4, 4)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        groupRow.addView(groupInput, LinearLayout.LayoutParams(dp(72), -2))
        groupRow.addView(TextView(this).apply {
            text = "  (000–255)"; textSize = 11f; typeface = mono; setTextColor(dimGreen)
        })
        root.addView(groupRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

        // ===== Окно вывода (Output window) — то же поле, что и журнал =====
        root.addView(TextView(this).apply {
            text = "Output:"; textSize = 13f; typeface = mono; setTextColor(green)
            setPadding(dp(4), dp(4), dp(4), dp(2))
        })
        val outFrame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFF050505.toInt(), 4, 4, 4, 4)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        logView = TextView(this).apply {
            textSize = 12f; typeface = mono
            setTextColor(green); setPadding(dp(10), dp(10), dp(10), dp(10))
            text = ""
            movementMethod = android.text.method.ScrollingMovementMethod.getInstance()
        }
        val outScroll = ScrollView(this).apply {
            addView(logView)
            background = null
        }
        mainScroll = outScroll
        outFrame.addView(outScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(outFrame, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(4) })
        logAnchor = outFrame
        quickAccessAnchor = addrRow
        diagnosticAnchor = groupRow

        // ===== Нижняя строка: действия с журналом =====
        val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottomRow.addView(vcdsButton("Save log", dimGreen) { shareLogs() }, vcdsBtnParams())
        bottomRow.addView(vcdsButton("Logs…", dimGreen) { openLogBrowser() }, vcdsBtnParams())
        bottomRow.addView(vcdsButton("Clear screen", dimGreen) { logView.text = "" }, vcdsBtnParams())
        root.addView(bottomRow, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
    }

    /** Кнопка в стиле VCDS: тёмный фон, цветная моноширинная рамка-подсветка при нажатии. */
    private fun vcdsButton(label: String, color: Int, action: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; typeface = android.graphics.Typeface.MONOSPACE
        minHeight = dp(42)
        setTextColor(color)
        background = rounded(0xFF141414.toInt(), 4, 4, 4, 4)
        setPadding(dp(6), dp(4), dp(6), dp(4))
        setOnClickListener { action() }
    }

    private fun vcdsBtnParams() = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) }

    /** Подключение по текущему адресу из поля Adresse (если сеанса ещё нет). */
    private fun ensureSession(client: Kw1281Client) {
        val addr = currentAddress()
        client.connectAuto(addr, diagnosticSettings.baudRate, diagnosticSettings.startBaudRate,
            fastInit = diagnosticSettings.fastInit, protocolMode = diagnosticSettings.protocolMode)
    }

    private fun currentAddress(): Int {
        val raw = if (::addressField.isInitialized) addressField.text.toString().trim() else ""
        val parsed = raw.toIntOrNull(16) ?: raw.toIntOrNull()
        return (parsed ?: diagnosticSettings.ecuAddress).coerceIn(0, 127)
    }

    private fun readMeasuringBlock() {
        val group = if (::groupInput.isInitialized) groupInput.text.toString().trim().toIntOrNull() else null
        if (group == null || group !in 0..255) { appendLog("Укажи номер группы 000-255"); return }
        runDiagnostic("Группа %03d".format(group)) { client -> ensureSession(client); client.readGroup(group) }
    }

    private fun confirmClearFaults() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Clear Fault Codes — адрес %02X?".format(currentAddress()))
            .setMessage("Сначала сохрани ошибки (Read Fault Codes → Save log). Очистка удаляет сохранённые DTC, но не устраняет неисправность.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                runDiagnostic("Очистка DTC") { client -> ensureSession(client); client.clearFaults() }
            }
            .show()
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
        // Режим протокола — как галки KWP-1281/KWP-2000 у Васи; нужен для Magneti Marelli 4MV.
        val protoMode = dropdownField(fields, "Режим протокола (галки KWP у Васи)",
            listOf(
                "Авто (подбирается по Start Baud)" to 0,
                "KW1281 slow-init (Magneti Marelli 4MV/4ME7, moto)" to 1,
                "KWP2000 fast-init (VAG, break на 9600)" to 2
            ),
            current.protocolMode)
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
                appendLog("Настройки сброшены по умолчанию (как в Васе): ${diagnosticSettings.describe()}")
                Toast.makeText(this, "Настройки по умолчанию восстановлены", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val a = address.text.toString().toIntOrNull()
                        val pm = protoMode()
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
                            protocolMode = pm
                        )
                        VasyaProtocol.resetCache()
                        diagnosticSettings.save(this)
                        if (::addressField.isInitialized) addressField.setText("%02X".format(a!!))
                        kwClient = null
                        appendLog("Настройки сохранены: ${diagnosticSettings.describe()}")
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun showNavigationMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Select Control Module")
        popup.menu.add(0, 2, 1, "Autoscan")
        popup.menu.add(0, 3, 2, "Protocol settings…")
        popup.menu.add(0, 4, 3, "About")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> addressField.requestFocus()
                2 -> scanControllers()
                3 -> showDiagnosticSettings()
                4 -> showAbout()
            }
            true
        }
        popup.show()
    }

    private fun showAbout() {
        android.app.AlertDialog.Builder(this)
            .setTitle("VCDS-KW1281 (VAG Diag Android)")
            .setMessage("""Интерфейс и логика инициализации — в стиле VCDS / Vasya Diagnost.

Автомобиль: Volkswagen Golf IV 1.6 16V BCB.
Адаптер: USB KKL / CH340. Протокол: KW1281 (K-Line).

Реализовано: fast-init 9600 / slow-init 5 бод, Autoscan блоков, идентификация, DTC (чтение/очистка), измерительные группы (сырые байты), базовые установки (ReadCoding 0x3E, только чтение).
Не реализовано: запись кодов, адаптации, live-графики. Совместимость проверяется на реальном автомобиле.""")
            .setPositiveButton("OK", null)
            .show()
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
                    val handshake = client.connectAuto(address, baud, diagnosticSettings.startBaudRate, fastInit = diagnosticSettings.fastInit, protocolMode = diagnosticSettings.protocolMode)
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
                appendLog("Autoscan: опрос ${targets.size} адресов KW1281 (скорость подбирается по адресу, как в Васе). Может занять несколько минут…")
                appendLog("Результат опроса KW1281: успешно установлено соединений ${found.size} из ${targets.size}.")
                if (found.isNotEmpty()) appendLog("ОТВЕТИЛИ:\n" + found.joinToString("\n\n"))
                appendLog("НЕ ОТВЕТИЛИ ПО KW1281 (это не доказывает отсутствие блока):\n" + missed.joinToString("\n"))
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

    private fun appendLog(message: String) {
        writeLogToFile(message)
        // журнал на экран обновляем только через UI-поток (appendLog может вызываться из рабочих потоков)
        runOnUiThread { appendLogUi(message) }
    }

    private fun appendLogUi(message: String) {
        if (!uiActive || !::logView.isInitialized) return
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logView.append("[$stamp] $message\n")
        // автопрокрутка вниз, чтобы последние строки всегда были видны
        try { (logView.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN) } catch (_: Exception) {}
    }

    /** Каталог internal files/logs — доступен FileProvider'у для отправки. */
    private fun logsDir(): File? = try {
        val dir = File(filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        if (dir.isDirectory && dir.canWrite()) dir else null
    } catch (_: Exception) { null }

    /** Текущий лог-файл сессии; создаётся при первом обращении, при отказе возвращает null. */
    private fun logFile(): File? {
        currentLogFile?.let { if (it.isFile) return it }
        val dir = logsDir() ?: return null
        return try {
            val f = File(dir, "vagdiag-${fileNameStamp.format(Date())}.log")
            f.createNewFile()
            currentLogFile = f
            f
        } catch (_: Exception) { null }
    }

    private fun writeLogToFile(message: String) {
        val f = logFile() ?: return
        try {
            f.appendText("${fileStamp.format(Date())} | $message\n")
        } catch (_: Exception) {}
    }

    /** Все файлы журналов, отсортированные по времени изменения (свежие первыми). */
    private fun allLogFiles(): List<File> {
        val dir = logsDir() ?: return emptyList()
        return (dir.listFiles { file -> file.isFile && file.name.startsWith("vagdiag-") }?.toList()
            ?: emptyList()).sortedByDescending { it.lastModified() }
    }

    /** Отправка файла журнала через FileProvider (Telegram/почта и т.п.) без бега к машине. */
    private fun shareLogs() {
        val files = allLogFiles()
        if (files.isEmpty()) {
            // fallback: то, что на экране
            val content = if (::logView.isInitialized) logView.text.toString() else ""
            if (content.isBlank()) { Toast.makeText(this, "Журнал пуст", Toast.LENGTH_SHORT).show(); return }
            copyToClipboard(content)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "VAG Diag — журнал (экран)")
                putExtra(Intent.EXTRA_TEXT, content)
                clipData = ClipData.newPlainText("vag-log", content)
            }
            startActivity(Intent.createChooser(intent, "Отправить журнал"))
            return
        }
        io.execute {
            try {
                val newest = files.first()
                val uri = logUri(newest)
                val extras = ArrayList<Uri>()
                for (f in files.drop(1).take(9)) {
                    try { extras.add(logUri(f)) } catch (_: Exception) {}
                }
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "text/plain"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(extras.apply { add(0, uri) }))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newUri(contentResolver, "vag-log", uri)
                    putExtra(Intent.EXTRA_SUBJECT, "VAG Diag — журналы (${files.size})")
                    putExtra(Intent.EXTRA_TEXT, "Файлы логов VAG Diag Android. Всего файлов: ${files.size}, свежайший: ${newest.name}")
                }
                runOnUiThread {
                    try { startActivity(Intent.createChooser(intent, "Отправить логи (можно выбрать несколько файлов)")) }
                    catch (e: Exception) { Toast.makeText(this, "Не удалось открыть меню отправки: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Ошибка подготовки файлов: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** Диалог со списком сохранённых журналов: отправить / копировать / удалить старые. */
    private fun openLogBrowser() {
        val files = allLogFiles()
        if (files.isEmpty()) { Toast.makeText(this, "Нет сохранённых журналов", Toast.LENGTH_SHORT).show(); return }
        val labels = files.map { f ->
            val sizeKb = (f.length() / 1024).coerceAtLeast(1)
            "${SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(f.lastModified()))}  ${f.name}  (${sizeKb} КБ)"
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Сохранённые журналы (${files.size})")
            .setItems(labels) { _, idx -> showLogActions(files[idx]) }
            .setNeutralButton("Отправить ВСЕ сразу") { _, _ -> shareLogs() }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun showLogActions(file: File) {
        val options = arrayOf("Отправить этот файл", "Скопировать всё в буфер", "Удалить файл")
        android.app.AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> {
                        try {
                            val uri = logUri(file)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                clipData = ClipData.newUri(contentResolver, "vag-log", uri)
                            }
                            startActivity(Intent.createChooser(intent, "Отправить ${file.name}"))
                        } catch (e: Exception) { Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show() }
                    }
                    1 -> {
                        val text = try { file.readText() } catch (e: Exception) { "(ошибка чтения: ${e.message})" }
                        copyToClipboard(text)
                        Toast.makeText(this, "Скопировано ${text.length} симв.", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        if (file.delete()) Toast.makeText(this, "Удалён", Toast.LENGTH_SHORT).show()
                        else Toast.makeText(this, "Не удалось удалить", Toast.LENGTH_SHORT).show()
                        if (currentLogFile == file) currentLogFile = null
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun logUri(file: File): Uri =
        DiagLogFileProvider.uriForFile(this, "$packageName.fileprovider", file)

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("vag-log", text))
        } catch (_: Exception) {}
    }

    /** Ограничивает папку logs ~15 МБ, удаляя самые старые файлы. Вызывается при старте. */
    private fun pruneOldLogs() {
        val files = allLogFiles() // свежие первыми
        var total = files.sumOf { it.length() }
        for (f in files.asReversed()) { // от старых к новым
            if (total <= 15_000_000) break
            try { if (f.delete()) total -= f.length() } catch (_: Exception) {}
        }
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
