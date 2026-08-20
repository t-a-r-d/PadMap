package com.slickstax841.padmap.inject

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * App-side socket to the shell sidecar. Never calls injectInputEvent itself.
 * All TCP runs on [io] — playback arrives on the main thread and Android
 * throws NetworkOnMainThreadException if we connect/write there (BUG-004 v65).
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
    private val ioThread = AtomicReference<Thread?>(null)
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "padmap-sidecar").apply {
            isDaemon = true
            ioThread.set(this)
        }
    }

    val isAvailable: Boolean
        get() = connected.get()

    fun ping(): Boolean = onIo {
        synchronized(lock) {
            if (!ensureConnectedLocked()) false
            else writeAndAckLocked { output!!.writeByte(CMD_PING.toInt()) }
        }
    }

    /**
     * Playback must never wait for a reconnect. The single [io] writer preserves
     * command order with queued DOWN/MOVE/UP commands after this probe.
     */
    fun pingAsync() {
        io.execute {
            try {
                synchronized(lock) {
                    if (ensureConnectedLocked()) {
                        writeAndAckLocked { output!!.writeByte(CMD_PING.toInt()) }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    fun pointerDown(id: Int, x: Float, y: Float): Boolean = onIo {
        synchronized(lock) {
            if (!ensureConnectedLocked()) false
            else writeAndAckLocked {
                output!!.writeByte(CMD_DOWN.toInt())
                output!!.writeByte(id)
                output!!.writeFloat(x)
                output!!.writeFloat(y)
            }
        }
    }

    fun pointerMove(id: Int, x: Float, y: Float): Boolean = onIo {
        synchronized(lock) {
            if (!ensureConnectedLocked()) false
            else writeAndAckLocked {
                output!!.writeByte(CMD_MOVE.toInt())
                output!!.writeByte(id)
                output!!.writeFloat(x)
                output!!.writeFloat(y)
            }
        }
    }

    fun batchUpdateAsync(updates: Map<Int, Pair<Float, Float>>) {
        if (updates.isEmpty()) return
        io.execute {
            try {
                synchronized(lock) {
                    if (!ensureConnectedLocked() || updates.isEmpty()) return@synchronized
                    writeAndAckLocked {
                        output!!.writeByte(CMD_BATCH.toInt())
                        output!!.writeByte(updates.size)
                        for ((id, pos) in updates) {
                            output!!.writeByte(id)
                            output!!.writeFloat(pos.first)
                            output!!.writeFloat(pos.second)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    fun batchUpdate(updates: Map<Int, Pair<Float, Float>>): Boolean = onIo {
        synchronized(lock) {
            if (!ensureConnectedLocked() || updates.isEmpty()) false
            else writeAndAckLocked {
                output!!.writeByte(CMD_BATCH.toInt())
                output!!.writeByte(updates.size)
                for ((id, pos) in updates) {
                    output!!.writeByte(id)
                    output!!.writeFloat(pos.first)
                    output!!.writeFloat(pos.second)
                }
            }
        }
    }

    fun pointerDownAsync(id: Int, x: Float, y: Float) {
        io.execute {
            try {
                synchronized(lock) {
                    if (!ensureConnectedLocked()) return@synchronized
                    writeAndAckLocked {
                        output!!.writeByte(CMD_DOWN.toInt())
                        output!!.writeByte(id)
                        output!!.writeFloat(x)
                        output!!.writeFloat(y)
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    fun pointerUpAsync(id: Int) {
        io.execute {
            try {
                synchronized(lock) {
                    if (!ensureConnectedLocked()) return@synchronized
                    writeAndAckLocked {
                        output!!.writeByte(CMD_UP.toInt())
                        output!!.writeByte(id)
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    fun pointerUp(id: Int): Boolean = onIo {
        synchronized(lock) {
            if (!ensureConnectedLocked()) false
            else writeAndAckLocked {
                output!!.writeByte(CMD_UP.toInt())
                output!!.writeByte(id)
            }
        }
    }

    fun releaseAll() {
        onIo {
            synchronized(lock) {
                if (!ensureConnectedLocked()) false
                else writeAndAckLocked { output!!.writeByte(CMD_RELEASE.toInt()) }
            }
        }
    }

    /** Queue a release behind any already accepted playback work. */
    fun releaseAllAsync() {
        io.execute {
            try {
                synchronized(lock) {
                    if (ensureConnectedLocked()) {
                        writeAndAckLocked { output!!.writeByte(CMD_RELEASE.toInt()) }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    fun disconnect() {
        onIo {
            synchronized(lock) { closeLocked() }
            true
        }
    }

    private fun onIo(block: () -> Boolean): Boolean {
        if (Thread.currentThread() === ioThread.get()) {
            return try {
                block()
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                false
            }
        }
        return try {
            io.submit(Callable { block() }).get(3, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            var c: Throwable = t
            val seen = HashSet<Throwable>()
            while (c.cause != null && seen.add(c)) c = c.cause!!
            lastError = c.message ?: c.javaClass.simpleName
            false
        }
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
