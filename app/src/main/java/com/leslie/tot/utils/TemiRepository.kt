package com.leslie.tot.utils

import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import com.robotemi.sdk.BatteryData
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest
import com.robotemi.sdk.listeners.OnBatteryStatusChangedListener
import com.robotemi.sdk.listeners.OnRobotReadyListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RobotState(
    val simulated: Boolean = false,
    /** temi SDK is connected and the robot can be commanded. */
    val ready: Boolean = false,
    val batteryPercent: Int? = null,
    val charging: Boolean = false
)

/**
 * The only class that talks to the temi SDK. Everything else goes through here, so a simulated
 * robot can be used when the app runs on a normal tablet / emulator.
 */
object TemiRepository : OnRobotReadyListener, OnBatteryStatusChangedListener {

    const val TILT_MIN_DEG = -15
    const val TILT_MAX_DEG = 45

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(RobotState())
    val state: StateFlow<RobotState> = _state.asStateFlow()

    private val _following = MutableStateFlow(false)
    /** True while temi is in "be with me" (follow the person) mode started by this app. */
    val following: StateFlow<Boolean> = _following.asStateFlow()

    private val _tilt = MutableStateFlow(0)
    /** Last tablet tilt this app commanded, in degrees (positive = camera looks up). */
    val tiltDegrees: StateFlow<Int> = _tilt.asStateFlow()

    private var activityInfo: ActivityInfo? = null
    private var listening = false
    private var pollJob: Job? = null

    fun init(info: ActivityInfo?) {
        activityInfo = info
    }

    /** Re-reads the "simulate robot" setting and attaches to (or detaches from) the real robot. */
    fun applyMode() {
        mainHandler.post {
            detachReal()
            pollJob?.cancel()
            pollJob = null
            if (AppSettings.simulateRobot.value) {
                _state.value = RobotState(simulated = true, ready = true, batteryPercent = 87, charging = false)
            } else {
                _state.value = RobotState(simulated = false, ready = false)
                attachReal()
            }
        }
    }

    /** Call from Activity.onStart so temi keeps the app's top-bar shortcut state in sync. */
    fun onActivityStart() {
        if (_state.value.ready && !_state.value.simulated) registerWithLauncher()
    }

    // -------- commands --------

    /**
     * Tilts the tablet to an absolute angle (positive = up). The angle is limited to
     * [TILT_MIN_DEG]..[TILT_MAX_DEG], a little inside what temi can do, so the scan never hits the end stops.
     */
    fun tiltTo(degrees: Int, speed: Float = 0.5f): Boolean {
        val d = degrees.coerceIn(TILT_MIN_DEG, TILT_MAX_DEG)
        if (_state.value.simulated) {
            _tilt.value = d
            return true
        }
        val robot = robotOrNull() ?: return false
        if (!_state.value.ready) return false
        return try {
            robot.tiltAngle(d, speed)
            _tilt.value = d
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Starts temi's built-in follow mode: it finds the nearest person and keeps a safe distance. */
    fun beWithMe(): Boolean {
        if (_state.value.simulated) {
            _following.value = true
            return true
        }
        val robot = robotOrNull() ?: return false
        if (!_state.value.ready) return false
        return try {
            robot.beWithMe()
            _following.value = true
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Ends follow mode (no-op if temi is not following). */
    fun stopFollowing() {
        if (_following.value) stopMovement()
    }

    fun stopMovement() {
        _following.value = false
        if (_state.value.simulated) return
        try {
            robotOrNull()?.stopMovement()
        } catch (e: Exception) {
            // ignore
        }
    }

    /** Turns temi's body by [degrees] (the SDK treats positive as counter-clockwise, i.e. to the left). */
    fun turnBy(degrees: Int, speed: Float = 0.6f): Boolean {
        if (degrees == 0) return true
        if (_state.value.simulated) return true
        val robot = robotOrNull() ?: return false
        if (!_state.value.ready) return false
        return try {
            robot.turnBy(degrees, speed)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Manual drive step: x = forward (1) / backward (-1), y = turn left (1) / right (-1).
     * [smart] lets temi's own navigation steer around obstacles. Send again at least every 500 ms to keep moving.
     */
    fun skidJoy(x: Float, y: Float, smart: Boolean = true): Boolean {
        if (_state.value.simulated) return true
        val robot = robotOrNull() ?: return false
        if (!_state.value.ready) return false
        return try {
            robot.skidJoy(x, y, smart)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Speaks text using temi TTS engine. */
    fun speak(text: String, showOnScreen: Boolean = false) {
        if (text.isBlank()) return
        if (_state.value.simulated) return
        try {
            val request = TtsRequest.create(text, showOnScreen)
            robotOrNull()?.speak(request)
        } catch (e: Exception) {
            // ignore
        }
    }

    // -------- temi SDK callbacks --------

    override fun onRobotReady(isReady: Boolean) {
        _state.update { it.copy(ready = isReady) }
        if (isReady) {
            registerWithLauncher()
            scope.launch { pollReal() }
        } else {
            _state.update { it.copy(batteryPercent = null) }
        }
    }

    override fun onBatteryStatusChanged(batteryData: BatteryData?) {
        if (batteryData == null) return
        _state.update { s -> s.copy(batteryPercent = batteryData.level, charging = batteryData.isCharging) }
    }

    // -------- internals --------

    private fun robotOrNull(): Robot? = try {
        Robot.getInstance()
    } catch (e: Throwable) {
        null
    }

    private fun attachReal() {
        val robot = robotOrNull() ?: return
        robot.addOnRobotReadyListener(this)
        robot.addOnBatteryStatusChangedListener(this)
        listening = true
        pollJob = scope.launch {
            while (isActive) {
                pollReal()
                delay(5000)
            }
        }
    }

    private fun detachReal() {
        if (!listening) return
        val robot = robotOrNull() ?: return
        robot.removeOnRobotReadyListener(this)
        robot.removeOnBatteryStatusChangedListener(this)
        listening = false
    }

    private fun registerWithLauncher() {
        val info = activityInfo ?: return
        mainHandler.post {
            try {
                robotOrNull()?.onStart(info)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun pollReal() {
        val robot = robotOrNull() ?: return
        try {
            if (!robot.isReady) {
                _state.update { it.copy(ready = false, batteryPercent = null) }
                return
            }
            val battery = robot.batteryData
            _state.update { s ->
                s.copy(
                    ready = true,
                    batteryPercent = battery?.level ?: s.batteryPercent,
                    charging = battery?.isCharging ?: s.charging
                )
            }
        } catch (e: Exception) {
            // keep the last known state
        }
    }
}
