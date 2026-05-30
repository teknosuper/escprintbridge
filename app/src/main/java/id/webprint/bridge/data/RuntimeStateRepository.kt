package id.webprint.bridge.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RuntimeState(
    val isPolling: Boolean = false,
    val printedCount: Int = 0,
    val failedCount: Int = 0,
    val pollsCount: Int = 0,
    val lastStatus: String = "Siap digunakan. Isi konfigurasi lalu klik Mulai.",
    val logs: List<String> = listOf("Siap digunakan. Isi konfigurasi lalu klik Mulai."),
)

class RuntimeStateRepository(context: Context) {

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val timeFormatter = SimpleDateFormat("HH.mm.ss", Locale("id", "ID"))

    fun load(): RuntimeState {
        return RuntimeState(
            isPolling = preferences.getBoolean(KEY_IS_POLLING, false),
            printedCount = preferences.getInt(KEY_PRINTED_COUNT, 0),
            failedCount = preferences.getInt(KEY_FAILED_COUNT, 0),
            pollsCount = preferences.getInt(KEY_POLLS_COUNT, 0),
            lastStatus = preferences.getString(KEY_LAST_STATUS, DEFAULT_LOG).orEmpty(),
            logs = decodeLogs(preferences.getString(KEY_LOGS, null)),
        )
    }

    fun reset() {
        preferences.edit()
            .putBoolean(KEY_IS_POLLING, false)
            .putInt(KEY_PRINTED_COUNT, 0)
            .putInt(KEY_FAILED_COUNT, 0)
            .putInt(KEY_POLLS_COUNT, 0)
            .putString(KEY_LAST_STATUS, DEFAULT_LOG)
            .putString(KEY_LOGS, encodeLogs(listOf(DEFAULT_LOG)))
            .apply()
    }

    fun setPolling(active: Boolean, message: String) {
        val state = load()
        saveState(
            state.copy(
                isPolling = active,
                lastStatus = message,
                logs = addLog(state.logs, decorate(message)),
            ),
        )
    }

    fun registerPoll(message: String) {
        val state = load()
        saveState(
            state.copy(
                isPolling = true,
                pollsCount = state.pollsCount + 1,
                lastStatus = message,
                logs = addLog(state.logs, decorate(message)),
            ),
        )
    }

    fun registerPrinted(message: String) {
        val state = load()
        saveState(
            state.copy(
                printedCount = state.printedCount + 1,
                lastStatus = message,
                logs = addLog(state.logs, decorate(message)),
            ),
        )
    }

    fun registerFailed(message: String) {
        val state = load()
        saveState(
            state.copy(
                failedCount = state.failedCount + 1,
                lastStatus = message,
                logs = addLog(state.logs, decorate(message)),
            ),
        )
    }

    fun appendLog(message: String) {
        val state = load()
        saveState(
            state.copy(
                lastStatus = message,
                logs = addLog(state.logs, decorate(message)),
            ),
        )
    }

    private fun saveState(state: RuntimeState) {
        preferences.edit()
            .putBoolean(KEY_IS_POLLING, state.isPolling)
            .putInt(KEY_PRINTED_COUNT, state.printedCount)
            .putInt(KEY_FAILED_COUNT, state.failedCount)
            .putInt(KEY_POLLS_COUNT, state.pollsCount)
            .putString(KEY_LAST_STATUS, state.lastStatus)
            .putString(KEY_LOGS, encodeLogs(state.logs))
            .apply()
    }

    private fun decorate(message: String): String {
        return "[${timeFormatter.format(Date())}] $message"
    }

    private fun addLog(existing: List<String>, entry: String): List<String> {
        return (listOf(entry) + existing).take(MAX_LOGS)
    }

    private fun encodeLogs(logs: List<String>): String {
        return logs.joinToString(LOG_SEPARATOR)
    }

    private fun decodeLogs(raw: String?): List<String> {
        if (raw.isNullOrBlank()) {
            return listOf(DEFAULT_LOG)
        }
        return raw.split(LOG_SEPARATOR).filter { it.isNotBlank() }
    }

    companion object {
        private const val PREFS_NAME = "print_bridge_runtime"
        private const val KEY_IS_POLLING = "is_polling"
        private const val KEY_PRINTED_COUNT = "printed_count"
        private const val KEY_FAILED_COUNT = "failed_count"
        private const val KEY_POLLS_COUNT = "polls_count"
        private const val KEY_LAST_STATUS = "last_status"
        private const val KEY_LOGS = "logs"
        private const val LOG_SEPARATOR = "\n---LOG---\n"
        private const val MAX_LOGS = 100
        private const val DEFAULT_LOG = "Siap digunakan. Isi konfigurasi lalu klik Mulai."
    }
}
