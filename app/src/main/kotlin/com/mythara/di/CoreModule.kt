package com.mythara.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Marker module so Hilt builds the SingletonComponent graph even before
 * any provides() methods are introduced. SettingsStore, HistoryRepository,
 * and AgentLoop are already `@Singleton + @Inject constructor`, so they
 * graph in automatically — but Hilt still needs at least one module
 * targeting the component to be happy.
 *
 * As we land more providers (OkHttp tuned for streaming, TextToSpeech
 * factory, Accessibility callback bus), they'll add @Provides methods
 * here.
 */
@Module
@InstallIn(SingletonComponent::class)
object CoreModule
