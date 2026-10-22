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
    fun isConnected(): Boolean = connected

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
    fun connectAuto(address: Int = 0x01, preferredBaudRate: Int = 0, preferredStartBaud: Int = 5, initRetries: Int = initRetryTimes, fastInit: Boolean = false, protocolMode: Int = 0): String {
        val forceSlow = protocolMode == 1
        val rates = VasyaProtocol.baudCandidates(if (fastInit && !forceSlow) 9600 else preferredStartBaud, address,
            preferredBaudRate.takeIf { it != 0 }, forceSlowInit = forceSlow)
        val errors = mutableListOf<String>()
        dbgVerbose("Автоопределение (как в Васе): кандидаты пост-init скоростей: ${rates.joinToString()} (${if (forceSlow) "принудительный slow-init KW1281" else if (fastInit) "fast-init 9600" else "slow-init, Start Baud=$preferredStartBaud"})")
        for (rate in rates) {
            connected = false
            log("Автоопределение ЭБУ: адрес %02X, проверка %d бод".format(address, rate))
            stage("Адрес %02X · инициализация на %d бод…".format(address, rate))
            try {
                val result = connect(address, rate, startBaudRate = preferredStartBaud, initRetries = initRetries, fastInit = fastInit && !forceSlow, protocolMode = protocolMode)
                log("Автоопределение: ответ получен на %d бод".format(rate))
                return "Соединение установлено с адресом %02X на %d бод\n%s".format(address, rate, result)
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                errors += "$rate бод: $reason"
                log("Автоопределение: %d бод — нет соединения (%s)".format(rate, reason))
            }
        }
        throw IOException("Не удалось установить соединение с адресом %02X. Проверены скорости: %s\n".format(address, rates.joinToString()) + errors.joinToString("\n") + MM4MV_HINT)
    }

    @Synchronized
    fun connect(address: Int = 0x01, baudRate: Int = 10400, startBaudRate: Int = 5, initRetries: Int = initRetryTimes, fastInit: Boolean = false, protocolMode: Int = 0): String {
        connected = false
        if (address !in 0..0x7F) throw IllegalArgumentException("KW1281 address must be 0..127")
        if (baudRate !in setOf(4800, 9600, 10400)) throw IllegalArgumentException("Supported post-init baud rates: 4800, 9600, 10400")
        // Как в Васе: Start Baud 0 = default (fast-init на CH340), 5 = slow-init, 9600 = fast-init.
        // protocolMode=1 принудительно включает slow-init даже при Start Baud 0/9600 (Magneti Marelli 4MV).
        if (protocolMode != 1 && !fastInit && startBaudRate != 0 && startBaudRate !in 1..5 && startBaudRate < 4800) {
            throw IllegalArgumentException("Start Baud должен быть 0 (default), 5 (slow-init) или 9600 (fast-init), как в Васе Диагносте")
        }
        val useFast = fastInit || (startBaudRate != 5 && startBaudRate >= 4800 && protocolMode != 1) || (startBaudRate == 0 && protocolMode != 1)
        // Каскад методов инициализации (подбор «как в Васе»): fast break -> реальные 5 бод -> serial-break.
        setLineParamsQuietly(if (useFast) VasyaProtocol.FAST_INIT_BAUD else baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        val method = VasyaProtocol.chooseMethod(port, if (useFast) 9600 else startBaudRate, protocolMode, { log(it) }, { dbg(it) })
        val effectiveBaud = if (method == VasyaProtocol.InitMethod.FAST_INIT_9600) VasyaProtocol.FAST_INIT_BAUD else baudRate
        if (effectiveBaud != baudRate) log("Пост-init скорость переключена на $effectiveBaud бод (метод инициализации: ${method.name})")
        dbgVerbose("Инициализация: метод=${method.name}, пост-init $effectiveBaud бод, echo=${echoDelayMs}мс, BLK=${blockDelayMs}мс, Int=${interByteDelayMs}мс, DelayCharInt=${charInterDelayMs}мс")
        pendingRx.clear()
        var lastError: IOException? = null
        var sync: Int? = null
        val retries = initRetries.coerceIn(1, 5)
        // Как в kw1281test/Vasya Diagnost: при отсутствии синхры повторяем init с нуля —
        // часть ЭБУ просыпается только со второго импульса.
        for (attempt in 1..retries) {
            stage("Отправка инициализации (попытка $attempt/$retries)…")
            setLineParamsQuietly(effectiveBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            VasyaProtocol.sendWakeUp(port, method, address, effectiveBaud, writeTimeoutMs = 1000, this::stage, this::dbgVerbose)
            // Возврат на пост-init скорость ДО чтения: ЭБУ шлёт синхру сразу после старта
            // именно на ней (10400/9600), читать на 5 бод смысла нет.
            setLineParamsQuietly(effectiveBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            pause(blockDelayMs)
            drainRx()
            stage("Ожидание синхры 0x55 (до ${initTimeoutMs} мс)…")
            try {
                sync = readByte(initTimeoutMs)
                break
            } catch (e: IOException) {
                lastError = e
                if (attempt < retries) {
                    log("Нет ответа ЭБУ за ${initTimeoutMs} мс (попытка $attempt/$retries) — отпускаю линию и повторяю инициализацию…")
                    try { port.setBreak(false) } catch (_: Exception) { }
                    try { port.setDTR(true) } catch (_: Exception) { }
                    // Пауза между попытками, как в kw1281test: даём ЭБУ отпустить линию.
                    Thread.sleep(300L * attempt)
                }
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
        // Как в Васе: команда 0x00, читаем блоки F6 (VIN-подобная), F7/F9 (номер детали),
        // FA (кодировка), FB (дата прошивки).
        val blocks = request(0x00)
        fun asciiOf(title: Int): String = blocks.filter { it.title == title }.flatMap { it.data.toList() }
            .map { it.toInt() and 0xFF }.filter { it in 0x20..0x7E }
            .map { it.toChar() }.joinToString("").trim()
        val parts = buildList {
            val vF6 = asciiOf(0xF6); if (vF6.isNotBlank()) add("Вин код (F6): $vF6")
            val vF7 = asciiOf(0xF7); if (vF7.isNotBlank()) add("Номер детали (F7): $vF7")
            val vF9 = asciiOf(0xF9); if (vF9.isNotBlank()) add("Доп. идентификация (F9): $vF9")
            val vFA = asciiOf(0xFA); if (vFA.isNotBlank()) add("Кодирование (FA): $vFA")
            val vFB = asciiOf(0xFB); if (vFB.isNotBlank()) add("Дата прошивки (FB): $vFB")
        }
        if (parts.isNotEmpty()) return parts.joinToString("\n")
        return blocks.joinToString("\n") { "Block %02X: %s".format(it.title, it.hex()) }
    }

    /**
     * Версия ПО ЭБУ — как поле «Версия» в Васе: команда 0x04, ответ приходит блоками F2
     * (три байта версии + ASCII-комментарий).
     */
    @Synchronized
    fun readVersion(): String {
        ensureConnected()
        val blocks = request(0x04)
        val f2 = blocks.filter { it.title == 0xF2 }.flatMap { it.data.toList() }
        if (f2.isEmpty()) return "ЭБУ не вернул блок версии (F2).\nСырые ответы:\n" + blocks.joinToString("\n") { "${it.title.toString(16).uppercase()}: ${it.hex()}" }
        val sb = StringBuilder()
        var i = 0
        while (i < f2.size) {
            val a = f2[i].toInt() and 0xFF
            val b = if (i + 1 < f2.size) f2[i + 1].toInt() and 0xFF else 0
            val c = if (i + 2 < f2.size) f2[i + 2].toInt() and 0xFF else 0
            sb.append("Версия: %d.%d.%d".format(a, b, c))
            i += 3
            val commentStart = i
            while (i < f2.size && (f2[i].toInt() and 0xFF) in 0x20..0x7E) i++
            if (i > commentStart) sb.append(" · ").append(f2.slice(commentStart until i).map { (it.toInt() and 0xFF).toChar() }.joinToString(""))
            sb.append('\n')
            if (i < f2.size && (f2[i].toInt() and 0xFF) == 0x00) i++ // разделитель записей
        }
        return sb.toString().trim()
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
                "Группа %03d — сырые значения (тип, A, B):\n".format(group) + triples.mapIndexed { i, t ->
                    "Значок %d: %02X %02X %02X".format(i + 1, t[0].toInt() and 255, t[1].toInt() and 255, t[2].toInt() and 255)
                }.joinToString("\n") + "\nФормула пересчёта зависит от ЭБУ (в KW1281 таблиц нет; VCDS берёт их из базы). Декодирование не подтверждено."
            } else "Ответ ECU title=%02X data=%s".format(b.title, b.data.joinToString(" ") { "%02X".format(it) })
        }
    }

    /**
     * Basic Settings (как «Basic Settings» в VCDS / «Адаптации» у Васи): команда ReadCoding 0x3E.
     * Ответ приходит блоками title 0xF1; показываем HEX и попытку ASCII-представления.
     */
    @Synchronized
    fun readBasicSettings(): String {
        ensureConnected()
        val blocks = request(0x3E)
        val payload = blocks.filter { it.title == 0xF1 }.flatMap { it.data.toList() }
        if (payload.isEmpty()) return "ЭБУ не вернул данных базовых установок (title 0xF1).\nСырые ответы:\n" + blocks.joinToString("\n") { "${it.title.toString(16).uppercase()}: ${it.hex()}" }
        val hex = payload.joinToString(" ") { "%02X".format(it) }
        val ascii = payload.map { c -> val v = c.toInt() and 0xFF; if (v in 0x20..0x7E) c.toInt().toChar() else '.' }.joinToString("")
        return "Базовые установки (ReadCoding 0x3E), %d байт:\nHEX:  %s\nASCII: %s\nВнимание: запись изменений (WriteCoding 0x3F) намеренно не реализована — неверный код выведет ЭБУ из строя.".format(payload.size, hex, ascii)
    }

    /**
     * Output Test — как кнопка «Output Test» в Васе/VCDS: команда 0x21 без параметров.
     * ЭБУ по очереди включает исполнительные механизмы (вентилятор, форсунки и т.д.),
     * отвечая блоками; показываем сырые блоки — расшифровка зависит от ЭБУ.
     */
    @Synchronized
    fun outputTest(): String {
        ensureConnected()
        val blocks = request(0x21)
        return "Output Test (команда 0x21) — ответов блоков: ${blocks.size}\n" +
            blocks.joinToString("\n") { "title=%02X data=%s".format(it.title, it.hex()) } +
            "\nВнимание: тест активирует исполнительные механизмы — выполняй только на исправном моторе по инструкции."
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
        val modeName = if (startBaudRate == 0) "default/fast-init" else if (startBaudRate >= 4800) "fast-init" else "slow-init"
        appendLine("ЭБУ не ответил (%s; адрес %02X, старт %d бод, пост-init %d бод). Порт открыт, но K-Line молчит.".format(modeName, address, startBaudRate, baudRate))
        appendLine("Частые причины:")
        if (usedBitBangFallback) appendLine("  драйвер не принял 5 бод (аппартное ограничение CH340: минимум ≈2339 бод) — инициализация выполнена программным break-кадром; линия гарантированно отпущена в idle.")
        appendLine("• Зажигание выключено или ЭБУ не питается (проверь предохранитель, включи зажигание перед подключением).")
        appendLine("• Неверный адрес блока или другой протокол (KWP2000/CAN вместо KW1281).")
        appendLine("• Попробуй: Start Baud 0 (default) или 9600 (fast-init, как в Васе когда он подключается), увеличить Init retry times до 3–5, Debug level=2 для подробного лога.")
        append(MM4MV_HINT.trimStart())
    }.trimEnd()

    companion object {
        /** Подсказка по Magneti Marelli 4MV/4ME7 (мотоциклы MV Agusta/Facco и др.). */
        internal const val MM4MV_HINT = """

Для Magneti Marelli 4MV / 4ME7 (moto ECU):
• Протокол — KW1281 slow-init: выбери режим «KW1281 slow-init» в настройках протокола
  (даже если Start Baud стоит 9600 — приложение принудительно сделает медленный старт)
  и скорость пост-init 10400 бод.
• К Line у Marelli часто выведен только на колодку диагностики двигателя — проверь, что
  кабель подключён именно к диагностическому разъёму ЭБУ, а не к CAN-шине.
• Если синхра приходит, но keywords некорректны — попробуй 9600 и 4800 (приложение
  перебирает их само в авто-режиме)."""
    }
}
