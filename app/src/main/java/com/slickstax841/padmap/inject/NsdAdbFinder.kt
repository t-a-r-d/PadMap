package com.slickstax841.padmap.inject

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class AdbEndpoint(val host: String, val port: Int) {
    companion object {
        /** Accepts "37123" or Android's "192.168.0.12:37123". */
        fun parse(raw: String, defaultHost: String = "127.0.0.1"): AdbEndpoint? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            val colon = s.lastIndexOf(':')
            if (colon > 0) {
                val host = s.substring(0, colon).trim().ifBlank { defaultHost }
                val port = s.substring(colon + 1).trim().toIntOrNull() ?: return null
                if (port !in 1..65535) return null
                return AdbEndpoint(host, port)
            }
            val port = s.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return AdbEndpoint(defaultHost, port)
        }
    }
}

object NsdAdbFinder {

    fun find(context: Context, pairing: Boolean, timeoutMs: Long = 4000): AdbEndpoint? {
        val type = if (pairing) "_adb-tls-pairing._tcp" else "_adb-tls-connect._tcp"
        val nsd = context.getSystemService(NsdManager::class.java) ?: return null
        val latch = CountDownLatch(1)
        var found: AdbEndpoint? = null
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        if (found != null) return
                        val host = resolved.host?.let { addr ->
                            if (addr is Inet4Address) addr.hostAddress else "127.0.0.1"
                        } ?: "127.0.0.1"
                        found = AdbEndpoint(host, resolved.port)
                        latch.countDown()
                    }
                })
            }
        }
        return try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            found
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    fun findWithRetry(context: Context, pairing: Boolean, attempts: Int = 4): AdbEndpoint? {
        repeat(attempts) { i ->
            find(context, pairing)?.let { return it }
            if (i < attempts - 1) Thread.sleep(800)
        }
        return null
    }
}
