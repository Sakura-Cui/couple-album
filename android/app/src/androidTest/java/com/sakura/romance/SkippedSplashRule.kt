package com.sakura.romance

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule

// The native splash video can keep ActivityScenario's idle wait busy on a headless emulator.
internal fun withSkippedSplash(activity: TestRule): TestRule = RuleChain.outerRule(object : ExternalResource() {
    private var skipper: Thread? = null
    override fun before() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        skipper = Thread {
            try { device.wait(Until.findObject(By.text("跳过")), 30000)?.click() }
            catch (_: androidx.test.uiautomator.StaleObjectException) { /* Splash already dismissed. */ }
        }.apply { isDaemon = true; start() }
    }
    override fun after() { skipper?.interrupt() }
}).around(activity)
