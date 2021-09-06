package org.calyxos.backup.storage.backup

import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.TimeUnit

private const val BACKUP_JOB_ID = 0
private const val TAG = "BackupJobService"

public abstract class BackupJobService(private val serviceClass: Class<out Service>) :
    JobService() {

    public companion object {
        public fun scheduleJob(
            context: Context,
            jobServiceClass: Class<*>,
            periodMillis: Long = TimeUnit.HOURS.toMillis(24),
            networkType: Int? = JobInfo.NETWORK_TYPE_UNMETERED,
            deviceIdle: Boolean = true,
            charging: Boolean = true,
        ) {
            val serviceComponent = ComponentName(context, jobServiceClass)
            val jobInfoBuilder = JobInfo.Builder(BACKUP_JOB_ID, serviceComponent)
                .setRequiresDeviceIdle(deviceIdle)
                .setRequiresCharging(charging)
                .setPeriodic(periodMillis)
                .setPersisted(true)
            if (networkType != null) jobInfoBuilder.setRequiredNetworkType(networkType)
            val jobScheduler: JobScheduler = context.getSystemService(JobScheduler::class.java)
            jobScheduler.schedule(jobInfoBuilder.build())
        }

        public fun isScheduled(context: Context): Boolean {
            val jobScheduler: JobScheduler = context.getSystemService(JobScheduler::class.java)
            return jobScheduler.getPendingJob(BACKUP_JOB_ID) != null
        }

        public fun cancelJob(context: Context) {
            val jobScheduler: JobScheduler = context.getSystemService(JobScheduler::class.java)
            jobScheduler.cancel(BACKUP_JOB_ID)
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.i(TAG, "onStartJob ${params?.jobId} ${params?.extras} ${params?.network}")
        val i = Intent(applicationContext, serviceClass)
        startForegroundService(i)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.i(TAG, "onStopJob ${params?.jobId}")
        return true
    }
}
