package com.example.traccerapp.service.reeldetection

import android.view.accessibility.AccessibilityNodeInfo

/** YouTube Shorts oynatıcısının kendi iç view-id'lerini arar. */
object YouTubeShortsDetector : ReelDetector {
    private val SHORTS_VIEW_ID_PARTS = listOf(
        "reel_player_page_container",
        "reels_viewer_container",
        "shorts_main_container"
    )

    override fun isReelContent(root: AccessibilityNodeInfo): Boolean =
        containsViewId(root, SHORTS_VIEW_ID_PARTS)
}
