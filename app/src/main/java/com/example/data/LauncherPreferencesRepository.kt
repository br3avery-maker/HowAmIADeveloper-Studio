package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.launcherDataStore by preferencesDataStore(name = "sovereign_launcher_prefs")

enum class LauncherViewMode {
    ICON_GRID,
    DESCRIPTION_LIST
}

enum class WallpaperStyle {
    CYBER_ZINE,
    DEFAULT_AURORA,
    CUSTOM_IMAGE,
    BREBRE_NEON_ORBS,
    AGENT_CIRCUIT
}

data class LauncherPrefs(
    val viewMode: LauncherViewMode = LauncherViewMode.ICON_GRID,
    val wallpaperStyle: WallpaperStyle = WallpaperStyle.CYBER_ZINE,
    val customWallpaperPath: String = "",
    val scrimDimAlpha: Float = 0.36f,
    val primaryBlossomServer: String = "https://blossom.primal.net"
)

class LauncherPreferencesRepository(context: Context) {
    private val appContext = context.applicationContext

    val prefsFlow: Flow<LauncherPrefs> = appContext.launcherDataStore.data.map { prefs ->
        val mode = runCatching {
            LauncherViewMode.valueOf(prefs[KEY_VIEW_MODE] ?: LauncherViewMode.ICON_GRID.name)
        }.getOrDefault(LauncherViewMode.ICON_GRID)
        val style = runCatching {
            WallpaperStyle.valueOf(prefs[KEY_WALLPAPER_STYLE] ?: WallpaperStyle.CYBER_ZINE.name)
        }.getOrDefault(WallpaperStyle.CYBER_ZINE)
        LauncherPrefs(
            viewMode = mode,
            wallpaperStyle = style,
            customWallpaperPath = prefs[KEY_CUSTOM_WALLPAPER].orEmpty(),
            scrimDimAlpha = (prefs[KEY_SCRIM_ALPHA] ?: 0.36f).coerceIn(0.10f, 0.85f),
            primaryBlossomServer = prefs[KEY_BLOSSOM_SERVER] ?: "https://blossom.primal.net"
        )
    }

    suspend fun toggleViewMode(current: LauncherViewMode) {
        val next = if (current == LauncherViewMode.ICON_GRID) {
            LauncherViewMode.DESCRIPTION_LIST
        } else {
            LauncherViewMode.ICON_GRID
        }
        appContext.launcherDataStore.edit { it[KEY_VIEW_MODE] = next.name }
    }

    suspend fun setWallpaperStyle(style: WallpaperStyle, customPath: String? = null) {
        appContext.launcherDataStore.edit {
            it[KEY_WALLPAPER_STYLE] = style.name
            if (customPath != null) {
                it[KEY_CUSTOM_WALLPAPER] = customPath
            }
        }
    }

    suspend fun setScrimAlpha(alpha: Float) {
        appContext.launcherDataStore.edit {
            it[KEY_SCRIM_ALPHA] = alpha.coerceIn(0.10f, 0.85f)
        }
    }

    suspend fun setBlossomServer(url: String) {
        val cleaned = url.trim().trimEnd('/')
        if (cleaned.isNotBlank()) {
            appContext.launcherDataStore.edit { it[KEY_BLOSSOM_SERVER] = cleaned }
        }
    }

    companion object {
        private val KEY_VIEW_MODE = stringPreferencesKey("view_mode")
        private val KEY_WALLPAPER_STYLE = stringPreferencesKey("wallpaper_style")
        private val KEY_CUSTOM_WALLPAPER = stringPreferencesKey("custom_wallpaper_path")
        private val KEY_SCRIM_ALPHA = floatPreferencesKey("scrim_dim_alpha")
        private val KEY_BLOSSOM_SERVER = stringPreferencesKey("primary_blossom_server")
    }
}
