package com.sky22333.netboot.data

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "netboot.db")
            // Production builds must never drop user data, so every schema change ships an
            // explicit migration instead of a destructive fallback.
            .addMigrations(AppDatabase.Migration1To2)
            .build()

    @Provides
    @Singleton
    fun provideHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // Redirects are followed manually so every hop can be re-checked against the
            // Microsoft host allow-list.
            .followRedirects(false)
            .followSslRedirects(false)
            // OkHttp defaults to 5 requests per host, which would silently cap the segmented
            // downloader below the user-selected 8 connections.
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = MaxDownloadConnections
                    maxRequestsPerHost = MaxDownloadConnections
                },
            )
            .build()

    /**
     * Must match the largest value SettingsRepository accepts for "download connections"; the
     * segmented downloader maps one OkHttp call to each segment.
     */
    const val MaxDownloadConnections = 8
}

