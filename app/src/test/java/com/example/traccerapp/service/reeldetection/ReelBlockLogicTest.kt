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
    fun `budget not exceeded one millisecond below limit (minute truncation)`() {
        assertFalse(hasExceededReelBudget(accumulatedMs = 15 * 60_000L - 1, budgetMinutes = 15))
    }
}
