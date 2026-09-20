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
            .build()

    @Provides
    @Singleton
    fun provideHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // Validate each redirect against the Microsoft host allow-list.
            .followRedirects(false)
            .followSslRedirects(false)
            // Allow all eight download connections; OkHttp defaults to five per host.
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = MaxDownloadConnections
                    maxRequestsPerHost = MaxDownloadConnections
                },
            )
            .build()

    /** Keep aligned with the connection choices in SettingsRepository. */
    const val MaxDownloadConnections = 8
}

