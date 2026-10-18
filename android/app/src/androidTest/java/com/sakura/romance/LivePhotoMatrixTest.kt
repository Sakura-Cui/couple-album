package com.sakura.romance

import androidx.media3.common.util.ParsableByteArray
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Reproduces the actual pinned Media3 parser's inability to express reflections. */
@RunWith(AndroidJUnit4::class)
class LivePhotoMatrixTest {
    @Test fun nativeParserRetainsOrdinaryRotationButDiscardsMirror() {
        val parser = Class.forName("androidx.media3.extractor.mp4.BoxParser")
            .getDeclaredMethod("parseTkhd", ParsableByteArray::class.java).apply { isAccessible = true }
        val cases = listOf(
            intArrayOf(1,0,0,1,0), intArrayOf(0,1,-1,0,90),
            intArrayOf(-1,0,0,-1,180), intArrayOf(0,-1,1,0,270),
            intArrayOf(-1,0,0,1,0), intArrayOf(1,0,0,-1,0),
            intArrayOf(0,1,1,0,0), intArrayOf(0,-1,-1,0,0))
        for ((a,b,c,d,rotation) in cases) {
            val bytes = ByteArray(92)
            val data = ByteBuffer.wrap(bytes)
            data.putInt(92).putInt(0x746b6864)
            data.putInt(20, 1).putInt(28, 600)
            data.position(48)
            for (value in intArrayOf(a*65536,b*65536,0,c*65536,d*65536,0,0,0,1 shl 30)) data.putInt(value)
            val result = parser.invoke(null, ParsableByteArray(bytes))!!
            val field = result.javaClass.getDeclaredField("rotationDegrees").apply { isAccessible = true }
            assertEquals("matrix=[$a,$b;$c,$d]", rotation, field.getInt(result))
        }
    }
}
