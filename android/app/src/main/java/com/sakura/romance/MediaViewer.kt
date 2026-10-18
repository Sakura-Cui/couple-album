package com.sakura.romance

import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun MediaViewer(model: AppModel, initial: Media) {
    var pages by remember { mutableStateOf(model.media.ifEmpty { listOf(initial) }) }
    val pager = rememberPagerState(initialPage = pages.indexOfFirst { it.id == initial.id }.coerceAtLeast(0)) { pages.size }
    val scope = rememberCoroutineScope()
    var zoomed by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }; var move by remember { mutableStateOf(false) }
    val item = pages[pager.currentPage.coerceIn(pages.indices)]
    LaunchedEffect(model.media) {
        val incoming = model.media.associateBy { it.id }
        pages = pages.map { incoming[it.id] ?: it } + model.media.filter { next -> pages.none { it.id == next.id } }
    }
    LaunchedEffect(item.id) { model.viewing = item; zoomed = false }
    LaunchedEffect(pager.settledPage, pages.size) {
        if (pager.settledPage >= pages.size - 2 && model.mediaMore && !model.mediaLoading && !model.mediaError) model.moreMedia()
    }
    Dialog(onDismissRequest = { model.viewing = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = ViewerSurface, contentColor = PureWhite) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ model.viewing = null }) { Icon(Icons.Outlined.Close, "关闭") }
                    Column(Modifier.weight(1f)) {
                        Text("${model.currentAlbum?.name.orEmpty()}-${pager.currentPage + 1}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(SimpleDateFormat("yyyy年MM月dd日 · HH:mm", Locale.CHINA).format(Date(item.created)), fontSize = 14.sp, color = ViewerSecondary)
                    }
                    Text("${pager.currentPage + 1} / ${pages.size}", Modifier.padding(8.dp), color = ViewerSecondary)
                }
                HorizontalPager(state = pager, key = { pages[it].id }, beyondViewportPageCount = 1, userScrollEnabled = !zoomed && !delete && !move,
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("media-pager")) { page ->
                    val media = pages[page]
                    val active = page == pager.currentPage && !pager.isScrollInProgress
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (media.video) {
                            var opened by remember(media.id) { mutableStateOf(false) }
                            if (page == pager.settledPage) opened = true
                            if (opened) Video(model, media, active) { direction ->
                                val next = (pager.currentPage + direction).coerceIn(pages.indices)
                                scope.launch { pager.animateScrollToPage(next) }
                            }
                            else AsyncImage(model.api.mediaUrl(media.id, true), null, model.images, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        } else key(media.id) {
                            var scale by remember { mutableFloatStateOf(1f) }; var offset by remember { mutableStateOf(Offset.Zero) }
                            LaunchedEffect(active) { if (!active) { scale = 1f; offset = Offset.Zero } }
                            val transform = rememberTransformableState { zoom, pan, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale > 1f) offset + pan else Offset.Zero
                                if (active) zoomed = scale > 1f
                            }
                            var attempt by remember { mutableIntStateOf(0) }
                            val imageUrl = model.api.mediaUrl(media.id, media.mime in setOf("image/heic", "image/heif"))
                            val downloaded = model.imageDownloads[imageUrl]
                            key(attempt) {
                                SubcomposeAsyncImage(imageUrl, media.name, model.images,
                                    Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                                    content = {
                                        val state = painter.state
                                        val loading = state is AsyncImagePainter.State.Loading
                                        val presentation = rememberMediaLoadingPresentation(loading && active, state is AsyncImagePainter.State.Error || !active)
                                        when {
                                            state is AsyncImagePainter.State.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                                Text("图片加载失败", color = PureWhite)
                                                TextButton({ model.imageDownloads.remove(imageUrl); attempt++ }) { Text("重新加载图片", color = SakuraPink) }
                                            }
                                            presentation.visible -> LoadingArtwork("image", loading, presentation,
                                                if (downloaded == null) "正在加载图片…" else if (downloaded >= 1f) "下载完成，正在准备画面…" else "正在下载图片… ${(downloaded * 100).toInt()}%",
                                                Modifier.padding(horizontal = 24.dp), ViewerSecondary, darkSurface = true, isActive = active, progress = downloaded)
                                            state is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent(
                                                Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale,
                                                    translationX = offset.x, translationY = offset.y)
                                                    .transformable(transform, canPan = { scale > 1f }))
                                        }
                                    })
                            }
                            LivePhotoOverlay(model, media, active && scale == 1f)
                        }
                    }
                }
                if (pager.currentPage >= pages.size - 2 && model.mediaMore) {
                    if (model.mediaLoading) Text("正在加载更多…", Modifier.align(Alignment.CenterHorizontally), color = ViewerSecondary)
                    else if (model.mediaError) TextButton({ model.moreMedia() }, Modifier.align(Alignment.CenterHorizontally)) { Text("加载更多失败，点击重试", color = SakuraPink) }
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton({ model.setCover(item) }, enabled = item.thumbnail) { Text("设为封面", color = if (item.thumbnail) PureWhite else TextTertiary) }
                    TextButton({ move = true }) { Text("移动", color = PureWhite) }
                    TextButton({ delete = true }) { Text("删除", color = SakuraPink) }
                }
            }
        }
    }
    if (delete) ConfirmDialog("删除这段回忆？", (if (item.live) "实况照片和对应动态部分会一并删除。" else "") + "删除后，所有已绑定设备都将无法查看。此操作无法在 App 内撤销。", { delete = false }) { model.deleteMedia(item); delete = false }
    if (move) MoveAlbumSheet(model, item.albumId, 1, dismiss = { move = false }) { destination ->
        model.moveMedia(item, destination); move = false
    }
}

