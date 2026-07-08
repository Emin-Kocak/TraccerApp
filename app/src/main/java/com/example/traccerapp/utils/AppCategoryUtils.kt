package com.example.traccerapp.utils

import android.content.Context
import android.content.pm.ApplicationInfo

enum class AppCategory(val label: String) {
    SOCIAL("Sosyal Medya"),
    GAME("Oyunlar"),
    VIDEO("Video/Medya"),
    COMMUNICATION("İletişim"),
    PRODUCTIVITY("Üretkenlik"),
    OTHER("Diğer")
}

object AppCategoryUtils {

    // Sistem kategorisi (ApplicationInfo.category) çoğu popüler uygulamada boş/tanımsız
    // döndüğü için, bilinen uygulamalar burada elle eşlenir. Sistem kategorisi yalnızca
    // bu listede olmayan paketler için yedek olarak kullanılır.
    private val KNOWN_PACKAGES: Map<String, AppCategory> = mapOf(
        // Sosyal Medya
        "com.instagram.android" to AppCategory.SOCIAL,
        "com.zhiliaoapp.musically" to AppCategory.SOCIAL,   // TikTok
        "com.ss.android.ugc.trill" to AppCategory.SOCIAL,   // TikTok (bazı bölgeler)
        "com.twitter.android" to AppCategory.SOCIAL,
        "com.facebook.katana" to AppCategory.SOCIAL,
        "com.facebook.lite" to AppCategory.SOCIAL,
        "com.snapchat.android" to AppCategory.SOCIAL,
        "com.pinterest" to AppCategory.SOCIAL,
        "com.linkedin.android" to AppCategory.SOCIAL,
        "com.reddit.frontpage" to AppCategory.SOCIAL,
        "com.zhiliao.musically.go" to AppCategory.SOCIAL,

        // Video / Medya
        "com.google.android.youtube" to AppCategory.VIDEO,
        "com.google.android.apps.youtube.music" to AppCategory.VIDEO,
        "com.netflix.mediaclient" to AppCategory.VIDEO,
        "com.spotify.music" to AppCategory.VIDEO,
        "com.disney.disneyplus" to AppCategory.VIDEO,
        "tv.twitch.android.app" to AppCategory.VIDEO,

        // İletişim
        "com.whatsapp" to AppCategory.COMMUNICATION,
        "com.whatsapp.w4b" to AppCategory.COMMUNICATION,
        "org.telegram.messenger" to AppCategory.COMMUNICATION,
        "com.facebook.orca" to AppCategory.COMMUNICATION,   // Messenger
        "com.google.android.gm" to AppCategory.COMMUNICATION,
        "com.google.android.apps.messaging" to AppCategory.COMMUNICATION,
        "com.microsoft.teams" to AppCategory.COMMUNICATION,
        "com.discord" to AppCategory.COMMUNICATION,
        "com.skype.raider" to AppCategory.COMMUNICATION,
        "com.Slack" to AppCategory.COMMUNICATION,

        // Üretkenlik
        "com.google.android.apps.docs" to AppCategory.PRODUCTIVITY,
        "com.google.android.apps.docs.editors.docs" to AppCategory.PRODUCTIVITY,
        "com.google.android.apps.docs.editors.sheets" to AppCategory.PRODUCTIVITY,
        "com.google.android.apps.docs.editors.slides" to AppCategory.PRODUCTIVITY,
        "com.google.android.calendar" to AppCategory.PRODUCTIVITY,
        "com.microsoft.office.word" to AppCategory.PRODUCTIVITY,
        "com.microsoft.office.excel" to AppCategory.PRODUCTIVITY,
        "com.microsoft.office.powerpoint" to AppCategory.PRODUCTIVITY,
        "com.microsoft.office.outlook" to AppCategory.PRODUCTIVITY,
        "notion.id" to AppCategory.PRODUCTIVITY,
        "com.todoist" to AppCategory.PRODUCTIVITY,
        "com.evernote" to AppCategory.PRODUCTIVITY,
        "com.trello" to AppCategory.PRODUCTIVITY,
        "com.asana.app" to AppCategory.PRODUCTIVITY,

        // Oyunlar (en yaygın olanlar; kalanı sistem kategorisine düşer)
        "com.tencent.ig" to AppCategory.GAME,               // PUBG Mobile
        "com.dts.freefireth" to AppCategory.GAME,           // Free Fire
        "com.supercell.clashofclans" to AppCategory.GAME,
        "com.supercell.clashroyale" to AppCategory.GAME,
        "com.king.candycrushsaga" to AppCategory.GAME,
        "com.roblox.client" to AppCategory.GAME,
        "com.mojang.minecraftpe" to AppCategory.GAME,
        "com.innersloth.spacemafia" to AppCategory.GAME,    // Among Us
        "com.miniclip.eightballpool" to AppCategory.GAME,
        "com.kiloo.subwaysurf" to AppCategory.GAME
    )

    /**
     * Bir paketin kategorisini belirler:
     * 1. Bilinen uygulamalar listesinde ara
     * 2. Yoksa Android'in sistem kategorisine (ApplicationInfo.category) bak
     * 3. O da tanımsızsa Diğer
     */
    fun categorize(context: Context, packageName: String): AppCategory {
        KNOWN_PACKAGES[packageName]?.let { return it }

        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            fromSystemCategory(info.category)
        } catch (_: Exception) {
            AppCategory.OTHER
        }
    }

    private fun fromSystemCategory(category: Int): AppCategory = when (category) {
        ApplicationInfo.CATEGORY_GAME -> AppCategory.GAME
        ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.SOCIAL
        ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_AUDIO -> AppCategory.VIDEO
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppCategory.PRODUCTIVITY
        else -> AppCategory.OTHER
    }
}
