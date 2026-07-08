# Sonsuz Kaydırma (Reels/Shorts) Engelleme Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Instagram Reels ve YouTube Shorts'ta, kullanıcının platform başına seçtiği modda (anında blok veya günlük dakika bütçesi) otomatik engelleme yapmak — mevcut AppLimit mekanizmasından bağımsız, Instagram/YouTube'un diğer bölümlerini (Home/DM/Profil/arama vb.) etkilemeden.

**Architecture:** Yeni `service/reeldetection/` paketi, `AccessibilityNodeInfo` ağacında Instagram/YouTube'un kendi iç view-id'lerini arayan sade bir tespit katmanı sağlar (rakip bir uygulamanın tekniğinden ilham alındı, kodu kopyalanmadı — bkz. spec). `AppAccessibilityService`, mevcut `TYPE_WINDOW_STATE_CHANGED` akışının yanına `TYPE_WINDOW_CONTENT_CHANGED` dinleyicisi ekler, tespit sonucuna göre mevcut overlay altyapısını (`showBlockingOverlay`) yeni bir "1 saat izin ver" seçeneğiyle genişleterek kullanır. Ayarlar `UserPreferences` (SharedPreferences) üzerinden platform başına saklanır, `BlockingSettingsScreen`'e yeni bir bölüm eklenir. Şema/migration değişikliği yok.

**Tech Stack:** Kotlin, Jetpack Compose, AccessibilityService, SharedPreferences (UserPreferences), JUnit4 (yalnızca Android framework'e bağımlı olmayan saf mantık için — bu projede otomatik test altyapısı/Robolectric yok; `AccessibilityNodeInfo`'ya bağımlı kod mevcut proje konvansiyonuna göre derleme kontrolü + cihazda manuel test ile doğrulanır).

**Not (test kapsamı):** Bu projede şu ana kadar hiç unit test yok, doğrulama `./gradlew :app:compileDebugKotlin` + kullanıcının kendi cihazında manuel testiyle yapılıyor (CLAUDE.md "Doğrulama notu"). Bu planda **saf Kotlin mantığı** (paket-adı eşlemesi, bütçe/susturma hesapları) için gerçek TDD (JUnit, zaten `testImplementation(libs.junit)` bağımlılığı mevcut, yeni bağımlılık eklenmiyor) uygulanıyor. `AccessibilityNodeInfo` ağacı gezen kod (node arama, iki detector) Android framework'e sıkı bağımlı olduğu için (Robolectric bu projede yok, spec kapsamı dışı) otomatik test yazılmıyor — derleme kontrolü + Task 8'deki cihaz doğrulama checklist'iyle doğrulanıyor. Bu, mevcut kod tabanının (`AppAccessibilityService.kt` içindeki `dumpNode`/`shouldIgnorePackage` gibi) hiç test edilmeyen node-tree kodlarıyla aynı konvansiyon.

---

## Task 1: Tespit arayüzü + node arama yardımcı fonksiyonu + iki platform detector'ı

**Files:**
- Create: `app/src/main/java/com/example/traccerapp/service/reeldetection/ReelDetector.kt`
- Create: `app/src/main/java/com/example/traccerapp/service/reeldetection/NodeSearch.kt`
- Create: `app/src/main/java/com/example/traccerapp/service/reeldetection/InstagramReelDetector.kt`
- Create: `app/src/main/java/com/example/traccerapp/service/reeldetection/YouTubeShortsDetector.kt`

Bu dosyalar `AccessibilityNodeInfo`'ya bağımlı — proje konvansiyonuna göre otomatik test yok (yukarıdaki nota bkz.), yalnızca derleme kontrolü.

- [ ] **Step 1: `ReelDetector.kt` arayüzünü yaz**

```kotlin
package com.example.traccerapp.service.reeldetection

import android.view.accessibility.AccessibilityNodeInfo

/** Bir platformun Reels/Shorts video oynatıcısının ekranda olup olmadığını tespit eder. */
interface ReelDetector {
    /** root'u recycle ETMEZ — çağıran taraf (AppAccessibilityService) sorumlu. */
    fun isReelContent(root: AccessibilityNodeInfo): Boolean
}
```

- [ ] **Step 2: `NodeSearch.kt` — recursive view-id arama yardımcısını yaz**

