package com.example.traccerapp.service.reeldetection

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Instagram Reels video oynatıcısını tespit eder.
 *
 * v1 view-id ile arıyordu (clips_viewer_container vb.) ama cihaz teşhisinde Instagram'ın
 * gerçek Reels ekranında hiçbir node'da view-id çıkmadı (id sayısı=0) — Instagram Litho ile
 * render ediyor ve view'lara accessibility id atamıyor. Tek kalan sinyal, oynatıcı node'un
 * TalkBack açıklaması (contentDescription), örn. "kullanıcı'dan Reels videosu. Oynatmak veya
 * duraklatmak için çift dokun." Cihaz dili Türkçe varsayımı (uygulamanın geneli de tamamen
 * Türkçe, i18n yok) — cihaz dili değişirse bu metin de değişir.
 *
 * NOT: bare "Reels videosu" YETMEZ — ana feed'deki "X bu Reels videosunu beğendi" önerilen kartları
 * da bu dizeyi içeriyor ve ana sayfadayken yanlışlıkla blok tetikliyordu (cihaz teşhisi). Gerçek
 * oynatıcıya özgü ". Oynatmak" eki ile daraltıldı: feed kartı "videosunu beğendi" der, ". Oynatmak"
 * içermez; normal (reel olmayan) feed videosunda ise "Reels videosu" öneki bulunmaz.
 */
object InstagramReelDetector : ReelDetector {
    private val REEL_CONTENT_DESC_PARTS = listOf("Reels videosu. Oynatmak")

    override fun isReelContent(root: AccessibilityNodeInfo): Boolean =
        containsContentDescription(root, REEL_CONTENT_DESC_PARTS)
}
