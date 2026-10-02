package com.peaceantz.stagescope.phone.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.peaceantz.stagescope.phone.PhoneApp
import com.peaceantz.stagescope.phone.link.ConfirmCommand
import com.peaceantz.stagescope.phone.link.WorkScheduler
import com.peaceantz.stagescope.shared.actions.ConfirmSource

/**
 * WearableListenerService callbacks acknowledge and persist, then hand durable work here. WorkManager
 * keeps the work across process death; expedited jobs run promptly on Android 12+ (as expedited
 * JobScheduler jobs, no foreground notification needed). Work is unique per id so a retransmitted
 * message cannot enqueue it twice.
 */
class WorkManagerScheduler(private val context: Context) : WorkScheduler {
    private fun wm() = WorkManager.getInstance(context)

    override fun enqueueAssistant(requestId: String) {
        val request = OneTimeWorkRequestBuilder<AssistantWorker>()
            .setInputData(Data.Builder().putString(AssistantWorker.KEY_REQUEST_ID, requestId).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag("assistant")
            .build()
        wm().enqueueUniqueWork("assistant-$requestId", ExistingWorkPolicy.KEEP, request)
    }

    override fun cancelAssistant(requestId: String) {
        wm().cancelUniqueWork("assistant-$requestId")
    }

    override fun enqueueConfirm(cmd: ConfirmCommand) {
        val request = OneTimeWorkRequestBuilder<ConfirmWorker>()
            .setInputData(
                Data.Builder()
                    .putString(ConfirmWorker.KEY_ACTION, cmd.actionId).putString(ConfirmWorker.KEY_OP, cmd.opId)
                    .putInt(ConfirmWorker.KEY_REVISION, cmd.revision).putString(ConfirmWorker.KEY_HASH, cmd.contentHash)
                    .putString(ConfirmWorker.KEY_BY, cmd.by.name).build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag("confirm")
            .build()
        // Unique by operation id: the same confirmation delivered twice runs once.
        wm().enqueueUniqueWork("confirm-${cmd.opId}", ExistingWorkPolicy.KEEP, request)
    }
}

class AssistantWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_REQUEST_ID) ?: return Result.failure()
        (applicationContext as PhoneApp).container.runAssistantRequest(id)
        // Never Result.retry(): a re-run could repeat a model call and its charge. Outcomes are recorded durably.
        return Result.success()
    }

    companion object {
        const val KEY_REQUEST_ID = "requestId"
    }
}

class ConfirmWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val action = inputData.getString(KEY_ACTION) ?: return Result.failure()
        val op = inputData.getString(KEY_OP) ?: return Result.failure()
        val hash = inputData.getString(KEY_HASH) ?: return Result.failure()
        val by = runCatching { ConfirmSource.valueOf(inputData.getString(KEY_BY) ?: "WATCH") }.getOrDefault(ConfirmSource.WATCH)
        (applicationContext as PhoneApp).container.runConfirm(ConfirmCommand(action, op, inputData.getInt(KEY_REVISION, -1), hash, by))
        return Result.success()
    }

    companion object {
        const val KEY_ACTION = "actionId"
        const val KEY_OP = "opId"
        const val KEY_REVISION = "revision"
        const val KEY_HASH = "hash"
        const val KEY_BY = "by"
    }
}

/** Transcribes a watch recording that arrived over the Data Layer channel. */
class TranscribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val memo = inputData.getString(KEY_MEMO) ?: return Result.failure()
        val request = inputData.getString(KEY_REQUEST) ?: return Result.failure()
        (applicationContext as PhoneApp).container.runTranscription(memo, request, inputData.getInt(KEY_RATE, 16_000))
        return Result.success()
    }

    companion object {
        const val KEY_MEMO = "memoId"
        const val KEY_REQUEST = "requestId"
        const val KEY_RATE = "rate"

        fun enqueue(context: Context, memoId: String, requestId: String, rate: Int) {
            val req = OneTimeWorkRequestBuilder<TranscribeWorker>()
                .setInputData(Data.Builder().putString(KEY_MEMO, memoId).putString(KEY_REQUEST, requestId).putInt(KEY_RATE, rate).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("transcribe-$memoId", ExistingWorkPolicy.KEEP, req)
        }
    }
}
