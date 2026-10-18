package com.sakura.romance

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivo.push.PushClient
import com.vivo.push.PushConfig
import com.vivo.push.listener.IPushQueryActionListener
import com.vivo.push.sdk.OpenClientPushMessageReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object MessagePush {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val syncLock = Mutex()
    private val connectionPolicy = PushConnectionPolicy()
    private var starting = false
    private var initialized = false
    private var generation = 0
    var status by mutableStateOf("尚未开启消息通知"); private set
    fun prefs(context: Context) = context.getSharedPreferences("private_messages", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun supported() = Build.MANUFACTURER.equals("vivo", true) && !BuildConfig.DEBUG
    fun permission(context: Context) = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun enable(context: Context) {
        prefs(context).edit().putBoolean("enabled", true).apply()
        restore(context)
    }

    fun disable(context: Context) {
        generation++
        starting = false
        prefs(context).edit().putBoolean("enabled", false).putBoolean("sync_disabled", true).apply()
        val epoch = generation
        if (initialized) scope.launch {
            delay(connectionPolicy.reserveDelay(SystemClock.elapsedRealtime()))
            if (epoch == generation && !enabled(context))
                runCatching { PushClient.getInstance(context).turnOffPush { } }
        }
        status = "消息通知已关闭，仍可查看收件箱"
        sync(context, "", false)
    }

    private fun sync(context: Context, regId: String, active: Boolean) {
        val app = context.applicationContext
        scope.launch { syncLock.withLock {
            val vault = Vault(app)
            if (vault.token.isBlank()) return@withLock
            val allowed = active && enabled(app) && permission(app)
            try {
                Api(vault).obj("/api/push/registration", "PUT", json("reg_id" to regId, "enabled" to allowed))
                if (active && enabled(app)) status = if (permission(app)) "通知已连接" else "请在系统设置中允许通知"
                if (!active) prefs(app).edit().putBoolean("sync_disabled", false).apply()
            } catch (_: Exception) {
                status = if (active) "通知连接未完成，点击重试" else "手机通知已关闭，联网后同步服务器"
            }
        } }
    }

    fun received(context: Context, regId: String) {
        scope.launch {
            if (!enabled(context) || regId.isBlank()) return@launch
            prefs(context).edit().putString("reg_id", regId).apply()
            sync(context, regId, permission(context))
        }
    }

    fun restore(context: Context) {
        val app = context.applicationContext
        if (prefs(app).getBoolean("sync_disabled", false)) sync(app, "", false)
        if (!enabled(app) || Vault(app).token.isBlank() || starting) return
        if (!supported()) { status = "此设备可查看收件箱；系统推送需要 vivo 正式安装包"; return }
        if (BuildConfig.VIVO_APP_KEY.isBlank()) { status = "此安装包尚未配置推送"; return }
        if (!permission(app)) {
            status = "请在系统设置中允许通知"
            sync(app, prefs(app).getString("reg_id", "").orEmpty(), false)
            return
        }
        val epoch = generation
        starting = true
        status = "正在连接消息通知…"
        scope.launch {
            delay(connectionPolicy.reserveDelay(SystemClock.elapsedRealtime()))
            if (epoch != generation || !enabled(app)) return@launch
            try {
                val client = PushClient.getInstance(app)
                if (!initialized) {
                    client.initialize(PushConfig.Builder().agreePrivacyStatement(true).build())
                    initialized = true
                }
                client.querySubscribeState { state ->
                    scope.launch {
                        if (epoch != generation || !enabled(app)) return@launch
                        when (state) {
                            0 -> readRegId(app, client, epoch)
                            1, 8013 -> subscribe(app, client, epoch)
                            else -> connectionFailed(epoch, state)
                        }
                    }
                }
            } catch (_: Exception) {
                starting = false
                status = "通知初始化失败，请检查安装包配置"
            }
        }
    }

    private fun subscribe(app: Context, client: PushClient, epoch: Int) {
        scope.launch {
            delay(connectionPolicy.reserveDelay(SystemClock.elapsedRealtime()))
            if (epoch != generation || !enabled(app)) return@launch
            if (!permission(app)) {
                starting = false
                status = "请在系统设置中允许通知"
                return@launch
            }
            try {
                client.turnOnPush { state ->
                    scope.launch {
                        if (epoch != generation || !enabled(app)) return@launch
                        if (connectionPolicy.subscriptionSucceeded(state)) readRegId(app, client, epoch)
                        else connectionFailed(epoch, state)
                    }
                }
            } catch (_: Exception) {
                starting = false
                status = "通知连接未完成，点击重试"
            }
        }
    }

    private fun readRegId(app: Context, client: PushClient, epoch: Int) {
        client.getRegId(object : IPushQueryActionListener {
            override fun onSuccess(regId: String) { scope.launch {
                if (epoch != generation || !enabled(app)) return@launch
                starting = false
                if (regId.isBlank()) status = "尚未获取到通知标识，请稍后重试"
                else received(app, regId)
            } }
            override fun onFail(code: Int) { scope.launch { connectionFailed(epoch, code) } }
        })
    }

    private fun connectionFailed(epoch: Int, code: Int) {
        if (epoch != generation) return
        starting = false
        status = when (code) {
            1002 -> "连接操作太快，请等待几秒后重新连接（1002）"
            1001 -> "今日连接次数已达上限，请明天重试（1001）"
            else -> "通知连接失败（$code），点击重试"
        }
    }

}

class RomancePushReceiver : OpenClientPushMessageReceiver() {
    override fun onReceiveRegId(context: Context, regId: String) = MessagePush.received(context, regId)
}

fun validMessageId(value: String?) = value?.takeIf { it.matches(Regex("[0-9a-f]{32}")) }
