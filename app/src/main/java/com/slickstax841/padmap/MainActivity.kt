package com.slickstax841.padmap

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.slickstax841.padmap.data.DataStore
import com.slickstax841.padmap.service.OverlayManager
import com.slickstax841.padmap.service.PadMapAccessibilityService
import com.slickstax841.padmap.ui.ControllerMappingScreen
import com.slickstax841.padmap.ui.GameLayoutScreen
import com.slickstax841.padmap.ui.HomeScreen
import com.slickstax841.padmap.ui.SettingsScreen
import com.slickstax841.padmap.ui.theme.PadMapTheme
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DataStore.init(applicationContext)
        setContent {
            PadMapTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                onEditPreset = { nav.navigate("controller_mapping/$it") },
                                onEditLayout = { nav.navigate("game_layout/$it") },
                                onSettings = { nav.navigate("settings") }
                            )
                        }
                        composable("controller_mapping/{id}") { back ->
                            val id = back.arguments?.getString("id") ?: return@composable
                            ControllerMappingScreen(presetId = id, onBack = { nav.popBackStack() })
                        }
                        composable("game_layout/{id}") { back ->
                            val id = back.arguments?.getString("id") ?: return@composable
                            GameLayoutScreen(layoutId = id, onBack = { nav.popBackStack() })
                        }
                        composable("settings") {
                            SettingsScreen(onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PadMapAccessibilityService.instance?.padMapUiVisible = true
    }

    override fun onPause() {
        super.onPause()
        PadMapAccessibilityService.instance?.padMapUiVisible = false
    }

    override fun onStop() {
        super.onStop()
        PadMapAccessibilityService.instance?.padMapUiVisible = false
        if (!isChangingConfigurations) {
            OverlayManager.instance?.hideIconOnAppBackground()
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            PadMapAccessibilityService.instance?.disableAndStop()
        }
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.source and InputDevice.SOURCE_GAMEPAD != 0 ||
            event.source and InputDevice.SOURCE_JOYSTICK != 0) {
            ControllerEventBus.emitKey(event)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (event.source and InputDevice.SOURCE_GAMEPAD != 0 ||
            event.source and InputDevice.SOURCE_JOYSTICK != 0) {
            ControllerEventBus.emitKey(event)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // Accept both SOURCE_JOYSTICK (sticks/triggers) and SOURCE_GAMEPAD (D-pad HAT on some controllers)
        val isController = (event.source and InputDevice.SOURCE_JOYSTICK != 0) ||
                           (event.source and InputDevice.SOURCE_GAMEPAD != 0)
        if (isController && event.action == MotionEvent.ACTION_MOVE) {
            listOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
                MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y).forEach { axis ->
                ControllerEventBus.emitAxis(axis, event.getAxisValue(axis))
            }
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }
}
