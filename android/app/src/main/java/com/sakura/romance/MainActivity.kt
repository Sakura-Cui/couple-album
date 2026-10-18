package com.sakura.romance

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

class MainActivity : ComponentActivity() {
    private var morningLocationJob: Job? = null
    private var morningPermissionsPending = false
    private val morningPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        morningPermissionsPending = false
        finishMorningSetup()
    }
    private fun setupMorning() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !model.bound || !MorningStore(this).enabled || morningPermissionsPending) return
        val prefs = MorningStore(this).prefs
        if (!prefs.getBoolean("weather_location_requested", false)) {
            val permissions = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            prefs.edit().putBoolean("weather_location_requested", true).apply()
            if (permissions.isNotEmpty()) {
                morningPermissionsPending = true
                morningPermissions.launch(permissions.toTypedArray())
                return
            }
        }
        finishMorningSetup()
    }
    private fun finishMorningSetup() {
        if (!model.bound || !MorningStore(this).enabled) return
        MorningReminder.restore(this)
        refreshMorningLocation()
    }
    private fun refreshMorningLocation() {
        morningLocationJob?.cancel()
        morningLocationJob = lifecycleScope.launch {
            MorningReminder.updateLocation(this@MainActivity)
            if (!MorningReminder.refresh(this@MainActivity)) MorningReminder.refreshSoon(this@MainActivity)
        }
    }
    val model get() = (application as RomanceApplication).model
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model.handleMessageIntent(intent)
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) setupMorning()
        })
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, 0xFF36313A.toInt())
        )
        setContent {
            LaunchedEffect(model.bound) { if (model.bound) setupMorning() }
            SakuraTheme {
                var showSplash by rememberSaveable { mutableStateOf(true) }
                if (showSplash) SakuraSplashScreen { showSplash = false }
                else {
                    SakuraApp(model)
                }
            }
        }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        model.handleMessageIntent(intent)
    }
    override fun onStart() {
        super.onStart(); model.resume()
        MessagePush.restore(this)
        MorningReminder.restore(this)
    }
    override fun onStop() { morningLocationJob?.cancel(); model.background(); super.onStop() }
}

