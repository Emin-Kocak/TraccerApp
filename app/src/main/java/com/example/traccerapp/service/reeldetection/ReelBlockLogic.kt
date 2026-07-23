package com.example.traccerapp.service.reeldetection

/** BUDGET modunda: birikmiş süre (ms), dakika cinsinden günlük bütçeyi aştı mı. */
fun hasExceededReelBudget(accumulatedMs: Long, budgetMinutes: Int): Boolean =
    (accumulatedMs / 60_000L) >= budgetMinutes
