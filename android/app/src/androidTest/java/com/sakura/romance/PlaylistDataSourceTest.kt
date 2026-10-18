package com.sakura.romance

import android.net.Uri
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaylistDataSourceTest {
    @Test fun playlistsAlwaysReloadWhileSegmentsUseEncryptedCacheFactory() {
        var network = 0
        var cached = 0
        val factory = PlaylistDataSourceFactory(
            DataSource.Factory { network++; ByteArrayDataSource("playlist-$network".toByteArray()) },
            DataSource.Factory { cached++; ByteArrayDataSource("segment".toByteArray()) })
        val source = factory.createDataSource()
        fun read(path: String): String {
            try {
                source.open(DataSpec(Uri.parse("https://localhost/$path")))
                val buffer = ByteArray(64)
                return String(buffer,0,source.read(buffer,0,buffer.size))
            } finally { source.close() }
        }
        assertEquals("playlist-1",read("480.m3u8"))
        assertEquals("playlist-2",read("480.m3u8?preview=1"))
        assertEquals("segment",read("480-00000.ts"))
        assertEquals(2,network)
        assertEquals(1,cached)
    }
}
