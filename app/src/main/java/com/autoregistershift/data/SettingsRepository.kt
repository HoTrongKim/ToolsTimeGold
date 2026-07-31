package com.autoregistershift.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.autoregistershift.model.AppSettings
import com.autoregistershift.model.CoordinatePoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.appDataStore by preferencesDataStore(name = "auto_register_shift")

class SettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.appDataStore.data.map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.appDataStore.edit { preferences ->
            preferences.write(transform(preferences.toSettings()))
        }
    }

    suspend fun reset() = update { AppSettings() }

    private fun MutablePreferences.write(value: AppSettings) {
        this[Keys.schemaVersion] = CURRENT_SCHEMA_VERSION
        this[Keys.targetPackage] = value.targetPackage
        this[Keys.scheduleTexts] = value.scheduleScreenTexts.encodeList()
        this[Keys.registerTexts] = value.registerButtonTexts.encodeList()
        this[Keys.noSlotTexts] = value.noSlotTexts.encodeList()
        this[Keys.successTexts] = value.successTexts.encodeList()
        this[Keys.fullTexts] = value.fullTexts.encodeList()
        this[Keys.errorTexts] = value.networkErrorTexts.encodeList()
        this[Keys.rateLimitTexts] = value.refreshRateLimitTexts.encodeList()
        this[Keys.detailTexts] = value.detailScreenTexts.encodeList()
        this[Keys.loadingTexts] = value.loadingTexts.encodeList()
        this[Keys.prohibitedTexts] = value.prohibitedTexts.encodeList()
        this[Keys.refreshInterval] = value.refreshIntervalMs
        this[Keys.waitAfterSwipe] = value.waitAfterSwipeMs
        this[Keys.waitAfterOpen] = value.waitAfterOpenSlotMs
        this[Keys.resultTimeout] = value.registrationTimeoutMs
        this[Keys.maxRetry] = value.maxRetry
        this[Keys.clickDebounce] = value.clickDebounceMs
        this[Keys.cooldown] = value.shiftCooldownMs
        this[Keys.maxRegistrations] = value.maxRegistrations
        this[Keys.maxRunMinutes] = value.maxRunMinutes
        this[Keys.maxClicks] = value.maxClicksPerMinute
        this[Keys.maxRefreshes] = value.maxRefreshesPerMinute
        this[Keys.maxUnknown] = value.maxUnknownScreens
        this[Keys.refreshDuration] = value.refreshSwipeDurationMs
        this[Keys.loadDuration] = value.loadSwipeDurationMs
        this[Keys.registerAll] = value.registerAll
        this[Keys.stopAfterSuccess] = value.stopAfterSuccess
        this[Keys.autoBack] = value.autoReturnToList
        this[Keys.sound] = value.soundOnSuccess
        this[Keys.vibrate] = value.vibrateOnSuccess
        this[Keys.overlay] = value.showOverlay
        this[Keys.keepScreen] = value.keepScreenOn
        this[Keys.continuousMode] = value.continuousMode
        this[Keys.coordinates] = value.coordinates.joinToString("\n") {
            listOf(it.id, it.name, it.xRatio, it.yRatio, it.enabled).joinToString("\t")
        }
    }

    private fun androidx.datastore.preferences.core.Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        val schemaVersion = this[Keys.schemaVersion] ?: 0
        val storedRefresh = this[Keys.refreshInterval] ?: defaults.refreshIntervalMs
        val storedWaitAfterSwipe = this[Keys.waitAfterSwipe] ?: defaults.waitAfterSwipeMs
        val storedMaxRefreshes = this[Keys.maxRefreshes] ?: defaults.maxRefreshesPerMinute
        val storedClickDebounce = this[Keys.clickDebounce] ?: defaults.clickDebounceMs
        val storedRefreshDuration = this[Keys.refreshDuration] ?: defaults.refreshSwipeDurationMs
        val wasPreviousFastPreset = schemaVersion == 4 &&
            storedRefresh == 1_000L &&
            storedWaitAfterSwipe == 600L
        val wasExtremeFastPreset = schemaVersion == 5 &&
            storedRefresh == 500L &&
            storedWaitAfterSwipe == 300L
        val shouldMigrateFastPreset = wasPreviousFastPreset || wasExtremeFastPreset
        return defaults.copy(
            targetPackage = this[Keys.targetPackage] ?: defaults.targetPackage,
            scheduleScreenTexts = this[Keys.scheduleTexts].decodeList(defaults.scheduleScreenTexts),
            registerButtonTexts = this[Keys.registerTexts].decodeList(defaults.registerButtonTexts),
            noSlotTexts = (
                this[Keys.noSlotTexts].decodeList(defaults.noSlotTexts) +
                    listOf("Hiện chưa có cuốc xe nào", "Vui lòng thử lại hoặc chuyển sang ngày khác")
                ).distinct(),
            successTexts = this[Keys.successTexts].decodeList(defaults.successTexts),
            fullTexts = this[Keys.fullTexts].decodeList(defaults.fullTexts),
            networkErrorTexts = this[Keys.errorTexts].decodeList(defaults.networkErrorTexts),
            refreshRateLimitTexts =
                this[Keys.rateLimitTexts].decodeList(defaults.refreshRateLimitTexts),
            detailScreenTexts = (
                this[Keys.detailTexts].decodeList(defaults.detailScreenTexts) +
                    listOf("Thu nhập cố định", "Loại dịch vụ", "Cách thức hoạt động của Giờ vàng")
                ).distinct(),
            loadingTexts = this[Keys.loadingTexts].decodeList(defaults.loadingTexts),
            prohibitedTexts = this[Keys.prohibitedTexts].decodeList(defaults.prohibitedTexts),
            refreshIntervalMs = when {
                shouldMigrateFastPreset -> 1_000L
                schemaVersion < 3 && storedRefresh == 3_000L -> 2_000L
                else -> storedRefresh
            },
            waitAfterSwipeMs = when {
                shouldMigrateFastPreset -> 300L
                schemaVersion < 3 && storedWaitAfterSwipe == 1_500L -> 1_200L
                else -> storedWaitAfterSwipe
            },
            waitAfterOpenSlotMs = this[Keys.waitAfterOpen] ?: defaults.waitAfterOpenSlotMs,
            registrationTimeoutMs = this[Keys.resultTimeout] ?: defaults.registrationTimeoutMs,
            maxRetry = this[Keys.maxRetry] ?: defaults.maxRetry,
            clickDebounceMs = when {
                wasPreviousFastPreset && storedClickDebounce == 150L -> 100L
                wasExtremeFastPreset && storedClickDebounce == 100L -> 100L
                schemaVersion < 4 && storedClickDebounce == 500L -> 200L
                else -> storedClickDebounce
            },
            shiftCooldownMs = this[Keys.cooldown] ?: defaults.shiftCooldownMs,
            maxRegistrations = this[Keys.maxRegistrations] ?: defaults.maxRegistrations,
            maxRunMinutes = this[Keys.maxRunMinutes] ?: defaults.maxRunMinutes,
            maxClicksPerMinute = this[Keys.maxClicks] ?: defaults.maxClicksPerMinute,
            maxRefreshesPerMinute = when {
                wasPreviousFastPreset && storedMaxRefreshes == 40 -> 35
                wasExtremeFastPreset && storedMaxRefreshes == 60 -> 35
                schemaVersion < 3 && storedMaxRefreshes == 20 -> 30
                else -> storedMaxRefreshes
            },
            maxUnknownScreens = this[Keys.maxUnknown] ?: defaults.maxUnknownScreens,
            refreshSwipeDurationMs = if (
                (wasPreviousFastPreset && storedRefreshDuration == 550L) ||
                    (wasExtremeFastPreset && storedRefreshDuration == 350L)
            ) {
                350L
            } else {
                storedRefreshDuration
            },
            loadSwipeDurationMs = this[Keys.loadDuration] ?: defaults.loadSwipeDurationMs,
            registerAll = this[Keys.registerAll] ?: defaults.registerAll,
            stopAfterSuccess = this[Keys.stopAfterSuccess] ?: defaults.stopAfterSuccess,
            autoReturnToList = this[Keys.autoBack] ?: defaults.autoReturnToList,
            soundOnSuccess = this[Keys.sound] ?: defaults.soundOnSuccess,
            vibrateOnSuccess = this[Keys.vibrate] ?: defaults.vibrateOnSuccess,
            showOverlay = this[Keys.overlay] ?: defaults.showOverlay,
            keepScreenOn = this[Keys.keepScreen] ?: defaults.keepScreenOn,
            continuousMode = this[Keys.continuousMode] ?: defaults.continuousMode,
            coordinates = decodeCoordinates(this[Keys.coordinates], defaults.coordinates)
        )
    }

    private fun List<String>.encodeList() = joinToString("\n")
    private fun String?.decodeList(default: List<String>) =
        this?.lineSequence()?.map(String::trim)?.filter(String::isNotBlank)?.toList()
            ?.takeIf(List<String>::isNotEmpty) ?: default

    private fun decodeCoordinates(raw: String?, defaults: List<CoordinatePoint>): List<CoordinatePoint> {
        if (raw.isNullOrBlank()) return defaults
        val parsed = raw.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 5) return@mapNotNull null
            val point = CoordinatePoint(
                id = parts[0],
                name = parts[1],
                xRatio = parts[2].toFloatOrNull() ?: return@mapNotNull null,
                yRatio = parts[3].toFloatOrNull() ?: return@mapNotNull null,
                enabled = parts[4].toBooleanStrictOrNull() ?: true
            )
            // Di chuyển điểm đăng ký mặc định cũ lên đúng tâm nút ở giao diện thực tế.
            if (point.id == "register" &&
                kotlin.math.abs(point.xRatio - .50f) < .001f &&
                kotlin.math.abs(point.yRatio - .82f) < .001f
            ) {
                point.copy(yRatio = .91f)
            } else {
                point
            }
        }.associateBy { it.id }
        return defaults.map { parsed[it.id] ?: it }
    }

    private object Keys {
        val schemaVersion = intPreferencesKey("settings_schema_version")
        val targetPackage = stringPreferencesKey("target_package")
        val scheduleTexts = stringPreferencesKey("schedule_texts")
        val registerTexts = stringPreferencesKey("register_texts")
        val noSlotTexts = stringPreferencesKey("no_slot_texts")
        val successTexts = stringPreferencesKey("success_texts")
        val fullTexts = stringPreferencesKey("full_texts")
        val errorTexts = stringPreferencesKey("error_texts")
        val rateLimitTexts = stringPreferencesKey("rate_limit_texts")
        val detailTexts = stringPreferencesKey("detail_texts")
        val loadingTexts = stringPreferencesKey("loading_texts")
        val prohibitedTexts = stringPreferencesKey("prohibited_texts")
        val refreshInterval = longPreferencesKey("refresh_interval")
        val waitAfterSwipe = longPreferencesKey("wait_after_swipe")
        val waitAfterOpen = longPreferencesKey("wait_after_open")
        val resultTimeout = longPreferencesKey("result_timeout")
        val maxRetry = intPreferencesKey("max_retry")
        val clickDebounce = longPreferencesKey("click_debounce")
        val cooldown = longPreferencesKey("cooldown")
        val maxRegistrations = intPreferencesKey("max_registrations")
        val maxRunMinutes = intPreferencesKey("max_run_minutes")
        val maxClicks = intPreferencesKey("max_clicks")
        val maxRefreshes = intPreferencesKey("max_refreshes")
        val maxUnknown = intPreferencesKey("max_unknown")
        val refreshDuration = longPreferencesKey("refresh_duration")
        val loadDuration = longPreferencesKey("load_duration")
        val registerAll = booleanPreferencesKey("register_all")
        val stopAfterSuccess = booleanPreferencesKey("stop_after_success")
        val autoBack = booleanPreferencesKey("auto_back")
        val sound = booleanPreferencesKey("sound")
        val vibrate = booleanPreferencesKey("vibrate")
        val overlay = booleanPreferencesKey("overlay")
        val keepScreen = booleanPreferencesKey("keep_screen")
        val continuousMode = booleanPreferencesKey("continuous_mode")
        val coordinates = stringPreferencesKey("coordinates")
    }

    companion object {
        private const val CURRENT_SCHEMA_VERSION = 7
    }
}
