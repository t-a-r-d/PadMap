package com.slickstax841.padmap.inject

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.slickstax841.padmap.service.PadMapAccessibilityService

/**
 * Top bar over the Android pair screen. Fields take the system keyboard
 * so the user never leaves that screen (leaving it kills the code/port).
 */
object PairOverlay {

    private val main = Handler(Looper.getMainLooper())
    private var window: LinearLayout? = null
    private var codeField: EditText? = null
    private var addrField: EditText? = null
    private var statusView: TextView? = null
    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
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
        busy = false
        val dm = app.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        val svc = PadMapAccessibilityService.instance
        val wmHost = svc ?: app
        val windowManager = wmHost.getSystemService(WindowManager::class.java)
        wm = windowManager

        val box = GradientDrawable().apply {
            setColor(Color.parseColor("#1A1A1A"))
            cornerRadius = dp(6).toFloat()
            setStroke(dp(1), Color.parseColor("#555555"))
        }

        val root = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(240, 8, 8, 8))
                setStroke(dp(1), Color.parseColor("#00BFFF"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        root.addView(TextView(app).apply {
            text = "Stay on the Android pair screen. Tap a field — keyboard comes up from the bottom."
            textSize = 11f
            setTextColor(Color.parseColor("#00BFFF"))
        })

        val code = EditText(app).apply {
            hint = "6-digit pairing code"
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(Color.WHITE)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            imeOptions = EditorInfo.IME_ACTION_NEXT
            isFocusable = true
            isFocusableInTouchMode = true
            background = box.constantState?.newDrawable()?.mutate()
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnTouchListener { v, _ ->
                enableKeyboard(v)
                false
            }
        }
        codeField = code
        root.addView(code, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val addr = EditText(app).apply {
            hint = "Paste IP:port  (192.168.x.x:12345)"
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(Color.WHITE)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            isFocusable = true
            isFocusableInTouchMode = true
            background = box.constantState?.newDrawable()?.mutate()
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnTouchListener { v, _ ->
                enableKeyboard(v)
                false
            }
            setOnEditorActionListener { _, _, _ ->
                submit(app)
                true
            }
        }
        addrField = addr
        root.addView(addr, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        val status = TextView(app).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.parseColor("#AAAAAA"))
        }
        statusView = status
        root.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        val row = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
        val submit = TextView(app).apply {
            text = "SUBMIT"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00BFFF"))
                cornerRadius = dp(6).toFloat()
            }
            setOnClickListener { submit(app) }
        }
        val close = TextView(app).apply {
            text = "CLOSE"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#888888"))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { hide() }
        }
        row.addView(submit)
        row.addView(close)
        root.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val type = if (svc != null)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }
        params = lp

        try {
            windowManager.addView(root, lp)
            window = root
        } catch (t: Throwable) {
            Toast.makeText(app, "Could not show pair bar: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun hide() {
        val root = window ?: return
        val manager = wm ?: root.context.getSystemService(WindowManager::class.java)
        runCatching { manager.removeView(root) }
        window = null
        codeField = null
        addrField = null
        statusView = null
        wm = null
        params = null
        busy = false
    }

    private fun enableKeyboard(field: View) {
        val lp = params ?: return
        val manager = wm ?: return
        val root = window ?: return
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        runCatching { manager.updateViewLayout(root, lp) }
        field.post {
            field.requestFocus()
            val imm = field.context.getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun submit(context: Context) {
        if (busy) return
        val code = codeField?.text?.toString()?.filter { it.isDigit() }.orEmpty()
        if (code.length != 6) {
            statusView?.text = "Enter the 6-digit code"
            return
        }
        busy = true
        statusView?.text = "Pairing…"
        val pasted = addrField?.text?.toString().orEmpty()
        Thread {
            val result = runCatching {
                if (!SidecarHost.isWirelessDebugOn(context)) {
                    error("Turn on Wireless debugging first")
                }
                val pairEp = if (pasted.isNotBlank()) {
                    AdbEndpoint.parse(pasted)
                        ?: error("IP:port should look like 192.168.0.12:37123")
                } else {
                    NsdAdbFinder.findWithRetry(context, pairing = true)
                        ?: error("Keep the Android pair screen open, or paste IP:port")
                }
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
                    onFailure = { statusView?.text = it.message ?: "Pair failed" }
                )
            }
        }.start()
    }
}
