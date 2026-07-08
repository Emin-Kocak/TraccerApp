package com.example.traccerapp.service.reeldetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReelDetectorRegistryTest {

    @Test
    fun `instagram package maps to InstagramReelDetector`() {
        assertEquals(InstagramReelDetector, ReelDetectorRegistry.detectorFor("com.instagram.android"))
    }

    @Test
    fun `instagram lite package maps to InstagramReelDetector`() {
        assertEquals(InstagramReelDetector, ReelDetectorRegistry.detectorFor("com.instagram.lite"))
    }

    @Test
    fun `youtube package maps to YouTubeShortsDetector`() {
        assertEquals(YouTubeShortsDetector, ReelDetectorRegistry.detectorFor("com.google.android.youtube"))
    }

    @Test
    fun `tiktok package has no detector`() {
        assertNull(ReelDetectorRegistry.detectorFor("com.zhiliaoapp.musically"))
    }

    @Test
    fun `unrelated package has no detector`() {
        assertNull(ReelDetectorRegistry.detectorFor("com.example.traccerapp"))
    }
}