```kotlin
package com.example.traccerapp.service.reeldetection

import android.view.accessibility.AccessibilityNodeInfo

/**
 * root altındaki node ağacında viewIdResourceName'i idParts listesinden herhangi
 * birini İÇEREN bir node var mı arar (tam eşleşme değil, contains — Instagram/YouTube
 * view-id'leri paket önekiyle gelir, örn. "com.instagram.android:id/clips_viewer_container").
 * Ziyaret edilen çocuk node'lar recycle edilir (root hariç — çağıran taraf sorumlu),
 * WindowLeaked/leak önlenir (mevcut AppAccessibilityService.dumpNode ile aynı disiplin).
 */
internal fun containsViewId(root: AccessibilityNodeInfo, idParts: List<String>): Boolean {
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        val isRoot = node === root
        val viewId = node.viewIdResourceName
        val matched = viewId != null && idParts.any { viewId.contains(it) }
        if (matched) {
            if (!isRoot) node.recycle()
            while (stack.isNotEmpty()) {
                val leftover = stack.removeLast()
                if (leftover !== root) leftover.recycle()
            }
            return true
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { stack.addLast(it) }
        }
        if (!isRoot) node.recycle()
    }
    return false
}
```

- [ ] **Step 3: `InstagramReelDetector.kt`'yi yaz**

```kotlin
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
```

- [ ] **Step 4: `YouTubeShortsDetector.kt`'yi yaz**

```kotlin
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
```

- [ ] **Step 5: Derlemeyi kontrol et**

Run: `./gradlew.bat :app:compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/traccerapp/service/reeldetection/ReelDetector.kt app/src/main/java/com/example/traccerapp/service/reeldetection/NodeSearch.kt app/src/main/java/com/example/traccerapp/service/reeldetection/InstagramReelDetector.kt app/src/main/java/com/example/traccerapp/service/reeldetection/YouTubeShortsDetector.kt
git commit -m "feat: add Instagram/YouTube reel content detectors"
```

---

## Task 2: Registry (paket adı → detector eşlemesi) — TDD

**Files:**
- Create: `app/src/main/java/com/example/traccerapp/service/reeldetection/ReelDetectorRegistry.kt`
- Test: `app/src/test/java/com/example/traccerapp/service/reeldetection/ReelDetectorRegistryTest.kt`

- [ ] **Step 1: Başarısız testi yaz**

`app/src/test/java/com/example/traccerapp/service/reeldetection/ReelDetectorRegistryTest.kt` dosyasını oluştur (bu, projedeki ilk `src/test` dosyası — `app/src/test/java/...` klasör yolu henüz yok, oluşturulacak):

```kotlin
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
```

- [ ] **Step 2: Testin başarısız olduğunu doğrula**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.example.traccerapp.service.reeldetection.ReelDetectorRegistryTest" --console=plain`
Expected: FAIL — `Unresolved reference: ReelDetectorRegistry` (derleme hatası, sınıf henüz yok)

- [ ] **Step 3: `ReelDetectorRegistry.kt`'yi yaz**

```kotlin
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
```

- [ ] **Step 4: Testin geçtiğini doğrula**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.example.traccerapp.service.reeldetection.ReelDetectorRegistryTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 5 test PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/traccerapp/service/reeldetection/ReelDetectorRegistry.kt app/src/test/java/com/example/traccerapp/service/reeldetection/ReelDetectorRegistryTest.kt
git commit -m "feat: add ReelDetectorRegistry with unit tests"
```

---

## Task 3: Bütçe/susturma saf mantığı — TDD

**Files:**
- Create: `app/src/main/java/com/example/traccerapp/service/reeldetection/ReelBlockLogic.kt`
- Test: `app/src/test/java/com/example/traccerapp/service/reeldetection/ReelBlockLogicTest.kt`

- [ ] **Step 1: Başarısız testi yaz**

```kotlin
package com.example.traccerapp.service.reeldetection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelBlockLogicTest {

    @Test
    fun `budget not exceeded when accumulated time below limit`() {
        assertFalse(hasExceededReelBudget(accumulatedMs = 10 * 60_000L, budgetMinutes = 15))
    }

    @Test
    fun `budget exceeded when accumulated time equals limit`() {
        assertTrue(hasExceededReelBudget(accumulatedMs = 15 * 60_000L, budgetMinutes = 15))
    }

    @Test
    fun `budget exceeded when accumulated time above limit`() {
        assertTrue(hasExceededReelBudget(accumulatedMs = 20 * 60_000L, budgetMinutes = 15))
    }

    @Test
    fun `zero accumulated time never exceeds positive budget`() {
        assertFalse(hasExceededReelBudget(accumulatedMs = 0L, budgetMinutes = 15))
    }

    @Test
    fun `suppressed when now is before suppress-until timestamp`() {
        val now = 1_000_000L
        assertTrue(isReelBlockSuppressed(suppressUntilMs = now + 60_000L, nowMs = now))
    }

    @Test
    fun `not suppressed when now is after suppress-until timestamp`() {
        val now = 1_000_000L
        assertFalse(isReelBlockSuppressed(suppressUntilMs = now - 1L, nowMs = now))
    }

    @Test
    fun `not suppressed when suppress-until is zero (never suppressed)`() {
        assertFalse(isReelBlockSuppressed(suppressUntilMs = 0L, nowMs = 1_000_000L))
    }
}
```

- [ ] **Step 2: Testin başarısız olduğunu doğrula**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.example.traccerapp.service.reeldetection.ReelBlockLogicTest" --console=plain`
Expected: FAIL — `Unresolved reference: hasExceededReelBudget`

