package ru.medoed.vagdiag

import android.content.Context

/**
 * Connection parameters inspired by the advanced options of Vasya Diagnost / KW1281test:
 * Start Baud, Echo delay, BLK, Int, Debug level, KW2, Delay Char int.
 * Values are validated in the settings dialog; the Kw1281Client consumes them during I/O.
 */
data class DiagnosticSettings(
    val ecuAddress: Int = 1,
    val baudRate: Int = 10400,
    val responseTimeoutMs: Int = 1000,
    val initTimeoutMs: Int = 5000,
    /** Start Baud: UART rate used for slow-init and as first candidate in auto-detection. */
    val startBaudRate: Int = 10400,
    /** Echo delay: pause after sending a byte before reading its echo/ACK (ms). */
    val echoDelayMs: Int = 5,
    /** BLK: extra pause after a complete block has been sent/received (ms). */
    val blockDelayMs: Int = 30,
    /** Int: inter-byte delay inside blocks (ms). */
    val interByteDelayMs: Int = 5,
    /** Delay Char int: additional pause between characters while transmitting (ms). */
    val charInterDelayMs: Int = 0,
    /** Debug level: 0 = off, 1 = normal TX/RX byte log, 2 = verbose handshake timing. */
    val debugLevel: Int = 0,
    /** KW2: keyword-2 mask applied to the ECU sync pattern (Vasya's default is 0xFF). */
    val kw2Mask: Int = 0xFF
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
            .putInt("kw2_mask", kw2Mask)
            .apply()
    }

    /** Concise one-line summary for the diagnostic log when settings are saved. */
    fun describe(): String =
        "адрес %02X, старт %d бод, пост-init %d бод, echo %d мс, BLK %d мс, Int %d мс, DelayCharInt %d мс, Debug %d, KW2 %02X".format(
            ecuAddress, startBaudRate, baudRate, echoDelayMs, blockDelayMs,
            interByteDelayMs, charInterDelayMs, debugLevel, kw2Mask
        )

    companion object {
        private const val PREFS = "vag_diag_settings"
        fun load(context: Context): DiagnosticSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return DiagnosticSettings(
                ecuAddress = p.getInt("ecu_address", 1),
                baudRate = p.getInt("baud_rate", 10400),
                responseTimeoutMs = p.getInt("response_timeout_ms", 1000),
                initTimeoutMs = p.getInt("init_timeout_ms", 5000),
                startBaudRate = p.getInt("start_baud_rate", 10400),
                echoDelayMs = p.getInt("echo_delay_ms", 5),
                blockDelayMs = p.getInt("block_delay_ms", 30),
                interByteDelayMs = p.getInt("inter_byte_delay_ms", 5),
                charInterDelayMs = p.getInt("char_inter_delay_ms", 0),
                debugLevel = p.getInt("debug_level", 0),
                kw2Mask = p.getInt("kw2_mask", 0xFF)
            )
        }
    }
}
