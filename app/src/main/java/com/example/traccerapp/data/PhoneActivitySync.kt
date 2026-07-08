package com.example.traccerapp.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log

/**
 * Telefon açılışı (unlock) ve kullanım oturumu (pickup→hangup) verisinin TEK yazarı.
 *
 * Veriyi OS'in zaten 7/24 tuttuğu kayıtlardan türetir (UsageStatsManager.queryEvents):
 *  - KEYGUARD_HIDDEN        → unlock (eski ACTION_USER_PRESENT karşılığı)
 *  - SCREEN_NON_INTERACTIVE → açık oturumu kapatır (eski ACTION_SCREEN_OFF karşılığı)
 *
 * Bu sayede uygulama/servis hiç çalışmıyorken bile (hatta kurulumdan önce!) OS'in
 * ~7 günlük penceresi kadar geçmiş veri gelir. 7 günden eski veri Room'da donuk
 * arşiv olarak kalır — sync yalnızca kendi kapsama penceresini değiştirir.
 *
 * Erişilebilirlik izni GEREKMEZ; yalnızca zaten istenen Kullanım Erişimi izni yeter.
 * İzin yoksa queryEvents boş döner → sync sessizce no-op (DB'ye dokunulmaz).
 */
object PhoneActivitySync {
    private const val TAG = "PhoneActivitySync"

    /** OS genelde ~7 gün tutar; pencereyi bir gün payla sorgula. */
    private const val QUERY_WINDOW_MS = 8L * 24 * 60 * 60 * 1000

    /** 1 sn'den kısa "oturumlar" gürültü sayılır (madde 18'deki eşikle aynı). */
    private const val MIN_SESSION_MS = 1_000L

    data class Derived(
        val unlocks: List<UnlockEvent>,
        val sessions: List<PhoneSession>,
        /** Silme/yazma penceresinin başı: OS'ten görülen ilk ilgili event. */
        val coverageStartMs: Long,
        /** Penceresinin sonu: sorgu anı (devam eden oturum yazılmaz, sonraki sync tamamlar). */
        val coverageEndMs: Long
    )

    /** OS event akışından unlock + oturum türetir. Hiç ilgili event yoksa null (izin yok / OEM vermiyor). */
    fun deriveFromOs(context: Context, nowMs: Long = System.currentTimeMillis()): Derived? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = try {
            usm.queryEvents(nowMs - QUERY_WINDOW_MS, nowMs)
        } catch (e: Exception) {
            Log.e(TAG, "queryEvents hatası", e)
            return null
        } ?: return null

        val unlocks = mutableListOf<UnlockEvent>()
        val sessions = mutableListOf<PhoneSession>()
        var sessionStart = -1L
        var firstRelevantMs = -1L
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.KEYGUARD_HIDDEN -> {
                    unlocks += UnlockEvent(timestampMs = event.timeStamp)
                    // Oturum zaten açıksa (ör. kilit ekranı ara katmanı tekrar gizlendi) başlangıcı koru
                    if (sessionStart < 0) sessionStart = event.timeStamp
                    if (firstRelevantMs < 0) firstRelevantMs = event.timeStamp
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    if (sessionStart >= 0) {
                        if (event.timeStamp - sessionStart >= MIN_SESSION_MS) {
                            sessions += PhoneSession(startMs = sessionStart, endMs = event.timeStamp)
                        }
                        sessionStart = -1L
                    }
                    if (firstRelevantMs < 0) firstRelevantMs = event.timeStamp
                }
            }
        }

        if (firstRelevantMs < 0) return null
        return Derived(unlocks, sessions, firstRelevantMs, nowMs)
    }

    /**
     * Türet + kapsama penceresindeki eski satırları OS versiyonuyla değiştir (tek transaction).
     * İdempotent: aynı pencerede tekrar çağrılması sonucu değiştirmez.
     */
    suspend fun sync(context: Context, dao: AppUsageDao, nowMs: Long = System.currentTimeMillis()) {
        val derived = deriveFromOs(context, nowMs) ?: run {
            Log.d(TAG, "OS event verisi yok — sync atlandı (izin verilmemiş olabilir)")
            return
        }
        try {
            dao.replacePhoneActivityWindow(
                windowStartMs = derived.coverageStartMs,
                windowEndMs = derived.coverageEndMs,
                sessions = derived.sessions,
                unlocks = derived.unlocks
            )
            Log.d(TAG, "✅ OS sync: ${derived.unlocks.size} unlock, ${derived.sessions.size} oturum yazıldı")
        } catch (e: Exception) {
            Log.e(TAG, "Sync DB yazma hatası", e)
        }
    }
}
