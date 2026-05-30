package id.webprint.bridge.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import id.webprint.bridge.MainActivity
import id.webprint.bridge.R
import id.webprint.bridge.data.PrintJobRepository
import id.webprint.bridge.data.SettingsRepository
import id.webprint.bridge.printer.EscPosPrinter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BridgeForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var printJobRepository: PrintJobRepository
    private lateinit var printer: EscPosPrinter
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        printJobRepository = PrintJobRepository(settingsRepository)
        printer = EscPosPrinter()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_idle)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loopJob?.isActive == true) {
            return START_STICKY
        }

        loopJob = serviceScope.launch {
            while (isActive) {
                val settings = settingsRepository.load()
                if (!settings.isReady()) {
                    updateNotification(getString(R.string.notification_waiting_config))
                    delay(5_000)
                    continue
                }

                var printDelivered = false
                try {
                    updateNotification(getString(R.string.notification_polling, settings.baseUrl))
                    val jobs = printJobRepository.fetchJobs(settings)
                    if (jobs.isEmpty()) {
                        delay(settings.pollingSeconds.coerceAtLeast(2L) * 1_000L)
                        continue
                    }

                    for (job in jobs) {
                        printDelivered = false
                        updateNotification(getString(R.string.notification_printing, job.id))
                        printer.print(job, settings)
                        printDelivered = true
                        printJobRepository.markComplete(settings, job.id)
                        updateNotification(getString(R.string.notification_success, job.id))
                    }
                } catch (exception: Exception) {
                    val message = exception.message ?: exception.javaClass.simpleName
                    updateNotification(getString(R.string.notification_error, message))
                    val lastJobId = printJobRepository.lastFetchedJobIds.firstOrNull()
                    if (!printDelivered && !lastJobId.isNullOrBlank()) {
                        runCatching {
                            printJobRepository.markFailed(settings, lastJobId, message)
                        }
                    }
                    delay(settings.pollingSeconds.coerceAtLeast(2L) * 1_000L)
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(content: String): Notification {
        val activityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_bridge)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_description)
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "print-bridge-service"
        private const val NOTIFICATION_ID = 2001
    }
}
