package com.tannmenghong.tbchat.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tannmenghong.tbchat.domain.model.AppSettings
import com.tannmenghong.tbchat.domain.model.PerformanceMode
import com.tannmenghong.tbchat.inference.api.Accelerator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("tbchat_settings")

/**
 * Settings live in DataStore rather than Room because they are read on every
 * screen and written rarely, and because a corrupt preferences file should
 * degrade to defaults instead of taking the database with it.
 */
@Singleton
class SettingsDataSource @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val downloadRoot = stringPreferencesKey("download_root")
        val wifiOnly = booleanPreferencesKey("wifi_only_downloads")
        val defaultChatModel = stringPreferencesKey("default_chat_model")
        val acceleratorOverride = stringPreferencesKey("accelerator_override")
        val threadOverride = intPreferencesKey("thread_override")
        val memoryCeiling = intPreferencesKey("memory_ceiling_percent")
        val performanceMode = stringPreferencesKey("performance_mode")
        val idleUnload = intPreferencesKey("idle_unload_seconds")
        val contextLength = intPreferencesKey("context_length")
        val offlineMode = booleanPreferencesKey("offline_mode")
        val diagnostics = booleanPreferencesKey("diagnostics_enabled")
        val acknowledgedLicenses = stringSetPreferencesKey("acknowledged_license_ids")
        val onboarded = booleanPreferencesKey("has_completed_onboarding")
        val calibration = stringPreferencesKey("calibration_json")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data
        // A read failure on a damaged file must not kill the collector on the
        // UI; defaults are always a safe answer for preferences.
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs.write(Keys.downloadRoot, updated.downloadRoot)
            prefs[Keys.wifiOnly] = updated.wifiOnlyDownloads
            prefs.write(Keys.defaultChatModel, updated.defaultChatModelId)
            prefs.write(Keys.acceleratorOverride, updated.acceleratorOverride?.name)
            if (updated.threadOverride != null) {
                prefs[Keys.threadOverride] = updated.threadOverride!!
            } else {
                prefs.remove(Keys.threadOverride)
            }
            prefs[Keys.memoryCeiling] = updated.memoryCeilingPercent
            prefs[Keys.performanceMode] = updated.performanceMode.name
            prefs[Keys.idleUnload] = updated.idleUnloadSeconds
            prefs[Keys.contextLength] = updated.contextLength
            prefs[Keys.offlineMode] = updated.offlineMode
            prefs[Keys.diagnostics] = updated.diagnosticsEnabled
            prefs[Keys.acknowledgedLicenses] = updated.acknowledgedLicenseIds
            prefs[Keys.onboarded] = updated.hasCompletedOnboarding
            prefs.write(Keys.calibration, updated.calibrationJson)
        }
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.write(
        key: Preferences.Key<String>,
        value: String?
    ) {
        if (value == null) remove(key) else set(key, value)
    }

    private fun Preferences.toSettings() = AppSettings(
        downloadRoot = this[Keys.downloadRoot],
        wifiOnlyDownloads = this[Keys.wifiOnly] ?: true,
        defaultChatModelId = this[Keys.defaultChatModel],
        acceleratorOverride = this[Keys.acceleratorOverride]?.let { name ->
            runCatching { Accelerator.valueOf(name) }.getOrNull()
        },
        threadOverride = this[Keys.threadOverride],
        memoryCeilingPercent = this[Keys.memoryCeiling] ?: 50,
        performanceMode = this[Keys.performanceMode]?.let { name ->
            runCatching { PerformanceMode.valueOf(name) }.getOrNull()
        } ?: PerformanceMode.BALANCED,
        idleUnloadSeconds = this[Keys.idleUnload] ?: 300,
        contextLength = this[Keys.contextLength] ?: 4096,
        offlineMode = this[Keys.offlineMode] ?: false,
        diagnosticsEnabled = this[Keys.diagnostics] ?: false,
        acknowledgedLicenseIds = this[Keys.acknowledgedLicenses] ?: emptySet(),
        hasCompletedOnboarding = this[Keys.onboarded] ?: false,
        calibrationJson = this[Keys.calibration]
    )
}
