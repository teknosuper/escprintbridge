package id.webprint.bridge.data

import android.content.Context

class SettingsRepository(context: Context) {

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): BridgeSettings {
        return BridgeSettings(
            clientUrl = preferences.getString(KEY_CLIENT_URL, "").orEmpty(),
            baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty(),
            token = preferences.getString(KEY_TOKEN, "").orEmpty(),
            deviceId = preferences.getString(KEY_DEVICE_ID, "").orEmpty(),
            outletId = preferences.getInt(KEY_OUTLET_ID, 1),
            queueType = preferences.getString(KEY_QUEUE_TYPE, QueueType.CASHIER.value).orEmpty(),
            stationId = preferences.getString(KEY_STATION_ID, "").orEmpty(),
            paperWidthColumns = preferences.getInt(KEY_PAPER_WIDTH_COLUMNS, 32),
            printerMode = preferences.getString(KEY_PRINTER_MODE, PrinterMode.TCP.value).orEmpty(),
            printerHost = preferences.getString(KEY_PRINTER_HOST, "").orEmpty(),
            printerPort = preferences.getInt(KEY_PRINTER_PORT, 9100),
            bluetoothMacAddress = preferences.getString(KEY_BLUETOOTH_MAC_ADDRESS, "").orEmpty(),
            bluetoothPrinterName = preferences.getString(KEY_BLUETOOTH_PRINTER_NAME, "").orEmpty(),
            bluetoothTransportMode = preferences.getString(
                KEY_BLUETOOTH_TRANSPORT_MODE,
                BluetoothTransportMode.AUTO.value,
            ).orEmpty(),
            pollingSeconds = preferences.getLong(KEY_POLLING_SECONDS, 3L),
            autoStart = preferences.getBoolean(KEY_AUTO_START, false),
        )
    }

    fun save(settings: BridgeSettings) {
        preferences.edit()
            .putString(KEY_CLIENT_URL, settings.clientUrl)
            .putString(KEY_BASE_URL, settings.baseUrl)
            .putString(KEY_TOKEN, settings.token)
            .putString(KEY_DEVICE_ID, settings.deviceId)
            .putInt(KEY_OUTLET_ID, settings.outletId)
            .putString(KEY_QUEUE_TYPE, settings.queueType)
            .putString(KEY_STATION_ID, settings.stationId)
            .putInt(KEY_PAPER_WIDTH_COLUMNS, settings.paperWidthColumns)
            .putString(KEY_PRINTER_MODE, settings.printerMode)
            .putString(KEY_PRINTER_HOST, settings.printerHost)
            .putInt(KEY_PRINTER_PORT, settings.printerPort)
            .putString(KEY_BLUETOOTH_MAC_ADDRESS, settings.bluetoothMacAddress)
            .putString(KEY_BLUETOOTH_PRINTER_NAME, settings.bluetoothPrinterName)
            .putString(KEY_BLUETOOTH_TRANSPORT_MODE, settings.bluetoothTransportMode)
            .putLong(KEY_POLLING_SECONDS, settings.pollingSeconds)
            .putBoolean(KEY_AUTO_START, settings.autoStart)
            .apply()
    }

    fun validate(settings: BridgeSettings): List<String> {
        val errors = mutableListOf<String>()
        if (!settings.baseUrl.startsWith("http://") && !settings.baseUrl.startsWith("https://")) {
            errors += "Base URL harus diawali http:// atau https://"
        }
        if (settings.token.isBlank()) {
            errors += "Token print queue wajib diisi"
        }
        if (settings.outletId <= 0) {
            errors += "Outlet ID wajib lebih dari 0"
        }
        if (QueueType.fromValue(settings.queueType) == QueueType.KITCHEN && settings.stationId.isNotBlank()) {
            settings.stationId.toIntOrNull() ?: run {
                errors += "Station ID harus angka jika diisi"
            }
        }
        if (settings.paperWidthColumns !in setOf(32, 48)) {
            errors += "Lebar kertas hanya mendukung 32 atau 48 kolom"
        }
        when (PrinterMode.fromValue(settings.printerMode)) {
            PrinterMode.TCP -> {
                if (settings.printerHost.isBlank()) {
                    errors += "Printer host wajib diisi untuk mode TCP"
                }
                if (settings.printerPort !in 1..65535) {
                    errors += "Printer port tidak valid"
                }
            }
            PrinterMode.BLUETOOTH -> {
                if (settings.bluetoothMacAddress.isBlank()) {
                    errors += "Printer Bluetooth wajib dipilih"
                }
            }
        }
        if (settings.pollingSeconds < 2) {
            errors += "Polling minimal 2 detik"
        }
        return errors
    }

    companion object {
        private const val PREFS_NAME = "print_bridge_settings"
        private const val KEY_CLIENT_URL = "client_url"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_OUTLET_ID = "outlet_id"
        private const val KEY_QUEUE_TYPE = "queue_type"
        private const val KEY_STATION_ID = "station_id"
        private const val KEY_PAPER_WIDTH_COLUMNS = "paper_width_columns"
        private const val KEY_PRINTER_MODE = "printer_mode"
        private const val KEY_PRINTER_HOST = "printer_host"
        private const val KEY_PRINTER_PORT = "printer_port"
        private const val KEY_BLUETOOTH_MAC_ADDRESS = "bluetooth_mac_address"
        private const val KEY_BLUETOOTH_PRINTER_NAME = "bluetooth_printer_name"
        private const val KEY_BLUETOOTH_TRANSPORT_MODE = "bluetooth_transport_mode"
        private const val KEY_POLLING_SECONDS = "polling_seconds"
        private const val KEY_AUTO_START = "auto_start"
    }
}
