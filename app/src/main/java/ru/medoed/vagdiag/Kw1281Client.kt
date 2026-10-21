package ru.medoed.vagdiag

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException

/**
 * Minimal KW1281 tester implementation. Must be validated against a real ECU/cable before relying on it.
 * Timing knobs (Echo delay, BLK, Int, Delay Char int), Start Baud and Debug level follow the
 * advanced-option model of Vasya Diagnost / kw1281test.
 */
class Kw1281Client(
    private val port: UsbSerialPort,
    private val log: (String) -> Unit,
    private val responseTimeoutMs: Int = 1000,
    private val initTimeoutMs: Int = 5000,
    private val echoDelayMs: Int = 5,
    private val blockDelayMs: Int = 30,
    private val interByteDelayMs: Int = 5,
    private val charInterDelayMs: Int = 0,
    private val debugLevel: Int = 0,
    private val initRetryTimes: Int = 2,
    /** Optional stage callback for the on-screen progress dialog (called from the IO thread). */
    private val onStage: ((String) -> Unit)? = null
) {
    data class Block(val counter: Int, val title: Int, val data: ByteArray) {
        fun hex(): String = (byteArrayOf(counter.toByte(), title.toByte()) + data).joinToString(" ") { "%02X".format(it) }
    }

    private var counter: Int = 0
    private var connected = false

    /** Debug level 1+: normal byte-level TX/RX logging; level 2 adds extra handshake detail. */
    private fun dbg(message: String) { if (debugLevel >= 1) log(message) }
    private fun dbgVerbose(message: String) { if (debugLevel >= 2) log(message) }
    private fun pause(ms: Int) { if (ms > 0) Thread.sleep(ms.toLong()) }
    /** Reports the current protocol stage to the progress dialog (if wired up). */
    private fun stage(message: String) { onStage?.invoke(message) }

    /**
     * Automatic connection attempt, Vasya Diagnost style: the ECU address is kept, but the
     * init mode and post-init UART rate are auto-selected. Start Baud 5 = KW1281 slow-init;
     * Start Baud 9600 (or fastInit=true) = KWP fast-init break on 9600 — exactly what makes
     * Vasya work with CH340 cables that cannot produce 5-baud frames.
     */
    @Synchronized
    fun connectAuto(address: Int = 0x01, preferredBaudRate: Int = 0, preferredStartBaud: Int = 5, initRetries: Int = initRetryTimes, fastInit: Boolean = false): String {
        val rates = VasyaProtocol.baudCandidates(if (fastInit) 9600 else preferredStartBaud, address, preferredBaudRate.takeIf { it != 0 })
        val errors = mutableListOf<String>()
        dbgVerbose("Автоопределение (как в Васе): кандидаты пост-init скоростей: ${rates.joinToString()} (${if (fastInit) "fast-init 9600" else "slow-init, Start Baud=$preferredStartBaud"})")
        for (rate in rates) {
            connected = false
            log("Автоопределение ЭБУ: адрес %02X, проверка %d бод".format(address, rate))
            stage("Адрес %02X · инициализация на %d бод…".format(address, rate))
            try {
                val result = connect(address, rate, startBaudRate = preferredStartBaud, initRetries = initRetries, fastInit = fastInit)
                log("Автоопределение: ответ получен на %d бод".format(rate))
                return "Соединение установлено с адресом %02X на %d бод\n%s".format(address, rate, result)
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                errors += "$rate бод: $reason"
                log("Автоопределение: %d бод — нет соединения (%s)".format(rate, reason))
            }
        }
        throw IOException("Не удалось установить соединение с адресом %02X. Проверены скорости: %s\n".format(address, rates.joinToString()) + errors.joinToString("\n"))
    }

    @Synchronized
    fun connect(address: Int = 0x01, baudRate: Int = 10400, startBaudRate: Int = 5, initRetries: Int = initRetryTimes, fastInit: Boolean = false): String {
        connected = false
        if (address !in 0..0x7F) throw IllegalArgumentException("KW1281 address must be 0..127")
        if (baudRate !in setOf(4800, 9600, 10400)) throw IllegalArgumentException("Supported post-init baud rates: 4800, 9600, 10400")
        // Как в Васе: Start Baud 5 = slow-init, Start Baud 9600 = fast-init (break-импульс).
        if (!fastInit && startBaudRate !in 1..5 && startBaudRate < 4800) {
            throw IllegalArgumentException("Start Baud должен быть 5 (slow-init) или 9600 (fast-init), как в Васе Диагносте")
        }
        val useFast = fastInit || startBaudRate >= 4800
        // Каскад методов инициализации (подбор «как в Васе»): fast break -> реальные 5 бод -> serial-break.
        setLineParamsQuietly(if (useFast) VasyaProtocol.FAST_INIT_BAUD else baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        val method = VasyaProtocol.chooseMethod(port, if (useFast) 9600 else startBaudRate, { log(it) }, { dbg(it) })
        val effectiveBaud = if (method == VasyaProtocol.InitMethod.FAST_INIT_9600) VasyaProtocol.FAST_INIT_BAUD else baudRate
        if (effectiveBaud != baudRate) log("Пост-init скорость переключена на $effectiveBaud бод (метод инициализации: ${method.name})")
        dbgVerbose("Инициализация: метод=${method.name}, пост-init $effectiveBaud бод, echo=${echoDelayMs}мс, BLK=${blockDelayMs}мс, Int=${interByteDelayMs}мс, DelayCharInt=${charInterDelayMs}мс")
        pendingRx.clear()
        var lastError: IOException? = null
        var sync: Int? = null
        var attempt = 0
        val retries = initRetries.coerceIn(1, 5)
        // Как в kw1281test/Vasya Diagnost: при отсутствии синхры повторяем init с нуля —
        // часть ЭБУ просыпается только со второго импульса.
        while (sync == null && attempt < retries) {
            attempt++
            stage("Отправка инициализации (попытка $attempt/$retries)…")
            if (!firstTry()) {
                log("Нет ответа ЭБУ за ${initTimeoutMs} мс (попытка $attempt/$retries) — повторяю инициализацию…")
                // Пауза между попытками, как в kw1281test: даём ЭБУ отпустить линию.
                Thread.sleep(500L * attempt)
            }
            setLineParamsQuietly(effectiveBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            VasyaProtocol.sendWakeUp(port, method, address, effectiveBaud, initTimeoutMs * 2, this::stage, this::dbgVerbose)
            // Возврат на пост-init скорость ДО чтения: ЭБУ шлёт синхру сразу после старта
            // именно на ней (10400/9600), читать на 5 бод смысла нет.
            setLineParamsQuietly(effectiveBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            pause(blockDelayMs)
            drainRx()
            stage("Ожидание синхры 0x55 (до ${initTimeoutMs} мс)…")
            try {
                sync = readByte(initTimeoutMs)
            } catch (e: IOException) {
                lastError = e
            }
        }
        if (sync == null) {
            // Гарантированно отпускаем линию (break/DTR=idle), иначе после неудачи ЭБУ остаётся
            // «в заложниках» у прижатой K-Line и следующие попытки молчат (симптом Васи:
            // два щелчка реле — тишина).
            try { port.setBreak(false) } catch (_: Exception) { }
            try { port.setDTR(true) } catch (_: Exception) { }
            throw IOException(buildNoConnectionHint(address, startBaudRate, effectiveBaud, method != VasyaProtocol.InitMethod.FIVE_BAUD_FRAME), lastError)
        }
        // Как в kw1281test/Vasya: допустимы стартовые мусор-байты 0x00/0xFF сразу после init —
        // ЭБУ иногда «пробует» линию перед синхрой. Ждём настоящий 0x55 ещё ~400 мс.
        var syncByte = sync!!
        while (syncByte != 0x55 && (syncByte == 0xFF || syncByte == 0x00)) {
            dbgVerbose("Стартовый байт %02X (мусор/idle) — продолжаю ждать синхру 0x55…".format(syncByte))
            try {
                syncByte = readByte(400)
            } catch (e: IOException) {
                throw IOException("ЭБУ прислал 0x%02X вместо синхры 0x55. Похоже на мусор или неверную скорость после инициализации — проверь контакт K-Line и попробуй другой режим (Start Baud 9600 = fast-init).".format(sync), e)
            }
        }
        if (syncByte != 0x55) {
            throw IOException("ЭБУ прислал 0x%02X вместо синхры 0x55. Проверь адрес блока и режим инициализации (slow/fast).".format(syncByte))
        }
        stage("Синхра получена — читаю keywords…")
        val key1 = readByte(responseTimeoutMs)
        val key2 = readByte(responseTimeoutMs)
        // Как kw1281test/Vasya: принимаем корректную пару complement-keywords (key1^0xFF==key2)
        // или классические D1 D1; произвольный набор — шум/неверная скорость.
        if (((key1.inv() and 0xFF) != key2) && !(key1 == 0xD1 && key2 == 0xD1)) {
            throw IOException("Некорректные ключевые слова handshake (%02X %02X) — линия шумит или скорость неверна".format(key1, key2))
        }
        val protocolVersion = ((key2 and 0x7F) shl 7) or (key1 and 0x7F)
        log("Sync 55; keywords %02X %02X; calculated protocol KWP%04d; baud=%d".format(key1, key2, protocolVersion, baudRate))
        if (protocolVersion >= 2000) {
            connected = false
            throw IOException("ЭБУ сообщил протокол KWP$protocolVersion. Эта версия приложения поддерживает только KW1281; KWP2000 пока не реализован.")
        }
        dbgVerbose("Handshake: ACK на keyword2=%02X после паузы" .format(key2.inv() and 0xFF))
        pause(echoDelayMs)
        writeRaw((key2.inv() and 0xFF))
        pause(blockDelayMs.coerceAtLeast(50))
        // Часть ЭБУ сразу шлёт первый блок без подтверждения нашего эха — не считаем это ошибкой.
        drainRxIfIdle()
        // Как в kw1281test: после handshake ЭБУ ждёт ACK на линию до отправки 0x09,
        // иначе он «закрывает» сеанс и приложение зря ловит таймауты.
        pause(echoDelayMs)
        writeRaw(0xC1) // AckHandshake
        pause(blockDelayMs.coerceAtLeast(50))
        stage("Handshake KW1281 — обмен ACK…")
        val initial = mutableListOf<Block>()
        var handshakeFinished = false
        // Как в kw1281test: ЭБУ ждёт ACK на линию до отправки 0x09, иначе он «закрывает» сеанс.
        repeat(12) {
            if (handshakeFinished) return@repeat
            val block = receiveBlock()
            initial += block
            counter = (block.counter + 1) and 0xFF
            stage("Получен блок %02X, подтверждаю…".format(block.title))
            if (block.title == 0x09 || block.title == 0x0A) handshakeFinished = true
            else {
                // Тот же ACK между блоками, что и в kw1281test/Vasya
                pause(echoDelayMs)
                writeRaw(0xC1)
                sendBlock(0x09)
            }
        }
        if (!handshakeFinished) throw IOException("ECU did not finish initial KW1281 handshake")
        connected = true
        log("KW1281 session opened at address %02X, baud=%d".format(address, baudRate))
        return "Соединение KW1281 установлено с адресом %02X на %d бод\nНачальные блоки:\n".format(address, baudRate) + initial.joinToString("\n") { "title=%02X data=%s".format(it.title, it.data.joinToString(" ") { b -> "%02X".format(b) }) }
    }

    @Synchronized
    fun readIdentification(): String {
        ensureConnected()
        val blocks = request(0x00)
        val text = blocks.filter { it.title == 0xF6 }.flatMap { it.data.toList() }
            .map { it.toInt() and 0xFF }.filter { it in 0x20..0x7E || it == 0x0A || it == 0x0D }
            .map { it.toChar() }.joinToString("").trim()
        return if (text.isNotBlank()) text else blocks.joinToString("\n") { "Block %02X: %s".format(it.title, it.hex()) }
    }

    @Synchronized
    fun readFaults(): String {
        ensureConnected()
        val blocks = request(0x07)
        val faults = mutableListOf<String>()
        blocks.filter { it.title == 0xFC }.forEach { b ->
            val d = b.data
            var i = 0
            while (i + 2 < d.size) {
                val code = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
                val status = d[i + 2].toInt() and 0xFF
                if (!(code == 0xFFFF && status == 0x88)) faults += "DTC %04X · статус %02X".format(code, status)
                i += 3
            }
        }
        return if (faults.isEmpty()) "Блок не вернул кодов неисправностей либо ответ не распознан.\nСырые ответы:\n" + blocks.joinToString("\n") { "${it.title.toString(16).uppercase()}: ${it.hex()}" }
        else faults.joinToString("\n")
    }

    @Synchronized
    fun clearFaults(): String {
        ensureConnected()
        val blocks = request(0x05)
        return if (blocks.any { it.title == 0xFC }) {
            "ЭБУ ответил блоком кодов после команды очистки; перечитай ошибки, чтобы подтвердить результат.\n" + blocks.joinToString("\n") { "${it.title.toString(16).uppercase()}: ${it.hex()}" }
        } else {
            "Команда очистки отправлена. Ответ ЭБУ не подтверждает результат однозначно:\n" + blocks.joinToString("\n") { "${it.title.toString(16).uppercase()}: ${it.hex()}" }
        }
    }

    @Synchronized
    fun readGroup(group: Int): String {
        ensureConnected()
        require(group in 0..255) { "Group must be 0..255" }
        val blocks = request(0x29, group)
        return blocks.joinToString("\n") { b ->
            if (b.title == 0xE7 && b.data.isNotEmpty()) {
                val d = b.data
                val triples = d.asList().chunked(3).filter { it.size == 3 }
                "Группа %03d — сырые значения (тип, A, B):\n".format(group) + triples.joinToString("\n") {
                    "%02X  %02X  %02X".format(it[0].toInt() and 255, it[1].toInt() and 255, it[2].toInt() and 255)
                } + "\nДекодирование формул/единиц ещё не подтверждено для этого ЭБУ."
            } else "Ответ ECU title=%02X data=%s".format(b.title, b.data.joinToString(" ") { "%02X".format(it) })
        }
    }

    @Synchronized
    fun disconnect() {
        if (!connected) return
        try { sendBlock(0x06) } catch (_: Exception) { }
        connected = false
    }

    private fun request(title: Int, vararg payload: Int): List<Block> {
        stage("Отправка команды…")
        sendBlock(title, *payload)
        val result = mutableListOf<Block>()
        repeat(32) {
            stage("Ожидание ответа ЭБУ…")
            val b = receiveBlock()
            result += b
            if (b.title == 0x09 || b.title == 0x0A) return result
            stage("Получен блок %02X — запрашиваю следующий…" .format(b.title))
            sendBlock(0x09)
        }
        throw IOException("Too many blocks; ECU response did not terminate")
    }

    private fun sendBlock(title: Int, vararg payload: Int) {
        require(payload.size <= 252) { "KW1281 block payload is too large" }
        val bytes = mutableListOf(payload.size + 3, counter, title)
        bytes.addAll(payload.map { it and 0xFF })
        counter = (counter + 1) and 0xFF
        dbg("TX блок: ${bytes.joinToString(" ") { "%02X".format(it) }} 03")
        bytes.forEach { value ->
            writeRaw(value)
            pause(echoDelayMs)
            val ack = readByte(responseTimeoutMs)
            if (ack != (value.inv() and 0xFF)) throw IOException("KW1281 ACK mismatch for %02X: got %02X".format(value, ack))
            dbgVerbose("TX %02X -> ACK %02X ok".format(value, ack))
            pause(interByteDelayMs + charInterDelayMs)
        }
        writeRaw(0x03) // end byte is not acknowledged
        pause(blockDelayMs.coerceAtLeast(10))
    }

    private fun receiveBlock(): Block {
        val length = readAndAck()
        if (length < 3) throw IOException("Invalid KW1281 block length: $length")
        val receivedCounter = readAndAck()
        val title = readAndAck()
        val data = ByteArray((length - 3).coerceAtLeast(0))
        for (i in data.indices) data[i] = readAndAck().toByte()
        val end = readByte(responseTimeoutMs)
        if (end != 0x03) throw IOException("Invalid KW1281 block terminator %02X".format(end))
        counter = (receivedCounter + 1) and 0xFF
        dbg("RX блок: ${((byteArrayOf(receivedCounter.toByte(), title.toByte()) + data)).joinToString(" ") { "%02X".format(it) }}")
        pause(blockDelayMs)
        return Block(receivedCounter, title, data)
    }

    private fun readAndAck(): Int {
        val b = readByte(responseTimeoutMs)
        pause(echoDelayMs)
        writeRaw(b.inv() and 0xFF)
        pause(interByteDelayMs)
        return b
    }

    private fun readByte(timeoutMs: Int): Int {
        pendingRx.removeFirstOrNull()?.let { return it }
        val buffer = ByteArray(1)
        val n = port.read(buffer, timeoutMs)
        if (n != 1) throw IOException("Timeout waiting for K-Line byte")
        return buffer[0].toInt() and 0xFF
    }

    private fun writeRaw(value: Int) {
        dbgVerbose("TX байт: %02X".format(value))
        port.write(byteArrayOf(value.toByte()), 500)
    }

    private fun ensureConnected() { if (!connected) throw IOException("Сначала подключись к ЭБУ двигателя") }

    /**
     * setParameters() с мягкой обработкой ошибок: часть CH340-чипов отказывается ставить
     * нестандартные скорости (5 бод) и бросает исключение — в этом случае логируем и
     * продолжаем, fallback-путь (UART-кадр 7O1) всё равно попытается сработать.
     */
    /** Байты, прочитанные вне readByte (толерантный handshake), — читаются первыми. */
    private val pendingRx = ArrayDeque<Int>()

    /** Собирает байты, пришедшие за короткое окно, без ошибки по таймауту (терпимость к «молчаливым» ЭБУ). */
    private fun drainRxIfIdle() {
        try {
            val buf = ByteArray(1)
            while (port.read(buf, 30) > 0) { pendingRx.addLast(buf[0].toInt() and 0xFF) }
        } catch (_: Exception) { }
    }

    /** Первый прогон выбранного метода (для сообщения о повторе). */
    private var triedOnce = false
    private fun firstTry(): Boolean = triedOnce.also { triedOnce = true }.let { !it }

    private fun setLineParamsQuietly(baud: Int, dataBits: Int, stopBits: Int, parity: Int) {
        try {
            port.setParameters(baud, dataBits, stopBits, parity)
        } catch (e: Exception) {
            dbg("setParameters($baud бод) не удался (${e.javaClass.simpleName}: ${e.message}) — драйвер ограничивает скорости; буду использовать доступный путь инициализации")
        }
    }

    /** Сбрасывает мусор из буфера приёма после смены скорости, чтобы не словить ложный «sync». */
    private fun drainRx() {
        try {
            val buf = ByteArray(64)
            while (port.read(buf, 0) > 0) { /* discard */ }
        } catch (_: Exception) { }
    }

    /** Человекочитаемая подсказка, когда ЭБУ так и не ответил (порт открыт — «реле щёлкнуло», связи нет). */
    private fun buildNoConnectionHint(address: Int, startBaudRate: Int, baudRate: Int, usedBitBangFallback: Boolean): String = buildString {
        appendLine("ЭБУ не ответил на slow-init (адрес %02X, старт %d бод, пост-init %d бод). Порт открыт, но K-Line молчит.".format(address, startBaudRate, baudRate))
        appendLine("Частые причины:")
        if (usedBitBangFallback) appendLine("  драйвер не принял 5 бод (аппартное ограничение CH340: минимум ≈2339 бод) — slow-init выполнен serial-break кадром; линия гарантированно отпущена в idle.")
        appendLine("• Зажигание выключено или ЭБУ не питается (проверь предохранитель, включи зажигание перед подключением).")
        appendLine("• Неверный адрес блока или другой протокол (KWP2000/CAN вместо KW1281).")
        appendLine("• Попробуй: Start Baud 9600 (fast-init, как в Васе когда он подключается), увеличить Init retry times до 3–5, Debug level=2 для подробного лога.")
    }.trimEnd()
}
