package home.wyoming_android_tts

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class WyomingTtsApp : Application() {

    companion object {
        const val CHANNEL_ID = "WyomingTtsServiceChannel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Wyoming TTS Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}
