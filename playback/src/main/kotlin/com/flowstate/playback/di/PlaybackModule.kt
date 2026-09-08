package com.flowstate.playback.di

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlaybackModule {

    /**
     * One application-scoped player shared by the UI, the [com.flowstate.playback.QueueController]
     * and the PlaybackService's MediaSession. Single-process, main-thread access only.
     */
    @Provides
    @Singleton
    fun exoPlayer(@ApplicationContext context: Context): ExoPlayer =
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // headphones yanked out -> pause
            .build()
}