- [ ] **Step 3: `ReelBlockLogic.kt`'yi yaz**

```kotlin
package com.example.traccerapp.service.reeldetection

/** BUDGET modunda: birikmiş süre (ms), dakika cinsinden günlük bütçeyi aştı mı. */
fun hasExceededReelBudget(accumulatedMs: Long, budgetMinutes: Int): Boolean =
    (accumulatedMs / 60_000L) >= budgetMinutes

/** "1 saat izin ver" penceresi içinde mi (suppressUntilMs, now'dan büyükse bloklanmaz). */
fun isReelBlockSuppressed(suppressUntilMs: Long, nowMs: Long): Boolean =
    nowMs < suppressUntilMs
```

- [ ] **Step 4: Testin geçtiğini doğrula**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.example.traccerapp.service.reeldetection.ReelBlockLogicTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 7 test PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/traccerapp/service/reeldetection/ReelBlockLogic.kt app/src/test/java/com/example/traccerapp/service/reeldetection/ReelBlockLogicTest.kt
git commit -m "feat: add reel budget/suppress pure logic with unit tests"
```

---

## Task 4: Erişilebilirlik servisi event konfigürasyonu

**Files:**
- Modify: `app/src/main/res/xml/accessibility_service_config.xml`

- [ ] **Step 1: `typeWindowContentChanged` event tipini ekle**

Dosyanın tamamı (mevcut hali tek satırlık `android:accessibilityEventTypes="typeWindowStateChanged"`):

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault"
    android:canRetrieveWindowContent="true"
    android:description="@string/accessibility_description"
    android:notificationTimeout="100" />
```

- [ ] **Step 2: Derlemeyi kontrol et**

Run: `./gradlew.bat :app:compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL` (XML kaynak değişikliği, Kotlin derlemesini etkilemez ama resource pipeline'ı tetikler)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/xml/accessibility_service_config.xml
git commit -m "feat: listen for window content changes to detect in-app reel navigation"
```

---

## Task 5: `UserPreferences` — platform başına ayar

**Files:**
- Modify: `app/src/main/java/com/example/traccerapp/data/UserPreferences.kt`

- [ ] **Step 1: `ReelBlockMode` enum'ını ve 6 yeni property'yi ekle**

Dosyanın en altına, `isDarkThemeEnabled` property'sinden sonra (kapanış `}` öncesine) ekle:

```kotlin
    // Reels/Shorts engelleme — bkz. docs/superpowers/specs/2026-07-09-reels-shorts-blocker-design.md
    var instagramReelBlockEnabled: Boolean
        get() = prefs.getBoolean("instagram_reel_block_enabled", false)
        set(value) { prefs.edit().putBoolean("instagram_reel_block_enabled", value).apply() }

    var instagramReelBlockMode: ReelBlockMode
        get() = ReelBlockMode.valueOf(
            prefs.getString("instagram_reel_block_mode", ReelBlockMode.INSTANT.name) ?: ReelBlockMode.INSTANT.name
        )
        set(value) { prefs.edit().putString("instagram_reel_block_mode", value.name).apply() }

    var instagramReelBudgetMinutes: Int
        get() = prefs.getInt("instagram_reel_budget_minutes", 15)
        set(value) { prefs.edit().putInt("instagram_reel_budget_minutes", value).apply() }

    var youtubeShortsBlockEnabled: Boolean
        get() = prefs.getBoolean("youtube_shorts_block_enabled", false)
        set(value) { prefs.edit().putBoolean("youtube_shorts_block_enabled", value).apply() }

    var youtubeShortsBlockMode: ReelBlockMode
        get() = ReelBlockMode.valueOf(
            prefs.getString("youtube_shorts_block_mode", ReelBlockMode.INSTANT.name) ?: ReelBlockMode.INSTANT.name
        )
        set(value) { prefs.edit().putString("youtube_shorts_block_mode", value.name).apply() }

    var youtubeShortsBudgetMinutes: Int
        get() = prefs.getInt("youtube_shorts_budget_minutes", 15)
        set(value) { prefs.edit().putInt("youtube_shorts_budget_minutes", value).apply() }
}

