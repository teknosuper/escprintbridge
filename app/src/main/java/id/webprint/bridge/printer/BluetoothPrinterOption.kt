package id.webprint.bridge.printer

data class BluetoothPrinterOption(
    val name: String,
    val address: String,
) {
    val label: String
        get() = "$name ($address)"
}
