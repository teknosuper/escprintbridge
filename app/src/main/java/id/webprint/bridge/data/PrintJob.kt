package id.webprint.bridge.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

data class PrintJob(
    val id: String,
    val type: String?,
    val printerMode: String?,
    val printerHost: String?,
    val printerPort: Int?,
    val bluetoothMacAddress: String?,
    val timeoutMs: Int?,
    val lines: List<PrintLine>,
    val rawBytes: ByteArray?,
    val paperWidth: String?,
    val receiptLayout: ReceiptLayout?,
    val transaction: JobTransaction?,
    val kitchenTicket: KitchenTicketPayload?,
) {
    companion object {
        fun fromJson(json: JSONObject): PrintJob {
            val printer = json.optJSONObject("printer")
            val payload = json.getJSONObject("payload")
            val rawBase64 = cleanNullable(payload.optString("raw_base64"))
            return PrintJob(
                id = json.getString("id"),
                type = cleanNullable(json.optString("type")),
                printerMode = cleanNullable(printer?.optString("mode")),
                printerHost = cleanNullable(printer?.optString("host")),
                printerPort = printer?.optInt("port")?.takeIf { it != 0 },
                bluetoothMacAddress = cleanNullable(printer?.optString("bluetooth_mac_address")),
                timeoutMs = printer?.optInt("timeout_ms")?.takeIf { it != 0 },
                lines = payload.optJSONArray("lines").toPrintLines(),
                rawBytes = rawBase64?.let { Base64.decode(it, Base64.DEFAULT) },
                paperWidth = cleanNullable(payload.optString("paper_width")),
                receiptLayout = json.optJSONObject("layout")?.toReceiptLayout(),
                transaction = json.optJSONObject("transaction")?.toTransactionPayload(),
                kitchenTicket = json.optJSONObject("ticket")?.toKitchenTicketPayload(json.optJSONObject("station")),
            )
        }

        fun fromPosQueueJson(json: JSONObject): PrintJob {
            val printer = json.optJSONObject("printer")
            val payload = json.optJSONObject("payload")
            val rawBase64 = cleanNullable(payload?.optString("raw_base64"))
            return PrintJob(
                id = json.get("id").toString(),
                type = cleanNullable(json.optString("type")),
                printerMode = cleanNullable(printer?.optString("mode")),
                printerHost = cleanNullable(printer?.optString("host")),
                printerPort = printer?.optInt("port")?.takeIf { it != 0 },
                bluetoothMacAddress = cleanNullable(printer?.optString("bluetooth_mac_address")),
                timeoutMs = printer?.optInt("timeout_ms")?.takeIf { it != 0 },
                lines = payload?.optJSONArray("lines").toPrintLines(),
                rawBytes = rawBase64?.let { Base64.decode(it, Base64.DEFAULT) },
                paperWidth = cleanNullable(json.optString("paper_width"))
                    ?: cleanNullable(payload?.optString("paper_width")),
                receiptLayout = json.optJSONObject("layout")?.toReceiptLayout(),
                transaction = json.optJSONObject("transaction")?.toTransactionPayload(),
                kitchenTicket = json.optJSONObject("ticket")?.toKitchenTicketPayload(json.optJSONObject("station")),
            )
        }

        private fun JSONArray?.toPrintLines(): List<PrintLine> {
            if (this == null) {
                return emptyList()
            }

            return buildList(length()) {
                for (index in 0 until length()) {
                    val item = getJSONObject(index)
                    add(
                        PrintLine(
                            text = cleanNullable(item.optString("text")),
                            align = cleanNullable(item.optString("align")) ?: "left",
                            bold = item.optBoolean("bold"),
                            feed = item.optInt("feed"),
                            cut = cleanNullable(item.optString("cut")),
                        ),
                    )
                }
            }
        }

        private fun JSONObject.toReceiptLayout(): ReceiptLayout {
            return ReceiptLayout(
                metaRows = optJSONArray("meta_rows").toLabelRows(),
                items = optJSONArray("items").toReceiptItems(),
                totals = optJSONArray("totals").toLabelRows(),
                payments = optJSONArray("payments").toLabelRows(),
                footerLines = optJSONArray("footer_lines").toStringList(),
            )
        }

        private fun JSONObject.toTransactionPayload(): JobTransaction {
            return JobTransaction(
                invoice = cleanNullable(optString("invoice")),
                date = cleanNullable(optString("date")),
                customer = cleanNullable(optString("customer")),
                orderType = cleanNullable(optString("order_type")),
            )
        }

        private fun JSONObject.toKitchenTicketPayload(stationJson: JSONObject?): KitchenTicketPayload {
            return KitchenTicketPayload(
                number = cleanNullable(optString("number")),
                notes = cleanNullable(optString("notes")),
                createdAt = cleanNullable(optString("created_at")),
                stationName = cleanNullable(stationJson?.optString("name")),
                stationCode = cleanNullable(stationJson?.optString("code")),
                items = optJSONArray("items").toKitchenItems(),
            )
        }

        private fun JSONArray?.toLabelRows(): List<LabelValueRow> {
            if (this == null) {
                return emptyList()
            }

            return buildList(length()) {
                for (index in 0 until length()) {
                    val item = getJSONObject(index)
                    add(
                        LabelValueRow(
                            label = cleanNullable(item.optString("label")).orEmpty(),
                            value = cleanNullable(item.optString("value")).orEmpty(),
                            strong = item.optBoolean("strong"),
                        ),
                    )
                }
            }
        }

        private fun JSONArray?.toReceiptItems(): List<ReceiptItemPayload> {
            if (this == null) {
                return emptyList()
            }

            return buildList(length()) {
                for (index in 0 until length()) {
                    val item = getJSONObject(index)
                    add(
                        ReceiptItemPayload(
                            name = cleanNullable(item.optString("name")).orEmpty(),
                            detailLeft = cleanNullable(item.optString("detail_left")),
                            detailRight = cleanNullable(item.optString("detail_right")),
                            promo = cleanNullable(item.optString("promo")),
                            notes = cleanNullable(item.optString("notes")),
                            modifiers = item.optJSONArray("modifiers").toLabelRows(),
                        ),
                    )
                }
            }
        }

        private fun JSONArray?.toKitchenItems(): List<KitchenItemPayload> {
            if (this == null) {
                return emptyList()
            }

            return buildList(length()) {
                for (index in 0 until length()) {
                    val item = getJSONObject(index)
                    add(
                        KitchenItemPayload(
                            name = cleanNullable(item.optString("name")).orEmpty(),
                            qty = item.optDouble("qty", 0.0),
                            notes = cleanNullable(item.optString("notes")),
                        ),
                    )
                }
            }
        }

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) {
                return emptyList()
            }

            return buildList(length()) {
                for (index in 0 until length()) {
                    cleanNullable(optString(index))?.let(::add)
                }
            }
        }

        private fun cleanNullable(value: String?): String? {
            val cleaned = value?.trim().orEmpty()
            if (cleaned.isBlank()) {
                return null
            }
            return if (cleaned.equals("null", ignoreCase = true)) null else cleaned
        }
    }
}

data class PrintLine(
    val text: String?,
    val align: String,
    val bold: Boolean,
    val feed: Int,
    val cut: String?,
)

data class ReceiptLayout(
    val metaRows: List<LabelValueRow>,
    val items: List<ReceiptItemPayload>,
    val totals: List<LabelValueRow>,
    val payments: List<LabelValueRow>,
    val footerLines: List<String>,
)

data class LabelValueRow(
    val label: String,
    val value: String,
    val strong: Boolean = false,
)

data class ReceiptItemPayload(
    val name: String,
    val detailLeft: String?,
    val detailRight: String?,
    val promo: String?,
    val notes: String?,
    val modifiers: List<LabelValueRow>,
)

data class JobTransaction(
    val invoice: String?,
    val date: String?,
    val customer: String?,
    val orderType: String?,
)

data class KitchenTicketPayload(
    val number: String?,
    val notes: String?,
    val createdAt: String?,
    val stationName: String?,
    val stationCode: String?,
    val items: List<KitchenItemPayload>,
)

data class KitchenItemPayload(
    val name: String,
    val qty: Double,
    val notes: String?,
)