enum class ReelBlockMode { INSTANT, BUDGET }
```

Not: `enum class ReelBlockMode` `class UserPreferences`'ın **kapanış parantezinden sonra**, dosyanın en altında, top-level olarak tanımlanıyor (Kotlin'de bir dosyada birden fazla top-level bildirim serbest — `AnalysisPeriod` enum'ının `UsageAnalysisScreen.kt`'de aynı şekilde top-level tanımlandığı desenle tutarlı).

- [ ] **Step 2: Derlemeyi kontrol et**

Run: `./gradlew.bat :app:compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/traccerapp/data/UserPreferences.kt
git commit -m "feat: add per-platform reel block settings to UserPreferences"
```

---

## Task 6: `AppAccessibilityService` — tespit, state, blok tetikleme, overlay genişletme

**Files:**
- Modify: `app/src/main/java/com/example/traccerapp/service/AppAccessibilityService.kt`

- [ ] **Step 1: Yeni importları ekle**

Mevcut import bloğunun sonuna (`import java.util.concurrent.ConcurrentHashMap` satırından sonra) ekle:

```kotlin
import com.example.traccerapp.data.ReelBlockMode
import com.example.traccerapp.data.UserPreferences
import com.example.traccerapp.service.reeldetection.ReelDetectorRegistry
import com.example.traccerapp.service.reeldetection.hasExceededReelBudget
import com.example.traccerapp.service.reeldetection.isReelBlockSuppressed
```

- [ ] **Step 2: `userPrefs` alanını ve yeni state alanlarını ekle**

`private lateinit var db: AppDatabase` satırının hemen altına ekle:

```kotlin
    private lateinit var userPrefs: UserPreferences
```

`// ─── Blocking Cooldown ────` bölümünün hemen altına (`lastBlockedTime: Long = 0L` satırından sonra) yeni bir bölüm ekle:

```kotlin

    // ─── Reels/Shorts Engelleme State ──────────────────────────────────────────
    private val reelUsageMs = ConcurrentHashMap<String, Long>()
    @Volatile private var reelContentStartMs: Long? = null
    @Volatile private var reelContentPackage: String? = null
    private val reelSuppressUntilMs = ConcurrentHashMap<String, Long>()
    @Volatile private var reelUsageDayStart: Long = 0L
    @Volatile private var lastReelCheckMs: Long = 0L
```

Companion object'e (`private const val BLOCK_COOLDOWN_MS = 800L` satırının altına) yeni sabitler ekle:

```kotlin
        private const val REEL_CHECK_THROTTLE_MS = 500L
        private const val REEL_SUPPRESS_DURATION_MS = 60 * 60 * 1000L
```

- [ ] **Step 3: `onCreate()`'i güncelle — `userPrefs` başlat + gün başlangıcını kaydet**

Mevcut:
```kotlin
    override fun onCreate() {
        super.onCreate()
        db            = AppDatabase.getDatabase(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        loadTodayUsageFromDB()
        observeLimitsCache()
```

Yeni:
```kotlin
    override fun onCreate() {
        super.onCreate()
        db            = AppDatabase.getDatabase(this)
        userPrefs     = UserPreferences(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        reelUsageDayStart = getTodayStartMs()

        loadTodayUsageFromDB()
        observeLimitsCache()
```

- [ ] **Step 4: `onAccessibilityEvent`'i güncelle — içerik-değişikliği dalını ekle + paket değişiminde açık reel oturumunu kapat**

Mevcut:
```kotlin
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val newPackage = event.packageName?.toString() ?: return

        // Overlay penceresinin kendisi odak alıp bu event'i tetiklemiş olabilir; yoksay
        if (newPackage == this.packageName) return

        // Overlay görünürken başka bir pakete geçilirse overlay'i kaldır
        if (overlayView != null && newPackage != blockedOverlayPackage) {
            Log.d(TAG, "Kullanıcı başka uygulamaya geçti, overlay kaldırılıyor")
            removeOverlay()
        }

        if (shouldIgnorePackage(newPackage)) return
        if (newPackage == currentPackage) return

        val now = System.currentTimeMillis()
        commitSessionAt(now)

        currentPackage = newPackage
        sessionStartMs = now
        Log.d(TAG, "▶ Session started: $newPackage")

        checkAndBlockIfNeeded(newPackage, now)
    }
```

Yeni:
```kotlin
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            checkReelContent()
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val newPackage = event.packageName?.toString() ?: return

        // Overlay penceresinin kendisi odak alıp bu event'i tetiklemiş olabilir; yoksay
        if (newPackage == this.packageName) return

        // Overlay görünürken başka bir pakete geçilirse overlay'i kaldır
        if (overlayView != null && newPackage != blockedOverlayPackage) {
            Log.d(TAG, "Kullanıcı başka uygulamaya geçti, overlay kaldırılıyor")
            removeOverlay()
        }

        if (shouldIgnorePackage(newPackage)) return
        if (newPackage == currentPackage) return

        val now = System.currentTimeMillis()
        commitSessionAt(now)
        commitReelSession(now)

        currentPackage = newPackage
        sessionStartMs = now
        Log.d(TAG, "▶ Session started: $newPackage")

        checkAndBlockIfNeeded(newPackage, now)
    }
```

- [ ] **Step 5: `onDestroy()`'a açık reel oturumunu kapatma çağrısı ekle**

