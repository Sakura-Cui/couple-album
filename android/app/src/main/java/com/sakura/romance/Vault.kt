package com.sakura.romance

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Vault(context: Context) {
    private val prefs = context.getSharedPreferences("private_connection", Context.MODE_PRIVATE)
    @Volatile private var cachedToken: String? = null
    private val alias = "sakura-device-token"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    internal fun encryptPreview(data: ByteArray, identity: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key())
            updateAAD(identity.toByteArray())
        }
        return cipher.iv + cipher.doFinal(data)
    }
    internal fun decryptPreview(data: ByteArray, identity: String): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            updateAAD(identity.toByteArray())
            doFinal(data.copyOfRange(12, data.size))
        }
    internal fun videoCacheKey(): ByteArray = synchronized(this) {
        val identity = "sakura-video-cache-key"
        prefs.getString("video_cache_key", null)?.let {
            return@synchronized decryptPreview(Base64.decode(it, Base64.NO_WRAP), identity)
        }
        val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        check(prefs.edit().putString("video_cache_key",
            Base64.encodeToString(encryptPreview(bytes, identity), Base64.NO_WRAP)).commit())
        bytes
    }
    var url: String
        get() = prefs.getString("url", "") ?: ""
        set(value) { prefs.edit().putString("url", value.trimEnd('/')).commit() }
    init {
        if (url.trimEnd('/') in listOf("https://legacy.example.com:9443", "https://album.example.com")) url = "https://192.0.2.10"
    }
    var token: String
        get() = synchronized(this) {
            cachedToken?.let { return@synchronized it }
            val encoded = prefs.getString("token", null)
            if (encoded == null) return@synchronized "".also { cachedToken = it }
            runCatching {
                val data = Base64.decode(encoded, Base64.NO_WRAP)
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
                }.doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8)
            }.getOrElse { "" }.also { cachedToken = it }
        }
        set(value) = synchronized(this) {
            if (value.isEmpty()) {
                check(prefs.edit().remove("token").commit()) { "无法清除设备凭证" }
            } else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
                check(prefs.edit().putString("token", Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)).commit()) { "无法保存设备凭证" }
            }
            cachedToken = value
        }
}
