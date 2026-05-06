package com.example.traccerapp.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

object AppIconUtils {

    // Kesinlikle görmezden gelinecek sistem paketleri
    private val SYSTEM_PACKAGE_BLACKLIST = setOf(
        "android",
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher2",
        "com.android.launcher3",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.gms.persistent",
        "com.android.phone",
        "com.android.settings",
        "com.android.vending",
        "com.sec.android.app.shealth",
        "com.samsung.android.health",
        "com.samsung.android.mobileservice",
        "com.sec.android.daemonapp",
        "com.samsung.android.app.omcagent",
        "com.samsung.android.sm",
        "com.samsung.android.sm.devicesecurity",
        "com.samsung.android.lool",
        "com.samsung.android.incallui",
        "com.samsung.android.dialer",
        "com.android.dialer",
        "com.android.contacts",
        "com.android.providers.contacts",
        "com.android.providers.media",
        "com.android.providers.downloads",
        "com.android.externalstorage",
        "com.android.packageinstaller",
        "com.android.server.telecom",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.keychain",
        "com.android.shell",
        "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.sec.android.inputmethod",
        "com.android.wallpaper",
        "com.android.wallpaper.livepicker",
        "com.samsung.android.wallpaper.res",
        "com.android.certinstaller",
        "com.android.camera",
        "com.android.camera2",
        "com.sec.android.app.camera",
        "com.android.deskclock",
        "com.android.calendar",
        "com.android.calculator2",
        "com.samsung.android.app.notes",
        "com.mobilepay",
        "com.sec.android.easyMover",
        "com.samsung.android.messaging",
        "com.android.mms",
        "com.google.android.markup",
        "com.google.android.as",
        "com.google.android.as.oss",
        "com.google.android.gmsintegration",
        "com.google.android.partnersetup",
        "com.google.android.setupwizard",
        "com.android.managedprovisioning",
        "com.android.systemui.plugins",
        "com.samsung.systemui.bixby2",
        "com.samsung.android.bixby.agent",
        "com.sec.android.app.bixby",
        "com.samsung.android.brightnessbackup",
        "com.samsung.android.app.cocktailbarservice",
        "com.samsung.android.forest",
        "com.samsung.android.service.peoplestripe",
        "com.samsung.android.spay",
        "com.samsung.android.samsungpay",
    )

    fun shouldTrack(context: Context, packageName: String): Boolean {
        // Blacklist kontrolü
        if (SYSTEM_PACKAGE_BLACKLIST.contains(packageName)) return false

        // Kendi uygulamamızı atla
        if (packageName == context.packageName) return false

        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)

            // Sistem uygulaması mı?
            val isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystemApp = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

            // Güncellenmiş sistem uygulamaları (WhatsApp gibi sonradan güncellenenler) gösterilsin
            // Ama saf sistem uygulamaları gösterilmesin
            if (isSystemApp && !isUpdatedSystemApp) return false

            // Launcher aktivitesi yoksa (arka plan servisi gibi) atla
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            launchIntent != null

        } catch (e: Exception) {
            false
        }
    }

    fun getAppName(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            // GET_META_DATA flag'i Instagram gibi uygulamaların doğru isim döndürmesini sağlar
            val info = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast(".")
                .replaceFirstChar { it.uppercase() }
        }
    }

    fun getAppIcon(context: Context, packageName: String): Drawable? {
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            null
        }
    }
}