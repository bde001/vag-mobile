package ru.medoed.vagdiag

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException

/**
 * Minimal KW1281 tester implementation. Must be validated against a real ECU/cable before relying on it.
 * Timing knobs (Echo delay, BLK, Int, Delay Char int), Start Baud, Debug level and KW2 mask follow the
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
    private val kw2Mask: Int = 0xFF
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
     * Automatic connection attempt: keep the selected ECU address, but try the common
     * post-init UART rates without requiring the user to guess one. A successful KW1281
     * handshake is the only condition that counts as detection; a timeout is not proof
     * that the ECU is absent or faulty.
     */
    @Synchronized
    fun connectAuto(address: Int = 0x01, preferredBaudRate: Int = 10400, preferredStartBaud: Int = 5): String {
        val rates = listOf(preferredBaudRate, 10400, 9600, 4800).distinct()
            .filter { it in setOf(4800, 9600, 10400) }
        val errors = mutableListOf<String>()
        dbgVerbose("Автоопределение: кандидаты пост-init скоростей: ${rates.joinToString()} (Start Baud влияет на slow-init)")
        for (rate in rates) {
            connected = false
            log("Автоопределение ЭБУ: адрес %02X, проверка %d бод".format(address, rate))
            try {
                val result = connect(address, rate, startBaudRate = preferredStartBaud)
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
    fun connect(address: Int = 0x01, baudRate: Int = 10400, startBaudRate: Int = 5): String {
        connected = false
        if (address !in 0..0x7F) throw IllegalArgumentException("KW1281 address must be 0..127")
        if (baudRate !in setOf(4800, 9600, 10400)) throw IllegalArgumentException("Supported post-init baud rates: 4800, 9600, 10400")
        // Start Baud controls the bit time of the slow-init wake-up. Valid KW1281 slow-init
        // is ~5 baud; Vasya Diagnost exposes this knob for KKL cables whose actual line
        // timing deviates from the nominal value, so anything in 1..115200 is accepted here.
        if (startBaudRate !in 1..115200) throw IllegalArgumentException("Start Baud must be 1..115200")
        val startBitMs = 1000L / startBaudRate
        port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        dbgVerbose("Slow-init: Start Baud=$startBaudRate (бит ≈${startBitMs} мс), пост-init baud=$baudRate, echo=${echoDelayMs}мс, BLK=${blockDelayMs}мс, Int=${interByteDelayMs}мс, DelayCharInt=${charInterDelayMs}мс, KW2 mask=%02X".format(kw2Mask))
        // KW1281 slow init: 7 data bits, odd parity, 1 stop bit, 5 baud.
        // Some USB-serial drivers (including some CH340 builds) do not implement setBreak().
        // In that case, fall back to a real 5-baud UART frame instead of crashing with
        // UnsupportedOperationException. A 5-baud byte takes about two seconds, so use 5 s timeout.
        try {
            val frame = mutableListOf(0) // start bit
            for (i in 0..6) frame.add((address shr i) and 1)
            val dataOnes = (0..6).count { ((address shr it) and 1) == 1 }
            frame += if (dataOnes % 2 == 0) 1 else 0 // odd parity
            frame += 1 // stop bit
            for (bit in frame) {
                port.setBreak(bit == 0)
                Thread.sleep(startBitMs)
            }
            port.setBreak(false)
        } catch (_: UnsupportedOperationException) {
            try {
                port.setParameters(5, 7, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_ODD)
                dbg("Драйвер не поддерживает setBreak(); slow-init кадром на 5 бод (7O1)")
                port.write(byteArrayOf((address and 0x7F).toByte()), initTimeoutMs)
            } catch (e: Exception) {
                throw IOException(
                    "CH340/USB-драйвер не поддерживает setBreak() и не смог передать slow-init на 5 бод. " +
                        "Проверь лог ошибки; связь с ЭБУ ещё не установлена.", e
                )
            }
        }
        pause(blockDelayMs)
        // Return to the configured post-init speed before reading ECU sync.
        port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        val sync = readByte(initTimeoutMs)
        if (sync != 0x55) throw IOException("ECU did not return KW1281 sync 55 (received %02X)".format(sync))
        val key1 = readByte(responseTimeoutMs)
        val rawKey2 = readByte(responseTimeoutMs)
        // KW2 mask (Vasya Diagnost option): applied to keyword 2 before calculating the
        // protocol version. The default 0xFF keeps the standard behaviour; other masks are
        // an expert workaround for non-standard responses and should only be changed knowingly.
        val key2 = rawKey2 and (kw2Mask and 0xFF)
        if (key2 != rawKey2) log("KW2 маска %02X изменила keyword2: %02X -> %02X".format(kw2Mask, rawKey2, key2))
        val protocolVersion = ((key2 and 0x7F) shl 7) or (key1 and 0x7F)
        log("Sync 55; keywords %02X %02X; calculated protocol KWP%04d; baud=%d".format(key1, key2, protocolVersion, baudRate))
        dbgVerbose("Handshake: ACK на keyword2=%02X после паузы" .format(key2.inv() and 0xFF))
        pause(echoDelayMs)
        writeRaw((key2.inv() and 0xFF))
        pause(blockDelayMs.coerceAtLeast(50))
        if (protocolVersion >= 2000) {
            connected = false
            throw IOException("ЭБУ сообщил протокол KWP$protocolVersion. Эта версия приложения поддерживает только KW1281; KWP2000 пока не реализован.")
        }
        val initial = mutableListOf<Block>()
        var handshakeFinished = false
        repeat(12) {
            if (handshakeFinished) return@repeat
            val block = receiveBlock()
            initial += block
            counter = (block.counter + 1) and 0xFF
            if (block.title == 0x09 || block.title == 0x0A) handshakeFinished = true
            else sendBlock(0x09)
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
}
