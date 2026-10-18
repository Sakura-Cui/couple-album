package com.sakura.romance

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Letter(val id: String, val title: String, val text: String, val created: Long, val read: Boolean, val image: Boolean) {
    companion object {
        fun parse(o: JSONObject) = Letter(o.getString("id"), o.getString("title"), o.getString("text"),
            (o.getDouble("created") * 1000).toLong(), !o.isNull("read_at"), o.getInt("has_image") == 1)
    }
}

@Composable
internal fun InboxScreen(model: AppModel) {
    val context = LocalContext.current
    var consent by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(MessagePush.enabled(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { MessagePush.restore(context) }
    LaunchedEffect(model.ready) { if (model.ready) model.loadLetters() }
    val detail = model.letterDetail
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp,
        bottom = LocalBottomClearance.current + 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ model.backFromInbox() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                Text(if (model.letterTarget == null) "悄悄话" else "给老婆宝宝的信", Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                IconButton({ model.loadLetters() }, enabled = !model.lettersLoading) { Icon(Icons.Outlined.Refresh, "刷新消息") }
            }
        }
        if (model.letterTarget == null) {
            item {
                GlassPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("老婆宝宝，想说的话都在这里。", fontSize = 16.sp)
                        Text(if (enabled) MessagePush.status else "开启通知，及时收到我的想念。", color = TextSecondary, fontSize = 14.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!enabled) TextButton({ consent = true }) { Text("开启消息通知") }
                            else {
                                TextButton({
                                    if (!MessagePush.permission(context)) context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                                    else MessagePush.restore(context)
                                }) { Text(if (MessagePush.permission(context)) "重新连接" else "允许系统通知") }
                                TextButton({ MessagePush.disable(context); enabled = false }) { Text("关闭通知") }
                            }
                        }
                    }
                }
            }
        }
        if (model.lettersLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        model.lettersError?.let { error -> item {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton({ model.loadLetters() }) { Text("重试") }
        } }
        if (model.letterTarget != null) {
            detail?.let { letter -> item {
                GlassPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(letter.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text(letterTime(letter.created), color = TextSecondary, fontSize = 14.sp)
                        if (letter.text.isNotBlank()) SelectionContainer { Text(letter.text, fontSize = 16.sp, lineHeight = 28.sp) }
                        if (letter.image) LetterImage(model, letter.id)
                    }
                }
            } }
        } else {
            if (!model.lettersLoading && model.letters.isEmpty() && model.lettersError == null) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.MailOutline, null, Modifier.size(48.dp), tint = RoseInk)
                    Spacer(Modifier.height(16.dp))
                    Text("第一封悄悄话，还在路上", fontSize = 20.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("收到的文字和图片，会一直留在这里。", color = TextSecondary, fontSize = 14.sp)
                }
            }
            items(model.letters, key = { it.id }) { letter ->
                GlassPanel(Modifier.fillMaxWidth().clickable { model.openLetter(letter.id) }) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text((if (!letter.read) "• " else "") + letter.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text((if (letter.image) "[图片] " else "") + letter.text, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 16.sp, color = TextSecondary)
                        Text(letterTime(letter.created), fontSize = 14.sp, color = TextTertiary)
                    }
                }
            }
            if (model.lettersBefore != null) item {
                TextButton({ model.loadLetters(more = true) }, enabled = !model.lettersLoading, modifier = Modifier.fillMaxWidth()) { Text("更早的悄悄话") }
            }
        }
    }
    if (consent) RomanceDialog(onDismissRequest = { consent = false }, title = { Text("接收悄悄话") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("开启后会使用 vivo 推送 SDK，处理应用信息、应用内设备标识符、设备硬件和系统信息，用于送达通知。通知栏会显示消息文字；完整图片在收件箱查看。你可以随时关闭。")
            TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dev.vivo.com.cn/documentCenter/doc/878"))) }) { Text("查看 vivo 推送隐私政策") }
        }
    }, confirmButton = { TextButton({
        consent = false; enabled = true; MessagePush.enable(context)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !MessagePush.permission(context)) permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }) { Text("同意并开启") } }, dismissButton = { TextButton({ consent = false }) { Text("暂不开启") } })
}

@Composable
private fun LetterImage(model: AppModel, id: String) {
    var retry by remember(id) { mutableIntStateOf(0) }
    key(id, retry) {
        SubcomposeAsyncImage(model = "${model.vault.url}/api/messages/$id/image", imageLoader = model.images,
            contentDescription = "信里的图片", modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 1200.dp),
            contentScale = ContentScale.Fit,
            loading = { Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
            error = { TextButton({ retry++ }) { Text("图片加载失败，点击重试") } })
    }
}

private fun letterTime(time: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(time))
