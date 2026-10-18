package com.sakura.romance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoveAlbumSheet(model: AppModel, sourceAlbumId: String, count: Int, dismiss: () -> Unit, move: (Album) -> Unit) {
    val destinations = model.albums.filter { it.id != sourceAlbumId }
    var selectedId by remember(sourceAlbumId) { mutableStateOf<String?>(null) }
    val selected = destinations.find { it.id == selectedId }
    val enabled = !model.busy && !model.managingMedia
    val height = LocalConfiguration.current.screenHeightDp.dp
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Transparent, contentColor = TextPrimary, dragHandle = null,
        scrimColor = ViewerSurface.copy(alpha = .35f)) {
        GlassPanel(Modifier.fillMaxWidth(), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tint = WarmWhite.copy(alpha = .96f), overContent = true) {
            Column(Modifier.fillMaxWidth().heightIn(max = height * .86f).padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 20.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(32.dp, 3.dp).clip(CircleShape).background(TextTertiary.copy(alpha = .25f)))
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("移动到相册", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("已选择 $count 项回忆", color = TextSecondary, fontSize = 13.sp)
                    }
                    IconButton(dismiss) { Icon(Icons.Outlined.Close, "关闭相册选择", tint = TextSecondary) }
                }
                Spacer(Modifier.height(20.dp))
                Text("选择目标相册", fontSize = 13.sp, color = TextSecondary)
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = height * .46f).testTag("move-album-list"),
                    verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 4.dp)) {
                    items(destinations, key = { it.id }) { album ->
                        val checked = album.id == selectedId
                        Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                            .selectable(checked, enabled = enabled, role = Role.RadioButton) { selectedId = album.id }
                            .testTag("move-album-${album.id}"),
                            color = if (checked) Color(0xFFF9E5EC) else PureWhite,
                            border = BorderStroke(1.dp, if (checked) RoseInk.copy(alpha = .45f) else BorderDefault),
                            shape = RoundedCornerShape(18.dp)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceSelected)) {
                                    if (album.protected) EncryptedAlbumCover(Modifier.fillMaxSize())
                                    else if (album.cover != null) AsyncImage(model.api.mediaUrl(album.cover, true), null, model.images, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    else AlbumPlaceholder(Modifier.fillMaxSize(), album.name.hashCode())
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(album.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (checked) RoseInk else TextPrimary,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(4.dp))
                                    Text("${album.count} 项回忆", color = TextSecondary, fontSize = 12.sp)
                                }
                                Spacer(Modifier.width(12.dp))
                                Icon(if (checked) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                                    Modifier.size(24.dp), tint = if (checked) RoseInk else TextTertiary.copy(alpha = .55f))
                            }
                        }
                    }
                    if (model.albumMore) item {
                        TextButton({ model.moreAlbums() }, Modifier.fillMaxWidth(), enabled = enabled) { Text(if (model.busy) "正在加载…" else "加载更多相册") }
                    }
                    if (destinations.isEmpty() && !model.albumMore) item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(36.dp), tint = RoseInk)
                            Spacer(Modifier.height(12.dp))
                            Text("还没有其他相册", fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(8.dp))
                            Text("先新建一个相册，再来移动回忆。", color = TextSecondary, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(dismiss, Modifier.weight(1f).heightIn(min = 52.dp)) { Text("取消", color = TextSecondary) }
                    Button({ selected?.let(move) }, Modifier.weight(2f).heightIn(min = 52.dp), enabled = selected != null && enabled,
                        shape = RoundedCornerShape(16.dp)) { Text("确认移动") }
                }
            }
        }
    }
}
