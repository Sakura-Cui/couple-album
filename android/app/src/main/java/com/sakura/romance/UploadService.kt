package com.sakura.romance

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

class UploadService : Service() {
    private val model get() = (application as RomanceApplication).model
    private var runId = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        runId = model.uploadRunId
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "回忆上传", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(ID, notification(this, model.tasks, model.activeUploadId))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "$packageName:upload"
        ).apply { acquire(6 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.hasExtra(RUN_ID) == true) runId = intent.getLongExtra(RUN_ID, runId)
        if (intent?.action == PAUSE) model.pauseUploads()
        if (!model.uploading) stopSelf()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        model.pauseUploads()
        model.message = "系统限制了本次后台上传时长，请返回后继续上传"
        stopSelf()
    }

    override fun onDestroy() {
        model.uploadServiceDestroyed(runId)
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val RUN_ID = "upload_run_id"
        private const val CHANNEL = "memory_uploads"
        private const val ID = 1001
        const val PAUSE = "com.sakura.romance.PAUSE_UPLOADS"

        private var lastUpdate = 0L
        private var lastStage: Pair<String?, String?>? = null

        private fun notification(context: Context, tasks: List<UploadTask>, activeId: String?): Notification {
            val pending = tasks.filter { it.status != "已完成" }
            val item = pending.firstOrNull { it.id == activeId }
            val progress = item?.let { (it.displayOffset * 1000 / it.size.coerceAtLeast(1)).toInt().let { value -> if (it.displayOffset > 0) value.coerceAtLeast(1) else 0 } } ?: 0
            val detail = if (item == null) "准备上传" else if (item.preparing) item.status else "${item.status} · ${item.progressLabel}"
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val pause = PendingIntent.getService(context, 1, Intent(context, UploadService::class.java).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("正在上传回忆")
                .setContentText("剩余 ${pending.size} 项 · $detail")
                .setProgress(1000, progress, item == null || item.preparing)
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(Notification.Action.Builder(null, "暂停上传", pause).build()).build()
        }

        fun update(context: Context, tasks: List<UploadTask>, activeId: String?) {
            val stage = activeId to tasks.firstOrNull { it.id == activeId }?.status
            val now = android.os.SystemClock.elapsedRealtime()
            if (stage == lastStage && now - lastUpdate < 500) return
            lastStage = stage
            lastUpdate = now
            if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) != null) manager.notify(ID, notification(context, tasks, activeId))
        }
    }
}
