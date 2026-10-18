package com.sakura.romance

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SakuraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = RoseInk, onPrimary = PureWhite,
        primaryContainer = SurfaceSelected, onPrimaryContainer = TextPrimary,
        secondary = MintGreen, onSecondary = TextPrimary,
        secondaryContainer = SurfaceSuccess, onSecondaryContainer = TextPrimary,
        tertiary = MistPink, onTertiary = TextPrimary,
        background = WarmWhite, onBackground = TextPrimary,
        surface = PureWhite, onSurface = TextPrimary,
        surfaceVariant = SurfaceSelected, onSurfaceVariant = TextSecondary,
        surfaceContainer = WarmWhite, surfaceContainerHigh = WarmWhite,
        outline = BorderDefault, outlineVariant = BorderDefault
    )) { CompositionLocalProvider(LocalContentColor provides TextPrimary, content = content) }
}

@Composable
internal fun SakuraApp(model: AppModel) {
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(model.inboxOpen) { if (model.inboxOpen) tab = 1 }
    var create by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    var deleteAlbum by remember { mutableStateOf(false) }
    var queue by rememberSaveable { mutableStateOf(false) }
    var deleteSelection by remember(model.currentAlbum?.id, model.ready) { mutableStateOf(false) }
    var moveSelection by remember(model.currentAlbum?.id, model.ready) { mutableStateOf(false) }
    var pickerAlbum by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pickerAlbum?.let { albumId ->
            if (uris.isNotEmpty()) {
                queue = true
                model.enqueue(uris, albumId)
            }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(model.uploading) {
        if (model.uploading && android.os.Build.VERSION.SDK_INT >= 33 &&
            model.getApplication<android.app.Application>().checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val preferences = model.getApplication<android.app.Application>().getSharedPreferences("notifications", 0)
            if (!preferences.getBoolean("asked", false)) {
                preferences.edit().putBoolean("asked", true).apply()
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    val connectionLoading = model.bound && !model.ready && (model.busy || model.checkingSession)
    val connectionPresentation = rememberLoadingPresentation(connectionLoading,
        !model.ready && !model.busy && !model.checkingSession, model.bound)
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model.message) {
        model.message?.let { snackbar.showSnackbar(it); model.message = null }
    }
    BackHandler(enabled = model.currentAlbum != null && model.viewing == null && model.ready && !model.inboxOpen) { model.closeAlbum() }
    BackHandler(enabled = model.inboxOpen && model.ready) { model.backFromInbox() }
    RomanceScene {
        Scaffold(containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        if (model.bound && model.ready) {
            if (model.selecting) Surface(Modifier.fillMaxWidth().testTag("selection-actions"), color = WarmWhite, shadowElevation = 8.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton({ moveSelection = true }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = model.selectedMediaIds.isNotEmpty() && !model.managingMedia,
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = PureWhite, disabledContainerColor = PureWhite, contentColor = RoseInk)) { Text("移动到相册") }
                    OutlinedButton({ deleteSelection = true }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = model.selectedMediaIds.isNotEmpty() && !model.managingMedia,
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = PureWhite, disabledContainerColor = PureWhite, contentColor = MaterialTheme.colorScheme.error)) { Text("删除所选") }
                }
            } else GlassBottomBar(tab, { model.closeInbox(); tab = it },
                actionIcon = if (tab != 0 || model.inboxOpen) null else if (model.currentAlbum == null) Icons.Outlined.Add else Icons.Outlined.FileUpload,
                actionLabel = if (model.currentAlbum == null) "新建相册" else "上传回忆",
                onAction = {
                    if (model.currentAlbum == null) create = true
                    else { pickerAlbum = model.currentAlbum!!.id; picker.launch(arrayOf("image/*", "video/*")) }
                })
        }
    }) { padding ->
        CompositionLocalProvider(LocalBottomClearance provides padding.calculateBottomPadding()) {
        GlassContent(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            when {
                !model.bound -> ConnectScreen(model)
                !model.ready || connectionPresentation.visible -> UnlockScreen(model, connectionLoading, connectionPresentation)
                model.inboxOpen -> InboxScreen(model)
                tab == 1 -> SettingsScreen(model) { queue = true }
                model.currentAlbum != null -> AlbumScreen(model, onEdit = { edit = true }, onDelete = { deleteAlbum = true }, onQueue = { queue = true })
                else -> AlbumsScreen(model) { queue = true }
            }
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
        }
    }
    if (create && model.ready) AlbumEditDialog(model, null) { create = false }
    if (edit && model.ready) AlbumEditDialog(model, model.currentAlbum) { edit = false }
    if (model.ready) model.albumToUnlock?.let { AlbumUnlockDialog(model, it) }
    if (deleteAlbum) ConfirmDialog("删除这个相册？", "这个相册没有照片或视频。删除后，所有已绑定设备都将不再显示它；如仍有上传任务，请先完成或移除任务。", { deleteAlbum = false }) { model.deleteAlbum(); deleteAlbum = false }
    if (queue && model.ready) UploadDialog(model) { queue = false }
    if (deleteSelection) ConfirmDialog("删除选中的 ${model.selectedMediaIds.size} 项回忆？",
        (if (model.media.any { it.id in model.selectedMediaIds && it.live }) "实况照片和对应动态部分会一并删除。" else "") + "删除后，所有已绑定设备都将无法查看。此操作无法在 App 内撤销。", { deleteSelection = false }) {
        deleteSelection = false; model.deleteSelectedMedia()
    }
    if (moveSelection) MoveAlbumSheet(model, model.currentAlbum?.id.orEmpty(), model.selectedMediaIds.size,
        dismiss = { moveSelection = false }) { destination ->
        moveSelection = false; model.moveSelectedMedia(destination)
    }
    if (model.ready) model.viewing?.let { MediaViewer(model, it) }
    }
}

