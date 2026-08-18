package com.slickstax841.padmap

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security

class PadMapApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
    }
}
