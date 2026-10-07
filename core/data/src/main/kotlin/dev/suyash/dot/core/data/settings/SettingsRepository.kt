package dev.suyash.dot.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.suyash.dot.core.domain.model.RingMode
import dev.suyash.dot.core.domain.time.DayParts
import dev.suyash.dot.core.domain.view.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/** User preferences (device-local; nothing sensitive lives here). */
data class UserSettings(
    val dayParts: DayParts = DayParts(),
    /** Style for new reminders: ring like a call, or a normal notification. */
    val defaultRingMode: RingMode = RingMode.RING,
    /** How long a reminder rings before becoming "missed". */
    val ringDurationSeconds: Int = 60,
    /** Auto-snooze an unanswered ring by this many minutes (0 = off), at most 3 times. */
    val autoSnoozeMinutes: Int = 0,
    /** Show task titles on the lock screen ring screen and notifications. */
    val showTitlesOnLockScreen: Boolean = true,
    /** Show only counts (no titles) in home-screen widgets. */
    val hideWidgetTitles: Boolean = false,
    /** Blank the app's preview in the recent-apps switcher. */
    val hideInRecents: Boolean = true,
    val onboardingComplete: Boolean = false,
    /** Sort order per list id; lists not in here use "My order". */
    val listSorts: Map<String, SortOrder> = emptyMap(),
    /** Ask for the fingerprint or screen lock when opening the app. */
    val appLock: Boolean = false,
    /** The "back up your tasks" hint was dismissed with "Not now". */
    val syncHintDismissed: Boolean = false,
) {
    fun sortOf(listId: String): SortOrder = listSorts[listId] ?: SortOrder.MY_ORDER

    /** Widgets show counts only when asked to, and always while App lock is on. */
    val hidesWidgetTitles: Boolean get() = hideWidgetTitles || appLock
}

@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {

    val settings: Flow<UserSettings> = store.data.map { it.toSettings() }.distinctUntilChanged()

    suspend fun current(): UserSettings = settings.first()

    suspend fun update(transform: (UserSettings) -> UserSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[MORNING] = next.dayParts.morning.toSecondOfDay() / 60
            prefs[AFTERNOON] = next.dayParts.afternoon.toSecondOfDay() / 60
            prefs[EVENING] = next.dayParts.evening.toSecondOfDay() / 60
            prefs[NIGHT] = next.dayParts.night.toSecondOfDay() / 60
            prefs[WEEKEND_START] = next.dayParts.weekendStart.name
            prefs[WEEK_START] = next.dayParts.weekStart.name
            prefs[RING_MODE] = next.defaultRingMode.name
            prefs[RING_SECONDS] = next.ringDurationSeconds
            prefs[AUTO_SNOOZE] = next.autoSnoozeMinutes
            prefs[LOCK_TITLES] = next.showTitlesOnLockScreen
            prefs[WIDGET_HIDE] = next.hideWidgetTitles
            prefs[HIDE_RECENTS] = next.hideInRecents
            prefs[ONBOARDED] = next.onboardingComplete
            prefs[LIST_SORTS] = next.listSorts.entries
                .filter { (id, sort) -> sort != SortOrder.MY_ORDER && id.none { it == ';' || it == '=' } }
                .joinToString(";") { (id, sort) -> "$id=${sort.name}" }
            prefs[APP_LOCK] = next.appLock
            prefs[SYNC_HINT_DISMISSED] = next.syncHintDismissed
        }
    }

    private fun Preferences.toSettings(): UserSettings {
        val defaults = UserSettings()
        fun time(key: Preferences.Key<Int>, fallback: LocalTime) =
            this[key]?.let { LocalTime.ofSecondOfDay((it.coerceIn(0, 1439) * 60).toLong()) } ?: fallback
        fun day(key: Preferences.Key<String>, fallback: DayOfWeek) =
            this[key]?.let { runCatching { DayOfWeek.valueOf(it) }.getOrNull() } ?: fallback
        return UserSettings(
            dayParts = DayParts(
                morning = time(MORNING, defaults.dayParts.morning),
                afternoon = time(AFTERNOON, defaults.dayParts.afternoon),
                evening = time(EVENING, defaults.dayParts.evening),
                night = time(NIGHT, defaults.dayParts.night),
                weekendStart = day(WEEKEND_START, defaults.dayParts.weekendStart),
                weekStart = day(WEEK_START, defaults.dayParts.weekStart),
            ),
            defaultRingMode = this[RING_MODE]?.let { runCatching { RingMode.valueOf(it) }.getOrNull() } ?: defaults.defaultRingMode,
            ringDurationSeconds = (this[RING_SECONDS] ?: defaults.ringDurationSeconds).coerceIn(15, 300),
            autoSnoozeMinutes = (this[AUTO_SNOOZE] ?: defaults.autoSnoozeMinutes).coerceIn(0, 60),
            showTitlesOnLockScreen = this[LOCK_TITLES] ?: defaults.showTitlesOnLockScreen,
            hideWidgetTitles = this[WIDGET_HIDE] ?: defaults.hideWidgetTitles,
            hideInRecents = this[HIDE_RECENTS] ?: defaults.hideInRecents,
            onboardingComplete = this[ONBOARDED] ?: defaults.onboardingComplete,
            listSorts = this[LIST_SORTS].orEmpty().split(';').mapNotNull { entry ->
                val (id, sort) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                runCatching { SortOrder.valueOf(sort) }.getOrNull()?.let { id to it }
            }.toMap(),
            appLock = this[APP_LOCK] ?: defaults.appLock,
            syncHintDismissed = this[SYNC_HINT_DISMISSED] ?: defaults.syncHintDismissed,
        )
    }

    private companion object {
        val MORNING = intPreferencesKey("day_morning_min")
        val AFTERNOON = intPreferencesKey("day_afternoon_min")
        val EVENING = intPreferencesKey("day_evening_min")
        val NIGHT = intPreferencesKey("day_night_min")
        val WEEKEND_START = stringPreferencesKey("weekend_start")
        val WEEK_START = stringPreferencesKey("week_start")
        val RING_MODE = stringPreferencesKey("default_ring_mode")
        val RING_SECONDS = intPreferencesKey("ring_seconds")
        val AUTO_SNOOZE = intPreferencesKey("auto_snooze_min")
        val LOCK_TITLES = booleanPreferencesKey("lock_titles")
        val WIDGET_HIDE = booleanPreferencesKey("widget_hide_titles")
        val HIDE_RECENTS = booleanPreferencesKey("hide_in_recents")
        val ONBOARDED = booleanPreferencesKey("onboarding_complete")
        val LIST_SORTS = stringPreferencesKey("list_sorts")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val SYNC_HINT_DISMISSED = booleanPreferencesKey("sync_hint_dismissed")
    }
}
