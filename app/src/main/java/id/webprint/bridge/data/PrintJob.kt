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
            val rawBase64 = payload.optString("raw_base64").ifBlank { null }
            return PrintJob(
                id = json.getString("id"),
                type = json.optString("type").ifBlank { null },
                printerMode = printer?.optString("mode")?.ifBlank { null },
                printerHost = printer?.optString("host")?.ifBlank { null },
                printerPort = printer?.optInt("port")?.takeIf { it != 0 },
                bluetoothMacAddress = printer?.optString("bluetooth_mac_address")?.ifBlank { null },
                timeoutMs = printer?.optInt("timeout_ms")?.takeIf { it != 0 },
                lines = payload.optJSONArray("lines").toPrintLines(),
                rawBytes = rawBase64?.let { Base64.decode(it, Base64.DEFAULT) },
                paperWidth = payload.optString("paper_width").ifBlank { null },
                receiptLayout = null,
                transaction = null,
                kitchenTicket = null,
            )
        }

        fun fromPosQueueJson(json: JSONObject): PrintJob {
            return PrintJob(
                id = json.get("id").toString(),
                type = json.optString("type").ifBlank { null },
                printerMode = null,
                printerHost = null,
                printerPort = null,
                bluetoothMacAddress = null,
                timeoutMs = null,
                lines = emptyList(),
                rawBytes = null,
                paperWidth = json.optString("paper_width").ifBlank { null },
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
                            text = item.optString("text").ifBlank { null },
                            align = item.optString("align").ifBlank { "left" },
                            bold = item.optBoolean("bold"),
                            feed = item.optInt("feed"),
                            cut = item.optString("cut").ifBlank { null },
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
                invoice = optString("invoice").ifBlank { null },
                date = optString("date").ifBlank { null },
                customer = optString("customer").ifBlank { null },
                orderType = optString("order_type").ifBlank { null },
            )
        }

        private fun JSONObject.toKitchenTicketPayload(stationJson: JSONObject?): KitchenTicketPayload {
            return KitchenTicketPayload(
                number = optString("number").ifBlank { null },
                notes = optString("notes").ifBlank { null },
                createdAt = optString("created_at").ifBlank { null },
                stationName = stationJson?.optString("name")?.ifBlank { null },
                stationCode = stationJson?.optString("code")?.ifBlank { null },
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
                            label = item.optString("label"),
                            value = item.optString("value"),
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
                            name = item.optString("name"),
                            detailLeft = item.optString("detail_left").ifBlank { null },
                            detailRight = item.optString("detail_right").ifBlank { null },
                            promo = item.optString("promo").ifBlank { null },
                            notes = item.optString("notes").ifBlank { null },
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
                            name = item.optString("name"),
                            qty = item.optDouble("qty", 0.0),
                            notes = item.optString("notes").ifBlank { null },
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
                    add(optString(index))
                }
            }
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
