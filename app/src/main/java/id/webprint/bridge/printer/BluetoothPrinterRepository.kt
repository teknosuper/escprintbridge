package id.webprint.bridge.printer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter

class BluetoothPrinterRepository {

    fun isBluetoothAvailable(): Boolean {
        return BluetoothAdapter.getDefaultAdapter() != null
    }

    @SuppressLint("MissingPermission")
    fun isBluetoothEnabled(): Boolean {
        return BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
    }

    @SuppressLint("MissingPermission")
    fun getBondedPrinters(): List<BluetoothPrinterOption> {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return emptyList()

        return adapter.bondedDevices
            .map { device ->
                BluetoothPrinterOption(
                    name = device.name?.ifBlank { "Unknown Printer" } ?: "Unknown Printer",
                    address = device.address.orEmpty(),
                )
            }
            .sortedBy { it.name.lowercase() }
    }
}
