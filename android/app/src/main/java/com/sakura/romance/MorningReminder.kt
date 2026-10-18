package com.sakura.romance

import android.Manifest
import android.app.*
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.location.Geocoder
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.Duration
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal data class MorningPlace(val latitude: Double, val longitude: Double, val name: String, val at: Long = 0) {
    val key get() = String.format(Locale.ROOT, "%.2f,%.2f", latitude, longitude)
    fun toJson() = json("latitude" to latitude, "longitude" to longitude, "name" to name, "at" to at).toString()
    companion object {
        fun parse(value: String?): MorningPlace? = runCatching {
            val o = JSONObject(value ?: return null)
            MorningPlace(o.getDouble("latitude"), o.getDouble("longitude"), o.optString("name", "最近定位"), o.optLong("at"))
        }.getOrNull()
    }
}

internal class MorningStore(private val context: Context) {
    val prefs = context.getSharedPreferences("morning_reminder", Context.MODE_PRIVATE)
    init {
        if (prefs.getInt("content_version", 0) < 3) prefs.edit().remove("briefs").remove("delivered")
            .remove("delivered_detail").remove("delivered_date").remove("error").remove("last_refresh")
            .remove("fallback").putInt("content_version", 3).commit()
    }
    init {
        if (!prefs.getBoolean("automatic_reminders", false)) prefs.edit()
            .putBoolean("enabled", true).putBoolean("automatic_reminders", true).commit()
    }
    val enabled get() = prefs.getBoolean("enabled", true)
    val fallback = MorningPlace(32.06167, 118.77778, "南京市")
    var location: MorningPlace?
        get() = MorningPlace.parse(prefs.getString("location", null))
        set(value) {
            val edit = prefs.edit().putString("location", value?.toJson())
            if (location?.key != value?.key) edit.remove("briefs")
            edit.apply()
        }
    fun place(now: Long = System.currentTimeMillis()): MorningPlace = location?.takeIf {
        MorningPolicy.useLocation(context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED, it.at, now)
    } ?: fallback
    fun today(): JSONObject? {
        val place = place()
        val now = Instant.now(); val zone = ZoneId.systemDefault()
        return runCatching {
            val items = JSONArray(prefs.getString("briefs", "[]"))
            (0 until items.length()).map { items.getJSONObject(it) }.firstOrNull {
                MorningPolicy.usable(it.getString("date"), it.getString("location_key"), it.getString("timezone"),
                    (it.getDouble("fetched_at") * 1000).toLong(), place.key, now, zone)
            }
        }.getOrNull()
    }

}

internal object MorningReminder {
    private const val DAILY = 60001
    private const val REFRESH = 62502
    private val refreshing = AtomicBoolean(false)

