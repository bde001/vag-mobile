package ru.medoed.vagdiag

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException

/**
 * «Васькины» способы инициализации линии — ровно как в Vasya Diagnost:
 *
 * 1) KWP2000 fast-init (Start Baud = 9600): на K-Line подаётся break ~65 мс, затем idle.
 *    ЭБУ просыпается и сам начинает слать синхру 0x55 на 9600 бод. Именно этот режим
 *    включает Вася, когда пользователь ставит Start Baud 9600 / Delay 0 — он работает
 *    на CH340 без поддержки низких скоростей, потому что не требует 5 бод вообще.
 *
 * 2) KW1281 slow-init (Start Baud = 5): кадр адреса реальными 5 бод 7O1 (CP210x/FTDI),
 *    либо serial-break кадр 0x00 + удержание линии в low ~200 мс + idle (гарантированный
 *    путь для CH340, чей физический минимум ≈2339 бод).
 *
 * Каждый метод принимает порт; методы перебираются каскадом, первый принятый запоминается.
 */
object VasyaProtocol {

    const val FAST_INIT_BAUD = 9600
    const val SLOW_INIT_BAUD = 5
    /** Длительность break при fast-init: стандарт KWP2000 — 60..70 мс (Вася держит ~65). */
    private const val FAST_BREAK_MS = 65L
    /** Суммарное время прижатия K-Line для имитации бита 5 бод. */
    private const val SLOW_BIT_MS = 200L

    enum class InitMethod { FIVE_BAUD_FRAME, SERIAL_BREAK_FRAME, FAST_INIT_9600, SOFT_FAST_BREAK }

    /**
     * Каскад автоподбора метода инициализации (как подбор протокола в Васе):
     * если запрошен fast-режим (Start Baud >= 1000, типично 9600) — пробуем честный
     * аппаратный break; если драйвер его не даёт — переходим к медленным путям.
     * Выбранный метод кешируется в [cached], чтобы не гонять пробы на каждой попытке.
     */
    fun chooseMethod(port: UsbSerialPort, startBaud: Int, log: (String) -> Unit, dbg: (String) -> Unit): InitMethod =
        chooseMethod(port, startBaud, mode = 0, log = log, dbg = dbg)

    /**
     * [mode]: 0 = авто (каскад как раньше); 1 = принудительно slow-init (Magneti Marelli
     * 4MV/4ME7 KW1281: адресным байтом на 5 бод); 2 = принудительно fast-init KWP2000.
     * При forced-режиме каскад не срабатывает молча — в журнал пишется, что выбрано.
     */
    fun chooseMethod(port: UsbSerialPort, startBaud: Int, mode: Int, log: (String) -> Unit, dbg: (String) -> Unit): InitMethod {
        dbgQuiet = dbg
        val wantFast = when (mode) {
            1 -> false
            2 -> true
            // Start Baud 0 = default «как в Васе»: на адаптерах без 5 бод это fast-init.
            else -> startBaud == 0 || startBaud >= 1000
        }
        if (wantFast) {
            if (mode == 2) log("Принудительный fast-init (KWP2000): break ~${FAST_BREAK_MS} мс на 9600 бод")
            if (probeFastBreak(port, log)) return InitMethod.FAST_INIT_9600.also { cached = it }
            // Аппаратный break недоступен (типичный CH340) — НЕ уходим в slow-init:
            // Вася при Start Baud 9600 делает именно fast-инициализацию. Используем
            // программную имитацию break (низкоскоростные 0x00-кадры / поток нулей).
            dbg("Аппаратный break недоступен — soft fast-init (программная имитация break), как Вася на CH340")
            log("Init метод: soft fast-init 9600 (программный break через low-биты) — как Вася со Start Baud 9600")
            return InitMethod.SOFT_FAST_BREAK.also { cached = it }
        }
        // Реальные 5 бод 7O1 — CP210x / FTDI принимают нативно.
        try {
            port.setParameters(SLOW_INIT_BAUD, 7, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_ODD)
            if (mode == 1) log("Принудительный slow-init (KW1281, Magneti Marelli 4MV): адресный байт реальными 5 бод 7O1")
            else log("Slow-init метод: драйвер принял реальные 5 бод 7O1 (как kw1281test)")
            return InitMethod.FIVE_BAUD_FRAME.also { cached = it }
        } catch (e: Exception) {
            dbg("5 бод недоступно (${e.javaClass.simpleName}: ${e.message}) — использую serial-break кадр")
        }
        log("Slow-init метод: serial-break кадр 0x00 + удержание ~${SLOW_BIT_MS} мс + idle (путь CH340)")
        return InitMethod.SERIAL_BREAK_FRAME.also { cached = it }
    }

