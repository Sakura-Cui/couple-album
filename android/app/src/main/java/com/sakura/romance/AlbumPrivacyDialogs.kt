package com.sakura.romance

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun EncryptedAlbumCover(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.encrypted_album_cover), "加密相册封面", modifier, contentScale = ContentScale.Crop)
}

@Composable
internal fun AlbumEditDialog(model: AppModel, album: Album?, dismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(album?.name.orEmpty()) }
    var protected by rememberSaveable { mutableStateOf(album?.protected == true) }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var changePassword by remember { mutableStateOf(false) }
    val needsPassword = protected && !model.albumSessionUnlocked
    RomanceDialog(onDismissRequest = { if (!model.busy) dismiss() }, title = { Text(if (album == null) "给回忆一个名字" else "编辑相册") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { if (it.length <= 80) name = it }, Modifier.fillMaxWidth(),
                label = { Text("相册名称") }, placeholder = { Text("例如：云南旅行") }, singleLine = true, enabled = !model.busy)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("加密相册")
                    Text("所有加密相册共用一个密码", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Switch(protected, { protected = it }, enabled = !model.busy && model.albumPrivacySupported)
            }
            if (!model.albumPrivacySupported) Text("请先更新服务器以使用相册加密", color = TextSecondary)
            if (needsPassword) {
                OutlinedTextField(password, { if (it.length <= 128) password = it }, Modifier.fillMaxWidth(),
                    label = { Text(if (model.albumPasswordEnabled) "加密相册密码" else "设置密码，至少 6 位") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !model.busy)
                if (!model.albumPasswordEnabled) OutlinedTextField(again, { again = it }, Modifier.fillMaxWidth(),
                    label = { Text("再次输入密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !model.busy)
            }
            if (album?.protected == true && model.albumPasswordEnabled) TextButton({ changePassword = true }, enabled = !model.busy) { Text("修改加密相册密码") }
        }
    }, confirmButton = { TextButton({ model.saveAlbum(album, name.trim(), protected, password, dismiss) },
        enabled = !model.busy && model.albumPrivacySupported && name.isNotBlank() &&
            (!needsPassword || (if (model.albumPasswordEnabled) password.isNotEmpty() else password.length >= 6 && password == again))) {
        Text(if (model.busy) "保存中…" else "保存")
    } }, dismissButton = { TextButton(dismiss, enabled = !model.busy) { Text("取消") } })
    if (changePassword) AlbumPasswordDialog(model) { changePassword = false; dismiss() }
}

@Composable
internal fun AlbumUnlockDialog(model: AppModel, album: Album) {
    var password by remember(album.id) { mutableStateOf("") }
    RomanceDialog(onDismissRequest = { if (!model.busy) model.cancelAlbumUnlock() }, title = { Text("解锁加密相册") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EncryptedAlbumCover(Modifier.fillMaxWidth().height(136.dp).clip(RoundedCornerShape(16.dp)))
            Text(album.name)
            OutlinedTextField(password, { if (it.length <= 128) password = it }, Modifier.fillMaxWidth(), label = { Text("加密相册密码") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !model.busy)
            Text("使用所有加密相册共用的密码。", color = TextSecondary)
        }
    }, confirmButton = { TextButton({ model.unlockAlbum(password) }, enabled = password.isNotEmpty() && !model.busy) { Text("解锁") } },
        dismissButton = { TextButton({ model.cancelAlbumUnlock() }, enabled = !model.busy) { Text("取消") } })
}

@Composable
private fun AlbumPasswordDialog(model: AppModel, dismiss: () -> Unit) {
    var old by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    RomanceDialog(onDismissRequest = { if (!model.busy) dismiss() }, title = { Text("修改加密相册密码") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("修改后，所有已绑定设备上的加密相册都需重新解锁。", color = TextSecondary)
            OutlinedTextField(old, { old = it }, Modifier.fillMaxWidth(), label = { Text("当前密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(password, { if (it.length <= 128) password = it }, Modifier.fillMaxWidth(), label = { Text("新密码，至少 6 位") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(again, { again = it }, Modifier.fillMaxWidth(), label = { Text("再次输入密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        }
    }, confirmButton = { TextButton({ model.changeAlbumPassword(old, password, dismiss) }, enabled = !model.busy && old.isNotEmpty() && password.length >= 6 && password == again) { Text("保存") } },
        dismissButton = { TextButton(dismiss, enabled = !model.busy) { Text("取消") } })
}
