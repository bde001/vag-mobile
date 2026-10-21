package ru.medoed.vagdiag

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException

/** Minimal KW1281 tester implementation. Must be validated against a real ECU/cable before relying on it. */
class Kw1281Client(
    private val port: UsbSerialPort,
    private val log: (String) -> Unit,
    private val responseTimeoutMs: Int = 1000,
    private val initTimeoutMs: Int = 5000
) {
    data class Block(val counter: Int, val title: Int, val data: ByteArray) {
        fun hex(): String = (byteArrayOf(counter.toByte(), title.toByte()) + data).joinToString(" ") { "%02X".format(it) }
    }

    private var counter: Int = 0
    private var connected = false

    @Synchronized
    fun connect(address: Int = 0x01, baudRate: Int = 9600): String {
        if (address !in 0..0x7F) throw IllegalArgumentException("KW1281 address must be 0..127")
        if (baudRate !in setOf(4800, 9600, 10400)) throw IllegalArgumentException("Supported post-init baud rates: 4800, 9600, 10400")
        port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
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
                Thread.sleep(200)
            }
            port.setBreak(false)
        } catch (_: UnsupportedOperationException) {
            try {
                port.setParameters(5, 7, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_ODD)
                port.write(byteArrayOf((address and 0x7F).toByte()), initTimeoutMs)
            } catch (e: Exception) {
                throw IOException(
                    "CH340/USB-драйвер не поддерживает setBreak() и не смог передать slow-init на 5 бод. " +
                        "Проверь лог ошибки; связь с ЭБУ ещё не установлена.", e
                )
            }
        }
        Thread.sleep(30)
        // Return to the configured post-init speed before reading ECU sync.
        port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        val sync = readByte(initTimeoutMs)
        if (sync != 0x55) throw IOException("ECU did not return KW1281 sync 55 (received %02X)".format(sync))
        val key1 = readByte(responseTimeoutMs)
        val key2 = readByte(responseTimeoutMs)
        val protocolVersion = ((key2 and 0x7F) shl 7) or (key1 and 0x7F)
        log("Sync 55; keywords %02X %02X; calculated protocol KWP%04d; baud=%d".format(key1, key2, protocolVersion, baudRate))
        Thread.sleep(25)
        writeRaw((key2.inv() and 0xFF))
        Thread.sleep(50)
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
        bytes.forEach { value ->
            writeRaw(value)
            val ack = readByte(responseTimeoutMs)
            if (ack != (value.inv() and 0xFF)) throw IOException("KW1281 ACK mismatch for %02X: got %02X".format(value, ack))
            Thread.sleep(1)
        }
        writeRaw(0x03) // end byte is not acknowledged
        Thread.sleep(10)
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
        return Block(receivedCounter, title, data)
    }

    private fun readAndAck(): Int {
        val b = readByte(responseTimeoutMs)
        Thread.sleep(5)
        writeRaw(b.inv() and 0xFF)
        return b
    }

    private fun readByte(timeoutMs: Int): Int {
        val buffer = ByteArray(1)
        val n = port.read(buffer, timeoutMs)
        if (n != 1) throw IOException("Timeout waiting for K-Line byte")
        return buffer[0].toInt() and 0xFF
    }

    private fun writeRaw(value: Int) {
        port.write(byteArrayOf(value.toByte()), 500)
    }

    private fun ensureConnected() { if (!connected) throw IOException("Сначала подключись к ЭБУ двигателя") }
}
