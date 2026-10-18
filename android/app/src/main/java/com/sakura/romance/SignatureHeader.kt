package com.sakura.romance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SignatureHeader() {
    Column(Modifier.fillMaxWidth().testTag("space-header")) {
        Text("我们的空间", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        BrandUnderline()
    }
}

@Composable
internal fun DeviceProfileCard(deviceName: String, connected: Boolean, signature: String,
                               saving: Boolean = false, save: (String, () -> Unit) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    GlassPanel(Modifier.fillMaxWidth(), tint = PureWhite.copy(alpha = .30f)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
            BrandIcon(Modifier.size(56.dp), contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(deviceName, Modifier.weight(1f, fill = false), fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.size(9.dp).clip(CircleShape)
                        .background(if (connected) Color(0xFF58B77C) else Color(0xFFD85D6B))
                        .semantics { contentDescription = if (connected) "已连接" else "连接失败" })
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "编辑个性签名") { editing = true }
                    .testTag("device-signature"), verticalAlignment = Alignment.CenterVertically) {
                    Text(signature.ifBlank { "写一句属于自己的话" },
                        Modifier.weight(1f).padding(vertical = 8.dp), fontSize = 14.sp, lineHeight = 22.sp,
                        color = if (signature.isBlank()) TextSecondary else RoseInk)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Outlined.Edit, "编辑个性签名", Modifier.size(16.dp), tint = RoseInk)
                }
            }
        }
    }
    if (editing) SignatureDialog(signature, saving, { editing = false }) { value ->
        save(value) { editing = false }
    }
}

@Composable
private fun SignatureDialog(initial: String, saving: Boolean, dismiss: () -> Unit, save: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf(initial) }
    val count = draft.codePointCount(0, draft.length)
    RomanceDialog(onDismissRequest = dismiss, title = { Text("编辑个性签名") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth().testTag("signature-input"),
                label = { Text("个性签名") }, placeholder = { Text("写一句属于自己的话") },
                minLines = 2, maxLines = 5, isError = count > 20,
                supportingText = { Text(if (count > 20) "已超出 ${count - 20} 字，请缩短后保存" else "$count / 20") })
            Text("最多20字，三台设备共享最新签名，留空清除。", color = TextSecondary)
        }
    }, confirmButton = {
        TextButton({ save(draft.trim()) }, enabled = count <= 20 && !saving) { Text("保存") }
    }, dismissButton = { TextButton(dismiss, enabled = !saving) { Text("取消") } })
}
