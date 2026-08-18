package com.slickstax841.padmap.inject

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.slickstax841.padmap.service.PadMapAccessibilityService

/**
 * Stays on top of Android's pair-with-code screen so leaving PadMap
 * does not dismiss that popup (which mints a new code and port).
 */
object PairOverlay {

    private val main = Handler(Looper.getMainLooper())
    private var window: LinearLayout? = null
    private var codeView: TextView? = null
    private var statusView: TextView? = null
    private val digits = StringBuilder()
    private var busy = false

    fun show(context: Context) {
        val app = context.applicationContext
        if (!android.provider.Settings.canDrawOverlays(app) &&
            PadMapAccessibilityService.instance == null
        ) {
            Toast.makeText(app, "Allow overlay first", Toast.LENGTH_SHORT).show()
            return
        }
        hide()
        digits.clear()
        busy = false
        val dm = app.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        val svc = PadMapAccessibilityService.instance
        val wmHost = svc ?: app
        val wm = wmHost.getSystemService(WindowManager::class.java)
        val root = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(230, 12, 12, 12))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#00BFFF"))
            }
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        val title = TextView(app).apply {
            text = "TYPE CODE HERE — leave the Android pair screen open"
            textSize = 11f
            setTextColor(Color.parseColor("#00BFFF"))
        }
        root.addView(title)

        val codeTv = TextView(app).apply {
            text = "------"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        }
        codeView = codeTv
        root.addView(codeTv)

        val status = TextView(app).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.parseColor("#AAAAAA"))
        }
        statusView = status
        root.addView(status)

        val grid = GridLayout(app).apply {
            columnCount = 3
            rowCount = 4
        }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "GO")
        keys.forEach { label ->
            val cell = TextView(app).apply {
                text = label
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#222222"))
                    cornerRadius = dp(6).toFloat()
                    setStroke(dp(1), Color.parseColor("#444444"))
                }
                setOnClickListener { onKey(app, label) }
            }
            val lp = GridLayout.LayoutParams().apply {
                width = dp(56)
                height = dp(40)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            grid.addView(cell, lp)
        }
        root.addView(grid)

        val close = TextView(app).apply {
            text = "CLOSE"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, dp(6), 0, 0)
            setOnClickListener { hide() }
        }
        root.addView(close)

        val type = if (svc != null)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(80)
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX - (e.rawX - downX).toInt()
                    params.y = startY + (e.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(root, params) }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(root, params)
            window = root
            Toast.makeText(app, "Open Pair with pairing code. Type it on this pad. Do not leave that screen.", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Toast.makeText(app, "Could not show pair pad: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun hide() {
        val root = window ?: return
        val wm = root.context.getSystemService(WindowManager::class.java)
        runCatching { wm.removeView(root) }
        window = null
        codeView = null
        statusView = null
        digits.clear()
        busy = false
    }

    private fun onKey(context: Context, label: String) {
        if (busy) return
        when (label) {
            "⌫" -> if (digits.isNotEmpty()) digits.deleteCharAt(digits.lastIndex)
            "GO" -> {
                if (digits.length == 6) submit(context)
                return
            }
            else -> if (digits.length < 6) digits.append(label)
        }
        codeView?.text = digits.toString().padEnd(6, '-')
        if (digits.length == 6) submit(context)
    }

    private fun submit(context: Context) {
        if (busy || digits.length != 6) return
        busy = true
        val code = digits.toString()
        statusView?.text = "Pairing…"
        Thread {
            val result = runCatching {
                if (!SidecarHost.isWirelessDebugOn(context)) {
                    error("Turn on Wireless debugging first")
                }
                val pairEp = NsdAdbFinder.findWithRetry(context, pairing = true)
                    ?: error("Keep the Android pair screen open")
                SidecarHost.pair(context, pairEp.host, pairEp.port, code)
                val connEp = NsdAdbFinder.findWithRetry(context, pairing = false)
                    ?: error("Paired. Leave Wireless debugging ON.")
                SidecarHost.start(context, connEp.host, connEp.port)
            }
            main.post {
                busy = false
                result.fold(
                    onSuccess = {
                        statusView?.text = "Injector running"
                        Toast.makeText(context, "Injector running — you will not need the code again", Toast.LENGTH_LONG).show()
                        hide()
                    },
                    onFailure = {
                        statusView?.text = it.message ?: "Pair failed"
                        digits.clear()
                        codeView?.text = "------"
                    }
                )
            }
        }.start()
    }
}
