package com.leslie.tot

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.leslie.tot.components.TotScreen
import com.leslie.tot.screens.CameraDepthScreen
import com.leslie.tot.screens.SignLanguageScreen
import com.leslie.tot.ui.theme.TotTheme
import com.leslie.tot.utils.AppSettings
import com.leslie.tot.utils.TemiRepository
import com.robotemi.sdk.Robot
import com.robotemi.sdk.listeners.OnRobotReadyListener

class MainActivity : ComponentActivity(), OnRobotReadyListener {

    private val robot: Robot?
        get() = try { Robot.getInstance() } catch (e: Throwable) { null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Only on the first launch of the process (not on rotation / re-creation).
        val firstLaunch = !AppSettings.isInitialised
        if (firstLaunch) AppSettings.init(applicationContext)

        TemiRepository.init(currentActivityInfo())
        if (firstLaunch) TemiRepository.applyMode()   // temi robot (or simulated robot)

        enableEdgeToEdge()

        setContent {
            TotTheme {
                // Only one screen is composed at a time, so only one uses the camera at a time.
                var screen by rememberSaveable { mutableStateOf(TotScreen.CAMERA) }
                when (screen) {
                    TotScreen.HAND_READING -> SignLanguageScreen(onNavigate = { screen = it })
                    else -> CameraDepthScreen(onNavigate = { screen = it })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        robot?.addOnRobotReadyListener(this)
        TemiRepository.onActivityStart()
    }

    override fun onStop() {
        super.onStop()
        robot?.removeOnRobotReadyListener(this)
    }

    override fun onRobotReady(isReady: Boolean) {
        if (!isReady) return
        val info = currentActivityInfo() ?: return
        try {
            robot?.onStart(info)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun currentActivityInfo() = try {
        packageManager.getActivityInfo(componentName, PackageManager.GET_META_DATA)
    } catch (e: Exception) {
        null
    }
}
