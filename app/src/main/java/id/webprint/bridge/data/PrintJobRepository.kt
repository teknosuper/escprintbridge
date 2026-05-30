package id.webprint.bridge.data

class PrintJobRepository(
    private val settingsRepository: SettingsRepository,
    private val apiClient: BridgeApiClient = BridgeApiClient(),
) {
    var lastFetchedJobIds: List<String> = emptyList()
        private set

    fun fetchJobs(settings: BridgeSettings = settingsRepository.load()): List<PrintJob> {
        val jobs = apiClient.fetchJobs(settings)
        lastFetchedJobIds = jobs.map { it.id }
        return jobs
    }

    fun markComplete(settings: BridgeSettings = settingsRepository.load(), jobId: String) {
        apiClient.markComplete(settings, jobId)
        lastFetchedJobIds = lastFetchedJobIds.filterNot { it == jobId }
    }

    fun markFailed(settings: BridgeSettings = settingsRepository.load(), jobId: String, reason: String) {
        apiClient.markFailed(settings, jobId, reason)
        lastFetchedJobIds = lastFetchedJobIds.filterNot { it == jobId }
    }

    fun getPollUrl(settings: BridgeSettings = settingsRepository.load()): String {
        return apiClient.buildPollUrlForDisplay(settings)
    }
}
