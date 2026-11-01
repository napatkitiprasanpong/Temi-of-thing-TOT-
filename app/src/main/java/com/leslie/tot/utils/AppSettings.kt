package com.leslie.tot.utils

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A single persisted setting that the UI can observe through [flow]. */
class Setting<T : Any>(val key: String, val default: T) {
    private val _flow = MutableStateFlow(default)
    val flow: StateFlow<T> = _flow.asStateFlow()
    val value: T get() = _flow.value

    fun set(newValue: T) {
        _flow.value = newValue
        AppSettings.persist(key, newValue)
    }

    @Suppress("UNCHECKED_CAST")
    internal fun load(p: SharedPreferences) {
        if (!p.contains(key)) return
        _flow.value = when (default) {
            is Int -> p.getInt(key, default) as T
            is Boolean -> p.getBoolean(key, default) as T
            is String -> (p.getString(key, default) ?: default) as T
            else -> default
        }
    }
}

/** Persisted settings of the temi-of-thinh (TOT) camera app. */
object AppSettings {

    const val PREFS_NAME = "tot_settings"

    private var prefs: SharedPreferences? = null
    lateinit var appContext: Context
        private set

    val isInitialised: Boolean get() = prefs != null

    /** Run without a temi: the follow / walk-to-me flow is pretended, nothing moves. */
    val simulateRobot = Setting("simulate_robot", false)

    // ---- Hand Reading screen ----
    /** How the alphabet model expects pixel values: 0 = 0..1, 1 = -1..1, 2 = 0..255 (see SignClassifier). */
    val signInputMode = Setting("sign_input_mode", 0)

    // ---- raised hand -> temi follows the person ----
    val raiseHandFollowEnabled = Setting("raise_hand_follow_enabled", false)
    val raiseHandFollowSeconds = Setting("raise_hand_follow_seconds", 30)
    /** Tilt temi's tablet slowly up and down until the camera sees a person. */
    val findPersonEnabled = Setting("find_person_enabled", false)

    // ---- raise hand -> temi walks to the person (uses the tablet camera, not temi's depth sensors) ----
    val approachEnabled = Setting("approach_enabled", true)
    /** After arriving, hand over to temi's follow mode (works at close range). */
    val approachThenFollow = Setting("approach_then_follow", true)
    val approachStopDistanceCm = Setting("approach_stop_cm", 130)
    /** Switch on if temi turns away from you instead of towards you. */
    val approachInvertTurn = Setting("approach_invert_turn", false)
    /** Camera focal length in image widths x 1000 (set by the Calibrate button). 785 = a 65 degree wide lens. */
    val approachFocalPermille = Setting("approach_focal_permille", 785)


    fun init(context: Context) {
        appContext = context.applicationContext
        val p = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        listOf(
            simulateRobot, raiseHandFollowEnabled, raiseHandFollowSeconds, findPersonEnabled,
            approachEnabled, approachThenFollow, approachStopDistanceCm, approachInvertTurn,
            approachFocalPermille, signInputMode
        ).forEach { it.load(p) }
    }

    internal fun persist(key: String, value: Any) {
        val p = prefs ?: return
        val e = p.edit()
        when (value) {
            is Int -> e.putInt(key, value)
            is Boolean -> e.putBoolean(key, value)
            is String -> e.putString(key, value)
        }
        e.apply()
    }
}
