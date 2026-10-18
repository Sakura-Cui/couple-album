package com.sakura.romance

import android.app.NotificationManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.lifecycle.Lifecycle
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MorningAlbumsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Suppress("DEPRECATION")
    @Test fun organizeDragAndPersistentWeatherWithoutNotifications() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val model = rule.activity.model
        val context = rule.activity
        rule.waitUntil(15000) { rule.onAllNodesWithText("跳过").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("跳过").performClick()
        device.executeShellCommand("pm revoke ${context.packageName} android.permission.POST_NOTIFICATIONS")
        rule.runOnUiThread { model.bind((InstrumentationRegistry.getArguments().getString("qa_morning_url") ?: "https://10.0.2.2:18445"), requireNotNull(InstrumentationRegistry.getArguments().getString("invite")), "早安与排序验收") }
        rule.waitUntil(20000) { model.ready && !model.busy && !model.albumsLoading }
        assertTrue("Morning reminders must be enabled automatically after binding", MorningStore(context).enabled)
        val denyLocation = device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_deny_button")), 5000)
        assertNotNull("Location permission should be requested automatically", denyLocation)
        denyLocation.click()
        rule.waitForIdle()
        assertEquals("南京市", MorningStore(context).place().name)
        val ids = runBlocking { (0 until 3).map { model.api.obj("/api/albums", "POST", json("name" to "早安相册 $it")).getString("id") } }
        rule.runOnUiThread { model.refresh() }
        rule.waitUntil(20000) { !model.busy && model.albums.size >= 3 }
        rule.onNodeWithTag("organize-albums").performClick()
        rule.waitUntil(10000) { model.organizingAlbums && !model.busy }
        rule.waitUntil(15000) { rule.onAllNodesWithTag("pin-${ids[2]}").fetchSemanticsNodes().isNotEmpty() }
        capture("morning-organizing")
        for (id in ids.take(2)) {
            rule.onNodeWithTag("album-grid").performScrollToKey(id)
            rule.waitForIdle()
            rule.onNodeWithTag("pin-$id").assertIsEnabled().assertIsDisplayed()
            capture("morning-before-pin")
            rule.onNodeWithTag("pin-$id").performClick()
            try { rule.waitUntil(10000) { !model.busy && model.albums.first { it.id == id }.pinned } }
            catch (failure: Throwable) {
                capture("morning-pin-failed")
                throw AssertionError("Pin failed: ready=${model.ready}, busy=${model.busy}, message=${model.message}, albums=${model.albums}", failure)
            }
        }
        val first = ids[0]; val second = ids[1]
        rule.onNodeWithTag("album-grid").performScrollToKey(first)
        rule.waitForIdle()
        val grid = rule.onNodeWithTag("album-grid").fetchSemanticsNode().boundsInRoot
        val a = rule.onNodeWithTag("album-$first").fetchSemanticsNode().boundsInRoot
        val b = rule.onNodeWithTag("album-$second").fetchSemanticsNode().boundsInRoot
        val start = Offset(b.center.x - grid.left, b.top + 60 - grid.top)
        val end = Offset(a.center.x - grid.left, a.top + 60 - grid.top)
        rule.onNodeWithTag("album-grid").performTouchInput {
            down(start); advanceEventTime(800); moveTo(end, 500); advanceEventTime(100); up()
        }
        rule.waitUntil(15000) { !model.busy && model.albums.first().id == second }
        assertEquals(listOf(second, first), model.albums.filter { it.pinned }.map { it.id })
        capture("morning-albums")
        rule.onNodeWithTag("album-grid").performScrollToKey("organize")
        rule.onNodeWithTag("organize-albums").performClick()
        rule.waitUntil { !model.organizingAlbums }

        rule.onNodeWithText("我们的空间").performClick()
        rule.onNodeWithTag("morning-settings").assertDoesNotExist()
        rule.onNodeWithText("给老婆宝宝的早安").assertDoesNotExist()
        rule.onNodeWithText("更新手机定位").assertDoesNotExist()
        rule.onNodeWithText("正在更新位置").assertDoesNotExist()
        assertEquals("32.06,118.78", MorningStore(context).place().key)
        rule.waitUntil(20000) { MorningStore(context).today() != null }
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        assertFalse(device.hasObject(By.res("com.android.permissioncontroller", "permission_deny_button")))
        capture("morning-settings")
        assertEquals("南京市", MorningStore(context).today()!!.getString("city"))
        assertTrue(MorningStore(context).today()!!.getBoolean("weather_available"))
        rule.onNodeWithTag("today-weather").assertIsDisplayed()
        rule.onNodeWithText("今日天气").assertIsDisplayed()
        rule.onNodeWithText("18～25℃").assertIsDisplayed()
        rule.onNodeWithText("小雨 · 降雨概率 60%").assertIsDisplayed()
        val notifications = context.getSystemService(NotificationManager::class.java)
        assertNull(notifications.getNotificationChannel("morning_love"))
        assertTrue(notifications.activeNotifications.none { it.id == 625 || it.id == 626 })
        val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java)
        assertNull(scheduler.getPendingJob(62501))
        assertNotNull(scheduler.getPendingJob(60001))
        rule.runOnUiThread { MorningStore(context).prefs.edit().putLong("last_refresh", 0).commit() }
        device.executeShellCommand("cmd jobscheduler run -f ${context.packageName} 60001")
        rule.waitUntil(20000) { MorningStore(context).prefs.getLong("last_refresh", 0) > 0 }
        rule.waitUntil(5000) { scheduler.getPendingJob(60001)?.minLatencyMillis?.let { it > 0 } == true }
        assertTrue(scheduler.getPendingJob(60001)!!.isPersisted)
        assertTrue(notifications.activeNotifications.none { it.id == 625 || it.id == 626 })
        rule.waitUntil(10000) { runCatching { rule.onNodeWithTag("weather-condition-icon").assertIsDisplayed(); true }.getOrDefault(false) }
        rule.onNodeWithTag("weather-condition-icon").assertContentDescriptionEquals("小雨")
        rule.onNodeWithText("每天 06:00 更新 · 打开页面自动检查").assertDoesNotExist()
        rule.onNodeWithText("天气来源：Open-Meteo").assertDoesNotExist()
        capture("weather-card")
        val originalBriefs = MorningStore(context).prefs.getString("briefs", "[]")!!
        for ((condition, screenshot) in listOf("晴" to "weather-sunny", "阴" to "weather-cloudy")) {
            val items = org.json.JSONArray(originalBriefs)
            for (i in 0 until items.length()) items.getJSONObject(i).put("condition", condition).put("rain_probability", 0)
            rule.runOnUiThread { MorningStore(context).prefs.edit().putString("briefs", items.toString()).commit() }
            rule.waitUntil(10000) { runCatching { rule.onNodeWithTag("weather-condition-icon").assertContentDescriptionEquals(condition).assertIsDisplayed(); true }.getOrDefault(false) }
            capture(screenshot)
        }
        rule.runOnUiThread { MorningStore(context).prefs.edit().putString("briefs", originalBriefs).commit() }

        // An expired cache must not be presented as today's weather while offline.
        device.executeShellCommand("svc wifi disable")
        device.executeShellCommand("svc data disable")
        try {
            rule.runOnUiThread {
                MorningStore(context).prefs.edit().putString("briefs", "[]").putString("error", "offline").commit()
            }
            rule.waitUntil(5000) { rule.onAllNodesWithText("18～25℃").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithTag("today-weather").assertIsDisplayed()
            capture("weather-card-empty")
        } finally {
            device.executeShellCommand("svc wifi enable")
            device.executeShellCommand("svc data enable")
        }
        rule.runOnUiThread { MorningReminder.refreshSoon(context) }
        rule.waitUntil(30000) { MorningStore(context).today() != null }
        // A fresh platform location must be picked up on foreground entry, without a UI action.
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        device.executeShellCommand("pm grant ${context.packageName} android.permission.ACCESS_COARSE_LOCATION")
        device.executeShellCommand("appops set ${context.packageName} android:mock_location allow")
        val locations = context.getSystemService(LocationManager::class.java)
        locations.addTestProvider(LocationManager.NETWORK_PROVIDER, false, false, false, false, false, false, false, Criteria.POWER_LOW, Criteria.ACCURACY_COARSE)
        try {
            locations.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
            val fix = Location(LocationManager.NETWORK_PROVIDER).apply {
                latitude = 31.23; longitude = 121.47; accuracy = 20f
                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            locations.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, fix)
            rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            // Deliver the fix while foreground access is active, as a real provider would.
            repeat(3) {
                SystemClock.sleep(150)
                fix.time = System.currentTimeMillis()
                fix.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                locations.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, fix)
            }
            try {
                rule.waitUntil(20000) { MorningStore(context).location?.let { kotlin.math.abs(it.latitude - 31.23) < .1 && kotlin.math.abs(it.longitude - 121.47) < .1 } == true }
            } catch (failure: Throwable) {
                throw AssertionError("Automatic location: lifecycle=${context.lifecycle.currentState}, enabled=${MorningStore(context).enabled}, saved=${MorningStore(context).location}, platform=${locations.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)}", failure)
            }
            assertEquals("最近定位", MorningStore(context).place().name)
        } finally {
            locations.removeTestProvider(LocationManager.NETWORK_PROVIDER)
            device.executeShellCommand("appops set ${context.packageName} android:mock_location deny")
        }
        rule.runOnUiThread { MorningReminder.disable(context) }
        assertFalse(MorningStore(context).enabled)
        rule.waitUntil(5000) { notifications.activeNotifications.none { it.id == 625 || it.id == 626 } }
        runBlocking { ids.forEach { model.api.request("/api/albums/$it", "DELETE") } }

    }
    @Test fun upgradeInvalidatesVersionTwoWeatherCopyOnce() {
        val prefs = MorningStore(rule.activity).prefs
        prefs.edit().putInt("content_version", 2).putString("briefs", "old weather copy")
            .putLong("last_refresh", 123L).putString("location", "saved location")
            .putBoolean("enabled", false).commit()
        MorningStore(rule.activity)
        assertEquals(3, prefs.getInt("content_version", 0))
        assertFalse(prefs.contains("briefs"))
        assertFalse(prefs.contains("last_refresh"))
        assertEquals("saved location", prefs.getString("location", null))
        assertFalse(prefs.getBoolean("enabled", true))
        prefs.edit().putString("briefs", "new weather copy").putLong("last_refresh", 456L).commit()
        MorningStore(rule.activity)
        assertEquals("new weather copy", prefs.getString("briefs", null))
        assertEquals(456L, prefs.getLong("last_refresh", 0))
    }

    @Test fun upgradeClearsOldNotificationsAndJobsOnce() {
        val context = rule.activity
        val prefs = MorningStore(context).prefs
        prefs.edit().remove("weather_card_migrated").putStringSet("sent_dates", setOf("2026-09-20")).commit()
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(android.app.NotificationChannel("morning_love", "old", NotificationManager.IMPORTANCE_DEFAULT))
        android.app.PendingIntent.getBroadcast(context, 625,
            android.content.Intent(context, MorningReceiver::class.java).setAction("com.sakura.romance.MORNING_ALARM"),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java)
        scheduler.schedule(android.app.job.JobInfo.Builder(62501, android.content.ComponentName(context, MorningRefreshService::class.java)).setPeriodic(21600000).build())
        rule.runOnUiThread { MorningReminder.removeLegacyNotifications(context) }
        assertNull(notifications.getNotificationChannel("morning_love"))
        assertNull(scheduler.getPendingJob(62501))
        assertFalse(prefs.contains("sent_dates"))
        assertNull(android.app.PendingIntent.getBroadcast(context, 625,
            android.content.Intent(context, MorningReceiver::class.java).setAction("com.sakura.romance.MORNING_ALARM"),
            android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE))
        prefs.edit().putBoolean("enabled", false).commit()
        rule.runOnUiThread { MorningReminder.removeLegacyNotifications(context) }
        assertFalse(MorningStore(context).enabled)
    }
    private fun capture(name: String) {
        rule.waitForIdle()
        Thread.sleep(400)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.takeScreenshot(File(rule.activity.filesDir, "qa-$name.png"))
    }
}
