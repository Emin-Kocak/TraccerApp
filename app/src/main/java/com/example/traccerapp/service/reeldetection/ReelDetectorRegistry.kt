package com.example.traccerapp.service.reeldetection

/** Paket adı → o platformun ReelDetector'ı. TikTok bilinçli olarak dahil değil (bkz. spec). */
object ReelDetectorRegistry {
    private val DETECTORS: Map<String, ReelDetector> = mapOf(
        "com.instagram.android" to InstagramReelDetector,
        "com.instagram.lite" to InstagramReelDetector,
        "com.google.android.youtube" to YouTubeShortsDetector
    )

    fun detectorFor(packageName: String): ReelDetector? = DETECTORS[packageName]
}