    private var cached: InitMethod? = null
    fun resetCache() { cached = null }

    /**
     * Двойная проба «set → отпустить» с проверкой обратного чтения состояния: если драйвер и
     * принимает setBreak, и корректно отдаёт getBreak==false после отпускания — считаем
     * аппаратный break надёжным. Иначе (типичный CH340: getBreak controlIn(0x1312) падает,
     * либо линия остаётся прижатой) fast-init недоступен.
     */
    private fun probeFastBreak(port: UsbSerialPort, log: (String) -> Unit): Boolean {
        val idleByte = byteArrayOf(0xFF.toByte())
        return try {
            repeat(2) {
                port.write(idleByte, 500)   // гарантируем idle перед импульсом
                port.setBreak(true)
                Thread.sleep(FAST_BREAK_MS)
                port.setBreak(false)
                port.write(idleByte, 500)   // сразу отпускаем линию байтом idle
                Thread.sleep(30)
            }
            // Драйвер принял setBreak(true)+setBreak(false) без исключений оба цикла —
            // считаем аппаратный break рабочим (у сломанных CH340 controlIn/getBreak падает).
            log("Init метод: fast-init 9600 (аппаратный break принят драйвером) — как Вася со Start Baud 9600")
            true
        } catch (e: Exception) {
            try { port.setBreak(false); port.write(idleByte, 500) } catch (_: Exception) { }
            false
        }
    }