@Composable
internal fun BrandUnderline(modifier: Modifier = Modifier) {
    Box(modifier.width(36.dp).height(3.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(
        RoseInk.copy(alpha = .65f), SakuraPink.copy(alpha = .48f)
    ))))
}

@Composable
internal fun BrandIcon(modifier: Modifier = Modifier, contentDescription: String? = stringResource(R.string.app_name)) {
    Box(modifier.clip(RoundedCornerShape(28)).background(Brush.linearGradient(
        listOf(Color(0xFFFFF8F3), Color(0xFFF5DCE6), Color(0xFFE3EDE5))))
        .border(1.dp, PureWhite.copy(alpha = .85f), RoundedCornerShape(28)), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_sakura_foreground), contentDescription,
            Modifier.fillMaxSize().scale(1.18f), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun ConnectScreen(model: AppModel) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var url by remember { mutableStateOf(model.vault.url) }
    var invitation by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp).padding(bottom = LocalBottomClearance.current), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        BrandIcon(Modifier.size(80.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.app_name), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        BrandUnderline(Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(8.dp))
        Text("把平凡的日子，收藏成我们的故事。", fontSize = 14.sp, color = TextTertiary)
        Spacer(Modifier.height(28.dp))
        GlassPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp)) {
                Text("连接我们的空间", Modifier.fillMaxWidth(), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("第一次见面，需要一张邀请函。", Modifier.fillMaxWidth(), color = TextTertiary, fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("空间地址") }, placeholder = { Text("https://你的域名:9443") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(invitation, { invitation = it }, Modifier.fillMaxWidth(), label = { Text("一次性邀请凭证") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("这台手机的名字") }, placeholder = { Text("例如：樱的手机") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(24.dp))
                Button(onClick = { focus.clearFocus(); keyboard?.hide(); model.bind(url, invitation, name); invitation = "" }, Modifier.fillMaxWidth().height(56.dp), enabled = !model.busy && invitation.isNotBlank() && url.isNotBlank(), shape = RoundedCornerShape(16.dp)) {
                    Text("进入我们的空间", fontSize = 16.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(16.dp), tint = TextTertiary)
            Spacer(Modifier.width(8.dp)); Text("只对已绑定的设备开放", color = TextTertiary, fontSize = 14.sp)
        }
    }
}

@Composable
private fun UnlockScreen(model: AppModel, connectionLoading: Boolean, presentation: LoadingPresentation) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        BrandIcon(Modifier.size(88.dp))
        Spacer(Modifier.height(24.dp))
        Text("正在连接我们的空间…", color = TextTertiary)
        Spacer(Modifier.height(24.dp))
        if (presentation.visible) LoadingArtwork("app", connectionLoading, presentation, "正在连接我们的空间…")
        else OutlinedButton({ model.resume() }) { Text("重新连接") }
    }
}

@Composable
internal fun Heading(title: String, subtitle: String, model: AppModel, queue: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp)); BrandUnderline()
        }
        GlassIconButton(Icons.Outlined.Refresh, "刷新", { model.refresh() }, !model.busy)
        Spacer(Modifier.width(8.dp))
        GlassIconButton(Icons.Outlined.CloudUpload, "上传队列", queue)
        }
        Spacer(Modifier.height(12.dp)); Text(subtitle, fontSize = 15.sp, color = TextSecondary)
    }
}

@Composable
internal fun ListLoadingState(loading: Boolean, failed: Boolean, presentation: LoadingPresentation, text: String, modifier: Modifier, progress: Float? = null, retry: () -> Unit) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (!failed) {
            LoadingArtwork("app", loading, presentation, text, progress = progress)
        } else {
            Text("加载失败，请重试", color = TextSecondary)
            TextButton(retry) { Text("重新加载") }
        }
    }
}

@Composable
internal fun EmptyState(title: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        BrandIcon(Modifier.size(72.dp), contentDescription = null)
        Spacer(Modifier.height(24.dp)); Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp)); Text(text, fontSize = 14.sp, color = TextTertiary, lineHeight = 24.sp)
    }
}

@Composable
internal fun ConfirmDialog(title: String, text: String, dismiss: () -> Unit, confirm: () -> Unit) {
    RomanceDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(text) }, confirmButton = { TextButton(confirm) { Text("确认删除") } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}

