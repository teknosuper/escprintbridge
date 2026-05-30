package id.webprint.bridge.printer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import id.webprint.bridge.data.BridgeSettings
import id.webprint.bridge.data.KitchenItemPayload
import id.webprint.bridge.data.LabelValueRow
import id.webprint.bridge.data.PrinterMode
import id.webprint.bridge.data.PrintJob
import id.webprint.bridge.data.PrintLine
import id.webprint.bridge.data.ReceiptItemPayload
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

class EscPosPrinter {

    fun print(job: PrintJob, settings: BridgeSettings) {
        val mode = resolvePrinterMode(job, settings)
        val bytes = buildPayload(job, settings)

        when (mode) {
            PrinterMode.TCP -> printTcp(job, settings, bytes)
            PrinterMode.BLUETOOTH -> printBluetooth(job, settings, bytes)
        }
    }

    private fun resolvePrinterMode(job: PrintJob, settings: BridgeSettings): PrinterMode {
        return PrinterMode.fromValue(job.printerMode ?: settings.printerMode)
    }

    private fun printTcp(job: PrintJob, settings: BridgeSettings, bytes: ByteArray) {
        val host = job.printerHost ?: settings.printerHost
        val port = job.printerPort ?: settings.printerPort
        val timeoutMs = job.timeoutMs ?: 5_000
        require(host.isNotBlank()) { "Printer host belum dikonfigurasi" }

        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.getOutputStream().use { output ->
                output.write(bytes)
                output.flush()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun printBluetooth(job: PrintJob, settings: BridgeSettings, bytes: ByteArray) {
        val macAddress = job.bluetoothMacAddress ?: settings.bluetoothMacAddress
        require(macAddress.isNotBlank()) { "Printer Bluetooth belum dipilih" }

        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: error("Bluetooth tidak tersedia di device ini")

        require(adapter.isEnabled) { "Bluetooth sedang nonaktif" }

        val device = adapter.bondedDevices.firstOrNull { it.address.equals(macAddress, ignoreCase = true) }
            ?: error("Printer Bluetooth dengan MAC $macAddress belum paired")

        runCatching {
            adapter.cancelDiscovery()
        }

        openBluetoothSocket(device).use { socket ->
            socket.connect()
            socket.outputStream.use { output ->
                output.write(bytes)
                output.flush()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openBluetoothSocket(device: BluetoothDevice): BluetoothSocket {
        return runCatching {
            device.createRfcommSocketToServiceRecord(SPP_UUID)
        }.getOrElse {
            device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        }
    }

    private fun buildPayload(job: PrintJob, settings: BridgeSettings): ByteArray {
        if (job.rawBytes != null) {
            return job.rawBytes
        }

        val output = ByteArrayOutputStream()
        output.write(INITIALIZE)
        when (job.type) {
            "receipt" -> appendReceipt(output, job, settings)
            "kitchen_ticket" -> appendKitchenTicket(output, job, settings)
            else -> job.lines.forEach { line ->
                appendLine(output, line)
            }
        }
        output.write(FEED_3)
        output.write(CUT_FULL)
        return output.toByteArray()
    }

    private fun appendReceipt(output: ByteArrayOutputStream, job: PrintJob, settings: BridgeSettings) {
        val columns = resolveColumns(job.paperWidth, settings.paperWidthColumns)
        val layout = job.receiptLayout
        val transaction = job.transaction

        layout?.metaRows?.forEachIndexed { index, row ->
            if (index == 0) {
                output.write(ALIGN_CENTER)
                output.write(BOLD_ON)
                row.value.writeLineTo(output)
                output.write(BOLD_OFF)
                output.write(ALIGN_LEFT)
            } else {
                appendKeyValueLine(output, row, columns)
            }
        }

        if (layout != null) {
            divider(columns).writeLineTo(output)
            layout.items.forEach { item ->
                appendReceiptItem(output, item, columns)
            }
            divider(columns).writeLineTo(output)
            layout.totals.forEach { row ->
                appendKeyValueLine(output, row, columns)
            }
            divider(columns).writeLineTo(output)
            layout.payments.forEach { row ->
                appendKeyValueLine(output, row, columns)
            }
            divider(columns).writeLineTo(output)
            output.write(ALIGN_CENTER)
            layout.footerLines.forEach { line ->
                line.writeLineTo(output)
            }
            output.write(ALIGN_LEFT)
            return
        }

        transaction?.invoice?.let {
            appendLine(output, PrintLine(text = "Invoice: $it", align = "left", bold = true, feed = 0, cut = null))
        }
    }

    private fun appendKitchenTicket(output: ByteArrayOutputStream, job: PrintJob, settings: BridgeSettings) {
        val columns = resolveColumns(job.paperWidth, settings.paperWidthColumns)
        val ticket = job.kitchenTicket
        val transaction = job.transaction

        output.write(ALIGN_CENTER)
        output.write(BOLD_ON)
        (ticket?.stationName ?: "KITCHEN").writeLineTo(output)
        ticket?.number?.let { "#$it".writeLineTo(output) }
        output.write(BOLD_OFF)
        output.write(ALIGN_LEFT)
        divider(columns).writeLineTo(output)

        transaction?.invoice?.let { "Invoice: $it".writeLineTo(output) }
        transaction?.customer?.let { "Customer: $it".writeLineTo(output) }
        transaction?.date?.let { "Waktu: $it".writeLineTo(output) }
        transaction?.orderType?.let { "Tipe: ${normalizeOrderType(it)}".writeLineTo(output) }
        ticket?.notes?.takeIf { it.isNotBlank() }?.let { "Catatan: $it".writeLineTo(output) }
        divider(columns).writeLineTo(output)

        ticket?.items?.forEach { item ->
            appendKitchenItem(output, item, columns)
        }
    }

    private fun appendLine(output: ByteArrayOutputStream, line: PrintLine) {
        output.write(alignmentBytes(line.align))
        output.write(if (line.bold) BOLD_ON else BOLD_OFF)

        line.text?.let {
            output.write(it.toByteArray(Charsets.UTF_8))
            output.write(LINE_BREAK)
        }

        repeat(line.feed.coerceAtLeast(0)) {
            output.write(LINE_BREAK)
        }

        when (line.cut?.lowercase()) {
            "partial" -> output.write(CUT_PARTIAL)
            "full" -> output.write(CUT_FULL)
        }
    }

    private fun alignmentBytes(align: String): ByteArray {
        return when (align.lowercase()) {
            "center" -> ALIGN_CENTER
            "right" -> ALIGN_RIGHT
            else -> ALIGN_LEFT
        }
    }

    private fun appendReceiptItem(output: ByteArrayOutputStream, item: ReceiptItemPayload, columns: Int) {
        output.write(BOLD_ON)
        wrapText(item.name, columns).forEach { it.writeLineTo(output) }
        output.write(BOLD_OFF)

        val detailLeft = item.detailLeft.orEmpty()
        val detailRight = item.detailRight.orEmpty()
        if (detailLeft.isNotBlank() || detailRight.isNotBlank()) {
            formatPair(detailLeft, detailRight, columns).forEach { it.writeLineTo(output) }
        }

        item.promo?.takeIf { it.isNotBlank() }?.let {
            wrapText("Promo: $it", columns).forEach { line -> line.writeLineTo(output) }
        }

        item.modifiers.forEach { modifier ->
            formatPair(modifier.label, modifier.value, columns).forEach { it.writeLineTo(output) }
        }

        item.notes?.takeIf { it.isNotBlank() }?.let {
            wrapText("Catatan: $it", columns).forEach { line -> line.writeLineTo(output) }
        }
    }

    private fun appendKitchenItem(output: ByteArrayOutputStream, item: KitchenItemPayload, columns: Int) {
        output.write(BOLD_ON)
        val title = "${formatQty(item.qty)}x ${item.name}"
        wrapText(title, columns).forEach { it.writeLineTo(output) }
        output.write(BOLD_OFF)

        item.notes?.takeIf { it.isNotBlank() }?.let {
            wrapText("Catatan: $it", columns).forEach { line -> line.writeLineTo(output) }
        }

        output.write(LINE_BREAK)
    }

    private fun appendKeyValueLine(output: ByteArrayOutputStream, row: LabelValueRow, columns: Int) {
        if (row.strong) {
            output.write(BOLD_ON)
        }
        formatPair(row.label, row.value, columns).forEach { it.writeLineTo(output) }
        if (row.strong) {
            output.write(BOLD_OFF)
        }
    }

    private fun formatPair(left: String, right: String, columns: Int): List<String> {
        if (right.isBlank()) {
            return wrapText(left, columns)
        }

        val rightWidth = maxOf(8, minOf(14, right.length + 1))
        val leftWidth = maxOf(8, columns - rightWidth - 1)
        val leftLines = wrapText(left, leftWidth).ifEmpty { listOf("") }
        val rightValue = right.take(rightWidth)

        return leftLines.mapIndexed { index, line ->
            if (index == 0) {
                line.padEnd(leftWidth) + " " + rightValue.padStart(rightWidth)
            } else {
                line
            }
        }
    }

    private fun wrapText(text: String, width: Int): List<String> {
        val clean = text.trim()
        if (clean.isBlank()) {
            return emptyList()
        }

        val words = clean.split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var current = ""

        for (word in words) {
            val candidate = if (current.isBlank()) word else "$current $word"
            if (candidate.length <= width) {
                current = candidate
                continue
            }

            if (current.isNotBlank()) {
                lines += current
            }

            if (word.length <= width) {
                current = word
            } else {
                word.chunked(width).forEach { chunk ->
                    if (chunk.length == width) {
                        lines += chunk
                    } else {
                        current = chunk
                    }
                }
            }
        }

        if (current.isNotBlank()) {
            lines += current
        }

        return lines
    }

    private fun resolveColumns(paperWidth: String?, fallbackColumns: Int): Int {
        return when (paperWidth?.lowercase()) {
            "80mm" -> 48
            "58mm" -> 32
            else -> fallbackColumns
        }
    }

    private fun divider(columns: Int): String = "-".repeat(columns.coerceAtLeast(16))

    private fun String.writeLineTo(output: ByteArrayOutputStream) {
        output.write(toByteArray(Charsets.UTF_8))
        output.write(LINE_BREAK)
    }

    private fun normalizeOrderType(value: String): String {
        return when (value.lowercase()) {
            "dine_in" -> "Dine In"
            "take_away" -> "Take Away"
            else -> value
        }
    }

    private fun formatQty(value: Double): String {
        val intValue = value.toInt()
        return if (value == intValue.toDouble()) intValue.toString() else value.toString()
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val INITIALIZE = byteArrayOf(0x1B, 0x40)
        private val LINE_BREAK = byteArrayOf(0x0A)
        private val BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01)
        private val BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)
        private val ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00)
        private val ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
        private val ALIGN_RIGHT = byteArrayOf(0x1B, 0x61, 0x02)
        private val FEED_3 = byteArrayOf(0x1B, 0x64, 0x03)
        private val CUT_FULL = byteArrayOf(0x1D, 0x56, 0x00)
        private val CUT_PARTIAL = byteArrayOf(0x1D, 0x56, 0x01)
    }
}
