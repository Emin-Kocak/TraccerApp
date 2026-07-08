package com.example.traccerapp.service.reeldetection

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Instagram Reels oynatıcısının kendi iç view-id'lerini arar (Instagram'ın kod isimleri,
 * metin/dilden bağımsız). Tab-seçili kontrolü veya kelime doğrulama katmanı YOK — v1
 * bilinçli olarak sade tutuldu, yanlış-pozitif görülürse sonradan eklenir (bkz. spec).
 */
object InstagramReelDetector : ReelDetector {
    private val REEL_VIEW_ID_PARTS = listOf(
        "clips_viewer_container",
        "clips_video_container",
        "clips_video_view_pager"
    )

    override fun isReelContent(root: AccessibilityNodeInfo): Boolean =
        containsViewId(root, REEL_VIEW_ID_PARTS)
}