Mevcut:
```kotlin
    override fun onDestroy() {
        flushJob?.cancel()
        inAppCheckJob?.cancel()
        unregisterReceiver(screenReceiver)
        commitCurrentSession()
        removeOverlay()   // WindowLeaked önle
```

Yeni:
```kotlin
    override fun onDestroy() {
        flushJob?.cancel()
        inAppCheckJob?.cancel()
        unregisterReceiver(screenReceiver)
        commitCurrentSession()
        commitReelSession()
        removeOverlay()   // WindowLeaked önle
```

- [ ] **Step 6: Reels/Shorts engelleme mantığını ekle — yeni bölüm**

`// ═══════════════════════ Accessibility Overlay ════════════════════════════` bölümünün hemen üstüne (yani `triggerBlock` fonksiyonundan sonra, overlay bölümünden önce) yeni bir bölüm ekle:

```kotlin
    // ═══════════════════════ Reels/Shorts Engelleme ════════════════════════════

    private fun checkReelContent() {
        val pkg = currentPackage ?: return
        if (blockedOverlayPackage != null) return // overlay zaten gösteriliyor
        val detector = ReelDetectorRegistry.detectorFor(pkg) ?: return
        if (!isReelBlockEnabledFor(pkg)) return

        val now = System.currentTimeMillis()
        if (now - lastReelCheckMs < REEL_CHECK_THROTTLE_MS) return
        lastReelCheckMs = now

        val root = rootInActiveWindow ?: return
        val isReel = try {
            detector.isReelContent(root)
        } finally {
            root.recycle()
        }
        handleReelDetectionResult(pkg, isReel, now)
    }

    private fun isReelBlockEnabledFor(pkg: String): Boolean = when (pkg) {
        "com.instagram.android", "com.instagram.lite" -> userPrefs.instagramReelBlockEnabled
        "com.google.android.youtube" -> userPrefs.youtubeShortsBlockEnabled
        else -> false
    }

    private fun reelBlockModeFor(pkg: String): ReelBlockMode = when (pkg) {
        "com.instagram.android", "com.instagram.lite" -> userPrefs.instagramReelBlockMode
        else -> userPrefs.youtubeShortsBlockMode
    }

    private fun reelBudgetMinutesFor(pkg: String): Int = when (pkg) {
        "com.instagram.android", "com.instagram.lite" -> userPrefs.instagramReelBudgetMinutes
        else -> userPrefs.youtubeShortsBudgetMinutes
    }

    private fun handleReelDetectionResult(pkg: String, isReel: Boolean, now: Long) {
        if (isReel) {
            if (reelContentStartMs == null) {
                reelContentStartMs = now
                reelContentPackage = pkg
            }
            evaluateReelBlock(pkg, now)
        } else {
            commitReelSession(now)
        }
    }

    private fun evaluateReelBlock(pkg: String, now: Long) {
        if (isReelBlockSuppressed(reelSuppressUntilMs[pkg] ?: 0L, now)) return

        when (reelBlockModeFor(pkg)) {
            ReelBlockMode.INSTANT -> triggerReelBlock(pkg)
            ReelBlockMode.BUDGET -> {
                resetReelUsageIfNewDay(now)
                val liveElapsed = (now - (reelContentStartMs ?: now)).coerceAtLeast(0L)
                val accumulated = (reelUsageMs[pkg] ?: 0L) + liveElapsed
                if (hasExceededReelBudget(accumulated, reelBudgetMinutesFor(pkg))) {
                    triggerReelBlock(pkg)
                }
            }
        }
    }

    private fun commitReelSession(now: Long = System.currentTimeMillis()) {
        val start = reelContentStartMs ?: return
        val pkg = reelContentPackage ?: return
        reelContentStartMs = null
        reelContentPackage = null
        val elapsed = (now - start).coerceAtLeast(0L)
        resetReelUsageIfNewDay(now)
        reelUsageMs.merge(pkg, elapsed, Long::plus)
    }

    private fun resetReelUsageIfNewDay(now: Long) {
        val todayStart = getTodayStartMs()
        if (reelUsageDayStart != todayStart) {
            reelUsageMs.clear()
            reelUsageDayStart = todayStart
        }
    }

    private fun triggerReelBlock(packageName: String) {
        val now = System.currentTimeMillis()
        if (packageName == lastBlockedPackage && now - lastBlockedTime < BLOCK_COOLDOWN_MS) return
        lastBlockedPackage = packageName
        lastBlockedTime = now
        val appName = AppInfoUtils.getAppName(this, packageName)
        mainHandler.post {
            showBlockingOverlay(
                packageName = packageName,
                appName = appName,
                reason = "Sonsuz kaydırma sınırı",
                onAllowTemporarily = {
                    reelSuppressUntilMs[packageName] = System.currentTimeMillis() + REEL_SUPPRESS_DURATION_MS
                }
            )
        }
    }

```

