package com.example.traccerapp.service.reeldetection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelBlockLogicTest {

    @Test
    fun `budget not exceeded when accumulated time below limit`() {
        assertFalse(hasExceededReelBudget(accumulatedMs = 10 * 60_000L, budgetMinutes = 15))
    }

    @Test
    fun `budget exceeded when accumulated time equals limit`() {
        assertTrue(hasExceededReelBudget(accumulatedMs = 15 * 60_000L, budgetMinutes = 15))
    }

    @Test
    fun `budget exceeded when accumulated time above limit`() {
        assertTrue(hasExceededReelBudget(accumulatedMs = 20 * 60_000L, budgetMinutes = 15))
    }

    @Test
    fun `zero accumulated time never exceeds positive budget`() {
        assertFalse(hasExceededReelBudget(accumulatedMs = 0L, budgetMinutes = 15))
    }

    @Test
    fun `suppressed when now is before suppress-until timestamp`() {
        val now = 1_000_000L
        assertTrue(isReelBlockSuppressed(suppressUntilMs = now + 60_000L, nowMs = now))
    }

    @Test
    fun `not suppressed when now is after suppress-until timestamp`() {
        val now = 1_000_000L
        assertFalse(isReelBlockSuppressed(suppressUntilMs = now - 1L, nowMs = now))
    }

    @Test
    fun `not suppressed when suppress-until is zero (never suppressed)`() {
        assertFalse(isReelBlockSuppressed(suppressUntilMs = 0L, nowMs = 1_000_000L))
    }
}
