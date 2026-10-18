package com.sakura.romance

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerMigrationTest {
    @Test fun publicIpUsesTrustedHttps() {
        val client = okhttp3.OkHttpClient()
        for ((path, expected) in listOf("/health" to 200, "/api/albums" to 401)) {
            client.newCall(okhttp3.Request.Builder().url("https://192.0.2.10$path").build()).execute().use {
                assertEquals(expected, it.code)
                org.junit.Assert.assertTrue(it.handshake!!.peerCertificates.first().toString().contains("Let's Encrypt"))
            }
        }
    }
    @Test fun serverMovePreservesDeviceCredentialsAndOtherSpaces() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("qa_server_migration", mode)
        }
        val prefs = context.getSharedPreferences("private_connection", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val existing = Vault(context)
            existing.url = "https://legacy.example.com:9443"
            existing.token = "existing-device-token"
            val ciphertext = prefs.getString("token", null)
            for (url in listOf("https://legacy.example.com:9443/", "https://album.example.com/")) {
                prefs.edit().putString("url", url).commit()
                val migrated = Vault(context)
                assertEquals("https://192.0.2.10", migrated.url)
                assertEquals("existing-device-token", migrated.token)
                assertEquals(ciphertext, prefs.getString("token", null))
                assertEquals("https://192.0.2.10", Vault(context).url)
            }
            val migrated = Vault(context)
            for (url in listOf("", "https://127.0.0.1:18444", "https://another-space.example:9443")) {
                migrated.url = url
                assertEquals(url, Vault(context).url)
                assertEquals("existing-device-token", Vault(context).token)
            }
        } finally { prefs.edit().clear().commit() }
    }
}
