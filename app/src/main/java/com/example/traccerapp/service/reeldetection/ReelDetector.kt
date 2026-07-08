package com.example.traccerapp.service.reeldetection

import android.view.accessibility.AccessibilityNodeInfo

/** Bir platformun Reels/Shorts video oynatıcısının ekranda olup olmadığını tespit eder. */
interface ReelDetector {
    /** root'u recycle ETMEZ — çağıran taraf (AppAccessibilityService) sorumlu. */
    fun isReelContent(root: AccessibilityNodeInfo): Boolean
}
