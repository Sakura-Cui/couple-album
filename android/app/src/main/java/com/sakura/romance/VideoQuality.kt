package com.sakura.romance

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import org.json.JSONObject

internal data class VideoQuality(val id: String, val label: String, val width: Int, val height: Int)

internal fun JSONObject.videoQualities(): List<VideoQuality> {
    val values = optJSONArray("qualities") ?: return emptyList()
    return (0 until values.length()).map { index ->
        values.getJSONObject(index).run { VideoQuality(getString("id"), getString("label"), getInt("width"), getInt("height")) }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun videoQualityParameters(tracks: Tracks, current: TrackSelectionParameters, quality: VideoQuality?): TrackSelectionParameters {
    if (quality != null && tracks.groups.none { group -> group.type == C.TRACK_TYPE_VIDEO && (0 until group.length).any { group.getTrackFormat(it).width == quality.width && group.getTrackFormat(it).height == quality.height } }) return current
    val builder = current.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
    if (quality != null) {
        for (group in tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }) {
            val index = (0 until group.length).firstOrNull {
                val format = group.getTrackFormat(it)
                format.width == quality.width && format.height == quality.height && group.isTrackSupported(it)
            }
            if (index != null) {
                builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                break
            }
        }
    }
    return builder.build()
}