    // Keep the old receiver identity solely to cancel reminders installed by older APKs.
    fun removeLegacyNotifications(context: Context) {
        val pending = PendingIntent.getBroadcast(context, 625,
            Intent(context, MorningReceiver::class.java).setAction("com.sakura.romance.MORNING_ALARM"),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        pending?.let { context.getSystemService(AlarmManager::class.java).cancel(it); it.cancel() }
        context.getSystemService(NotificationManager::class.java).apply {
            cancel(625); cancel(626); deleteNotificationChannel("morning_love")
        }
        val prefs = MorningStore(context).prefs
        if (!prefs.getBoolean("weather_card_migrated", false)) {
            context.getSystemService(JobScheduler::class.java).apply { cancel(62501); cancel(REFRESH) }
            prefs.edit().putBoolean("enabled", Vault(context).token.isNotEmpty())
                .remove("sent_dates").remove("delivered").remove("delivered_detail").remove("delivered_date")
                .remove("automatic_alarm_requested").remove("automatic_permissions_requested")
                .putBoolean("weather_card_migrated", true).commit()
        }
    }
    fun enable(context: Context) {
        MorningStore(context).prefs.edit().putBoolean("enabled", true).apply()
        restore(context)
        refreshSoon(context)
    }
    fun disable(context: Context) {
        MorningStore(context).prefs.edit().putBoolean("enabled", false).remove("briefs").remove("last_refresh").remove("error").apply()
        context.getSystemService(JobScheduler::class.java).apply { cancel(DAILY); cancel(REFRESH) }
    }
    fun restore(context: Context, reset: Boolean = false) {
        if (!MorningStore(context).enabled) return
        if (Vault(context).token.isEmpty()) { disable(context); return }
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (reset) scheduler.cancel(DAILY)
        if (scheduler.getPendingJob(DAILY) == null) {
            val now = ZonedDateTime.now()
            val delay = Duration.between(now, MorningPolicy.nextRefresh(now)).toMillis()
            scheduler.schedule(JobInfo.Builder(DAILY, ComponentName(context, MorningRefreshService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(delay).setPersisted(true)
                .setBackoffCriteria(60000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        }
    }
    fun needsRefresh(context: Context): Boolean {
        val store = MorningStore(context)
        return store.enabled && MorningPolicy.needsRefresh(store.prefs.getLong("last_refresh", 0), store.today() != null, ZonedDateTime.now())
    }
    fun refreshSoon(context: Context) {
        if (!needsRefresh(context)) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(REFRESH) == null) scheduler.schedule(JobInfo.Builder(REFRESH, ComponentName(context, MorningRefreshService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(0).setBackoffCriteria(60000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
    }
    suspend fun refresh(context: Context): Boolean {
        if (!needsRefresh(context)) return true
        if (!refreshing.compareAndSet(false, true)) return false
        try {
            val store = MorningStore(context)
            if (!store.enabled) return true
            val vault = Vault(context)
            val token = vault.token
            if (token.isEmpty()) { disable(context); return true }
            val startedAt = System.currentTimeMillis()
            val place = store.place()
            val zone = ZoneId.systemDefault().id
            @Suppress("DEPRECATION")
            val city = if (place.name != "最近定位") place.name else withContext(Dispatchers.IO) {
                runCatching {
                    Geocoder(context, Locale.CHINA).getFromLocation(place.latitude, place.longitude, 1)
                        ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
                }.getOrNull() ?: "当地"
            }
            val result = Api(vault).obj("/api/morning/briefs", "POST", json("latitude" to place.latitude, "longitude" to place.longitude, "city" to city.take(40), "timezone" to zone))
            if (store.enabled && Vault(context).token == token && store.place().key == place.key && ZoneId.systemDefault().id == zone) {
                store.prefs.edit().putString("briefs", result.getJSONArray("items").toString()).putLong("last_refresh", startedAt).remove("error").commit()
                return store.today() != null
            }
            return false
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error is ApiError && error.status == 401) { disable(context); return true }
            MorningStore(context).prefs.edit().putString("error", error.message ?: "天气暂时无法更新").apply()
            return false
        } finally { refreshing.set(false) }
    }
    @Suppress("DEPRECATION") // Android 8-10 still use the single-update location API.
    suspend fun updateLocation(context: Context) {
        val store = MorningStore(context)
        if (!store.enabled) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            store.location = null
            return
        }
        var located = false
        val manager = context.getSystemService(LocationManager::class.java)
        fun save(location: Location) {
            if (!store.enabled || !MorningPolicy.fresh(location.time, System.currentTimeMillis(), MorningPolicy.MAX_FIX_AGE)) return
            if (location.time < (store.location?.at ?: 0)) return
            located = true
            // Retain only the most recent coarse location, never a trail.
            store.location = MorningPlace(location.latitude, location.longitude, "最近定位", location.time)
        }
        val last = manager.getProviders(true).mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        last?.let(::save)
        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider != null) withTimeoutOrNull(8000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (continuation.isActive) { save(location); continuation.resume(Unit) }
                        manager.removeUpdates(this)
                    }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                try { manager.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }
                catch (_: SecurityException) { if (continuation.isActive) continuation.resume(Unit) }
            }
        }
        if (!located) store.location = null
    }
}

class MorningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        MorningReminder.restore(context, reset = true)
        MorningReminder.refreshSoon(context)
    }
}

class MorningRefreshService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        jobs[params.jobId] = scope.launch {
            val success = MorningReminder.refresh(this@MorningRefreshService)
            withContext(Dispatchers.Main) {
                jobs.remove(params.jobId)
                jobFinished(params, !success)
                if (success) MorningReminder.restore(this@MorningRefreshService, reset = params.jobId == 60001)
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { jobs.remove(params.jobId)?.cancel(); return MorningStore(this).enabled }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
