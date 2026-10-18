package com.sakura.romance

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import com.google.common.collect.ImmutableList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class VideoQualityTest {
    @Test fun manualPortraitQualityOverridesOnlyVideoAndAutoClearsIt() {
        val group = TrackGroup("video", Format.Builder().setSampleMimeType("video/avc").setWidth(480).setHeight(854).build(),
            Format.Builder().setSampleMimeType("video/avc").setWidth(720).setHeight(1280).build())
        val tracks = Tracks(ImmutableList.of(Tracks.Group(group, true, intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED), booleanArrayOf(true, false))))
        val initial = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT.buildUpon().setPreferredAudioLanguage("zh").build()
        val selected = videoQualityParameters(tracks, initial, VideoQuality("720", "高清", 720, 1280))
        assertEquals(listOf(1), selected.overrides[group]!!.trackIndices)
        assertEquals(initial.preferredAudioLanguages, selected.preferredAudioLanguages)
        assertTrue(videoQualityParameters(tracks, selected, null).overrides.isEmpty())
        assertEquals(selected, videoQualityParameters(Tracks.EMPTY, selected, VideoQuality("720", "高清", 720, 1280)))
    }
}
