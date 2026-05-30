package id.webprint.bridge.data

data class BridgeSettings(
    val clientUrl: String = "",
    val baseUrl: String = "",
    val token: String = "",
    val deviceId: String = "",
    val outletId: Int = 1,
    val queueType: String = QueueType.CASHIER.value,
    val stationId: String = "",
    val paperWidthColumns: Int = 32,
    val printerMode: String = PrinterMode.TCP.value,
    val printerHost: String = "",
    val printerPort: Int = 9100,
    val bluetoothMacAddress: String = "",
    val bluetoothPrinterName: String = "",
    val bluetoothTransportMode: String = BluetoothTransportMode.AUTO.value,
    val pollingSeconds: Long = 3L,
    val autoStart: Boolean = false,
) {
    fun isReady(): Boolean {
        return baseUrl.isNotBlank() &&
            token.isNotBlank() &&
            outletId > 0 &&
            when (printerMode) {
                PrinterMode.BLUETOOTH.value -> bluetoothMacAddress.isNotBlank()
                else -> printerHost.isNotBlank() && printerPort > 0
            }
    }
}

enum class QueueType(val value: String) {
    CASHIER("cashier"),
    KITCHEN("kitchen");

    companion object {
        fun fromValue(value: String?): QueueType {
            return entries.firstOrNull { it.value == value } ?: CASHIER
        }
    }
}

enum class PrinterMode(val value: String) {
    TCP("tcp"),
    BLUETOOTH("bluetooth");

    companion object {
        fun fromValue(value: String?): PrinterMode {
            return entries.firstOrNull { it.value == value } ?: TCP
        }
    }
}

enum class BluetoothTransportMode(val value: String) {
    AUTO("auto"),
    CLASSIC("classic"),
    BLE("ble");

    companion object {
        fun fromValue(value: String?): BluetoothTransportMode {
            return entries.firstOrNull { it.value == value } ?: AUTO
        }
    }
}
