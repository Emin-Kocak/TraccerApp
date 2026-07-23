package com.example.traccerapp.service.sessionprompt

/** Kısa çıkış toleransı: bu süre içinde geri dönülürse oturum devam eder, soru gelmez. */
const val SESSION_GRACE_MS = 5 * 60_000L
const val MIN_SESSION_MINUTES = 5
const val MAX_SESSION_MINUTES = 180
val SESSION_PRESET_MINUTES = listOf(5, 10, 15, 30, 60)

/**
 * Mod açık uygulamaya girişte dakika sorusu gösterilmeli mi?
 * - remainingMs == null → hiç oturum başlamamış → sor.
 * - remainingMs <= 0   → önceki oturum bitmiş → sor (yeni oturum hakkı, blok değil).
 * - lastExitMs == null → uygulamadan hiç çıkılmamış (aktif oturum) → sorma.
 * - grace penceresi aşıldıysa → oturum düşmüş → sor.
 */
fun shouldPromptForSession(remainingMs: Long?, lastExitMs: Long?, nowMs: Long): Boolean {
    if (remainingMs == null) return true
    if (remainingMs <= 0L) return true
    val lastExit = lastExitMs ?: return false
    return nowMs - lastExit > SESSION_GRACE_MS
}

/** Uygulamada geçen süreyi bütçeden düşer. Negatif elapsed (saat oynaması) bütçeyi arttırmaz. */
fun deductSessionBudget(remainingMs: Long, elapsedMs: Long): Long =
    remainingMs - elapsedMs.coerceAtLeast(0L)

fun isSessionExpired(remainingMs: Long): Boolean = remainingMs <= 0L

fun clampSessionMinutes(minutes: Int): Int =
    minutes.coerceIn(MIN_SESSION_MINUTES, MAX_SESSION_MINUTES)
