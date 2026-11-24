package com.jarvis.assistant.voice

import android.media.AudioTrack
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioTrack

/** Robolectric 4.14 does not provide float-array writes for its in-memory AudioTrack. */
@Implements(AudioTrack::class)
@Suppress("UNUSED_PARAMETER")
class FloatAudioTrackShadow : ShadowAudioTrack() {
    @Implementation
    protected fun write(audioData: FloatArray, offset: Int, size: Int, mode: Int): Int = size
}
