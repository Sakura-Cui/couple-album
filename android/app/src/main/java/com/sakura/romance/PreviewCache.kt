package com.sakura.romance

import android.content.Context
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.security.MessageDigest

/** Only thumbnail bytes are persisted, encrypted and scoped to this device binding. */
internal class PreviewCache(context: Context, private val vault: Vault) : Interceptor {
    private val directory = File(context.cacheDir, "album-previews")
    private val lock = Any()
    private fun identity(url: String, token: String = vault.token) = token + "\n" + url
    private fun file(identity: String) = File(directory,
        MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) })

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET" || vault.token.isEmpty() ||
            !request.url.toString().startsWith(vault.url.trimEnd('/') + "/api/media/") ||
            !request.url.encodedPath.matches(Regex("/api/media/[0-9a-f]{32}/thumbnail"))) return chain.proceed(request)
        val token = request.header("Authorization")?.removePrefix("Bearer ") ?: return chain.proceed(request)
        if (token != vault.token) return chain.proceed(request)
        val identity = identity(request.url.toString(), token)
        val destination = file(identity)
        val cached = synchronized(lock) {
            if (!destination.isFile) null else runCatching {
                vault.decryptPreview(destination.readBytes(), identity).also { destination.setLastModified(System.currentTimeMillis()) }
            }.getOrElse { destination.delete(); null }
        }
        if (cached != null) return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(cached.toResponseBody("image/jpeg".toMediaType())).build()
        val response = chain.proceed(request)
        val body = response.body ?: return response
        if (response.code != 200 || body.contentLength() !in 1..2_097_152 ||
            body.contentType()?.type != "image") return response
        val bytes = body.bytes()
        synchronized(lock) {
            runCatching {
                directory.mkdirs()
                val temporary = File(directory, destination.name + ".tmp")
                temporary.writeBytes(vault.encryptPreview(bytes, identity))
                check(temporary.renameTo(destination))
                var total = directory.listFiles().orEmpty().sumOf { it.length() }
                for (entry in directory.listFiles().orEmpty().sortedBy { it.lastModified() }) {
                    if (total <= 64L * 1024 * 1024) break
                    val length = entry.length()
                    if (entry.delete()) total -= length
                }
            }
        }
        return response.newBuilder().body(bytes.toResponseBody(body.contentType())).build()
    }

    fun invalidate(url: String) = synchronized(lock) { file(identity(url)).delete(); Unit }
}
