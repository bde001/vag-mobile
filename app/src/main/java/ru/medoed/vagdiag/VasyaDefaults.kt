package ru.medoed.vagdiag

/**
 * Значения по умолчанию и логика выбора параметров «как в Васе Диагносте».
 *
 * Вася при подключении к блоку сам подбирает скорость post-init:
 *   - адрес 01 (двигатель), 15, 17, 25, 35, 36 -> сначала 10400 бод;
 *   - остальные блоки (ABS, климат, комфорт и т.п.) -> сначала 9600 бод;
 *   если ответа нет — перебирает оставшиеся скорости KW1281 (9600/4800).
 * Start Baud = 5 формирует медленный wake-up кадр; Start Baud = 9600 включает
 * fast-init (break ~65 мс + idle) — именно так Вася подключается ко многим ЭБУ
 * через CH340, где 5 бод аппаратно недоступны (см. VasyaProtocol).
 */
object VasyaDefaults {

    /** Start Baud по умолчанию — 9600 (fast-init, «как в Васе»): надёжнее всего на CH340. */
    const val START_BAUD = 9600
    /** Значение Start Baud для классического KW1281 slow-init. */
    const val SLOW_INIT_BAUD = 5

    /** Скорость post-init, с которой Вася начинает опрос конкретного блока. */
    fun preferredBaud(address: Int): Int = when (address) {
        0x01, 0x15, 0x17, 0x25, 0x35, 0x36 -> 10400
        else -> 9600
    }

    /** Полный список скоростей-кандидатов: приоритетная первая, далее остальные. */
    fun baudCandidates(address: Int, override: Int? = null): List<Int> {
        val first = override?.takeIf { it in setOf(4800, 9600, 10400) } ?: preferredBaud(address)
        return listOf(first, 10400, 9600, 4800).distinct()
    }

    /** Васькине допустимое значение Start Baud: 5 (slow-init) или 9600 (fast-init). */
    val START_BAUD_CHOICES = listOf(SLOW_INIT_BAUD, START_BAUD)

    /**
     * Настройки «как в Васе» (Restore defaults в его диалоге Protocol settings):
     * Start Baud 5, Echo Delay 0, Blk Int 55, Char Int 1, KP2 Time 25 мс, Debug Level 0.
     */
    fun vasyaSettings(ecuAddress: Int = 1): DiagnosticSettings = DiagnosticSettings(
        ecuAddress = ecuAddress,
        // null = автоподбор скорости по адресу, как делает Вася Диагност
        baudRate = 0,
        responseTimeoutMs = 1000,
        initTimeoutMs = 5000,
        startBaudRate = START_BAUD, // 9600 = fast-init как у Васи с CH340; 5 — классический slow-init
        echoDelayMs = 0,
        blockDelayMs = 55,
        interByteDelayMs = 1,
        charInterDelayMs = 1,
        debugLevel = 0,
        initRetryTimes = 2
    )
}
