package com.sakura.romance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SettingsScreen(model: AppModel, queue: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
        .padding(top = 24.dp, bottom = LocalBottomClearance.current + 20.dp)) {
        Spacer(Modifier.height(8.dp))
        SignatureHeader()
        Spacer(Modifier.height(12.dp)); Text("安心收藏，慢慢回看。", color = TextSecondary, fontSize = 15.sp)
        Spacer(Modifier.height(24.dp))
        TodayWeatherCard()
        Spacer(Modifier.height(12.dp))
        GlassPanel(Modifier.fillMaxWidth().clickable { model.openInbox() }) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MailOutline, null, tint = RoseInk)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("悄悄话", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("给老婆宝宝的文字和图片", fontSize = 14.sp, color = TextSecondary)
                }
                Icon(Icons.Outlined.ChevronRight, "打开收件箱", tint = TextSecondary)
            }
        }
        Spacer(Modifier.height(12.dp))
        DeviceProfileCard(model.deviceName, model.serverConnected, model.signature, model.signatureSaving, model::saveSignature)
        Spacer(Modifier.height(12.dp))
        GlassPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth().clickable(onClick = queue).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsIcon(Icons.Outlined.CloudUpload)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("上传队列", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp)); Text("${model.tasks.count { it.status != "已完成" }} 个待完成任务", color = TextSecondary, fontSize = 14.sp)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = TextSecondary)
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = BorderDefault.copy(alpha = .09f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIcon(Icons.Outlined.Storage)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("已收藏 ${formatSize(model.used)}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp)); Text("当前空间配额 ${formatSize(model.quota)}", color = TextSecondary, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { if (model.quota > 0) (model.used.toFloat() / model.quota).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = RoseInk, trackColor = MintGreen.copy(alpha = .42f))
            }
        }
        Spacer(Modifier.height(20.dp)); Text("${stringResource(R.string.app_name)}  ${BuildConfig.VERSION_NAME}\n两个人，一处收藏回忆的地方。", color = TextTertiary, fontSize = 12.sp, lineHeight = 24.sp)
        Spacer(Modifier.height(16.dp))
    }
}

