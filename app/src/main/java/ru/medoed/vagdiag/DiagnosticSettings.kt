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
    /** Start Baud: bit-rate knob of the KW1281 slow-init wake-up (Vasya default: 5). */
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
    val initRetryTimes: Int = 2
) {
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
            .apply()
    }

    /** Concise one-line summary for the diagnostic log when settings are saved. */
    fun describe(): String =
        "адрес %02X, старт %d бод, пост-init %s, echo %d мс, Blk Int %d мс, Char Int %d мс, DelayCharInt %d мс, Debug %d, повторов инициализации %d".format(
            ecuAddress, startBaudRate, if (baudRate == 0) "авто по адресу (как в Васе)" else "$baudRate бод",
            echoDelayMs, blockDelayMs,
            interByteDelayMs, charInterDelayMs, debugLevel, initRetryTimes
        )

    companion object {
        private const val PREFS = "vag_diag_settings"
        fun load(context: Context): DiagnosticSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // First launch (or after clearing app data): fall back to Vasya Diagnost defaults.
            if (!p.contains("ecu_address")) return VasyaDefaults.vasyaSettings()
            return DiagnosticSettings(
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
                initRetryTimes = p.getInt("init_retry_times", 2)
            )
        }
    }
}
