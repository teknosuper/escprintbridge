package id.webprint.bridge.printer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import id.webprint.bridge.data.BluetoothTransportMode
import id.webprint.bridge.data.BridgeSettings
import id.webprint.bridge.data.KitchenItemPayload
import id.webprint.bridge.data.LabelValueRow
import id.webprint.bridge.data.PrinterMode
import id.webprint.bridge.data.PrintJob
import id.webprint.bridge.data.PrintLine
import id.webprint.bridge.data.ReceiptItemPayload
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

class EscPosPrinter(
    context: Context,
) {

    private val blePrinterClient = BluetoothGattPrinterClient(context.applicationContext)

    fun print(job: PrintJob, settings: BridgeSettings) {
        val mode = resolvePrinterMode(job, settings)
        val bytes = buildPayload(job, settings, mode)

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
            writeEscPosPayload(socket.getOutputStream(), bytes)
        }
    }

    @SuppressLint("MissingPermission")
    private fun printBluetooth(job: PrintJob, settings: BridgeSettings, bytes: ByteArray) {
        val macAddress = job.bluetoothMacAddress ?: settings.bluetoothMacAddress
        require(macAddress.isNotBlank()) { "Printer Bluetooth belum dipilih" }

        when (BluetoothTransportMode.fromValue(settings.bluetoothTransportMode)) {
            BluetoothTransportMode.CLASSIC -> printBluetoothClassic(macAddress, bytes)
            BluetoothTransportMode.BLE -> blePrinterClient.print(macAddress, bytes)
            BluetoothTransportMode.AUTO -> {
                val classicResult = runCatching { printBluetoothClassic(macAddress, bytes) }
                if (classicResult.isFailure) {
                    blePrinterClient.print(macAddress, bytes)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun printBluetoothClassic(macAddress: String, bytes: ByteArray) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: error("Bluetooth tidak tersedia di device ini")

        require(adapter.isEnabled) { "Bluetooth sedang nonaktif" }

        val device = adapter.bondedDevices.firstOrNull { it.address.equals(macAddress, ignoreCase = true) }
            ?: error("Printer Bluetooth dengan MAC $macAddress belum paired")

        runCatching {
            adapter.cancelDiscovery()
        }

        connectBluetoothSocket(device).use { socket ->
            writeEscPosPayload(socket.outputStream, bytes)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectBluetoothSocket(device: BluetoothDevice): BluetoothSocket {
        val attempts = listOf<(BluetoothDevice) -> BluetoothSocket>(
            { target -> target.createRfcommSocketToServiceRecord(SPP_UUID) },
            { target -> target.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            { target ->
                val method = target.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                method.invoke(target, 1) as BluetoothSocket
            },
        )

        var lastError: Throwable? = null
        attempts.forEachIndexed { index, createSocket ->
            val socket = runCatching { createSocket(device) }.getOrElse {
                lastError = it
                return@forEachIndexed
            }

            try {
                socket.connect()
                return socket
            } catch (exception: Exception) {
                lastError = exception
                runCatching { socket.close() }
            }
        }

        throw IllegalStateException(
            "Gagal membuka koneksi Bluetooth printer. ${lastError?.message ?: "Socket connect error"}",
            lastError,
        )
    }

    private fun writeEscPosPayload(output: OutputStream, bytes: ByteArray) {
        output.use {
            it.write(bytes)
            it.flush()
            // Beberapa printer thermal Bluetooth butuh jeda singkat sebelum socket ditutup.
            Thread.sleep(350)
        }
    }

    private fun buildPayload(job: PrintJob, settings: BridgeSettings, mode: PrinterMode): ByteArray {
        if (job.rawBytes != null) {
            return job.rawBytes
        }

        if (job.lines.isNotEmpty()) {
            val output = ByteArrayOutputStream()
            output.write(INITIALIZE)
            job.lines.forEach { line ->
                appendLine(output, line)
            }
            if (mode == PrinterMode.BLUETOOTH) {
                output.write(FEED_6)
            } else {
                output.write(FEED_3)
                output.write(CUT_FULL)
            }
            return output.toByteArray()
        }

        if (job.type.equals("receipt", ignoreCase = true) && (job.receiptLayout != null || job.transaction != null)) {
            val output = ByteArrayOutputStream()
            output.write(INITIALIZE)
            appendReceipt(output, job, settings)
            if (mode == PrinterMode.BLUETOOTH) {
                output.write(FEED_6)
            } else {
                output.write(FEED_3)
                output.write(CUT_FULL)
            }
            return output.toByteArray()
        }

        if (
            job.type.equals("kitchen_ticket", ignoreCase = true) &&
            (job.kitchenTicket != null || job.transaction != null)
        ) {
            val output = ByteArrayOutputStream()
            output.write(INITIALIZE)
            appendKitchenTicket(output, job, settings)
            if (mode == PrinterMode.BLUETOOTH) {
                output.write(FEED_6)
            } else {
                output.write(FEED_3)
                output.write(CUT_FULL)
            }
            return output.toByteArray()
        }

        throw IllegalStateException(
            "Job print tidak membawa payload final dari web. Kirim payload.raw_base64 agar Android hanya bertindak sebagai bridge printer.",
        )
    }

    private fun appendReceipt(output: ByteArrayOutputStream, job: PrintJob, settings: BridgeSettings) {
        val columns = resolveColumns(job.paperWidth, settings.resolvedPaperWidthColumns())
        val layout = job.receiptLayout
        val transaction = job.transaction

        layout?.metaRows?.forEach { row ->
            if (row.label.isBlank() && row.value.isNotBlank()) {
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
            appendReceiptItemsHeader(output, columns)
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
            transaction?.invoice?.takeIf { it.isNotBlank() }?.let {
                appendBarcode(output, it)
            }
            output.write(ALIGN_LEFT)
            return
        }

        transaction?.invoice?.let {
            appendLine(output, PrintLine(text = "Invoice: $it", align = "left", bold = true, feed = 0, cut = null))
        }
    }

    private fun appendKitchenTicket(output: ByteArrayOutputStream, job: PrintJob, settings: BridgeSettings) {
        val columns = resolveColumns(job.paperWidth, settings.resolvedPaperWidthColumns())
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
        formatReceiptPrimaryLine(
            qty = item.detailLeft.orEmpty(),
            name = item.name,
            total = item.detailRight.orEmpty(),
            columns = columns,
        ).forEach { line ->
            output.write(BOLD_ON)
            line.writeLineTo(output)
            output.write(BOLD_OFF)
        }

        item.promo?.takeIf { it.isNotBlank() }?.let {
            wrapText("  Promo: $it", columns).forEach { line -> line.writeLineTo(output) }
        }

        item.modifiers.forEach { modifier ->
            val left = listOf("  ", modifier.label).filter { it.isNotBlank() }.joinToString("")
            formatPair(left, modifier.value, columns).forEach { it.writeLineTo(output) }
        }

        item.notes?.takeIf { it.isNotBlank() }?.let {
            wrapText("  Catatan: $it", columns).forEach { line -> line.writeLineTo(output) }
        }

        output.write(LINE_BREAK)
    }

    private fun appendReceiptItemsHeader(output: ByteArrayOutputStream, columns: Int) {
        formatReceiptPrimaryLine(
            qty = "Qty",
            name = "Item",
            total = "Total",
            columns = columns,
        ).forEach { line ->
            line.writeLineTo(output)
        }
        output.write(LINE_BREAK)
    }

    private fun formatReceiptPrimaryLine(qty: String, name: String, total: String, columns: Int): List<String> {
        val cleanName = name.trim()
        if (cleanName.isBlank()) {
            return emptyList()
        }

        if (qty.isBlank() && total.isBlank()) {
            return wrapText(cleanName, columns)
        }

        val qtyWidth = maxOf(3, minOf(5, qty.length.coerceAtLeast(3)))
        val totalWidth = maxOf(6, minOf(10, total.length.coerceAtLeast(5)))
        val nameWidth = maxOf(8, columns - qtyWidth - totalWidth - 2)
        val nameLines = wrapText(cleanName, nameWidth).ifEmpty { listOf("") }

        return nameLines.mapIndexed { index, line ->
            when (index) {
                0 -> qty.padEnd(qtyWidth) + " " + line.padEnd(nameWidth) + " " + total.padStart(totalWidth)
                else -> " ".repeat(qtyWidth + 1) + line
            }
        }
    }

    private fun appendBarcode(output: ByteArrayOutputStream, value: String) {
        val payload = "{B$value".toByteArray(Charsets.US_ASCII)
        if (payload.size > 255) {
            return
        }
        output.write(ALIGN_CENTER)
        output.write(BARCODE_HRI_BELOW)
        output.write(BARCODE_HEIGHT)
        output.write(BARCODE_WIDTH)
        output.write(byteArrayOf(0x1D, 0x6B, 0x49, payload.size.toByte()))
        output.write(payload)
        output.write(LINE_BREAK)
        output.write(ALIGN_LEFT)
    }

    private fun appendReceiptItemLegacy(output: ByteArrayOutputStream, item: ReceiptItemPayload, columns: Int) {
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
        private val FEED_6 = byteArrayOf(0x1B, 0x64, 0x06)
        private val CUT_FULL = byteArrayOf(0x1D, 0x56, 0x00)
        private val CUT_PARTIAL = byteArrayOf(0x1D, 0x56, 0x01)
        private val BARCODE_HRI_BELOW = byteArrayOf(0x1D, 0x48, 0x02)
        private val BARCODE_HEIGHT = byteArrayOf(0x1D, 0x68, 0x50)
        private val BARCODE_WIDTH = byteArrayOf(0x1D, 0x77, 0x02)
    }
}
