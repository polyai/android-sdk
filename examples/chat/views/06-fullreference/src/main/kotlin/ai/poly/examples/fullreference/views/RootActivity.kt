// Copyright PolyAI Limited

package ai.poly.examples.fullreference.views

import ai.poly.examples.fullreference.views.databinding.ActivityRootBinding
import ai.poly.examples.fullreference.views.databinding.ScreenChatBinding
import ai.poly.examples.fullreference.views.databinding.ScreenConnectBinding
import ai.poly.examples.fullreference.views.databinding.ScreenErrorBinding
import ai.poly.messaging.ChatSession
import ai.poly.messaging.ConnectionStatus
import ai.poly.messaging.MessagingEvent
import ai.poly.messaging.POLY_MESSAGING_VERSION
import ai.poly.messaging.PolyMessaging
import ai.poly.messaging.SessionStatus
import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Rung 06 (Views) — the full production reference. A container that owns the single [ChatSession]
 * and swaps the connect / loading / chat / error screens, with lifecycle transitions driven off the
 * client's events / connectionStatus / sessionState streams. The back chevron pauses back to
 * connect WITHOUT ending the session; the ✕ ends it for good.
 */
class RootActivity : ComponentActivity() {

    private enum class Screen { CONNECT, LOADING, CHAT, ERROR }

    private lateinit var binding: ActivityRootBinding
    private var session: ChatSession? = null
    private var wasResumed = false
    private var screen = Screen.CONNECT
    private var chatController: ChatScreenController? = null

    /** Tied to the current client (not the ChatSession) so they survive an in-place start-new. */
    private val lifecycleJobs = mutableListOf<Job>()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRootBinding.inflate(layoutInflater)
        setContentView(binding.root)
        actionBar?.title = "PolyMessaging"
        actionBar?.setHomeAsUpIndicator(R.drawable.ic_chevron_left)

        // Edge-to-edge (targetSdk 35+): the decor action bar overlays the content — pad the top
        // with the dispatched status-bar inset (the decor folds the action bar into it), and lift
        // the bottom above the gesture nav bar (closed) / keyboard (open).
        ViewCompat.setOnApplyWindowInsetsListener(binding.container) { v, insets ->
            val bottom = maxOf(
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
            )
            v.updatePadding(
                top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top,
                bottom = bottom,
            )
            insets
        }

