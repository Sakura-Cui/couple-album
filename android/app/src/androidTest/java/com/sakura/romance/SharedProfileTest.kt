package com.sakura.romance

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Requires the isolated localhost fixture scripts/qa_shared_profile_server.py. */
@RunWith(AndroidJUnit4::class)
class SharedProfileTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val model get() = rule.activity.model
    private fun qa(path: String, body: org.json.JSONObject? = null) = runBlocking { model.api.obj(path, "POST", body) }

    @Test fun sharedSignaturePollsImportsOnceAndPreservesExplicitEdits() {
        rule.mainClock.advanceTimeBy(5100)
        rule.runOnUiThread { model.vault.url = "https://10.0.2.2:18449" }
        val invite = qa("/qa/reset").getString("invite")
        rule.activity.getSharedPreferences("personal_profile", 0).edit().putString("signature", "原有签名💕").commit()
        rule.runOnUiThread { model.bind("https://10.0.2.2:18449", invite, "演示主设备") }
        rule.waitUntil(30000) { model.ready && !model.busy }
        assertEquals("", model.signature)
        qa("/qa/migration-source")
        rule.waitUntil(15000) { model.signature == "原有签名💕" }
        qa("/qa/signature", json("signature" to "另一台改的签名"))
        rule.waitUntil(15000) { model.signature == "另一台改的签名" }
        var saved = false
        rule.runOnUiThread { model.saveSignature("我的新签名") { saved = true } }
        rule.waitUntil(15000) { saved && !model.signatureSaving }
        assertEquals("我的新签名", runBlocking { model.api.obj("/api/session").getString("signature") })
        rule.runOnUiThread { model.background(); model.resume() }
        rule.waitUntil(15000) { model.ready && model.signature == "我的新签名" }
        assertEquals("原有签名💕", rule.activity.getSharedPreferences("personal_profile", 0).getString("signature", null))
        saved = false
        rule.runOnUiThread { model.saveSignature("") { saved = true } }
        rule.waitUntil(15000) { saved && !model.signatureSaving }
        rule.runOnUiThread { model.background(); model.resume() }
        rule.waitUntil(15000) { model.ready && model.signature == "" }
    }
}