- [ ] **Step 7: `showBlockingOverlay`'e `onAllowTemporarily` parametresi ekle**

Mevcut:
```kotlin
    private fun showBlockingOverlay(packageName: String, appName: String, reason: String) {
```

Yeni:
```kotlin
    private fun showBlockingOverlay(
        packageName: String,
        appName: String,
        reason: String,
        onAllowTemporarily: (() -> Unit)? = null
    ) {
```

Aynı fonksiyon içinde `setContent { BlockingOverlayContent(...) }` çağrısı — mevcut:
```kotlin
            setContent {
                BlockingOverlayContent(
                    appName   = appName,
                    packageName = packageName,
                    reason    = reason,
                    onGoHome  = {
                        removeOverlay()
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }
                )
            }
```

Yeni:
```kotlin
            setContent {
                BlockingOverlayContent(
                    appName   = appName,
                    packageName = packageName,
                    reason    = reason,
                    onGoHome  = {
                        removeOverlay()
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    },
                    onAllowTemporarily = onAllowTemporarily?.let { callback ->
                        {
                            callback()
                            removeOverlay()
                        }
                    }
                )
            }
```

- [ ] **Step 8: `BlockingOverlayContent`'e "1 saat izin ver" butonunu ekle**

Mevcut fonksiyon imzası:
```kotlin
    @Composable
    private fun BlockingOverlayContent(
        appName: String,
        packageName: String,
        reason: String,
        onGoHome: () -> Unit
    ) {
```

Yeni imza:
```kotlin
    @Composable
    private fun BlockingOverlayContent(
        appName: String,
        packageName: String,
        reason: String,
        onGoHome: () -> Unit,
        onAllowTemporarily: (() -> Unit)? = null
    ) {
```

