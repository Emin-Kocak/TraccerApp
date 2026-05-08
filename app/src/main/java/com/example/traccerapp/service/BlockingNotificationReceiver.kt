package com.example.traccerapp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.traccerapp.BlockingActivity

class BlockingNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == "FORCE_STOP_APP" && context != null) {
            val packageName = intent.getStringExtra("packageName") ?: return

            try {
                Log.d("BlockingReceiver", "Zorla kapatma yerine Engelleme Ekranı tetikleniyor: $packageName")

                // Uygulamayı force stop yapmak yerine senin yazdığın Engelleme ekranını fırlatıyoruz!
                val blockingIntent = Intent(context, BlockingActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("packageName", packageName)
                    // Eğer intent'ten appName geliyorsa al, yoksa varsayılan yaz
                    putExtra("appName", intent.getStringExtra("appName") ?: "Uygulama")
                    putExtra("reason", "Süren doldu")
                }
                context.startActivity(blockingIntent)

            } catch (e: Exception) {
                Log.e("BlockingReceiver", "Engelleme ekranı açılamadı: $packageName", e)
            }
        }
    }
}