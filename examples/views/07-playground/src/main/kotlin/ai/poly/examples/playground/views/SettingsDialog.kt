// Copyright PolyAI Limited

//  SettingsDialog.kt — Examples/views/07-playground
//  A scrollable form of grouped sections — live diagnostics, raw frame sends,
//  close-code simulations, display toggles, environment / session / advanced knobs,
//  and reset. Edits write straight back to DevSettings (SharedPreferences),
//  presented as a full-screen Dialog with its own title bar.
//
//  Close-code note: OkHttp refuses to SEND the reserved code 1006 used for "force
//  reconnect" / "network drop", so both rows send 4002 — the SDK's sendable app code
//  that routes through the same exponential-backoff reconnect path. 4001 / 1000 are
//  sendable.

package ai.poly.examples.playground.views

import ai.poly.messaging.DevSettings
import ai.poly.messaging.LogLevel
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SettingsDialog(
    context: Context,
    private val scope: LifecycleCoroutineScope,
    private val settings: DevSettings,
    private val diagnostics: DevDiagnostics,
    private val hasLiveSession: Boolean,
    private val hasResumableSession: Boolean,
    private val onApplyAndRestart: () -> Unit,
    private val onForceReconnect: () -> Unit,
    private val onSimulateDrop: () -> Unit,
    private val onDisconnectClean: () -> Unit,
    private val onSimulateServerReject: () -> Unit,
    private val onSimulateIdleTimeout: () -> Unit,
    private val onSendHeartbeat: () -> Unit,
    private val onSendTypingStart: () -> Unit,
    private val onSendTypingStop: () -> Unit,
    private val onSendUserEndSession: () -> Unit,
    private val onSendUserLeft: () -> Unit,
) : Dialog(context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val hasAnySession: Boolean get() = hasLiveSession || hasResumableSession

    private val contentStack = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val diagnosticLabels = mutableMapOf<String, TextView>()
    private lateinit var envMenuButton: TextView
    private lateinit var logLevelButton: TextView
    private lateinit var clusterRow: View
    private lateinit var customUrlRow: View
    private lateinit var resolvedEnvLabel: TextView
    private val overlay = FrameLayout(context)
    private var refreshJobs = mutableListOf<Job>()
    private var toastJob: Job? = null

    init {
        val root = FrameLayout(context).apply {
            setBackgroundColor(Palette.systemGray6)
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // Title bar: title + Done.
        val titleBar = FrameLayout(context)
        titleBar.addView(
            TextView(context).apply {
                text = "Dev Settings"
                setTextColor(Palette.label)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTypeface(typeface, Typeface.BOLD)
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        titleBar.addView(
            TextView(context).apply {
                text = "Done"
                setTextColor(Palette.systemBlue)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setOnClickListener { dismiss() }
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ),
        )
        column.addView(titleBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

        val scroll = ScrollView(context)
        contentStack.setPadding(dp(16), dp(16), dp(16), dp(32))
        scroll.addView(contentStack)
        column.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(column)
        root.addView(overlay) // toast layer
        setContentView(root)
        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        window?.setBackgroundDrawableResource(android.R.color.white)

        buildSections()
        startRefreshing()
        setOnDismissListener { stopRefreshing() }
    }

    // ---- Section assembly ----

    private fun buildSections() {
        contentStack.removeAllViews()
        diagnosticLabels.clear()
        if (hasAnySession) addSection(mismatchBanner())
        addSection(diagnosticsSection())
        if (hasLiveSession) {
            addSection(sendFramesSection())
            addSection(disconnectSection())
        }
        addSection(displaySection())
        addSection(environmentSection())
        addSection(sessionSection())
        addSection(advancedSection())
        addSection(resetSection())
        refreshDiagnostics()
    }

    private fun addSection(v: View) {
        contentStack.addView(
            v,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(22) },
        )
    }

    private fun mismatchBanner(): View {
        val stack = cardStack()
        stack.addView(
            label(
                if (hasLiveSession) "⚠︎ Live session active" else "⚠︎ Resumable session exists",
                15f, Palette.label, bold = true,
            ),
        )
        stack.addView(
            label(
                if (hasLiveSession) {
                    "Streaming, greeting and environment changes only apply to NEW sessions."
                } else {
                    "A previous session is stored on disk. Session-creation settings here only apply once you start fresh."
                },
                12f, Palette.secondaryLabel,
            ),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        stack.addView(
            actionButton("Apply & Start New Session", R.drawable.ic_autorenew, Color.WHITE, Palette.systemOrange) {
                onApplyAndRestart()
                dismiss()
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        return card(stack)
    }

    private fun diagnosticsSection(): View {
        val stack = cardStack()
        val rows = listOf(
            "Status", "Session ID", "Ready", "Last sequence", "Frames in / out",
            "Chunks received", "Heartbeats in", "Reconnects", "Last frame",
            "Server streaming", "Max message size", "Server heartbeat", "Server max reconnect",
        )
        rows.forEachIndexed { i, r ->
            val (row, value) = labeledValueRow(r)
            diagnosticLabels[r] = value
            stack.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { if (i > 0) topMargin = dp(12) },
            )
        }
        return section(
            "DIAGNOSTICS", card(stack),
            "Live values from the SDK. Counters reset when a fresh client is created.",
        )
    }

    private fun sendFramesSection(): View {
        val stack = cardStack()
        stack.addView(actionRow("Send HEARTBEAT", R.drawable.ic_ecg) { fire("Heartbeat sent", onSendHeartbeat) })
        stack.addView(actionRow("Send USER_TYPING (started)", R.drawable.ic_chat_typing) { fire("Typing started", onSendTypingStart) })
        stack.addView(actionRow("Send USER_TYPING (stopped)", R.drawable.ic_chat_typing_filled) { fire("Typing stopped", onSendTypingStop) })
        stack.addView(actionRow("Send USER_END_SESSION", R.drawable.ic_close_circle, destructive = true) { fire("End session sent", onSendUserEndSession) })
        stack.addView(actionRow("Send USER_LEFT", R.drawable.ic_logout, destructive = true) { fire("User left sent", onSendUserLeft) })
        return section(
            "SEND FRAMES", card(stack),
            "Wire-level events sent directly to the server via the raw connection.",
        )
    }

    private fun disconnectSection(): View {
        val stack = cardStack()
        stack.addView(actionRow("Force reconnect (4002)", R.drawable.ic_autorenew) { fire("Reconnect triggered", onForceReconnect) })
        stack.addView(actionRow("Simulate network drop (4002)", R.drawable.ic_wifi_off) { fire("Simulated network drop", onSimulateDrop) })
        stack.addView(actionRow("Simulate idle timeout (4002)", R.drawable.ic_clock_alert) { fire("Idle-timeout close sent", onSimulateIdleTimeout) })
        stack.addView(actionRow("Simulate server reject (4001)", R.drawable.ic_shield_alert, destructive = true) { fire("Server-reject close sent", onSimulateServerReject) })
        stack.addView(actionRow("Clean disconnect (1000)", R.drawable.ic_block, destructive = true) { fire("Clean disconnect sent", onDisconnectClean) })
        return section(
            "DISCONNECT / RECONNECT", card(stack),
            "Exercises each ConnectionService close-code path. 4002 reconnects, 4001 refetches, " +
                "1000 is terminal. (OkHttp can't send reserved codes, so the first two use 4002 " +
                "instead of 1006 — same reconnect path.)",
        )
    }

    private fun displaySection(): View {
        val stack = cardStack()
        stack.addView(toggleRow("Show debug strip in chat", settings.showDebugStrip.value) { settings.setShowDebugStrip(it) })
        stack.addView(spaced(toggleRow("Message timestamps", settings.showMessageTimestamps.value) { settings.setShowMessageTimestamps(it) }))
        return section("DISPLAY", card(stack), "Visual debug aids inside the chat screen.")
    }

    private fun environmentSection(): View {
        val stack = cardStack()

        envMenuButton = menuButton(settings.environmentKind.value.displayName) { button ->
            val popup = PopupMenu(context, button)
            DevSettings.EnvironmentKind.entries.forEachIndexed { i, kind ->
                popup.menu.add(0, i, i, kind.displayName)
            }
            // Single-choice with the current selection checked.
            popup.menu.setGroupCheckable(0, true, true)
            popup.menu.getItem(DevSettings.EnvironmentKind.entries.indexOf(settings.environmentKind.value)).isChecked = true
            popup.setOnMenuItemClickListener { item ->
                val kind = DevSettings.EnvironmentKind.entries[item.itemId]
                settings.setEnvironmentKind(kind)
                button.text = kind.displayName
                updateEnvironmentVisibility()
                refreshResolvedEnv()
                true
            }
            popup.show()
        }
        stack.addView(fieldRow("Target", envMenuButton))

        val clusterField = textFieldRow("Cluster name", "us-1", settings.clusterName.value) {
            settings.setClusterName(it)
            refreshResolvedEnv()
        }
        clusterRow = spaced(clusterField)
        stack.addView(clusterRow)

        val restRow = textFieldRow("REST base URL", "https://...", settings.customRestUrl.value, uri = true) {
            settings.setCustomRestUrl(it)
            refreshResolvedEnv()
        }
        val wsRow = textFieldRow("WS base URL", "wss://.../ws", settings.customWsUrl.value, uri = true) {
            settings.setCustomWsUrl(it)
        }
        customUrlRow = spaced(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(restRow)
                addView(spaced(wsRow))
            },
        )
        stack.addView(customUrlRow)

        val (resolvedRow, resolvedValue) = labeledValueRow("Resolved")
        resolvedEnvLabel = resolvedValue
        stack.addView(spaced(resolvedRow))

        updateEnvironmentVisibility()
        refreshResolvedEnv()
        return section(
            "ENVIRONMENT", card(stack),
            "Switches the REST + WebSocket base URLs. Applies on next session creation.",
        )
    }

    private fun sessionSection(): View {
        val stack = cardStack()
        stack.addView(toggleRow("Streaming enabled", settings.streamingEnabled.value) { settings.setStreamingEnabled(it) })

        logLevelButton = menuButton(logLevelName(settings.logLevel.value)) { button ->
            val popup = PopupMenu(context, button)
            LOG_LEVELS.forEachIndexed { i, level -> popup.menu.add(0, i, i, logLevelName(level)) }
            popup.menu.setGroupCheckable(0, true, true)
            popup.menu.getItem(LOG_LEVELS.indexOf(settings.logLevel.value)).isChecked = true
            popup.setOnMenuItemClickListener { item ->
                val level = LOG_LEVELS[item.itemId]
                settings.setLogLevel(level)
                button.text = logLevelName(level)
                true
            }
            popup.show()
        }
        stack.addView(spaced(fieldRow("Log level", logLevelButton)))
        return section("SESSION", card(stack), "Sent in the /sessions request body.")
    }

    private fun advancedSection(): View {
        val stack = cardStack()
        stack.addView(
            stepperRow("Heartbeat", { settings.heartbeatIntervalSeconds.value }, 0, 300, 5, { secondsLabel(it, "30s") }) {
                settings.setHeartbeatIntervalSeconds(it)
            },
        )
        stack.addView(
            spaced(
                stepperRow("Session timeout", { settings.sessionTimeoutSeconds.value }, 0, 86_400, 60, { secondsLabel(it, "10m") }) {
                    settings.setSessionTimeoutSeconds(it)
                },
            ),
        )
        stack.addView(
            spaced(
                stepperRow("Max reconnects", { settings.maxReconnectAttempts.value }, 0, 50, 1, { if (it == 0) "10 (default)" else "$it" }) {
                    settings.setMaxReconnectAttempts(it)
                },
            ),
        )
        return section(
            "ADVANCED", card(stack),
            "0 = use SDK default. Server SessionCapabilities still wins after SESSION_START.",
        )
    }

    private fun resetSection(): View {
        val stack = cardStack()
        stack.addView(
            actionButton("Reset to defaults", R.drawable.ic_restore, Palette.systemRed, Palette.systemGray5) {
                settings.resetToDefaults()
                buildSections() // reload after reset
            },
        )
        return card(stack)
    }

    // ---- Environment helpers ----

    private fun updateEnvironmentVisibility() {
        clusterRow.visibility =
            if (settings.environmentKind.value == DevSettings.EnvironmentKind.CLUSTER) View.VISIBLE else View.GONE
        customUrlRow.visibility =
            if (settings.environmentKind.value == DevSettings.EnvironmentKind.CUSTOM) View.VISIBLE else View.GONE
    }

    private fun refreshResolvedEnv() {
        resolvedEnvLabel.text = settings.environmentDisplayName()
    }

    private fun logLevelName(level: LogLevel): String = when (level) {
        LogLevel.NONE -> "None"; LogLevel.ERROR -> "Error"; LogLevel.WARN -> "Warn"
        LogLevel.INFO -> "Info"; LogLevel.DEBUG -> "Debug"
    }

    // ---- Diagnostics refresh (flows + 1s timer) ----

    private fun startRefreshing() {
        // Every diagnostics flow triggers an immediate refresh; the 1s ticker keeps the
        // relative "last frame" label fresh between events.
        val flows = listOf(
            diagnostics.connectionLabel, diagnostics.sessionId, diagnostics.isReady,
            diagnostics.lastSequence, diagnostics.framesIn, diagnostics.framesOut,
            diagnostics.chunksIn, diagnostics.heartbeatsIn, diagnostics.reconnectCount,
            diagnostics.lastInboundAt, diagnostics.streamingCapability, diagnostics.maxMessageSize,
            diagnostics.serverHeartbeatSeconds, diagnostics.serverMaxReconnectAttempts,
        )
        flows.forEach { flow ->
            refreshJobs += scope.launch { flow.collect { refreshDiagnostics() } }
        }
        refreshJobs += scope.launch {
            while (true) {
                delay(1_000)
                refreshDiagnostics()
            }
        }
    }

    private fun stopRefreshing() {
        refreshJobs.forEach { it.cancel() }
        refreshJobs.clear()
        toastJob?.cancel()
    }

    private fun refreshDiagnostics() {
        fun set(key: String, value: String) {
            diagnosticLabels[key]?.text = value
        }
        set("Status", diagnostics.connectionLabel.value)
        set("Session ID", shortened(diagnostics.sessionId.value))
        set("Ready", if (diagnostics.isReady.value) "yes" else "no")
        set("Last sequence", "${diagnostics.lastSequence.value}")
        set("Frames in / out", "${diagnostics.framesIn.value} / ${diagnostics.framesOut.value}")
        set("Chunks received", "${diagnostics.chunksIn.value}")
        set("Heartbeats in", "${diagnostics.heartbeatsIn.value}")
        set("Reconnects", "${diagnostics.reconnectCount.value}")
        set("Last frame", lastFrameLabel())
        set("Server streaming", diagnostics.streamingCapability.value?.let { if (it) "yes" else "no" } ?: "—")
        set("Max message size", diagnostics.maxMessageSize.value?.let { if (it == 0) "unlimited" else "$it bytes" } ?: "—")
        set("Server heartbeat", diagnostics.serverHeartbeatSeconds.value?.let { if (it == 0) "disabled" else "${it}s" } ?: "—")
        set("Server max reconnect", diagnostics.serverMaxReconnectAttempts.value?.let { if (it == 0) "unlimited" else "$it" } ?: "—")
    }

    private fun lastFrameLabel(): String {
        val at = diagnostics.lastInboundAt.value ?: return "—"
        val s = ((System.currentTimeMillis() - at) / 1_000L).toInt()
        if (s < 1) return "just now"
        if (s < 60) return "${s}s ago"
        return "${s / 60}m ago"
    }

    private fun shortened(s: String?): String {
        if (s.isNullOrEmpty()) return "—"
        if (s.length <= 12) return s
        return s.take(4) + "…" + s.takeLast(8)
    }

    private fun secondsLabel(secs: Int, def: String): String {
        if (secs == 0) return "$def (default)"
        if (secs >= 3_600) return "${secs / 3_600}h" + if (secs % 3_600 == 0) "" else " ${secs % 3_600 / 60}m"
        if (secs >= 60) return "${secs / 60}m" + if (secs % 60 == 0) "" else " ${secs % 60}s"
        return "${secs}s"
    }

    private fun fire(message: String, action: () -> Unit) {
        action()
        overlay.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        toast(message)
    }

    /** Bottom toast, fade in / hold ~1.3s / fade out. */
    private fun toast(text: String) {
        toastJob?.cancel()
        overlay.removeAllViews()
        val label = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = GradientDrawable().apply {
                setColor(Color.argb((0.82f * 255).toInt(), 0, 0, 0))
                cornerRadius = 16 * density
            }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            alpha = 0f
        }
        overlay.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = dp(24) },
        )
        label.animate().alpha(1f).setDuration(200).start()
        toastJob = scope.launch {
            delay(1_500)
            label.animate().alpha(0f).setDuration(300).withEndAction { overlay.removeView(label) }.start()
        }
    }

    // ---- Row builders ----

    private fun card(content: View): View {
        val card = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 12 * density
            }
        }
        card.addView(content)
        return card
    }

    private fun cardStack(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
    }

    private fun section(title: String, card: View, footer: String?): View {
        val outer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(
            label(title, 12f, Palette.secondaryLabel).apply { setPadding(dp(2), 0, 0, dp(6)) },
        )
        outer.addView(card)
        if (footer != null) {
            outer.addView(
                label(footer, 11f, Palette.secondaryLabel).apply { setPadding(dp(2), dp(6), 0, 0) },
            )
        }
        return outer
    }

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun labeledValueRow(name: String): Pair<View, TextView> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            label(name, 15f, Palette.label),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val value = label("—", 15f, Palette.secondaryLabel).apply {
            gravity = Gravity.END
            maxLines = 1
        }
        row.addView(value)
        return row to value
    }

    private fun fieldRow(name: String, control: View): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            label(name, 15f, Palette.label),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(control)
        return row
    }

    private fun toggleRow(name: String, value: Boolean, onChange: (Boolean) -> Unit): View {
        @Suppress("UseSwitchCompatOrMaterialCode")
        val toggle = Switch(context).apply {
            isChecked = value
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        return fieldRow(name, toggle)
    }

    private fun textFieldRow(name: String, placeholder: String, value: String, uri: Boolean = false, onChange: (String) -> Unit): View {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(label(name, 12f, Palette.secondaryLabel))
        val field = EditText(context).apply {
            hint = placeholder
            setText(value)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            if (uri) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = GradientDrawable().apply {
                setColor(Palette.systemGray6)
                cornerRadius = 6 * density
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            doAfterTextChanged { onChange(it?.toString() ?: "") }
        }
        column.addView(
            field,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) },
        )
        return column
    }

    private fun stepperRow(
        name: String,
        value: () -> Int,
        min: Int,
        max: Int,
        step: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            label(name, 15f, Palette.label),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val valueLabel = label(format(value()), 15f, Palette.secondaryLabel)
        row.addView(valueLabel)

        fun stepButton(symbol: String, delta: Int): TextView = TextView(context).apply {
            text = symbol
            gravity = Gravity.CENTER
            setTextColor(Palette.label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            background = GradientDrawable().apply {
                setColor(Palette.systemGray5)
                cornerRadius = 6 * density
            }
            contentDescription = if (delta > 0) "Increment" else "Decrement"
            setOnClickListener {
                val v = (value() + delta).coerceIn(min, max)
                onChange(v)
                valueLabel.text = format(v)
            }
        }
        row.addView(
            stepButton("−", -step),
            LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(8) },
        )
        row.addView(
            stepButton("+", step),
            LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(8) },
        )
        return row
    }

    private fun actionRow(title: String, iconRes: Int, destructive: Boolean = false, action: () -> Unit): View {
        val enabled = hasLiveSession
        val tint = when {
            !enabled -> Palette.secondaryLabel
            destructive -> Palette.systemRed
            else -> Palette.label
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isEnabled = enabled
            isClickable = enabled
            if (enabled) setOnClickListener { action() }
        }
        row.addView(
            ImageView(context).apply {
                setImageDrawable(ContextCompat.getDrawable(context, iconRes))
                ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(tint))
            },
            LinearLayout.LayoutParams(dp(18), dp(18)),
        )
        row.addView(
            label(title, 15f, tint).apply { setPadding(dp(10), 0, 0, 0) },
        )
        return row
    }

    private fun actionButton(title: String, iconRes: Int, fg: Int, bg: Int, action: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(bg)
                cornerRadius = 8 * density
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener { action() }
        }
        row.addView(
            ImageView(context).apply {
                setImageDrawable(ContextCompat.getDrawable(context, iconRes))
                ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(fg))
            },
            LinearLayout.LayoutParams(dp(14), dp(14)),
        )
        row.addView(label(title, 12f, fg, bold = true).apply { setPadding(dp(6), 0, 0, 0) })
        return row
    }

    private fun menuButton(title: String, onClick: (TextView) -> Unit): TextView =
        TextView(context).apply {
            text = title
            setTextColor(Palette.label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = GradientDrawable().apply {
                setColor(Palette.systemGray5)
                cornerRadius = 8 * density
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            setOnClickListener { onClick(this) }
        }

    private companion object {
        val LOG_LEVELS = listOf(LogLevel.NONE, LogLevel.ERROR, LogLevel.WARN, LogLevel.INFO, LogLevel.DEBUG)
    }

    private fun spaced(v: View): View {
        (v.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(v)
        }
    }
}
