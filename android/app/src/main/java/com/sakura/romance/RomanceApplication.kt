package com.sakura.romance

import android.app.Application

class RomanceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MorningReminder.removeLegacyNotifications(this)
        MessagePush.restore(this)
    }

    // The foreground service and recreated activities share the same in-memory queue runner.
    val model by lazy { AppModel(this) }
}
