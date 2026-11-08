package com.nativeauto.di

import com.nativeauto.media.AndroidMediaSessionRepository
import com.nativeauto.media.MediaSessionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {

    @Binds
    abstract fun bindMediaSessionRepository(impl: AndroidMediaSessionRepository): MediaSessionRepository
}
