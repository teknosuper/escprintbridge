package id.webprint.bridge.printer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import id.webprint.bridge.data.BluetoothTransportMode
import id.webprint.bridge.data.BridgeSettings
import id.webprint.bridge.data.PrintJob
import id.webprint.bridge.data.PrintLine
import id.webprint.bridge.data.PrinterMode
import java.net.InetSocketAddress
import java.net.Socket

data class PrinterCheckResult(
    val ok: Boolean,
    val message: String,
)

class PrinterDiagnostics(
    context: Context,
    private val printer: EscPosPrinter = EscPosPrinter(context),
) {
    private val blePrinterClient = BluetoothGattPrinterClient(context.applicationContext)

    fun quickCheck(settings: BridgeSettings): PrinterCheckResult {
        return when (PrinterMode.fromValue(settings.printerMode)) {
            PrinterMode.TCP -> {
                if (settings.printerHost.isBlank() || settings.printerPort !in 1..65535) {
                    PrinterCheckResult(false, "Konfigurasi printer TCP belum benar.")
                } else {
                    PrinterCheckResult(true, "Konfigurasi printer TCP valid.")
                }
            }
            PrinterMode.BLUETOOTH -> quickBluetoothCheck(settings)
        }
    }

    fun validate(settings: BridgeSettings): PrinterCheckResult {
        return when (PrinterMode.fromValue(settings.printerMode)) {
            PrinterMode.TCP -> validateTcp(settings)
            PrinterMode.BLUETOOTH -> validateBluetooth(settings)
        }
    }

    fun testPrint(settings: BridgeSettings): PrinterCheckResult {
        return try {
            val job = PrintJob(
                id = "test-print",
                type = null,
                printerMode = settings.printerMode,
                printerHost = settings.printerHost,
                printerPort = settings.printerPort,
                bluetoothMacAddress = settings.bluetoothMacAddress,
                timeoutMs = 5000,
                lines = listOf(
                    PrintLine("TEST PRINTER", "center", true, 0, null),
                    PrintLine("Web Print Bridge", "center", false, 1, null),
                    PrintLine("Koneksi printer berhasil.", "left", false, 1, null),
                    PrintLine("Waktu test manual.", "left", false, 2, "full"),
                ),
                rawBytes = null,
                paperWidth = if (settings.resolvedPaperWidthColumns() >= 48) "80mm" else "58mm",
                receiptLayout = null,
                transaction = null,
                kitchenTicket = null,
            )
            printer.print(job, settings)
            val transport = when (PrinterMode.fromValue(settings.printerMode)) {
                PrinterMode.TCP -> "TCP"
                PrinterMode.BLUETOOTH -> "Bluetooth ${BluetoothTransportMode.fromValue(settings.bluetoothTransportMode).name}"
            }
            PrinterCheckResult(true, "Test print selesai melalui $transport. Jika kertas belum keluar, coba ganti transport Bluetooth lalu ulangi test.")
        } catch (exception: Exception) {
            PrinterCheckResult(false, "Test print gagal: ${exception.message ?: exception.javaClass.simpleName}")
        }
    }

    private fun validateTcp(settings: BridgeSettings): PrinterCheckResult {
        return try {
            require(settings.printerHost.isNotBlank()) { "Host printer TCP kosong." }
            require(settings.printerPort in 1..65535) { "Port printer TCP tidak valid." }
            Socket().use { socket ->
                socket.connect(InetSocketAddress(settings.printerHost, settings.printerPort), 5000)
            }
            PrinterCheckResult(true, "Printer TCP terhubung: ${settings.printerHost}:${settings.printerPort}")
        } catch (exception: Exception) {
            PrinterCheckResult(false, "Printer TCP gagal: ${exception.message ?: exception.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun validateBluetooth(settings: BridgeSettings): PrinterCheckResult {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
                ?: error("Bluetooth tidak tersedia di device ini.")
            require(adapter.isEnabled) { "Bluetooth sedang nonaktif." }
            require(settings.bluetoothMacAddress.isNotBlank()) { "MAC address printer Bluetooth kosong." }

            when (BluetoothTransportMode.fromValue(settings.bluetoothTransportMode)) {
                BluetoothTransportMode.CLASSIC -> {
                    val device = adapter.bondedDevices.firstOrNull {
                        it.address.equals(settings.bluetoothMacAddress, ignoreCase = true)
                    } ?: error("Printer Bluetooth Classic belum paired / tidak ditemukan.")
                    runCatching { adapter.cancelDiscovery() }
                    val socket = runCatching {
                        device.createRfcommSocketToServiceRecord(EscPosPrinter.SPP_UUID)
                    }.getOrElse {
                        device.createInsecureRfcommSocketToServiceRecord(EscPosPrinter.SPP_UUID)
                    }
                    socket.use { it.connect() }
                    PrinterCheckResult(true, "Printer Bluetooth Classic terhubung: ${device.name ?: "Unknown"}")
                }
                BluetoothTransportMode.BLE -> {
                    PrinterCheckResult(true, blePrinterClient.validate(settings.bluetoothMacAddress))
                }
                BluetoothTransportMode.AUTO -> {
                    val classic = runCatching {
                        val device = adapter.bondedDevices.firstOrNull {
                            it.address.equals(settings.bluetoothMacAddress, ignoreCase = true)
                        } ?: error("Printer Bluetooth Classic belum paired / tidak ditemukan.")
                        runCatching { adapter.cancelDiscovery() }
                        val socket = runCatching {
                            device.createRfcommSocketToServiceRecord(EscPosPrinter.SPP_UUID)
                        }.getOrElse {
                            device.createInsecureRfcommSocketToServiceRecord(EscPosPrinter.SPP_UUID)
                        }
                        socket.use { it.connect() }
                        "Printer Bluetooth Auto siap melalui Classic: ${device.name ?: "Unknown"}"
                    }
                    if (classic.isSuccess) {
                        PrinterCheckResult(true, classic.getOrThrow())
                    } else {
                        PrinterCheckResult(true, blePrinterClient.validate(settings.bluetoothMacAddress))
                    }
                }
            }
        } catch (exception: Exception) {
            PrinterCheckResult(false, "Printer Bluetooth gagal: ${exception.message ?: exception.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun quickBluetoothCheck(settings: BridgeSettings): PrinterCheckResult {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
                ?: error("Bluetooth tidak tersedia di device ini.")
            require(adapter.isEnabled) { "Bluetooth sedang nonaktif." }
            require(settings.bluetoothMacAddress.isNotBlank()) { "MAC address printer Bluetooth kosong." }

            when (BluetoothTransportMode.fromValue(settings.bluetoothTransportMode)) {
                BluetoothTransportMode.BLE -> {
                    runCatching { adapter.getRemoteDevice(settings.bluetoothMacAddress) }
                        .getOrElse { error("Perangkat BLE printer tidak dikenali.") }
                    PrinterCheckResult(true, "Printer BLE siap: ${settings.bluetoothMacAddress}")
                }
                else -> {
                    val device = adapter.bondedDevices.firstOrNull {
                        it.address.equals(settings.bluetoothMacAddress, ignoreCase = true)
                    } ?: error("Printer Bluetooth belum paired / tidak ditemukan.")
                    PrinterCheckResult(true, "Printer Bluetooth siap: ${device.name ?: "Unknown"}")
                }
            }
        } catch (exception: Exception) {
            PrinterCheckResult(false, "Monitor Bluetooth: ${exception.message ?: exception.javaClass.simpleName}")
        }
    }
}
