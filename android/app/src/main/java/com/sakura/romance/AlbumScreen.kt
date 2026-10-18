package com.sakura.romance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter

@Composable
internal fun AlbumScreen(model: AppModel, onEdit: () -> Unit, onDelete: () -> Unit, onQueue: () -> Unit) {
    val album = model.currentAlbum ?: return
    val grid = rememberLazyGridState()
    val previews = remember(album.id, model.images) { mutableStateMapOf<String, Boolean>() }
    var entered by remember(album.id) { mutableStateOf(false) }
    val visibleIds by remember(album.id) { derivedStateOf {
        grid.layoutInfo.visibleItemsInfo.filter { item -> model.media.getOrNull(item.index)?.id == item.key }
            .mapNotNull { it.key as? String }
    } }
    LaunchedEffect(model.media) {
        if (!entered && model.media.isNotEmpty()) grid.scrollToItem(0)
    }
    val firstScreenIds = visibleIds.takeIf { it.isNotEmpty() }
        ?.filter { id -> model.media.any { it.id == id && it.thumbnail } }
    val prepared = firstScreenIds?.count { it in previews } ?: 0
    val firstScreenSettled = firstScreenIds != null && prepared == firstScreenIds.size &&
        !model.mediaLoading && visibleIds.firstOrNull() == model.media.firstOrNull()?.id
    LaunchedEffect(firstScreenSettled) {
        if (firstScreenSettled) entered = true
    }
    val loading = if (model.media.isEmpty()) model.mediaLoading else !entered
    val previewReady = firstScreenIds?.count { previews[it] == true } ?: 0
    val previewFailed = firstScreenIds?.count { previews[it] == false } ?: 0
    val progress = firstScreenIds?.let { if (it.isEmpty()) 1f else previewReady.toFloat() / it.size }
    val loadingText = firstScreenIds?.let { "正在准备首屏预览… $previewReady / ${it.size}" + if (previewFailed > 0) " · $previewFailed 张待重试" else "" } ?: "正在读取相册…"
    val presentation = rememberLoadingPresentation(loading,
        model.mediaError || (firstScreenSettled && previewFailed > 0), album.id)
    var menu by remember { mutableStateOf(false) }
    BackHandler(enabled = model.selecting) { if (!model.managingMedia) model.clearSelection() }
    Column(Modifier.fillMaxSize()) {
        GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ if (model.selecting) model.clearSelection() else model.closeAlbum() }, enabled = !model.managingMedia) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, if (model.selecting) "取消多选" else "返回相册")
            }
            Column(Modifier.weight(1f)) {
                Text(album.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${album.count} 个共同珍藏的瞬间", color = TextTertiary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton({ model.refresh() }, enabled = !model.busy && !model.managingMedia) { Icon(Icons.Outlined.Refresh, "刷新") }
            IconButton(onQueue) { Icon(Icons.Outlined.CloudUpload, "上传队列", tint = RoseInk) }
            Box {
                IconButton({ menu = true }, enabled = !model.managingMedia) { Icon(Icons.Outlined.MoreVert, "相册操作") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("编辑相册") }, { menu = false; onEdit() })
                    if (!model.mediaLoading && !model.mediaError && model.media.isEmpty() &&
                        model.tasks.none { it.album == album.id && it.status != "已完成" }) {
                        DropdownMenuItem({ Text("删除相册") }, { menu = false; onDelete() })
                    }
                }
            }
        }
        }
        if (!model.selecting) TextButton({ model.beginSelection() }, Modifier.align(Alignment.End).padding(end = 16.dp),
            enabled = model.media.isNotEmpty() && !model.busy, colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary)) { Text("多选") }
        if (model.selecting) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("已选 ${model.selectedMediaIds.size} 项", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            TextButton({ model.selectLoadedMedia() }, enabled = !model.managingMedia && model.media.isNotEmpty(), colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary)) {
                Text(if (model.media.isNotEmpty() && model.media.all { it.id in model.selectedMediaIds }) "取消全选" else "全选已加载")
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
        if (model.media.isEmpty() && !presentation.visible && !model.mediaError) EmptyState("这里，留给我们的故事", "上传照片或视频，\n让这段回忆有迹可循。", Modifier.fillMaxSize())
        if (model.media.isNotEmpty()) LazyVerticalGrid(GridCells.Fixed(3),
            Modifier.fillMaxSize().padding(horizontal = 12.dp)
                .graphicsLayer { alpha = if (presentation.visible) 0f else 1f }
                .then(if (presentation.visible) Modifier.clearAndSetSemantics {} else Modifier)
                .testTag("album-media-grid"), state = grid,
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = LocalBottomClearance.current + 20.dp)) {
            items(model.media, key = { it.id }) { item ->
                val selected = item.id in model.selectedMediaIds
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(SurfaceSelected)
                    .border(if (selected) 3.dp else 0.dp, if (selected) SakuraPink else Color.Transparent, RoundedCornerShape(14.dp))
                    .semantics { this.selected = selected }
                    .combinedClickable(enabled = !model.managingMedia,
                        onClick = { if (model.selecting) model.toggleSelection(item.id) else model.viewing = item },
                        onLongClick = { model.toggleSelection(item.id) }), contentAlignment = Alignment.Center) {
                    if (item.thumbnail) {
                        var failed by remember(item.id, model.images) { mutableStateOf(false) }
                        var loaded by remember(item.id, model.images) { mutableStateOf(false) }
                        var attempt by remember(item.id, model.images) { mutableIntStateOf(0) }
                        key(attempt) {
                            AsyncImage(model.api.mediaUrl(item.id, true), item.name, model.images,
                                Modifier.fillMaxSize().testTag(if (loaded) "preview-ready-${item.id}" else "preview-pending-${item.id}"),
                                contentScale = ContentScale.Crop, onState = {
                                    failed = it is AsyncImagePainter.State.Error
                                    loaded = it is AsyncImagePainter.State.Success
                                    if (loaded || failed) previews[item.id] = loaded
                                })
                        }
                        if (failed) TextButton({ attempt++ }) { Text("重试预览图") }
                        else if (!loaded) CircularProgressIndicator(Modifier.size(20.dp), color = SakuraPink, strokeWidth = 2.dp)
                    } else Icon(Icons.Outlined.Movie, item.name, tint = SakuraPink)
                    if (item.live) Text("实况", Modifier.align(Alignment.BottomStart).padding(6.dp)
                        .background(ViewerSurface.copy(alpha = .75f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp).testTag("live-photo-badge-${item.id}"),
                        color = PureWhite, fontSize = 11.sp)
                    if (item.video) Icon(Icons.Outlined.PlayCircle, "视频", Modifier.align(Alignment.BottomEnd).padding(8.dp).size(24.dp), tint = PureWhite)
                    if (model.selecting) Icon(if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        if (selected) "已选择" else "未选择", Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp)
                            .background(PureWhite, CircleShape), tint = TextPrimary)
                }
            }
            if (model.mediaMore) item(span = { GridItemSpan(3) }) { TextButton({ model.moreMedia() }, Modifier.fillMaxWidth(), enabled = !model.busy && !model.managingMedia) { Text("加载更多回忆") } }
        }
        if (presentation.visible || (model.media.isEmpty() && model.mediaError))
            ListLoadingState(loading, model.mediaError, presentation, loadingText,
                Modifier.matchParentSize().pointerInput(presentation.visible) {
                    if (presentation.visible) awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }, progress) { model.refresh() }
        }

    }

}

