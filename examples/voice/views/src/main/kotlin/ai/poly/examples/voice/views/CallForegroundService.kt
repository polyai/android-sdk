// Copyright PolyAI Limited

package ai.poly.examples.voice.views

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps the process foregrounded — with a **microphone**-type foreground service — while a call is
 * live, so Android doesn't cut background mic access or throttle the connection when the app is
 * backgrounded. Without it, the OS tears the WebRTC peer down a few seconds after you leave the app
 * and the SDK reports `CallState.Failed(PolyError.Voice.Disconnected)`.
 *
 * This belongs in the **app**, not the headless SDK (the SDK has no notification UI to show). Start it
 * right before `call.start()` and [stop] it when the call ends.
 */
class CallForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keep the CPU running for the WebRTC media/network threads while backgrounded — without it the
        // OS throttles them, RTP stops, and the gateway times the call out even though the mic is alive.
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "poly:voice-call")
                .apply { setReferenceCounted(false); acquire(60 * 60 * 1000L /* 1h safety cap */) }
        }
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PolyAI voice call")
            .setContentText("Call in progress")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Voice calls", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "poly_voice_call"
        private const val NOTIFICATION_ID = 1001

        /** Start the service (call from a foreground Activity, right before `call.start()`). */
        fun start(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        /** Stop the service when the call ends. Safe to call when it isn't running. */
        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
