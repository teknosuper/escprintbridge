package id.webprint.bridge.data

import org.json.JSONObject

data class QueueCheckResult(
    val ok: Boolean,
    val message: String,
    val pollUrl: String,
)

class QueueDiagnostics(
    private val apiClient: BridgeApiClient = BridgeApiClient(),
) {
    fun check(settings: BridgeSettings): QueueCheckResult {
        val pollUrl = apiClient.buildPollUrlForDisplay(settings)
        return try {
            val response = apiClient.fetchQueueResponse(settings)
            val json = JSONObject(response.body)
            val jobsCount = json.optJSONArray("jobs")?.length() ?: 0
            QueueCheckResult(
                ok = response.code in 200..299,
                message = "Queue OK. HTTP ${response.code}, jobs=$jobsCount",
                pollUrl = pollUrl,
            )
        } catch (exception: Exception) {
            QueueCheckResult(
                ok = false,
                message = "Queue gagal: ${exception.message ?: exception.javaClass.simpleName}",
                pollUrl = pollUrl,
            )
        }
    }
}
