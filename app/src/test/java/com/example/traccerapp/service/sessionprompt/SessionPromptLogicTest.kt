package com.example.traccerapp.service.sessionprompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPromptLogicTest {

    @Test
    fun `hic oturum baslamamissa sorar`() {
        assertTrue(shouldPromptForSession(remainingMs = null, lastExitMs = null, nowMs = 1_000L))
    }

    @Test
    fun `kalan sure sifir veya altiysa sorar`() {
        assertTrue(shouldPromptForSession(remainingMs = 0L, lastExitMs = 500L, nowMs = 1_000L))
        assertTrue(shouldPromptForSession(remainingMs = -1L, lastExitMs = 500L, nowMs = 1_000L))
    }

    @Test
    fun `aktif oturumda hic cikis yoksa sormaz`() {
        assertFalse(shouldPromptForSession(remainingMs = 60_000L, lastExitMs = null, nowMs = 1_000L))
    }

    @Test
    fun `grace penceresi icinde donuste sormaz`() {
        // Tam sınırda (== 5 dk) hâlâ grace içinde
        assertFalse(shouldPromptForSession(remainingMs = 60_000L, lastExitMs = 0L, nowMs = SESSION_GRACE_MS))
    }

    @Test
    fun `grace penceresi asilirsa sorar`() {
        assertTrue(shouldPromptForSession(remainingMs = 60_000L, lastExitMs = 0L, nowMs = SESSION_GRACE_MS + 1))
    }

    @Test
    fun `butce dusumu gecen sureyi dusurur`() {
        assertEquals(40_000L, deductSessionBudget(remainingMs = 60_000L, elapsedMs = 20_000L))
    }

    @Test
    fun `negatif elapsed butceyi arttirmaz`() {
        assertEquals(60_000L, deductSessionBudget(remainingMs = 60_000L, elapsedMs = -5_000L))
    }

    @Test
    fun `butce negatife dusebilir ve expired sayilir`() {
        val remaining = deductSessionBudget(remainingMs = 10_000L, elapsedMs = 25_000L)
        assertTrue(isSessionExpired(remaining))
        assertFalse(isSessionExpired(1L))
        assertTrue(isSessionExpired(0L))
    }

    @Test
    fun `dakika secimi sinirlara kirpilir`() {
        assertEquals(MIN_SESSION_MINUTES, clampSessionMinutes(1))
        assertEquals(MAX_SESSION_MINUTES, clampSessionMinutes(999))
        assertEquals(25, clampSessionMinutes(25))
    }
}
