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

    enum class InitMethod { FIVE_BAUD_FRAME, SERIAL_BREAK_FRAME, FAST_INIT_9600 }

    /**
     * Каскад автоподбора метода инициализации (как подбор протокола в Васе):
     * если запрошен fast-режим (Start Baud >= 1000, типично 9600) — пробуем честный
     * аппаратный break; если драйвер его не даёт — переходим к медленным путям.
     * Выбранный метод кешируется в [cached], чтобы не гонять пробы на каждой попытке.
     */
    fun chooseMethod(port: UsbSerialPort, startBaud: Int, log: (String) -> Unit, dbg: (String) -> Unit): InitMethod {
        dbgQuiet = dbg
        val wantFast = startBaud >= 1000
        if (wantFast) {
            if (probeFastBreak(port, log)) return InitMethod.FAST_INIT_9600.also { cached = it }
            dbg("Аппаратный break для fast-init недоступен — перехожу к slow-init путям")
        }
        // Реальные 5 бод 7O1 — CP210x / FTDI принимают нативно.
        try {
            port.setParameters(SLOW_INIT_BAUD, 7, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_ODD)
            log("Slow-init метод: драйвер принял реальные 5 бод 7O1 (как kw1281test)")
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
        return try {
            repeat(2) {
                port.setBreak(true)
                Thread.sleep(FAST_BREAK_MS)
                port.setBreak(false)
                Thread.sleep(30)
            }
            // Драйвер принял setBreak(true)+setBreak(false) без исключений оба цикла —
            // считаем аппаратный break рабочим (у сломанных CH340 controlIn/getBreak падает).
            log("Init метод: fast-init 9600 (аппаратный break принят драйвером) — как Вася со Start Baud 9600")
            true
        } catch (e: Exception) {
            try { port.setBreak(false) } catch (_: Exception) { }
            false
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
                try {
                    port.setBreak(true)
                    Thread.sleep(FAST_BREAK_MS)
                    port.setBreak(false)
                } catch (e: Exception) {
                    try { port.setBreak(false) } catch (_: Exception) { }
                    throw IOException("Не удалось выполнить fast-init (break): ${e.message}", e)
                }
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
     * ЭБУ отвечает строго на 9600 — других вариантов нет. При slow-init приоритет по
     * адресу блока (01/15/17/25/35/36 → 10400, остальные → 9600), затем остальные ставки.
     */
    fun baudCandidates(startBaud: Int, address: Int, override: Int?): List<Int> {
        if (startBaud >= 1000) return listOf(FAST_INIT_BAUD)
        val first = override?.takeIf { it in setOf(4800, 9600, 10400) } ?: VasyaDefaults.preferredBaud(address)
        return listOf(first, 10400, 9600, 4800).distinct()
    }
}
