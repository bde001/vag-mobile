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
    private val initRetryTimes: Int = 2
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

    /**
     * Automatic connection attempt, Vasya Diagnost style: the ECU address is kept, but the
     * post-init UART rate is auto-selected — for engine/airbag/instrument/central-lock blocks
     * 10400 baud is tried first, for all other blocks 9600 (see VasyaDefaults.preferredBaud),
     * then the remaining KW1281 rates. Slow-init always runs at Start Baud (default 5).
     * A successful KW1281 handshake is the only condition that counts as detection; a timeout
     * is not proof that the ECU is absent or faulty.
     */
    @Synchronized
    fun connectAuto(address: Int = 0x01, preferredBaudRate: Int = 0, preferredStartBaud: Int = 5, initRetries: Int = initRetryTimes): String {
        val rates = VasyaDefaults.baudCandidates(address, preferredBaudRate.takeIf { it != 0 })
        val errors = mutableListOf<String>()
        dbgVerbose("Автоопределение (как в Васе): кандидаты пост-init скоростей: ${rates.joinToString()} (Start Baud влияет на slow-init)")
        for (rate in rates) {
            connected = false
            log("Автоопределение ЭБУ: адрес %02X, проверка %d бод".format(address, rate))
            try {
                val result = connect(address, rate, startBaudRate = preferredStartBaud, initRetries = initRetries)
                log("Автоопределение: ответ получен на %d бод".format(rate))
                return "Автоматически определён ответ ЭБУ на адресе %02X, скорость %d бод\n%s".format(address, rate, result)
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                errors += "$rate бод: $reason"
                log("Автоопределение: %d бод — нет соединения (%s)".format(rate, reason))
            }
        }
        throw IOException("Не удалось установить KW1281-соединение с адресом %02X. Проверены скорости: %s\n".format(address, rates.joinToString()) + errors.joinToString("\n"))
    }

    @Synchronized
    fun connect(address: Int = 0x01, baudRate: Int = 10400, startBaudRate: Int = 5, initRetries: Int = initRetryTimes): String {
        connected = false
        if (address !in 0..0x7F) throw IllegalArgumentException("KW1281 address must be 0..127")
        if (baudRate !in setOf(4800, 9600, 10400)) throw IllegalArgumentException("Supported post-init baud rates: 4800, 9600, 10400")
        // Start Baud controls the bit time of the slow-init wake-up. Valid KW1281 slow-init
        // is ~5 baud; Vasya Diagnost exposes this knob for KKL cables whose actual line
        // timing deviates from the nominal value, so anything in 1..115200 is accepted here.
        if (startBaudRate !in 1..115200) throw IllegalArgumentException("Start Baud must be 1..115200")
        val startBitMs = 1000L / startBaudRate
        // Как в Vasya Diagnost / kw1281test: линия остаётся на пост-init скорости, а
        // slow-init формируется удержанием BREAK нужной длительности (бит ≈ Start Baud).
        // Ставить реальные 5 бод через setParameters драйверы CH340 отказываются
        // ("Unsupported baud rate: 5"), поэтому этот путь основной.
        setLineParamsQuietly(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        // Драйвер может молча игнорировать setBreak() (некоторые сборки CH340). Проверяем
        // это и предупреждаем, но основной путь — BREAK на линии.
        val breakSupported = probeBreak()
        if (!breakSupported) {
            log("ВНИМАНИЕ: USB-драйвер не поддерживает setBreak() — медленный старт имитируется " +
                "UART-кадром 7O1 на 5 бод (как в kw1281test).")
        }
        dbgVerbose("Slow-init: линия $baudRate бод, BREAK-бит ≈${startBitMs} мс (Start Baud=$startBaudRate), echo=${echoDelayMs}мс, BLK=${blockDelayMs}мс, Int=${interByteDelayMs}мс, DelayCharInt=${charInterDelayMs}мс")
        var usedBitBang = false
        var firstAttempt = true
        var lastError: IOException? = null
        var sync: Int? = null
        var attempt = 0
        val retries = initRetries.coerceIn(1, 5)
        // Как в kw1281test/Vasya Diagnost: при отсутствии синхры повторяем slow-init с нуля —
        // часть ЭБУ просыпается только со второго импульса.
        while (sync == null && attempt < retries) {
            attempt++
            if (!firstAttempt) {
                log("Нет ответа ЭБУ за ${initTimeoutMs} мс (попытка $attempt/$retries) — повторяю slow-init…")
                // Пауза между попытками, как в kw1281test: даём ЭБУ отпустить линию.
                Thread.sleep(500L * attempt)
                setLineParamsQuietly(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            }
            firstAttempt = false
            if (breakSupported) {
                try {
                    sendSlowInitBreak(address, startBitMs)
                } catch (_: UnsupportedOperationException) {
                    usedBitBang = true
                    sendSlowInitBitBang(address)
                }
            } else {
                usedBitBang = true
                sendSlowInitBitBang(address)
            }
            pause(blockDelayMs)
            drainRx()
            try {
                sync = readByte(initTimeoutMs)
            } catch (e: IOException) {
                lastError = e
            }
        }
        if (sync == null) throw IOException(buildNoConnectionHint(address, startBaudRate, baudRate, usedBitBang || !breakSupported), lastError)
        val syncByte = sync!!
        if (syncByte != 0x55) {
            val hint = if (syncByte == 0xFF || syncByte == 0x00)
                "\nПохоже на мусор из-за неверной скорости после инициализации — попробуй другую скорость пост-init."
            else ""
            throw IOException("ЭБУ прислал 0x%02X вместо синхры 0x55.$hint Проверь скорость пост-init в настройках и качество контакта K-Line.".format(syncByte))
        }
        val key1 = readByte(responseTimeoutMs)
        val key2 = readByte(responseTimeoutMs)
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
        // Как в kw1281test: после handshake ЭБУ ждёт ACK на линию до отправки 0x09,
        // иначе он «закрывает» сеанс и приложение зря ловит таймауты.
        pause(echoDelayMs)
        writeRaw(0xC1) // AckHandshake
        pause(blockDelayMs.coerceAtLeast(50))
        val initial = mutableListOf<Block>()
        var handshakeFinished = false
        repeat(12) {
            if (handshakeFinished) return@repeat
            val block = receiveBlock()
            initial += block
            counter = (block.counter + 1) and 0xFF
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
        sendBlock(title, *payload)
        val result = mutableListOf<Block>()
        repeat(32) {
            val b = receiveBlock()
            result += b
            if (b.title == 0x09 || b.title == 0x0A) return result
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
    private fun setLineParamsQuietly(baud: Int, dataBits: Int, stopBits: Int, parity: Int) {
        try {
            port.setParameters(baud, dataBits, stopBits, parity)
        } catch (e: Exception) {
            dbg("setParameters($baud бод) не удался (${e.javaClass.simpleName}: ${e.message}) — драйвер ограничивает скорости; буду использовать доступный путь инициализации")
        }
    }

    /**
     * Проверяет, реально ли драйвер умеет setBreak(). Некоторые CH340-сборки бросают
     * UnsupportedOperationException, другие принимают вызов молча, но линию не держат.
     */
    private fun probeBreak(): Boolean = try {
        port.setBreak(true)
        Thread.sleep(5)
        port.setBreak(false)
        true
    } catch (_: Exception) {
        false
    }

    /** Slow-init через BREAK: 8 бит (st + 7 данных + odd parity + stop), бит ≈ startBitMs мс. */
    private fun sendSlowInitBreak(address: Int, startBitMs: Long) {
        val frame = mutableListOf(0) // start bit
        for (i in 0..6) frame.add((address shr i) and 1)
        val dataOnes = (0..6).count { ((address shr it) and 1) == 1 }
        frame += if (dataOnes % 2 == 0) 1 else 0 // odd parity
        frame += 1 // stop bit
        dbgVerbose("Slow-init BREAK: адрес %02X, побитово ${frame.joinToString("")}" /* e.g. 010000011 */)
        for (bit in frame) {
            port.setBreak(bit == 0)
            Thread.sleep(startBitMs)
        }
        port.setBreak(false)
    }

    /**
     * Fallback без BREAK — как в kw1281test/Vasya: реальный UART-кадр на 5 бод, 7E1/7O1.
     * Байт на 5 бод идёт ~2 с, поэтому пишем с удвоенным initTimeout.
     */
    private fun sendSlowInitBitBang(address: Int) {
        try {
            port.setParameters(5, 7, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_ODD)
            dbg("setBreak недоступен — slow-init кадром 7O1 на 5 бод")
            port.write(byteArrayOf((address and 0x7F).toByte()), initTimeoutMs * 2)
        } catch (e: Exception) {
            throw IOException(
                "Не удалось выполнить slow-init: драйвер USB-порта не поддерживает ни setBreak(), " +
                    "ни установку 5 бод. Типичная причина — дешёвый CH340-«KKL» кабель или неподдерживаемый чип.", e
            )
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
        if (usedBitBangFallback) appendLine("  драйвер не поддержал setBreak(), использован fallback-кадр 5 бод — проверь, что линия реально работает на такой низкой скорости.")
        appendLine("• Зажигание выключено или ЭБУ не питается (проверь предохранитель, включи зажигание перед подключением).")
        appendLine("• Неверный адрес блока или другой протокол (KWP2000/CAN вместо KW1281).")
        appendLine("• Попробуй: другую скорость пост-init (9600/4800), увеличить Init retry times до 3–5, Debug level=2 для подробного лога.")
    }.trimEnd()
}
