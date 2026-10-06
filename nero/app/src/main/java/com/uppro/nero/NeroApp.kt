package com.uppro.nero

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.uppro.nero.data.Store

class NeroApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        createChannels(this)
    }

    companion object {
        const val CH_SERVICE = "nero_service"
        const val CH_REMINDER = "nero_reminder"

        fun createChannels(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH_SERVICE, "Nero ativo", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Aviso fixo enquanto a barra do Nero ou o modo notebook estão ligados."
                    setShowBadge(false)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_REMINDER, "Lembretes e timers", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Avisos de lembretes, compromissos e fim do timer."
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 220, 120, 220, 120, 380)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(),
                    )
                },
            )
        }
    }
}
