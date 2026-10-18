package com.sakura.romance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

@Composable
internal fun SakuraSplashScreen(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(5_000)
        onFinished()
    }

    RomanceScene {
        FallingSakura(Modifier.fillMaxSize())
        TextButton(
            onClick = onFinished,
            modifier = Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(16.dp)
        ) {
            Text("跳过", color = TextSecondary)
        }

        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BrandIcon(Modifier.size(96.dp))
            Spacer(Modifier.size(28.dp))
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("小樱", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Filled.Favorite, "爱心", Modifier.size(28.dp), tint = SakuraPink)
                Spacer(Modifier.width(12.dp))
                Text("小桃", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            }
            Spacer(Modifier.size(44.dp))
            Text("把心动写进每一个平凡日子", fontSize = 15.sp, color = TextSecondary)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.app_name), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextSecondary)
            Spacer(Modifier.size(4.dp))
            Text("只属于我们的回忆", fontSize = 12.sp, color = TextTertiary)
        }
    }
}

@Composable
private fun FallingSakura(modifier: Modifier = Modifier) {
    AndroidView(factory = { SakuraParticleView(it) }, modifier = modifier)
}