        showConnect()
    }

    // ---- Screen transitions ----

    private fun transition(view: View) {
        chatController?.stop()
        chatController = null
        binding.container.removeAllViews()
        binding.container.addView(view)
        invalidateOptionsMenu()
        actionBar?.setDisplayHomeAsUpEnabled(screen != Screen.CONNECT)
    }

    private fun showConnect() {
        screen = Screen.CONNECT
        val b = ScreenConnectBinding.inflate(LayoutInflater.from(this), binding.container, false)
        val hasActiveSession = session != null
        val canResume = PolyMessaging.hasResumableSession()
        val primaryShowsResume = hasActiveSession || canResume
        b.primaryAction.text = if (primaryShowsResume) "Resume Chat" else "Start Chat"
        b.primaryAction.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (primaryShowsResume) R.drawable.ic_resume else R.drawable.ic_bolt, 0, 0, 0,
        )
        b.primaryAction.compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
        styleProminent(b.primaryAction)
        b.primaryAction.setOnClickListener { configureAndStart(forceFresh = false) }
        b.startNewAction.visibility = if (primaryShowsResume) View.VISIBLE else View.GONE
        b.startNewAction.backgroundTintList = ColorStateList.valueOf(Palette.systemGray5)
        b.startNewAction.setTextColor(Palette.systemBlue)
        b.startNewAction.isAllCaps = false
        b.startNewAction.setOnClickListener { configureAndStart(forceFresh = true) }
        if (hasActiveSession) {
            b.statusCaption.text = "Your conversation is still active"
            b.statusCaption.visibility = View.VISIBLE
        } else if (canResume) {
            b.statusCaption.text = "A previous conversation is available to resume"
            b.statusCaption.visibility = View.VISIBLE
        }
        b.versionLabel.text = "PolyMessaging Android SDK v$POLY_MESSAGING_VERSION"
        transition(b.root)
    }

    private fun showLoading() {
        screen = Screen.LOADING
        transition(LayoutInflater.from(this).inflate(R.layout.screen_loading, binding.container, false))
    }

    private fun showChat() {
        val s = session ?: return
        if (screen == Screen.CHAT) return
        screen = Screen.CHAT
        val b = ScreenChatBinding.inflate(LayoutInflater.from(this), binding.container, false)
        requestNotificationPermissionIfNeeded()
        val controller = ChatScreenController(this, b, s, wasResumed)
        transition(b.root)
        chatController = controller // transition() clears it — set after
        controller.start()
    }

    private fun showError(message: String) {
        screen = Screen.ERROR
        val b = ScreenErrorBinding.inflate(LayoutInflater.from(this), binding.container, false)
        b.errorMessage.text = message
        styleProminent(b.goBack)
        b.goBack.setOnClickListener { showConnect() }
        transition(b.root)
    }

    private fun styleProminent(button: android.widget.Button) {
        button.backgroundTintList = ColorStateList.valueOf(Palette.systemBlue)
        button.setTextColor(Color.WHITE)
        button.isAllCaps = false
    }

    // ---- Nav actions ----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_END, 0, "End Conversation")
            .setIcon(R.drawable.ic_close_circle)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(MENU_END)?.isVisible = screen != Screen.CONNECT
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { showConnect(); true } // pause back WITHOUT ending
        MENU_END -> { confirmEnd(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun confirmEnd() {
        AlertDialog.Builder(this)
            .setTitle("End Conversation")
            .setMessage("This will permanently end the current conversation. You won't be able to resume it.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("End Conversation") { _, _ -> endConversation() }
            .show()
    }

    private fun endConversation() {
        val pending = session
        lifecycleScope.launch {
            runCatching { pending?.end() }
            session = null
            wasResumed = false
            showConnect()
        }
    }

    // ---- SDK lifecycle ----

    private fun configureAndStart(forceFresh: Boolean) {
        val existing = session
        if (existing != null) {
            if (forceFresh) {
                // Reuse the live client; spin up a fresh ChatSession and ask the server for a
                // brand-new conversation. The persistent lifecycle subscriptions (same client)
                // flip us back to chat.
                wasResumed = false
                session = ChatSession(existing.client)
                showLoading()
                lifecycleScope.launch {
                    runCatching { existing.client.startNewSession() }
                        .onFailure { showError("Couldn't start a new session.\n$it") }
                }
            } else {
                showChat()
            }
            return
        }

        // The connection config was set once in FullReferenceApplication via initialize(...);
        // the no-arg facade reuses it. Resume vs start-fresh is the only difference here.
        val s = if (forceFresh) PolyMessaging.start() else PolyMessaging.chat()
        session = s
        wasResumed = false
        showLoading()
        subscribeLifecycle(s.client)
    }

    private fun subscribeLifecycle(client: ai.poly.messaging.PolyMessagingClient) {
        lifecycleJobs.forEach { it.cancel() }
        lifecycleJobs.clear()

        lifecycleJobs += lifecycleScope.launch {
            client.events.collect { event ->
                if (event is MessagingEvent.SessionStart && screen == Screen.LOADING) showChat()
                if (event is MessagingEvent.Disconnected && screen == Screen.LOADING) {
                    event.error?.let { showError("Couldn't connect.\n${it.debugDescription}") }
                }
            }
        }
        lifecycleJobs += lifecycleScope.launch {
            client.connectionStatus.collect { status ->
                if (status is ConnectionStatus.Failed && screen == Screen.LOADING) {
                    val message = status.reason?.debugDescription ?: "Unknown failure"
                    showError("Connection failed.\n$message")
                }
            }
        }
        lifecycleJobs += lifecycleScope.launch {
            client.sessionState.collect { state ->
                if (state.status == SessionStatus.RESTORED) wasResumed = true
                if (state.isReady && (screen == Screen.LOADING || screen == Screen.ERROR)) showChat()
                if (state.isError && screen == Screen.LOADING) {
                    showError(state.errorMessage ?: "Couldn't start the session.")
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        private const val MENU_END = 1
    }
}
