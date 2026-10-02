package com.peaceantz.stagescope.ui.assistant

import android.net.Uri
import com.peaceantz.stagescope.shared.assistant.TaskKind
import com.peaceantz.stagescope.shared.measurement.SnapshotOrigin

/** Route patterns and builders for everything reached from the Assistant page. Ids are encoded; nothing else travels in a route. */
object AssistantRoutes {
    const val LISTEN = "assistantListen/{task}/{origin}?memo={memo}&conv={conv}&edit={edit}&draft={draft}"
    const val REPLY = "assistantReply/{conversationId}"
    const val ACTION = "assistantAction/{conversationId}/{actionId}"
    const val TASKS = "assistantTasks"
    const val ISSUES = "assistantIssues"
    const val ISSUE = "assistantIssue/{issueId}"
    const val SETTINGS = "assistantSettings"
    const val PROVIDERS = "assistantProviders"
    const val SHOWS = "assistantShows"
    const val MEMOS = "assistantMemos"

    const val ARG_TASK = "task"
    const val ARG_ORIGIN = "origin"
    const val ARG_MEMO = "memo"
    const val ARG_CONV = "conv"
    const val ARG_EDIT = "edit"
    const val ARG_DRAFT = "draft"
    const val ARG_CONVERSATION_ID = "conversationId"
    const val ARG_ACTION_ID = "actionId"
    const val ARG_ISSUE_ID = "issueId"

    fun listen(task: TaskKind, origin: SnapshotOrigin, memo: String? = null, conv: String? = null, edit: String? = null, draft: Boolean = false): String =
        "assistantListen/${task.name}/${origin.name}" + buildString {
            val q = listOfNotNull(
                memo?.let { "memo=${Uri.encode(it)}" }, conv?.let { "conv=${Uri.encode(it)}" }, edit?.let { "edit=${Uri.encode(it)}" }, if (draft) "draft=1" else null,
            )
            if (q.isNotEmpty()) append('?').append(q.joinToString("&"))
        }

    fun reply(conversationId: String) = "assistantReply/${Uri.encode(conversationId)}"
    fun action(conversationId: String, actionId: String) = "assistantAction/${Uri.encode(conversationId)}/${Uri.encode(actionId)}"
    fun issue(issueId: String) = "assistantIssue/${Uri.encode(issueId)}"
}

/** The Assistant page's way out to its sub-screens (kept as one object so the page signature stays readable). */
class AssistantNavigator(
    val ask: (task: TaskKind, origin: SnapshotOrigin, conversationId: String?, editsActionId: String?) -> Unit,
    val reply: (conversationId: String) -> Unit,
    val action: (conversationId: String, actionId: String) -> Unit,
    val tasks: () -> Unit,
    val issues: () -> Unit,
    val issue: (issueId: String) -> Unit,
    val settings: () -> Unit,
    val providers: () -> Unit,
    val shows: () -> Unit,
    val memos: () -> Unit,
    val reviewMemo: (memoId: String) -> Unit,
    val resumeDraft: () -> Unit,
    val close: () -> Unit,
)
