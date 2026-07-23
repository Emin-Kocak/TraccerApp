package com.example.traccerapp.service.reeldetection

import android.view.accessibility.AccessibilityNodeInfo

/**
 * YouTube Shorts video oynatıcısını tespit eder.
 *
 * v1 view-id ile arıyordu (reel_player_page_container vb.) ama cihaz teşhisinde YouTube'un
 * gerçek Shorts ekranında hiçbir node'da view-id çıkmadı (id sayısı=0) — Instagram'daki gibi
 * (bkz. InstagramReelDetector) view-id kullanılamıyor. Tek kalan sinyal, Shorts'a özgü ses/remix
 * özelliğinin TalkBack açıklaması: "Bu sesi kullanan daha fazla video izleyin" — bu metin normal
 * YouTube izleme ekranında (Ana Sayfa'daki videolarda) çıkmıyor, yalnızca dikey Shorts
 * oynatıcısında. Cihaz dili Türkçe varsayımı (uygulamanın geneli de tamamen Türkçe, i18n yok).
 */
object YouTubeShortsDetector : ReelDetector {
    private val SHORTS_CONTENT_DESC_PARTS = listOf("Bu sesi kullanan daha fazla video izleyin")

    override fun isReelContent(root: AccessibilityNodeInfo): Boolean =
        containsContentDescription(root, SHORTS_CONTENT_DESC_PARTS)
}
