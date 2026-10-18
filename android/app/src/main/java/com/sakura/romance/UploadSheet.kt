package com.sakura.romance

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UploadDialog(model: AppModel, dismiss: () -> Unit) {
    var showHelp by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    val selected = model.tasks.filter { it.id in selectedIds }
    LaunchedEffect(model.tasks.map { it.id }) {
        selectedIds = selectedIds.intersect(model.tasks.map { it.id }.toSet())
        if (model.tasks.isEmpty()) selecting = false
    }
    val listState = rememberLazyListState()
    val displayed = displayUploadTasks(model.tasks)
    val focusId = uploadFocusId(model.tasks, model.activeUploadId)
    val completed = model.tasks.count { it.status == "已完成" }
    LaunchedEffect(focusId, completed, selecting) {
        val index = displayed.indexOfFirst { it.id == focusId }
        if (index >= 0 && !selecting) listState.scrollToItem(index)
    }
    val queueProgress = queueLoadingProgress(model.tasks)
    val queueKey = model.tasks.map { it.id }.sorted()
    val initiallyCompleted = remember(queueKey) { queueProgress.completed }
    val pending = model.tasks.count { it.status != "已完成" }
    val configuration = LocalConfiguration.current
    val artworkHeight = if (model.tasks.isEmpty() || selecting) 0.dp else
        ((configuration.screenWidthDp - 72).coerceIn(0, LoadingMaxWidth) / LoadingAspectRatio + 20).dp
    val maxListHeight = ((configuration.screenHeightDp.dp * .48f).coerceAtMost(420.dp) - artworkHeight).coerceAtLeast(96.dp)
    ModalBottomSheet(onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Transparent, contentColor = TextPrimary,
        scrimColor = Color(0xFF302638).copy(alpha = .22f), dragHandle = null) {
        GlassPanel(Modifier.fillMaxWidth(), RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            tint = PureWhite.copy(alpha = .50f), overContent = true) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 20.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(32.dp, 3.dp).clip(CircleShape)
                    .background(TextSecondary.copy(alpha = .25f)))
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("上传回忆", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (model.tasks.isNotEmpty()) TextButton({
                        selecting = !selecting
                        selectedIds = emptySet()
                    }, Modifier.heightIn(min = 48.dp).testTag("upload-select")) {
                        Text(if (selecting) "取消多选" else "多选", color = RoseInk)
                    }
                    IconButton({ showHelp = !showHelp }, Modifier.semantics { stateDescription = if (showHelp) "说明已展开" else "说明已收起" }) {
                        Icon(Icons.Outlined.Info, "上传说明", Modifier.size(21.dp), tint = RoseInk)
                    }
                }
                if (model.tasks.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("$pending 项待完成 · ${model.tasks.size - pending} 项已完成", fontSize = 13.sp, color = TextSecondary)
                }
                AnimatedVisibility(showHelp) {
                    Surface(Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(16.dp), color = PureWhite.copy(alpha = .50f)) {
                        Text("选好文件，解锁后自动上传。\n后台可继续，通知栏可暂停。\n失败后可继续上传，随时可移除任务。",
                            Modifier.padding(14.dp), fontSize = 13.sp, lineHeight = 21.sp, color = TextSecondary)
                    }
                }
                if (model.tasks.isNotEmpty() && !selecting) {
                    LoadingBackdrop(Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp), verticalPadding = 8.dp) {
                        SakuraLoading("queue", queueProgress.progress, queueProgress.completed, queueKey,
                            initiallyCompleted = initiallyCompleted)
                    }
                }
                Spacer(Modifier.height(if (model.tasks.isEmpty()) 20.dp else 12.dp))
                if (model.tasks.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        SettingsIcon(Icons.Outlined.CloudUpload)
                        Spacer(Modifier.height(16.dp))
                        Text("还没有上传任务", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        Text("选好的照片和视频，会在这里相聚。", fontSize = 13.sp, color = TextSecondary)
                    }
                } else LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = maxListHeight).testTag("upload-queue-list"), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 4.dp)) {
                    items(displayed, key = { it.id }) { task ->
                        val complete = task.status == "已完成"
                        val failed = task.status == "上传失败"
                        val status = if (!model.uploading && task.status in listOf("正在上传", "准备上传", "校验文件", "校验续传位置", "正在保存")) "已暂停" else task.status
                        val statusColor = when { failed -> Color(0xFFB34753); complete -> Color(0xFF326D59); else -> RoseInk }
                        val progress = (task.displayOffset.toFloat() / task.size.coerceAtLeast(1)).coerceIn(0f, 1f)
                        Surface(modifier = Modifier.testTag("upload-task-${task.id}"), shape = RoundedCornerShape(20.dp), color = PureWhite.copy(alpha = .46f), contentColor = TextPrimary) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (selecting) Checkbox(task.id in selectedIds, { checked ->
                                        selectedIds = if (checked) selectedIds + task.id else selectedIds - task.id
                                    }, Modifier.size(48.dp).testTag("upload-select-${task.id}"))
                                    if (!model.albumSessionUnlocked && model.albums.firstOrNull { it.id == task.album }?.protected != false)
                                        EncryptedAlbumCover(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
                                    else UploadThumbnail(task, model.images)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(task.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                        Spacer(Modifier.height(6.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(5.dp).clip(CircleShape).background(statusColor))
                                            Spacer(Modifier.width(6.dp))
                                            Text(status, color = statusColor, fontSize = 12.sp)
                                        }
                                    }
                                    if (!selecting) IconButton({ model.removeTask(task) }, modifier = Modifier.size(48.dp).testTag("upload-remove-${task.id}")) {
                                        Icon(Icons.Outlined.Close, "移除任务", Modifier.size(18.dp))
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                if (!complete) {
                                    if (task.preparing && model.uploading && task.id == model.activeUploadId)
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), color = statusColor, trackColor = RoseInk.copy(alpha = .09f))
                                    else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                                        color = statusColor, trackColor = RoseInk.copy(alpha = .09f))
                                    Spacer(Modifier.height(8.dp))
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(if (complete) formatSize(task.size) else "${formatSize(task.displayOffset)} / ${formatSize(task.size)}", color = TextSecondary, fontSize = 12.sp)
                                    if (!complete) Text(if (task.preparing && model.uploading) task.status else task.progressLabel, color = statusColor, fontSize = 12.sp)
                                }
                                if (!selecting && !complete && task.id != model.activeUploadId) {
                                    TextButton({ model.uploadNow(task.id) }, Modifier.align(Alignment.End).testTag("upload-now-${task.id}")) {
                                        Text("立即上传", color = RoseInk)
                                    }
                                }
                                if (complete && task.completedAt > 0) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("完成于 " + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(task.completedAt)), color = TextTertiary, fontSize = 11.sp)
                                }
                                if (task.error.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(task.error, color = statusColor, fontSize = 12.sp, lineHeight = 18.sp)
                                }
                            }
                        }
                    }
                }
                model.uploadNotice?.let {
                    Text(it, Modifier.fillMaxWidth().padding(top = 12.dp).testTag("upload-notice"),
                        fontSize = 12.sp, lineHeight = 18.sp, color = RoseInk)
                }
                Spacer(Modifier.height(16.dp))
                if (selecting) {
                    Text("已选 ${selected.size} 项", color = TextSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton({
                            model.removeTasks(selectedIds)
                            selectedIds = emptySet()
                        }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("upload-delete-selected"),
                            enabled = selected.isNotEmpty(), shape = RoundedCornerShape(16.dp)) { Text("删除所选") }
                        Button({ model.continueUploads(selectedIds) },
                            Modifier.weight(1f).heightIn(min = 48.dp).testTag("upload-resume-selected"),
                            enabled = selected.any { it.status != "已完成" && it.id != model.activeUploadId },
                            shape = RoundedCornerShape(16.dp)) { Text("继续所选") }
                    }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(dismiss, Modifier.heightIn(min = 48.dp).weight(1f)) { Text("收起", color = TextSecondary) }
                    if (pending > 0) Button({ if (model.uploading) model.pauseUploads() else model.startUploads() },
                        Modifier.heightIn(min = 48.dp).weight(2f), shape = RoundedCornerShape(16.dp)) {
                        Icon(if (model.uploading) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (model.uploading) "暂停上传" else "继续上传 / 重试", fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
