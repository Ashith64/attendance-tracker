package app.attendancetracker.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.attendancetracker.MainActivity
import app.attendancetracker.data.AttendanceDatabase
import app.attendancetracker.data.AttendanceRepository
import app.attendancetracker.network.DataStoreCookiePersistence
import app.attendancetracker.network.PersistentCookieJar
import app.attendancetracker.network.PortalFetcher
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager sync.
 *
 * Hard rule:
 * - Uses ONLY persisted cookies. Never touches or decrypts CredentialStore.
 * - If the session is missing or expired, posts 'Open the app to refresh your attendance'
 *   and does nothing else.
 */
class AttendanceSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "periodic_attendance_sync"
        const val SYNC_INTERVAL_HOURS = 6L
        const val CHANNEL_ID = "attendance_sync_session"
        const val NOTIFICATION_ID = 1001
        const val SESSION_EXPIRED_MESSAGE = "Open the app to refresh your attendance"

        /**
         * Enqueues the 6-hour periodic cookie-only background sync with a connected-network constraint.
         *
         * Note: Android's WorkManager does not guarantee exact 6-hour execution; Doze mode,
         * App Standby Buckets, and OEM battery optimizations may delay periodic jobs significantly.
         */
        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<AttendanceSyncWorker>(
                SYNC_INTERVAL_HOURS,
                TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }

    override suspend fun doWork(): Result {
        val cookieJar = PersistentCookieJar(DataStoreCookiePersistence(applicationContext))
        if (!cookieJar.hasCookiesFor(PortalFetcher.DEFAULT_BASE_URL)) {
            postSessionExpiredNotification(applicationContext)
            return Result.success()
        }

        val database = AttendanceDatabase.getInstance(applicationContext)
        val repository = AttendanceRepository(database.portalDao())
        val defaultUserAgent = try {
            android.webkit.WebSettings.getDefaultUserAgent(applicationContext)
        } catch (_: Throwable) {
            PortalFetcher.DEFAULT_BROWSER_USER_AGENT
        }
        val fetcher = PortalFetcher(cookieJar = cookieJar, userAgent = defaultUserAgent)

        val summary = fetcher.syncAll(repository = repository)
        if (summary.sessionExpired) {
            postSessionExpiredNotification(applicationContext)
        }
        return Result.success()
    }

    private fun postSessionExpiredNotification(context: Context) {
        ensureNotificationChannel(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Attendance Tracker")
            .setContentText(SESSION_EXPIRED_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Attendance Sync",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Alerts when portal session needs to be refreshed in the app"
            }
            manager.createNotificationChannel(channel)
        }
    }
}
