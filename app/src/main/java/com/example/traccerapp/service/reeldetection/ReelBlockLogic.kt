package com.example.traccerapp.service.reeldetection

/** BUDGET modunda: birikmiş süre (ms), dakika cinsinden günlük bütçeyi aştı mı. */
fun hasExceededReelBudget(accumulatedMs: Long, budgetMinutes: Int): Boolean =
    (accumulatedMs / 60_000L) >= budgetMinutes

/** "1 saat izin ver" penceresi içinde mi (suppressUntilMs, now'dan büyükse bloklanmaz). */
fun isReelBlockSuppressed(suppressUntilMs: Long, nowMs: Long): Boolean =
    nowMs < suppressUntilMs
