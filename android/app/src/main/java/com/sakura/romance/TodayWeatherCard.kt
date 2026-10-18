package com.sakura.romance

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.ZonedDateTime
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun TodayWeatherCard() {
    val context = LocalContext.current
    val store = remember { MorningStore(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var revision by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                revision++
                refreshing = MorningReminder.needsRefresh(context)
                try {
                    if (!MorningReminder.refresh(context)) MorningReminder.refreshSoon(context)
                } finally { refreshing = false; revision++ }
                val now = ZonedDateTime.now()
                delay(Duration.between(now, MorningPolicy.nextRefresh(now)).toMillis().coerceIn(1000, 60000))
            }
        }
    }
    val detail = remember(revision) { store.today() }
    val error = remember(revision) { store.prefs.getString("error", null) }
    GlassPanel(Modifier.fillMaxWidth().testTag("today-weather")) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("今日天气", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("${detail?.optString("city") ?: store.place().name} · ${java.time.LocalDate.now()}", color = TextSecondary, fontSize = 14.sp)
            if (detail != null) {
                val condition = detail.optString("condition")
                val icon = when {
                    "雷" in condition -> Icons.Outlined.Thunderstorm
                    "雪" in condition -> Icons.Outlined.AcUnit
                    "雨" in condition -> Icons.Outlined.WaterDrop
                    "雾" in condition -> Icons.Outlined.Air
                    condition == "晴" -> Icons.Outlined.WbSunny
                    "云" in condition || condition == "阴" -> Icons.Outlined.CloudQueue
                    else -> Icons.Outlined.HelpOutline
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("${detail.optDouble("low").roundToInt()}～${detail.optDouble("high").roundToInt()}℃", Modifier.weight(1f), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    Icon(icon, contentDescription = condition, modifier = Modifier.size(48.dp).testTag("weather-condition-icon"), tint = RoseInk)
                }
                Text("${detail.optString("condition")} · 降雨概率 ${(detail.optDouble("rain_probability") * 100).roundToInt()}%", color = TextSecondary, fontSize = 14.sp)
                HorizontalDivider(color = BorderDefault.copy(alpha = .09f))
                Text(detail.optString("advice"), fontSize = 14.sp, lineHeight = 24.sp)
                Text(detail.optString("love"), color = RoseInk, fontSize = 14.sp, lineHeight = 24.sp)
                val time = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date((detail.optDouble("fetched_at") * 1000).toLong()))
                Text("天气数据更新于 $time", color = TextTertiary, fontSize = 12.sp)
            } else {
                Text(if (refreshing) "正在更新今日天气…" else "今日天气暂未更新，联网后会自动重试。", color = TextSecondary, fontSize = 14.sp, lineHeight = 24.sp)
            }
            if (detail != null && error != null) Text("暂时无法刷新，当前展示最近获取的今日天气。", color = TextTertiary, fontSize = 12.sp)
        }
    }
}
