package app.meanwhile.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.meanwhile.MeanwhileApp
import app.meanwhile.service.CgmService
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MeanwhileApp).container
        // Watchdog: WorkManager survives the OS killing the CGM service — restart it and catch up
        // on outcome tagging / a missed 6 am check, so the app heals itself without being opened.
        CgmService.start(applicationContext)
        runCatching { container.housekeeping() }
        // Offline is not a failure: the next 15-min run tries again (CGM is localhost and must not
        // wait for internet, which is why the periodic work has no network constraint).
        if (!container.network.online.value) return Result.success()
        return when (container.sync.syncOnce()) {
            SyncOutcome.FAILED -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private const val NOW = "sync-now"
        private const val PERIODIC = "sync-periodic"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Sync as soon as there is a network; called after every local write. */
        fun requestNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /** Safety net in case a one-off request is lost. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                // UPDATE so existing installs drop the old network constraint.
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