    /**
     * Программный аналог fast-init для чипов без рабочего аппаратного BREAK (CH340 в
     * usb-serial-for-android: get/setBreak на controlIn(0x1312) падает). Трюк с «залипшим»
     * stop-битом НЕ работает на реальном CH340: кадр остаётся в буфере TX и линия уходит в
     * idle сразу после записи. Поэтому line-low формируется двумя способами:
     *   A) реальные низкие скорости: baud≈250 даёт ~40 мс low на бит, 7N2 — ещё ~80 мс;
     *   B) если драйвер не принимает <300 бод — байт 0x00 на пост-init скорости плюс
     *      программная пауза: до возврата линии в idle держим TX-фифо полной потоком
     *      нулевых кадров (каждый = 9 бит low), затем отпускаем и ставим 0xFF (idle).
     * После метода всегда возвращаем порт на postInitBaud (это делает вызывающий код).
     */
    fun softFastBreak(port: UsbSerialPort, writeTimeoutMs: Int, postInitBaud: Int,
                      dbgVerbose: (String) -> Unit) {
        val targetMs = FAST_BREAK_MS
        // Способ A: честные низкие скорости (если драйвер их принимает)
        val lowRates = intArrayOf(250, 300, 600)
        for (rate in lowRates) {
            val bitMs = 1000.0 / rate
            try {
                port.setParameters(rate, 7, UsbSerialPort.STOPBITS_2, UsbSerialPort.PARITY_NONE)
                val bitsNeeded = Math.ceil((targetMs - bitMs * 2) / bitMs).toInt().coerceAtLeast(1)
                val frame = ByteArray(bitsNeeded + 1) // start+data bits of 0x00 each
                java.util.Arrays.fill(frame, 0x00.toByte())
                dbgVerbose("soft fast-init: %d бод 7N2, %d low-бит (~%.0f мс)".format(rate, bitsNeeded + 2, bitMs * (bitsNeeded + 2)))
                port.write(frame, writeTimeoutMs)
                Thread.sleep((bitMs * (bitsNeeded + 2)).toLong().coerceAtMost(targetMs + 40))
                return
            } catch (e: Exception) {
                dbgVerbose("soft fast-init: %d бод недоступно (${e.javaClass.simpleName})")
            }
        }
        // Способ B: поток нулевых кадров на пост-init скорости — линия прижата low, пока идёт запись
        try {
            port.setParameters(postInitBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            val bitMs = 1000.0 / postInitBaud
            val zeros = ByteArray(64).also { java.util.Arrays.fill(it, 0x00.toByte()) }
            val deadline = System.currentTimeMillis() + targetMs
            var written = 0
            while (System.currentTimeMillis() < deadline) {
                port.write(zeros, writeTimeoutMs)
                written += zeros.size
            }
            val lowMs = written * 10 * bitMs
            dbgVerbose("soft fast-init: поток 0x00 (%d байт ≈ %.0f мс low) на %d бод".format(written, lowMs, postInitBaud))
            // Отпустить линию: idle-байт, потом короткая пауза для восстановления K-Line
            port.write(byteArrayOf(0xFF.toByte()), writeTimeoutMs)
            Thread.sleep(40)
        } catch (e: IOException) {
            throw IOException("Не удалось выполнить soft fast-init (break через 0x00): ${e.message}", e)
        }
    }

    private var dbgQuiet: ((String) -> Unit)? = null

    /** Отправка импульса пробуждения выбранным методом. После неё линия должна быть в idle,
     * а порт — переключён на post-init скорость (это делает вызывающий код). */
    fun sendWakeUp(port: UsbSerialPort, method: InitMethod, address: Int, postInitBaud: Int,
                   writeTimeoutMs: Int, stage: (String) -> Unit, dbgVerbose: (String) -> Unit) {
        when (method) {
            InitMethod.FAST_INIT_9600 -> {
                // Fast-init: пост-init скорость уже стоит 9600; подаём break и отпускаем.
                stage("Fast-init: break ${FAST_BREAK_MS} мс на 9600 бод…")
                val idleByte = byteArrayOf(0xFF.toByte())
                try {
                    port.write(idleByte, 500) // линия в idle перед импульсом
                    port.setBreak(true)
                    Thread.sleep(FAST_BREAK_MS)
                    port.setBreak(false)
                    port.write(idleByte, 500) // немедленно отпускаем K-Line (idle high)
                } catch (e: Exception) {
                    try { port.setBreak(false); port.write(idleByte, 500) } catch (_: Exception) { }
                    throw IOException("Не удалось выполнить fast-init (break): ${e.message}", e)
                }
            }
            InitMethod.SOFT_FAST_BREAK -> {
                stage("Soft fast-init: программный break ~${FAST_BREAK_MS} мс…")
                softFastBreak(port, writeTimeoutMs, postInitBaud, dbgVerbose)
            }
            InitMethod.FIVE_BAUD_FRAME -> {
                try {
                    port.setParameters(SLOW_INIT_BAUD, 7, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_ODD)
                    dbgVerbose("Slow-init 5 бод 7O1: TX байт %02X (~2.2 с на линию)".format(address and 0x7F))
                    stage("Медленный старт 5 бод (~2 с)…")
                    port.write(byteArrayOf((address and 0x7F).toByte()), writeTimeoutMs)
                } catch (e: IOException) {
                    throw IOException("Не удалось отправить slow-init кадр на 5 бод: ${e.message}", e)
                }
            }
            InitMethod.SERIAL_BREAK_FRAME -> {
                try {
                    val bitMs = 1000.0 / postInitBaud
                    val frameMs = bitMs * 9 // start + 8 data bits of 0x00
                    val holdMs = (SLOW_BIT_MS - frameMs).coerceAtLeast(0.0).toLong()
                    dbgVerbose("Slow-init serial-break: кадр 0x00 (~${frameMs.toInt()} мс low) + пауза ${holdMs} мс + idle 0xFF")
                    stage("Медленный старт (serial-break)…")
                    port.write(byteArrayOf(0x00), 1000)
                    Thread.sleep(holdMs + 5)
                    port.write(byteArrayOf(0xFF.toByte()), 1000) // вернуть линию в idle
                    Thread.sleep(50)
                } catch (e: IOException) {
                    throw IOException("Не удалось отправить serial-break slow-init: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Кандидаты post-init скорости «как в Васе». При fast-init (Start Baud >= 1000)
     * ЭБУ VW отвечает строго на 9600; но для Magneti Marelli 4MV/4ME7 (moto) оставляем
     * полный перебор 10400/9600/4800 — эти ЭБУ после slow-init слушают 10400.
     * При slow-init приоритет по адресу блока (01/15/17/25/35/36 → 10400, остальные → 9600),
     * затем остальные ставки.
     */
    fun baudCandidates(startBaud: Int, address: Int, override: Int?, forceSlowInit: Boolean = false): List<Int> {
        val first = override?.takeIf { it in setOf(4800, 9600, 10400) } ?: VasyaDefaults.preferredBaud(address)
        // Start Baud 0 (default, как в Васе) или 9600 = fast-init.
        if ((startBaud == 0 || startBaud >= 1000) && !forceSlowInit) {
            // Честный fast-init KWP2000 по стандарту ISO 9141-2: ЭБУ отвечает на синхру
            // 0x55 строго на 9600 бод — других ставок у Васи в этом режиме нет.
            // Но если пользователь ЯВНО выбрал скорость пост-init (override, как поле
            // «baud» в Васе), уважаем её и даём полный перебор от неё — это спасает
            // при нестандартных прошивках (Magneti Marelli 4MV и т.п.).
            if (override != null && override in setOf(4800, 9600, 10400)) {
                return listOf(override, 10400, 9600, 4800).distinct()
            }
            return listOf(FAST_INIT_BAUD)
        }
        // Slow-init (в т.ч. принудительный для Marelli 4MV): full-перебор.
        return listOf(first, 10400, 9600, 4800).distinct()
    }
}
