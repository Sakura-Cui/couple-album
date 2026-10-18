package com.sakura.romance

import org.json.JSONObject

data class UploadTask(
    val id: String, val uri: String, val album: String, val name: String,
    val mime: String, val size: Long, val hash: String = "", val remote: String = "",
    val offset: Long = 0, val status: String = "等待上传", val error: String = "", val completedAt: Long = 0,
    val sentOffset: Long? = null, val requestId: String = id
) {
    val displayOffset get() = (sentOffset ?: offset).coerceIn(0, size.coerceAtLeast(0))
    val progressPercent get() = (displayOffset * 100 / size.coerceAtLeast(1)).toInt()
    val progressLabel get() = if (displayOffset > 0 && progressPercent == 0)
        if (displayOffset * 1000 < size) "<0.1%" else String.format(java.util.Locale.ROOT, "%.1f%%", displayOffset * 100.0 / size.coerceAtLeast(1)) else "$progressPercent%"
    val preparing get() = status.startsWith("正在重试") || status in listOf("准备上传", "校验文件", "校验续传位置", "正在保存")

    fun toJson() = json("id" to id, "uri" to uri, "album" to album, "name" to name, "mime" to mime,
        "size" to size, "hash" to hash, "remote" to remote, "offset" to offset, "status" to status, "error" to error, "completed_at" to completedAt, "request_id" to requestId)
    companion object {
        fun parse(o: JSONObject) = UploadTask(o.getString("id"), o.getString("uri"), o.getString("album"), o.getString("name"),
            o.getString("mime"), o.getLong("size"), o.optString("hash"), o.optString("remote"), o.optLong("offset"),
            if (o.optString("status") == "已完成") "已完成" else "已暂停", o.optString("error"), o.optLong("completed_at"), requestId = o.optString("request_id", o.getString("id")))
    }
}

internal fun displayUploadTasks(tasks: List<UploadTask>): List<UploadTask> =
    tasks.filter { it.status != "已完成" } + tasks.filter { it.status == "已完成" }.sortedBy { it.completedAt }

internal fun prioritizeUploadTasks(tasks: List<UploadTask>, id: String, activeId: String?): List<UploadTask> {
    val selected = tasks.find { it.id == id && it.status != "已完成" } ?: return tasks
    val active = tasks.find { it.id == activeId && it.id != id && it.status != "已完成" }
    return listOfNotNull(selected, active) + displayUploadTasks(tasks).filter { it.id != id && it.id != active?.id }
}

internal fun uploadFocusId(tasks: List<UploadTask>, activeId: String?): String? =
    tasks.find { it.id == activeId && it.status != "已完成" }?.id
        ?: displayUploadTasks(tasks).firstOrNull()?.id

