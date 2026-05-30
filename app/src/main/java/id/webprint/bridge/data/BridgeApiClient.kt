package id.webprint.bridge.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BridgeApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun fetchJobs(settings: BridgeSettings): List<PrintJob> {
        val pollUrl = buildPollUrl(settings)
        val request = Request.Builder()
            .url(pollUrl)
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Fetch job failed with HTTP ${response.code}")
            }

            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            return when {
                json.has("jobs") -> parseQueueJobs(json.optJSONArray("jobs"))
                json.isNull("job") -> emptyList()
                else -> listOf(PrintJob.fromJson(json.getJSONObject("job")))
            }
        }
    }

    fun markComplete(settings: BridgeSettings, jobId: String) {
        sendQueueStatus(
            url = buildQueueStatusUrl(settings, jobId, "done"),
            payload = JSONObject().put("status", "completed"),
        )
    }

    fun markFailed(settings: BridgeSettings, jobId: String, reason: String) {
        sendQueueStatus(
            url = buildQueueStatusUrl(settings, jobId, "fail"),
            payload = JSONObject()
                .put("status", "failed")
                .put("reason", reason.take(500)),
        )
    }

    private fun sendQueueStatus(
        url: String,
        payload: JSONObject,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Content-Type", JSON_MEDIA_TYPE)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Update job status failed with HTTP ${response.code}")
            }
        }
    }

    private fun parseQueueJobs(jsonArray: JSONArray?): List<PrintJob> {
        if (jsonArray == null) {
            return emptyList()
        }

        return buildList(jsonArray.length()) {
            for (index in 0 until jsonArray.length()) {
                add(PrintJob.fromPosQueueJson(jsonArray.getJSONObject(index)))
            }
        }
    }

    private fun buildPollUrl(settings: BridgeSettings): String {
        val queueType = QueueType.fromValue(settings.queueType).value
        val httpUrl = "${settings.baseUrl}/api/print-queue/$queueType"
            .toHttpUrlOrNull()
            ?: error("Base URL tidak valid")

        return httpUrl.newBuilder()
            .addQueryParameter("token", settings.token)
            .addQueryParameter("outlet_id", settings.outletId.toString())
            .apply {
                if (queueType == QueueType.KITCHEN.value && settings.stationId.isNotBlank()) {
                    addQueryParameter("station_id", settings.stationId)
                }
                if (settings.deviceId.isNotBlank()) {
                    addQueryParameter("device_id", settings.deviceId)
                }
            }
            .build()
            .toString()
    }

    private fun buildQueueStatusUrl(settings: BridgeSettings, jobId: String, action: String): String {
        val httpUrl = "${settings.baseUrl}/api/print-queue/$jobId/$action"
            .toHttpUrlOrNull()
            ?: error("Base URL tidak valid")

        return httpUrl.newBuilder()
            .addQueryParameter("token", settings.token)
            .build()
            .toString()
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val JSON_MEDIA_TYPE = "application/json"
    }
}
