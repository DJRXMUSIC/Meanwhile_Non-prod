package app.meanwhile.data.learn

import app.meanwhile.log.AppLog
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.meanwhile.MeanwhileApp

/** Runs the learn cycle off the alarm receiver; retries once online if the 1 am attempt failed. */
class LearnWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as MeanwhileApp).container
        val force = inputData.getBoolean(KEY_FORCE, false)
        AppLog.i("Learn", "nightly worker running" + if (force) " (retry)" else "")
        val result = c.nightly.learnCycleIfNeeded(force = force)
        if (result?.status == "failed" && !force) enqueueRetryWhenOnline(applicationContext)
        return Result.success()
    }

    companion object {
        private const val KEY_FORCE = "force"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "learn-cycle", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LearnWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build(),
            )
        }

        private fun enqueueRetryWhenOnline(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "learn-cycle-retry", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<LearnWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(androidx.work.workDataOf(KEY_FORCE to true))
                    .build(),
            )
        }
    }
}
