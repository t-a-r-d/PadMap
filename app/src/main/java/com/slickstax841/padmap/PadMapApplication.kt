package com.slickstax841.padmap

import android.app.Application
import com.slickstax841.padmap.service.InjectManager
import org.lsposed.hiddenapibypass.HiddenApiBypass

class PadMapApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Bypass Android's hidden API enforcement so InjectManager can access
        // InputManager.injectInputEvent() via reflection without a privileged app.
        // Must be called before InjectManager.init() resolves the method references.
        HiddenApiBypass.addHiddenApiExemptions("")
        InjectManager.init()
    }
}
