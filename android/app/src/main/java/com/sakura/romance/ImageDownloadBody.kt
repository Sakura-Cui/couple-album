package com.sakura.romance

import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

/** Reports bytes actually consumed by Coil; decoding completion remains Coil's success state. */
internal class ImageDownloadBody(private val body: ResponseBody, private val progress: (Float?) -> Unit) : ResponseBody() {
    private val length = body.contentLength()
    private val source = object : ForwardingSource(body.source()) {
        private var received = 0L
        private var lastReport = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val count = super.read(sink, byteCount)
            if (count > 0) received += count
            val now = System.nanoTime()
            if (count == -1L || received == length || now - lastReport >= 150_000_000L) {
                progress(if (length > 0) (received.toDouble() / length).toFloat().coerceIn(0f, 1f) else null)
                lastReport = now
            }
            return count
        }
    }.buffer()
    init { progress(if (length > 0) 0f else null) }
    override fun contentType() = body.contentType()
    override fun contentLength() = length
    override fun source() = source
}
