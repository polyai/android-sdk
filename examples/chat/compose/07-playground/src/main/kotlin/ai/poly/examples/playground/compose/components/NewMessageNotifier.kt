// Copyright PolyAI Limited

package ai.poly.examples.playground.compose.components

import ai.poly.messaging.ChatSession
import ai.poly.messaging.MessagingEvent
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/**
 * Controls when [NewMessageNotifier] raises a local banner for a new agent message. Flip this at the
 * call site to suit your app.
 */
enum class NotificationPolicy {
    /** Only while the chat isn't on screen (default) — no banner while you're reading the conversation. */
    WHEN_BACKGROUNDED,

    /** On every new agent message, even while the chat is open. */
    ALWAYS,

    /** Never post a banner. */
    NEVER,
}

/**
 * Posts a local-notification banner with the full agent reply on each new (completed) message.
 * ⚠️ Local-notification workaround, not remote push —
 * there's no FCM here, so once the OS kills the process nothing arrives.
 *
 * [policy] decides *when* it fires: [NotificationPolicy.WHEN_BACKGROUNDED] (the default) stays quiet
 * while you're actually looking at the chat and only banners when it's off screen;
 * [NotificationPolicy.ALWAYS] banners every time; [NotificationPolicy.NEVER] is off. Collection is
 * gated on the CREATED lifecycle (not STARTED) so a reply that lands while the chat is backgrounded
 * can still raise a banner. Dedupes on the server `messageId` (persisted) so resume/relaunch replays
 * don't re-notify.
 *
 * Drop it into the chat screen: `NewMessageNotifier(session)`.
 */
@Composable
fun NewMessageNotifier(session: ChatSession, policy: NotificationPolicy = NotificationPolicy.WHEN_BACKGROUNDED) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val store = remember { NotifiedMessageStore(context) }

    LaunchedEffect(policy) { if (policy != NotificationPolicy.NEVER) ensureChannel(context) }

    // Request POST_NOTIFICATIONS on API 33+ (silently no-ops on older versions / if denied).
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(policy) {
        if (policy != NotificationPolicy.NEVER &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // events is a multicast SharedFlow, so observing it doesn't disturb the chat UI.
    LaunchedEffect(session, policy) {
        if (policy == NotificationPolicy.NEVER) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
            session.client.events.collect { event ->
                // Completed messages only — full text + stable messageId (chunks ignored).
                val message: Triple<String, String, String>? = when (event) {
                    is MessagingEvent.AgentMessage ->
                        Triple(event.payload.messageId, event.payload.agentName ?: "New message", event.payload.text)
                    is MessagingEvent.LiveAgentMessage ->
                        Triple(event.payload.messageId, event.payload.agentName ?: "New message", event.payload.text)
                    else -> null
                }
                if (message == null) return@collect
                val (id, title, body) = message
                if (store.contains(id)) return@collect // skip replays
                // WHEN_BACKGROUNDED stays quiet whenever the chat is VISIBLE (STARTED or RESUMED) — not just
                // focused — so a reply that lands while the shade is down, a dialog is up, or the app is in
                // split-screen never banners over a chat you are looking at. You are already reading it. Either way mark it handled so a later replay can't re-notify.
                val onScreen = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (policy == NotificationPolicy.ALWAYS || !onScreen) post(context, id, title, body)
                store.markShown(id)
            }
        }
    }
}

private const val CHANNEL_ID = "poly.newMessages"

private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(CHANNEL_ID, "New messages", NotificationManager.IMPORTANCE_HIGH)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

private fun post(context: Context, id: String, title: String, body: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return // not granted — nothing to show
    }
    // Tapping the banner brings the app (existing task) back to the foreground.
    val contentIntent = PendingIntent.getActivity(
        context,
        0,
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setContentIntent(contentIntent)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
}

/**
 * Persisted, bounded set of already-notified `messageId`s — survives relaunch so the SDK's
 * replay-on-resume doesn't re-fire old banners.
 */
private class NotifiedMessageStore(context: Context) {
    private val prefs = context.getSharedPreferences("poly.notifier", Context.MODE_PRIVATE)
    private val ids = ArrayDeque(prefs.getString(KEY, "")!!.split("\n").filter { it.isNotEmpty() })
    private val seen = HashSet(ids)

    fun contains(id: String): Boolean = seen.contains(id)

    fun markShown(id: String) {
        if (!seen.add(id)) return
        ids.addLast(id)
        while (ids.size > CAP) {
            val removed = ids.removeFirst()
            seen.remove(removed)
        }
        prefs.edit().putString(KEY, ids.joinToString("\n")).apply()
    }

    companion object {
        private const val KEY = "notifiedMessageIds"
        private const val CAP = 500
    }
}
