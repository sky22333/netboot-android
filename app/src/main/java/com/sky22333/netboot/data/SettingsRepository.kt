package com.sky22333.netboot.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val themeMode: Int = 0,
    val downloadConnections: Int = 4,
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = context.settingsDataStore

    val settings: Flow<AppSettings> = store.data.map { preferences ->
        AppSettings(
            themeMode = preferences[ThemeMode] ?: 0,
            downloadConnections = preferences[DownloadConnections] ?: 4,
        )
    }

    suspend fun setThemeMode(mode: Int) {
        require(mode in 0..2)
        store.edit { it[ThemeMode] = mode }
    }

    suspend fun setDownloadConnections(connections: Int) {
        require(connections in setOf(1, 2, 4, 8))
        store.edit { it[DownloadConnections] = connections }
    }

    private companion object {
        val ThemeMode = intPreferencesKey("theme_mode")
        val DownloadConnections = intPreferencesKey("download_connections")
    }
}
