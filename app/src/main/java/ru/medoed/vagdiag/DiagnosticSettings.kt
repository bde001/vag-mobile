package ru.medoed.vagdiag

import android.content.Context

data class DiagnosticSettings(
    val ecuAddress: Int = 1,
    val baudRate: Int = 10400,
    val responseTimeoutMs: Int = 1000,
    val initTimeoutMs: Int = 5000
) {
    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("ecu_address", ecuAddress)
            .putInt("baud_rate", baudRate)
            .putInt("response_timeout_ms", responseTimeoutMs)
            .putInt("init_timeout_ms", initTimeoutMs)
            .apply()
    }

    companion object {
        private const val PREFS = "vag_diag_settings"
        fun load(context: Context): DiagnosticSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return DiagnosticSettings(
                ecuAddress = p.getInt("ecu_address", 1),
                baudRate = p.getInt("baud_rate", 10400),
                responseTimeoutMs = p.getInt("response_timeout_ms", 1000),
                initTimeoutMs = p.getInt("init_timeout_ms", 5000)
            )
        }
    }
}