Mevcut "Go Home button" bloğunun hemen sonrasına (Column'un kapanışından önce) ekle:

```kotlin
                Button(
                    onClick  = onGoHome,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape    = RoundedCornerShape(14.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF7C3AED)
                    )
                ) {
                    Text(
                        text       = "Ana Ekrana Dön",
                        fontSize   = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color      = Color.White
                    )
                }

                if (onAllowTemporarily != null) {
                    TextButton(onClick = onAllowTemporarily) {
                        Text(
                            text     = "1 saat izin ver",
                            fontSize = 13.sp,
                            color    = Color(0xFF9CA3AF)
                        )
                    }
                }
```

(Not: mevcut "Go Home button" kod bloğu zaten dosyada var — bu step, o bloğun hemen ardına yeni `if` bloğunu ekliyor, mevcut Button'ı yeniden yazmıyor.)

- [ ] **Step 9: Derlemeyi kontrol et**

Run: `./gradlew.bat :app:compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/example/traccerapp/service/AppAccessibilityService.kt
git commit -m "feat: wire reel detection into AppAccessibilityService with instant/budget modes"
```

---

## Task 7: Ayarlar ekranı — platform başına aç/kapa + mod + bütçe

**Files:**
- Modify: `app/src/main/java/com/example/traccerapp/ui/screens/BlockingSettingsScreen.kt`

- [ ] **Step 1: `ReelBlockMode` importunu ekle**

Mevcut importlara (`import com.example.traccerapp.data.UserPreferences` satırının altına) ekle:

```kotlin
import com.example.traccerapp.data.ReelBlockMode
```

- [ ] **Step 2: `BlockingSettingsScreen()` içine yeni bölümü ekle**

Mevcut (Aktif Limitler bloğunun hemen sonrası):
```kotlin
            // Aktif limitler
            val active = activeLimits.filter { it.isTimeLimitEnabled || it.isScheduleEnabled }
            if (active.isNotEmpty()) {
                item {
                    Text("Aktif Limitler", color = TextSecondary, fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
                items(active) { limit ->
                    ActiveLimitRow(limit) {
                        selectedApp = allApps.find { it.packageName == limit.packageName }
                            ?: AppInfo(limit.appName, limit.packageName)
                        showBottomSheet = true
                    }
                }
            }

            item {
                Text("Tüm Uygulamalar", color = TextSecondary, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            }
```

Yeni (araya `ReelBlockSettingsSection` item'ı eklenir):
```kotlin
            // Aktif limitler
            val active = activeLimits.filter { it.isTimeLimitEnabled || it.isScheduleEnabled }
            if (active.isNotEmpty()) {
                item {
                    Text("Aktif Limitler", color = TextSecondary, fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
                items(active) { limit ->
                    ActiveLimitRow(limit) {
                        selectedApp = allApps.find { it.packageName == limit.packageName }
                            ?: AppInfo(limit.appName, limit.packageName)
                        showBottomSheet = true
                    }
                }
            }

            item {
                ReelBlockSettingsSection(prefs)
            }

            item {
                Text("Tüm Uygulamalar", color = TextSecondary, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            }
```

- [ ] **Step 3: `ReelBlockSettingsSection` ve `ReelBlockPlatformCard` composable'larını ekle**

`SectionTitle` composable'ının hemen üstüne (dosyada `LimitSettingsContent`'ten sonra, `SectionTitle`'dan önce) ekle:

```kotlin
@Composable
fun ReelBlockSettingsSection(prefs: UserPreferences) {
    var igEnabled by remember { mutableStateOf(prefs.instagramReelBlockEnabled) }
    var igMode by remember { mutableStateOf(prefs.instagramReelBlockMode) }
    var igBudget by remember { mutableIntStateOf(prefs.instagramReelBudgetMinutes) }

    var ytEnabled by remember { mutableStateOf(prefs.youtubeShortsBlockEnabled) }
    var ytMode by remember { mutableStateOf(prefs.youtubeShortsBlockMode) }
    var ytBudget by remember { mutableIntStateOf(prefs.youtubeShortsBudgetMinutes) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Sonsuz Kaydırma Engelleme", color = TextSecondary, fontSize = 13.sp,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
        )

        ReelBlockPlatformCard(
            title = "Instagram Reels",
            enabled = igEnabled,
            mode = igMode,
            budgetMinutes = igBudget,
            onEnabledChange = { igEnabled = it; prefs.instagramReelBlockEnabled = it },
            onModeChange = { igMode = it; prefs.instagramReelBlockMode = it },
            onBudgetMinutesChange = { igBudget = it; prefs.instagramReelBudgetMinutes = it }
        )

        ReelBlockPlatformCard(
            title = "YouTube Shorts",
            enabled = ytEnabled,
            mode = ytMode,
            budgetMinutes = ytBudget,
            onEnabledChange = { ytEnabled = it; prefs.youtubeShortsBlockEnabled = it },
            onModeChange = { ytMode = it; prefs.youtubeShortsBlockMode = it },
            onBudgetMinutesChange = { ytBudget = it; prefs.youtubeShortsBudgetMinutes = it }
        )
    }
}

@Composable
private fun ReelBlockPlatformCard(
    title: String,
    enabled: Boolean,
    mode: ReelBlockMode,
    budgetMinutes: Int,
    onEnabledChange: (Boolean) -> Unit,
    onModeChange: (ReelBlockMode) -> Unit,
    onBudgetMinutesChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface)
            .padding(16.dp)
    ) {
        SectionTitle(title, enabled, onEnabledChange)

        if (enabled) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkElevated)
                    .padding(4.dp)
            ) {
                ReelBlockMode.entries.forEach { option ->
                    val isSelected = option == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) PurplePrimary else Color.Transparent)
                            .clickable { onModeChange(option) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (option == ReelBlockMode.INSTANT) "Anında" else "Süre Sınırı",
                            color = if (isSelected) Color.White else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            if (mode == ReelBlockMode.BUDGET) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = { if (budgetMinutes > 5) onBudgetMinutesChange(budgetMinutes - 5) }) {
                        Icon(Icons.Default.Remove, null, tint = PurpleLight)
                    }
                    Text("$budgetMinutes Dakika/gün", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { onBudgetMinutesChange(budgetMinutes + 5) }) {
                        Icon(Icons.Default.Add, null, tint = PurpleLight)
                    }
                }
            }
        }
    }
}

```

- [ ] **Step 4: Derlemeyi kontrol et**

Run: `./gradlew.bat :app:compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/traccerapp/ui/screens/BlockingSettingsScreen.kt
git commit -m "feat: add reel block settings UI to BlockingSettingsScreen"
```

---

## Task 8: Tam doğrulama, CLAUDE.md güncellemesi, cihaz test checklist'i

**Files:**
- Modify: `c:\Users\Emink\Desktop\Projeler\TraccerApp\TraccerApp\CLAUDE.md`

- [ ] **Step 1: Tam derleme + tüm unit testleri çalıştır**

Run: `./gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`, Task 2 + Task 3'teki 12 test PASS

- [ ] **Step 2: CLAUDE.md'ye madde 26'yı ekle**

`## Devam eden / ertelenmiş iş` başlığının hemen üstüne ekle:

```markdown
### 26. Sonsuz Kaydırma (Reels/Shorts) Engelleme — yeni (2026-07-09)
- Instagram Reels ve YouTube Shorts için AppLimit'ten bağımsız yeni bir engelleme katmanı: `service/reeldetection/` paketi (`ReelDetector` arayüzü + `InstagramReelDetector`/`YouTubeShortsDetector`, ikisi de ilgili platformun kendi iç `viewIdResourceName`'lerini node ağacında arıyor — `NodeSearch.containsViewId`). TikTok bilinçli olarak dışarıda (ana akışı zaten tamamen kısa-video, "sadece reels'i ayırma" TikTok'ta anlamsız — mevcut AppLimit yeterli). Mimari TikTok/Facebook'u sonradan eklemeye açık (bkz. `docs/superpowers/specs/2026-07-09-reels-shorts-blocker-design.md`).
- Tespit tekniği, kullanıcının kendi cihazına kurulu bir rakip uygulamanın (kişisel/eğitim amaçlı JADX ile decompile edilen) genel yaklaşımından ilham aldı — **kod kopyalanmadı**, yalnızca "platformun kendi view-id'lerini ara" fikri kullanıldı, orijinal Kotlin implementasyonu yazıldı. Rakibin tam kapsamlı uzamsal/i18n-kelime doğrulama katmanı bilinçli olarak alınmadı (YAGNI, tek kullanıcılı kişisel app için gereksiz) — yanlış-pozitif görülürse eklenir.
- `accessibility_service_config.xml`'e `typeWindowContentChanged` eklendi (madde 17/18'deki `typeWindowStateChanged`'in yanına) — Instagram/YouTube içinde sekme değişimini (paket değişmeden) yakalamak için gerekliydi. 500ms throttle (`REEL_CHECK_THROTTLE_MS`) ile pil/performans korunuyor, sadece `ReelDetectorRegistry`'de karşılığı olan paketlerde (Instagram/YouTube) node ağacı taranıyor.
- İki blok modu, kullanıcı platform başına `BlockingSettingsScreen`'den seçiyor (`UserPreferences.instagramReelBlockMode`/`youtubeShortsBlockMode`, `ReelBlockMode.INSTANT`/`BUDGET`): INSTANT tespit anında bloklar; BUDGET, `dailyUsageMs` (madde 25) ile aynı ruhta ayrı bir `reelUsageMs` sayacı biriktirir, günlük dakika bütçesini (`instagramReelBudgetMinutes`/`youtubeShortsBudgetMinutes`, varsayılan 15dk) aşınca bloklar. Bütçe sayacı yalnızca in-memory (DB'ye yazılmaz) — servis yeniden başlarsa sıfırlanır, kabul edilebilir risk (madde 13'teki `dailyUsageMs` kadar kritik değil, ekran süresi geçmişini etkilemiyor).
- Mevcut overlay altyapısı (`showBlockingOverlay`/`BlockingOverlayContent`, madde 2) yeni opsiyonel `onAllowTemporarily` parametresiyle genişletildi — `null` ise (mevcut AppLimit blokları) davranış değişmedi, reel-blok çağrılarında "1 saat izin ver" butonu eklendi (`reelSuppressUntilMs`, her iki modda da aynı davranış).
- İlk kez bu projede otomatik unit test yazıldı (`app/src/test/`) — yalnızca Android framework'e bağımlı olmayan saf mantık için (`ReelDetectorRegistry`, `ReelBlockLogic`), `AccessibilityNodeInfo`'ya bağımlı node-arama kodu mevcut proje konvansiyonuyla (Robolectric yok) yalnızca derleme + cihaz testiyle doğrulandı.
- Şema/migration değişikliği yok — tüm yeni state `UserPreferences` (SharedPreferences) veya `AppAccessibilityService` içinde in-memory.
```

- [ ] **Step 3: CLAUDE.md değişikliğini commit et**

```bash
git add CLAUDE.md
git commit -m "docs: document Reels/Shorts blocker feature (madde 26)"
```

- [ ] **Step 4: Kullanıcıya bırakılacak cihaz doğrulama checklist'i (bu adımda kod yazılmaz, kullanıcıya iletilecek)**

Aşağıdaki senaryolar kullanıcının kendi cihazında test edilmeli (bu proje emülatör/otomatik cihaz testi altyapısına sahip değil):

1. Ayarlar → Limitler → "Sonsuz Kaydırma Engelleme" → Instagram Reels'i aç, mod "Anında" seç → Instagram'da Reels sekmesine gir → anında overlay ile bloklanmalı, geri tuşuna basılmalı.
2. Aynı senaryoda "1 saat izin ver"e bas → Reels'e tekrar girildiğinde 1 saat boyunca bloklanmamalı.
3. Instagram Reels modunu "Süre Sınırı" (örn. 5dk) yap → Reels'te toplam 5 dk geçirince (sekme değiştirip geri dönse bile, süre birikmeli) bloklanmalı.
4. Instagram'ın Home/DM/Profil/arama kısımlarında **hiç blok tetiklenmemeli** (Reels'e hiç girilmediyse).
5. Aynı 4 senaryo YouTube Shorts için tekrarlanmalı.
6. TikTok'un bu özellikten etkilenmediği (ayarlarda hiç seçenek olmadığı) doğrulanmalı.
7. Mevcut AppLimit (günlük uygulama limiti) davranışının hiç değişmediği doğrulanmalı (regresyon kontrolü — madde 1/13/25).
