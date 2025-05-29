package home.wyoming_android_tts

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class WyomingTtsService : Service() {

    companion object {
        private const val TAG = "WyomingTtsService"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        // Initialization code for the service, e.g., setting up the server socket
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")

        // Promote to foreground service
        val notification: Notification = NotificationCompat.Builder(this, WyomingTtsApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title)) // Using string resource
            .setContentText(getString(R.string.notification_message)) // Using string resource
            .setSmallIcon(R.mipmap.ic_launcher) // Replace with your actual app icon
            .build()

        // For Android Q and above, need to specify foregroundServiceType in manifest
        // and call startForeground with the type if not done implicitly by manifest.
        // However, startForeground itself is the main call.
        startForeground(NOTIFICATION_ID, notification)

        // Start your server thread here
        // For now, just log that it would start
        Log.i(TAG, "Wyoming TTS Server would start listening here.")

        // If the service is killed, restart it
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        // Cleanup code, e.g., stopping the server socket, releasing resources
        stopForeground(STOP_FOREGROUND_REMOVE) // Use STOP_FOREGROUND_REMOVE to remove notification
    }

    override fun onBind(intent: Intent?): IBinder? {
        // This service is not intended to be bound by external components in the typical sense,
        // as it communicates over a raw socket.
        Log.d(TAG, "Service onBind")
        return null
    }
}
