package com.sakura.romance

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

internal class InboxModel(
    private val api: Api,
    private val scope: CoroutineScope,
    private val ready: () -> Boolean,
    private val visibilityEpoch: () -> Int,
    private val onError: (Exception) -> Unit
) {
    var inboxOpen by mutableStateOf(false); private set
    var letterTarget by mutableStateOf<String?>(null); private set
    var letterDetail by mutableStateOf<Letter?>(null); private set
    var letters by mutableStateOf<List<Letter>>(emptyList()); private set
    var lettersBefore by mutableStateOf<Long?>(null); private set
    var lettersLoading by mutableStateOf(false); private set
    var lettersError by mutableStateOf<String?>(null); private set
    private var lettersJob: Job? = null

    fun cancel() { lettersJob?.cancel() }
    fun showTarget(id: String) { inboxOpen = true; letterTarget = id; letterDetail = null }
    fun resetBinding() {
        inboxOpen = false; letterTarget = null; letterDetail = null; letters = emptyList(); lettersBefore = null; lettersError = null
    }
    fun clearVisible() {
        lettersJob?.cancel(); letterDetail = null; letters = emptyList(); lettersError = null
    }
    fun openInbox() { inboxOpen = true; letterTarget = null }
    fun closeInbox() { inboxOpen = false; letterTarget = null; letterDetail = null; lettersJob?.cancel() }
    fun backFromInbox() {
        if (letterTarget == null) closeInbox()
        else { letterTarget = null; letterDetail = null; loadLetters() }
    }
    fun openLetter(id: String) { letterDetail = null; letterTarget = validMessageId(id); loadLetters() }
    fun loadLetters(more: Boolean = false) {
        if (!ready()) return
        lettersJob?.cancel()
        val epoch = visibilityEpoch()
        val target = letterTarget
        lettersJob = scope.launch {
            lettersLoading = true; lettersError = null
            try {
                if (target != null) {
                    val result = Letter.parse(api.obj("/api/messages/$target"))
                    if (ready() && epoch == visibilityEpoch() && letterTarget == target) {
                        letterDetail = result
                        api.obj("/api/messages/$target/read", "PUT")
                        letters = letters.map { if (it.id == target) it.copy(read = true) else it }
                    }
                } else {
                    val path = "/api/messages" + if (more && lettersBefore != null) "?before=$lettersBefore" else ""
                    val result = api.obj(path)
                    val items = result.getJSONArray("items")
                    val page = (0 until items.length()).map { Letter.parse(items.getJSONObject(it)) }
                    if (ready() && epoch == visibilityEpoch() && letterTarget == null) {
                        letters = if (more) (letters + page).distinctBy { it.id } else page
                        lettersBefore = if (result.isNull("next_before")) null else result.getLong("next_before")
                    }
                }
            } catch (error: Exception) {
                if (error !is CancellationException) {
                    lettersError = error.message ?: "消息加载失败"
                    if (error is ApiError && error.status in listOf(401, 423)) onError(error)
                }
            } finally { lettersLoading = false }
        }
    }
}
