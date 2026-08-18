package com.slickstax841.padmap.inject

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-side socket to the shell sidecar. Never calls injectInputEvent itself.
 */
object SidecarClient {

    const val PORT = 18741

    /** Per-install token set by SidecarHost before ping/start. */
    @Volatile
    var authToken: String = ""

    private const val CMD_PING: Byte = 0x50
    private const val CMD_DOWN: Byte = 0x44
    private const val CMD_MOVE: Byte = 0x4D
    private const val CMD_BATCH: Byte = 0x42
    private const val CMD_UP: Byte = 0x55
    private const val CMD_RELEASE: Byte = 0x52

    @Volatile
    var lastError: String = ""
        private set

    private val lock = Any()
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private val connected = AtomicBoolean(false)

    val isAvailable: Boolean
        get() = connected.get()

    fun ping(): Boolean = synchronized(lock) {
        if (!ensureConnectedLocked()) return false
        return writeAndAckLocked {
            output!!.writeByte(CMD_PING.toInt())
        }
    }

    fun pointerDown(id: Int, x: Float, y: Float): Boolean = synchronized(lock) {
        if (!ensureConnectedLocked()) return false
        return writeAndAckLocked {
            output!!.writeByte(CMD_DOWN.toInt())
            output!!.writeByte(id)
            output!!.writeFloat(x)
            output!!.writeFloat(y)
        }
    }

    fun pointerMove(id: Int, x: Float, y: Float): Boolean = synchronized(lock) {
        if (!ensureConnectedLocked()) return false
        return writeAndAckLocked {
            output!!.writeByte(CMD_MOVE.toInt())
            output!!.writeByte(id)
            output!!.writeFloat(x)
            output!!.writeFloat(y)
        }
    }

    fun batchUpdate(updates: Map<Int, Pair<Float, Float>>): Boolean = synchronized(lock) {
        if (!ensureConnectedLocked() || updates.isEmpty()) return false
        return writeAndAckLocked {
            output!!.writeByte(CMD_BATCH.toInt())
            output!!.writeByte(updates.size)
            for ((id, pos) in updates) {
                output!!.writeByte(id)
                output!!.writeFloat(pos.first)
                output!!.writeFloat(pos.second)
            }
        }
    }

    fun pointerUp(id: Int): Boolean = synchronized(lock) {
        if (!ensureConnectedLocked()) return false
        return writeAndAckLocked {
            output!!.writeByte(CMD_UP.toInt())
            output!!.writeByte(id)
        }
    }

    fun releaseAll() {
        synchronized(lock) {
            if (!ensureConnectedLocked()) return
            writeAndAckLocked { output!!.writeByte(CMD_RELEASE.toInt()) }
        }
    }

    fun disconnect() {
        synchronized(lock) { closeLocked() }
    }

    private fun ensureConnectedLocked(): Boolean {
        if (connected.get() && socket?.isConnected == true) return true
        return try {
            closeLocked()
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 800)
            s.soTimeout = 1500
            val out = DataOutputStream(s.getOutputStream())
            val inp = DataInputStream(s.getInputStream())
            out.write(authToken.toByteArray())
            out.flush()
            if (inp.readByte().toInt() != 0) {
                lastError = "sidecar rejected token"
                s.close()
                return false
            }
            socket = s
            output = out
            input = inp
            connected.set(true)
            lastError = ""
            true
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            closeLocked()
            false
        }
    }

    private inline fun writeAndAckLocked(write: () -> Unit): Boolean {
        return try {
            write()
            output!!.flush()
            val ok = input!!.readByte().toInt() == 0
            if (!ok) lastError = "sidecar command failed"
            ok
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            closeLocked()
            false
        }
    }

    private fun closeLocked() {
        connected.set(false)
        try { input?.close() } catch (_: Throwable) {}
        try { output?.close() } catch (_: Throwable) {}
        try { socket?.close() } catch (_: Throwable) {}
        input = null
        output = null
        socket = null
    }
}
