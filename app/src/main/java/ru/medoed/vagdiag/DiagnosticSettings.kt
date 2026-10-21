package ru.medoed.vagdiag

import android.content.Context

/**
 * Connection parameters mirroring Vasya Diagnost's protocol settings:
 * Start Baud, Echo Delay, Blk Int, Char Int, Debug Level. Defaults match Vasya exactly
 * (see VasyaDefaults.vasyaSettings); values are validated in the settings dialog and
 * consumed by Kw1281Client during I/O.
 */
data class DiagnosticSettings(
    val ecuAddress: Int = 1,
    /** 0 = auto-select post-init baud by ECU address (Vasya-style); otherwise 4800/9600/10400. */
    val baudRate: Int = 0,
    val responseTimeoutMs: Int = 1000,
    val initTimeoutMs: Int = 5000,
    /**
     * Start Baud — как в Васе Диагносте: 5 = KW1281 slow-init (кадр адреса на 5 бод),
     * 9600 = fast-init (break-импульс на 9600, ЭБУ сам начинает слать синхру).
     * Именно «Start Baud 9600 + Echo Delay 0» у Васи работает с CH340 без поддержки 5 бод.
     */
    val startBaudRate: Int = VasyaDefaults.START_BAUD,
    /** Echo delay (Vasya "Echo Delay"): pause around echo/ACK handling. Vasya default: 0. */
    val echoDelayMs: Int = 0,
    /** Blk Int (Vasya "Blk Int"): inter-block delay. Vasya default: 55. */
    val blockDelayMs: Int = 55,
    /** Char Int (Vasya "Char Int"): inter-character delay inside blocks. Vasya default: 1. */
    val interByteDelayMs: Int = 1,
    /** Delay Char int: additional pause between characters while transmitting (ms). */
    val charInterDelayMs: Int = 1,
    /** Debug level: 0 = off, 1 = normal TX/RX byte log, 2 = verbose handshake timing. */
    val debugLevel: Int = 0,
    /** Init retry times: how many slow-init attempts before giving up (Vasya-style knob). */
    val initRetryTimes: Int = 2,
    /** 0 = авто (подбирается по Start Baud), 1 = KWP-1281 slow-init, 2 = KWP-2000 fast-init. */
    val protocolMode: Int = 0
) {
    /** Fast-init активен: явно выбран режим 2 или Start Baud >= 1000 (как 9600 у Васи). */
    val fastInit: Boolean get() = protocolMode == 2 || (protocolMode == 0 && startBaudRate >= 1000)
    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("ecu_address", ecuAddress)
            .putInt("baud_rate", baudRate)
            .putInt("response_timeout_ms", responseTimeoutMs)
            .putInt("init_timeout_ms", initTimeoutMs)
            .putInt("start_baud_rate", startBaudRate)
            .putInt("echo_delay_ms", echoDelayMs)
            .putInt("block_delay_ms", blockDelayMs)
            .putInt("inter_byte_delay_ms", interByteDelayMs)
            .putInt("char_inter_delay_ms", charInterDelayMs)
            .putInt("debug_level", debugLevel)
            .putInt("init_retry_times", initRetryTimes)
            // Маркер версии настроек: старые сборки могли сохранить Start Baud=9600 из-за
            // кривого дефолта — при обновлении один раз сбрасываем на васьины значения.
            .putInt("settings_schema", SETTINGS_SCHEMA)
            // Протокол по умолчанию: KWP-1281 (как галки в Васе); fast-init включается
            // значением Start Baud = 9600 либо явным выбором режима.
            .putInt("protocol_mode", protocolMode)
            .apply()
    }

    /** Concise one-line summary for the diagnostic log when settings are saved. */
    fun describe(): String =
        "адрес %02X, старт %d бод (%s), пост-init %s, echo %d мс, Blk Int %d мс, Char Int %d мс, DelayCharInt %d мс, Debug %d, повторов инициализации %d".format(
            ecuAddress, startBaudRate, if (fastInit) "fast-init" else "slow-init",
            if (baudRate == 0) "авто по адресу (как в Васе)" else "$baudRate бод",
            echoDelayMs, blockDelayMs,
            interByteDelayMs, charInterDelayMs, debugLevel, initRetryTimes
        )

    companion object {
        private const val PREFS = "vag_diag_settings"
        // Schema 3: добавлены protocol_mode и васькины диапазоны Start Baud (5 или 9600).
        private const val SETTINGS_SCHEMA = 3

        /**
         * Sanitize как в Васе: Start Baud принимает только его два реальных значения —
         * 5 (slow-init) или 9600 (fast-init). Всё остальное чинится ближайшим осмысленным.
         */
        private fun sanitize(s: DiagnosticSettings): DiagnosticSettings {
            val sb = when {
                s.startBaudRate in 1..5 -> 5
                s.startBaudRate >= 4800 -> VasyaProtocol.FAST_INIT_BAUD
                else -> VasyaDefaults.START_BAUD
            }
            return s.copy(startBaudRate = sb)
        }

        fun load(context: Context): DiagnosticSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // First launch (or after clearing app data): fall back to Vasya Diagnost defaults.
            if (!p.contains("ecu_address")) return VasyaDefaults.vasyaSettings()
            // Миграция: если настройки сохранены старой версией (schema < 2) или там стоит
            // невалидный Start Baud вроде 9600 — чиним молча при загрузке.
            if (p.getInt("settings_schema", 1) < SETTINGS_SCHEMA) return VasyaDefaults.vasyaSettings()
            return sanitize(DiagnosticSettings(
                ecuAddress = p.getInt("ecu_address", 1),
                baudRate = p.getInt("baud_rate", 0),
                responseTimeoutMs = p.getInt("response_timeout_ms", 1000),
                initTimeoutMs = p.getInt("init_timeout_ms", 5000),
                startBaudRate = p.getInt("start_baud_rate", VasyaDefaults.START_BAUD),
                echoDelayMs = p.getInt("echo_delay_ms", 0),
                blockDelayMs = p.getInt("block_delay_ms", 55),
                interByteDelayMs = p.getInt("inter_byte_delay_ms", 1),
                charInterDelayMs = p.getInt("char_inter_delay_ms", 1),
                debugLevel = p.getInt("debug_level", 0),
                initRetryTimes = p.getInt("init_retry_times", 2),
                protocolMode = p.getInt("protocol_mode", 0)
            ))
        }
    }
}
