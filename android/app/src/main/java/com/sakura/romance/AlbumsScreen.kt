package com.sakura.romance

import androidx.activity.compose.BackHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.time.LocalDate

@Composable
internal fun AlbumsScreen(model: AppModel, queue: () -> Unit) {
    val loading = model.albumsLoading && model.albums.isEmpty()
    val presentation = rememberLoadingPresentation(loading, model.albumsError)
    val anniversary = anniversaryStatus(LocalDate.now())
    val blossom = painterResource(R.drawable.ic_sakura_foreground)
    val grid = rememberLazyGridState()
    val editing = model.organizingAlbums
    var displayed by remember { mutableStateOf(model.albums) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(model.albums) { if (dragging == null) displayed = model.albums }
    BackHandler(editing) { model.finishOrganizingAlbums() }
    fun moveUnderPointer() {
        val id = dragging ?: return
        val selected = displayed.firstOrNull { it.id == id } ?: return
        val target = grid.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != id && pointer.x >= it.offset.x && pointer.x < it.offset.x + it.size.width &&
                pointer.y >= it.offset.y && pointer.y < it.offset.y + it.size.height &&
                displayed.any { album -> album.id == it.key && album.pinned == selected.pinned }
        } ?: return
        val from = displayed.indexOfFirst { it.id == id }
        val to = displayed.indexOfFirst { it.id == target.key }
        displayed = displayed.toMutableList().apply { add(to, removeAt(from)) }
    }
    LaunchedEffect(dragging) {
        while (dragging != null) {
            val top = grid.layoutInfo.viewportStartOffset + 100
            val bottom = grid.layoutInfo.viewportEndOffset - 120
            val speed = when { pointer.y < top -> -16f; pointer.y > bottom -> 16f; else -> 0f }
            if (speed != 0f) { grid.scrollBy(speed); moveUnderPointer() }
            delay(16)
        }
    }
    val gestures = if (!editing || model.busy) Modifier else Modifier.pointerInput(editing, model.busy) {
        detectDragGesturesAfterLongPress(
            onDragStart = { start ->
                val item = grid.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                    displayed.any { it.id == item.key } && start.x >= item.offset.x && start.x < item.offset.x + item.size.width &&
                        start.y >= item.offset.y && start.y < item.offset.y + item.size.height
                }
                if (item != null) {
                    dragging = item.key as String; pointer = start
                    grab = start - Offset(item.offset.x.toFloat(), item.offset.y.toFloat())
                }
            },
            onDrag = { change, amount -> if (dragging != null) { change.consume(); pointer += amount; moveUnderPointer() } },
            onDragCancel = { dragging = null; displayed = model.albums },
            onDragEnd = {
                val album = displayed.firstOrNull { it.id == dragging }
                if (album != null && displayed != model.albums) {
                    val group = displayed.filter { it.pinned == album.pinned }
                    model.positionAlbum(album, album.pinned, group.getOrNull(group.indexOf(album) + 1)?.id)
                }
                dragging = null; displayed = model.albums
            }
        )
    }
    LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().then(gestures).testTag("album-grid"), state = grid,
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = LocalBottomClearance.current + 20.dp)) {
        item(span = { GridItemSpan(2) }, key = "heading") { Heading("我们的相册", "每一个瞬间，都值得被珍藏", model, queue) }
        item(span = { GridItemSpan(2) }, key = "anniversary") {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                color = PureWhite.copy(alpha = .04f), contentColor = TextPrimary,
                border = BorderStroke(.75.dp, PureWhite.copy(alpha = .40f))) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))) {
                    Spacer(Modifier.matchParentSize().drawBehind {
                        val extent = minOf(size.height - 16.dp.toPx(), 72.dp.toPx()) * 1.8f
                        drawCircle(PureWhite.copy(alpha = .38f), 70.dp.toPx(),
                            Offset(size.width - 38.dp.toPx(), size.height + 16.dp.toPx()), style = Stroke(1.dp.toPx()))
                        translate(size.width - extent + 12.dp.toPx(), (size.height - extent) / 2f) {
                            with(blossom) { draw(Size(extent, extent), alpha = .30f) }
                        }
                        listOf(Offset(.73f, .22f), Offset(.94f, .25f), Offset(.65f, .83f)).forEachIndexed { index, point ->
                            val center = Offset(size.width * point.x, size.height * point.y)
                            rotate(30f + index * 50f, center) {
                                drawOval(RoseInk.copy(alpha = .10f), center - Offset(4.dp.toPx(), 8.dp.toPx()), Size(8.dp.toPx(), 16.dp.toPx()))
                            }
                        }
                    })
                    Text(anniversary.headline,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                        fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
                        color = TextPrimary, textAlign = TextAlign.Start)
                }
            }
        }
        if (displayed.isNotEmpty()) item(span = { GridItemSpan(2) }, key = "organize") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (editing) "长按拖动，同一区域内排序" else "一起收藏的回忆", Modifier.weight(1f), color = TextSecondary, fontSize = 13.sp)
                TextButton(onClick = { if (editing) model.finishOrganizingAlbums() else model.organizeAlbums() },
                    enabled = !model.busy && !model.albumsLoading, modifier = Modifier.testTag("organize-albums")) {
                    Text(if (editing) "完成" else "整理")
                }
            }
        }
        if (presentation.visible || (displayed.isEmpty() && model.albumsError)) item(span = { GridItemSpan(2) }) {
            ListLoadingState(loading, model.albumsError, presentation, "正在加载相册…", Modifier.heightIn(min = 240.dp)) { model.refresh() }
        } else if (displayed.isEmpty()) item(span = { GridItemSpan(2) }) {
            EmptyState("第一本相册，从这里开始", "点击右下角新建相册，\n把照片和视频放进共同的回忆里。", Modifier.heightIn(min = 280.dp))
        } else {
            for (pinned in listOf(true, false)) {
                val group = displayed.filter { it.pinned == pinned }
                if (group.isNotEmpty()) {
                    if (pinned || displayed.any { it.pinned }) item(span = { GridItemSpan(2) }, key = "section-$pinned") {
                        Text(if (pinned) "置顶相册" else "全部相册", color = RoseInk, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    items(group, key = { it.id }) { album ->
                        val moving = dragging == album.id
                        val position = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == album.id }?.offset
                        val translation = if (moving && position != null) pointer - grab - Offset(position.x.toFloat(), position.y.toFloat()) else Offset.Zero
                        GlassPanel(Modifier.fillMaxWidth().zIndex(if (moving) 2f else 0f)
                            .graphicsLayer { translationX = translation.x; translationY = translation.y; scaleX = if (moving) 1.03f else 1f; scaleY = scaleX }
                            .clip(RoundedCornerShape(22.dp)).clickable(enabled = !editing) { model.openAlbum(album) }
                            .testTag("album-${album.id}"), RoundedCornerShape(22.dp)) {
                            Column(Modifier.padding(6.dp)) {
                                Box(Modifier.fillMaxWidth().aspectRatio(1.12f).clip(RoundedCornerShape(17.dp)).background(SurfaceSelected), contentAlignment = Alignment.Center) {
                                    if (album.protected) EncryptedAlbumCover(Modifier.fillMaxSize())
                                    else if (album.cover != null) AsyncImage(model.api.mediaUrl(album.cover, true), album.name, model.images, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    else AlbumPlaceholder(Modifier.fillMaxSize(), album.name.hashCode())
                                    if (album.pinned) Icon(Icons.Outlined.PushPin, "已置顶", Modifier.align(Alignment.TopEnd).padding(8.dp).background(PureWhite, RoundedCornerShape(8.dp)).padding(4.dp), tint = RoseInk)
                                }
                                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
                                    Text(album.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(5.dp)); Text(if (album.protected) "加密相册 · ${album.count} 个瞬间" else "${album.count} 个瞬间", color = TextSecondary, fontSize = 12.sp)
                                    if (editing) TextButton({ model.toggleAlbumPin(album) }, enabled = !model.busy && dragging == null,
                                        modifier = Modifier.fillMaxWidth().testTag("pin-${album.id}")) { Text(if (album.pinned) "取消置顶" else "置顶") }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (model.albumMore && !editing) item(span = { GridItemSpan(2) }) {
            TextButton({ model.moreAlbums() }, Modifier.fillMaxWidth(), enabled = !model.busy) { Text("加载更多相册") }
        }
    }
}
