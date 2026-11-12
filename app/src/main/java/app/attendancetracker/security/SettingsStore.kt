package app.attendancetracker.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_settings",
)

interface SettingsStore {
    val thresholdPercent: Flow<Int>
    suspend fun setThresholdPercent(percent: Int)

    companion object {
        const val DEFAULT_THRESHOLD = 75
        val THRESHOLD_OPTIONS = listOf(65, 75, 80, 85)
    }
}

class DataStoreSettingsStore(
    private val context: Context,
) : SettingsStore {

    companion object {
        private val KEY_THRESHOLD_PERCENT = intPreferencesKey("attendance_threshold_percent")
    }

    override val thresholdPercent: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        val value = prefs[KEY_THRESHOLD_PERCENT] ?: SettingsStore.DEFAULT_THRESHOLD
        if (value in SettingsStore.THRESHOLD_OPTIONS) value else SettingsStore.DEFAULT_THRESHOLD
    }

    override suspend fun setThresholdPercent(percent: Int) {
        require(percent in SettingsStore.THRESHOLD_OPTIONS) {
            "Threshold must be one of ${SettingsStore.THRESHOLD_OPTIONS}"
        }
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_THRESHOLD_PERCENT] = percent
        }
    }
}
