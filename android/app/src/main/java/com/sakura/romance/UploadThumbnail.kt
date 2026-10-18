package com.sakura.romance

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun UploadThumbnail(task: UploadTask, images: ImageLoader) {
    val context = LocalContext.current
    val video = task.mime.startsWith("video/")
    var failed by remember(task.uri) { mutableStateOf(false) }
    val frame by produceState<Bitmap?>(null, task.uri) {
        if (video) value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(task.uri))
                val original = if (Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 192, 192)
                } else retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                original?.let {
                    val factor = minOf(1f, 192f / maxOf(it.width, it.height))
                    if (factor < 1f) Bitmap.createScaledBitmap(it, (it.width * factor).toInt().coerceAtLeast(1),
                        (it.height * factor).toInt().coerceAtLeast(1), true).also { _ -> original.recycle() } else it
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { null }
            finally { retriever.release() }
        }
    }
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(SurfaceSelected), contentAlignment = Alignment.Center) {
        if (video) {
            if (frame != null) Image(frame!!.asImageBitmap(), "${task.name} 缩略图", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Outlined.Movie, "${task.name} 视频预览不可用", tint = TextSecondary)
            Icon(Icons.Outlined.PlayCircle, "视频", Modifier.align(Alignment.BottomEnd).padding(4.dp).size(20.dp), tint = PureWhite)
        } else if (failed) Icon(Icons.Outlined.BrokenImage, "${task.name} 预览不可用", tint = TextSecondary)
        else AsyncImage(Uri.parse(task.uri), "${task.name} 缩略图", images, Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop, onError = { failed = true })
    }
}
