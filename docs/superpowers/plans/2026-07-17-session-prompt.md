# Oturum Bazlı Kullanım İzni ("Girişte süre sor") Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mod açık uygulamaya girişte "bu oturumda kaç dakika?" soran overlay; seçilen bütçe dolunca mevcut blok overlay'i ile engelleme.

**Architecture:** `AppLimit`'e `isSessionPromptEnabled` kolonu (Migration 3→4). Oturum durumu `AppAccessibilityService` içinde in-memory (reel-bütçesi deseni). Soru ekranı mevcut `TYPE_ACCESSIBILITY_OVERLAY` altyapısının ikinci içeriği. Saf karar mantığı `service/sessionprompt/SessionPromptLogic.kt`'de, JUnit ile test edilir.

**Tech Stack:** Kotlin, Jetpack Compose, Room (KSP), AccessibilityService, JUnit4.

**Spec:** `docs/superpowers/specs/2026-07-17-session-prompt-design.md`

**Not:** Depoda henüz hiç commit yok. Commit adımları kullanıcı onayıyla atılır; onay yoksa atlanır (derleme/test adımları atlanamaz).

---

### Task 1: SessionPromptLogic (saf mantık, TDD)

**Files:**
- Create: `app/src/main/java/com/example/traccerapp/service/sessionprompt/SessionPromptLogic.kt`
- Test: `app/src/test/java/com/example/traccerapp/service/sessionprompt/SessionPromptLogicTest.kt`

- [ ] **Step 1: Başarısız testi yaz**

```kotlin
package com.example.traccerapp.service.sessionprompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPromptLogicTest {

    @Test
    fun `hic oturum baslamamissa sorar`() {
        assertTrue(shouldPromptForSession(remainingMs = null, lastExitMs = null, nowMs = 1_000L))
    }

    @Test
    fun `kalan sure sifir veya altiysa sorar`() {
        assertTrue(shouldPromptForSession(remainingMs = 0L, lastExitMs = 500L, nowMs = 1_000L))
        assertTrue(shouldPromptForSession(remainingMs = -1L, lastExitMs = 500L, nowMs = 1_000L))
    }

    @Test
    fun `aktif oturumda hic cikis yoksa sormaz`() {
        assertFalse(shouldPromptForSession(remainingMs = 60_000L, lastExitMs = null, nowMs = 1_000L))
    }

    @Test
    fun `grace penceresi icinde donuste sormaz`() {
        // Tam sınırda (== 5 dk) hâlâ grace içinde
        assertFalse(shouldPromptForSession(remainingMs = 60_000L, lastExitMs = 0L, nowMs = SESSION_GRACE_MS))
    }

    @Test
    fun `grace penceresi asilirsa sorar`() {
        assertTrue(shouldPromptForSession(remainingMs = 60_000L, lastExitMs = 0L, nowMs = SESSION_GRACE_MS + 1))
    }

    @Test
    fun `butce dusumu gecen sureyi dusurur`() {
        assertEquals(40_000L, deductSessionBudget(remainingMs = 60_000L, elapsedMs = 20_000L))
    }

    @Test
    fun `negatif elapsed butceyi arttirmaz`() {
        assertEquals(60_000L, deductSessionBudget(remainingMs = 60_000L, elapsedMs = -5_000L))
    }

    @Test
    fun `butce negatife dusebilir ve expired sayilir`() {
        val remaining = deductSessionBudget(remainingMs = 10_000L, elapsedMs = 25_000L)
        assertTrue(isSessionExpired(remaining))
        assertFalse(isSessionExpired(1L))
        assertTrue(isSessionExpired(0L))
    }

    @Test
    fun `dakika secimi sinirlara kirpilir`() {
        assertEquals(MIN_SESSION_MINUTES, clampSessionMinutes(1))
        assertEquals(MAX_SESSION_MINUTES, clampSessionMinutes(999))
        assertEquals(25, clampSessionMinutes(25))
    }
}
```

- [ ] **Step 2: Testin başarısız olduğunu doğrula**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.traccerapp.service.sessionprompt.SessionPromptLogicTest" --max-workers=1 --console=plain`
Expected: derleme hatası ("unresolved reference: shouldPromptForSession") — dosya henüz yok.

- [ ] **Step 3: Minimal implementasyonu yaz**

```kotlin
package com.example.traccerapp.service.sessionprompt

/** Kısa çıkış toleransı: bu süre içinde geri dönülürse oturum devam eder, soru gelmez. */
const val SESSION_GRACE_MS = 5 * 60_000L
const val MIN_SESSION_MINUTES = 5
const val MAX_SESSION_MINUTES = 180
val SESSION_PRESET_MINUTES = listOf(5, 10, 15, 30, 60)

/**
 * Mod açık uygulamaya girişte dakika sorusu gösterilmeli mi?
 * - remainingMs == null → hiç oturum başlamamış → sor.
 * - remainingMs <= 0   → önceki oturum bitmiş → sor (yeni oturum hakkı, blok değil).
 * - lastExitMs == null → uygulamadan hiç çıkılmamış (aktif oturum) → sorma.
 * - grace penceresi aşıldıysa → oturum düşmüş → sor.
 */
fun shouldPromptForSession(remainingMs: Long?, lastExitMs: Long?, nowMs: Long): Boolean {
    if (remainingMs == null) return true
    if (remainingMs <= 0L) return true
    val lastExit = lastExitMs ?: return false
    return nowMs - lastExit > SESSION_GRACE_MS
}

/** Uygulamada geçen süreyi bütçeden düşer. Negatif elapsed (saat oynaması) bütçeyi arttırmaz. */
fun deductSessionBudget(remainingMs: Long, elapsedMs: Long): Long =
    remainingMs - elapsedMs.coerceAtLeast(0L)

fun isSessionExpired(remainingMs: Long): Boolean = remainingMs <= 0L

fun clampSessionMinutes(minutes: Int): Int =
    minutes.coerceIn(MIN_SESSION_MINUTES, MAX_SESSION_MINUTES)
```

- [ ] **Step 4: Testlerin geçtiğini doğrula**

Run: aynı gradle komutu.
Expected: PASS (9 test).

- [ ] **Step 5: Commit (kullanıcı onayı varsa)**

```bash
git add app/src/main/java/com/example/traccerapp/service/sessionprompt/SessionPromptLogic.kt app/src/test/java/com/example/traccerapp/service/sessionprompt/SessionPromptLogicTest.kt
git commit -m "feat: oturum sorusu saf karar mantigi + testler"
```

---

### Task 2: Veri katmanı — AppLimit kolonu, Migration 3→4, DAO

**Files:**
- Modify: `app/src/main/java/com/example/traccerapp/data/AppUsageEntity.kt` (AppLimit)
- Modify: `app/src/main/java/com/example/traccerapp/data/AppDatabase.kt`
- Modify: `app/src/main/java/com/example/traccerapp/data/AppUsageDao.kt` (getActiveLimits)

- [ ] **Step 1: AppLimit'e alan ekle** — `blockEndMinute` satırının altına:

```kotlin
    val blockEndMinute: Int = 0,
    // Oturum bazlı izin: girişte "bu oturumda kaç dakika?" sorulsun mu (spec: 2026-07-17)
    val isSessionPromptEnabled: Boolean = false,
```

- [ ] **Step 2: Migration 3→4 yaz** — `MIGRATION_2_3`'ün altına:

```kotlin
/** v3 → v4: uygulama başına "girişte süre sor" (oturum bazlı izin) bayrağı. Mevcut veriyi korur. */
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `app_limits` ADD COLUMN `isSessionPromptEnabled` INTEGER NOT NULL DEFAULT 0")
    }
}
```

`@Database(... version = 3 ...)` → `version = 4`; `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)` → `.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)`.

- [ ] **Step 3: getActiveLimits sorgusunu genişlet** — servisin `limitsCache`'i bu uygulamaları da görmeli:

```kotlin
    @Query("SELECT * FROM app_limits WHERE isTimeLimitEnabled = 1 OR isScheduleEnabled = 1 OR isSessionPromptEnabled = 1")
    fun getActiveLimits(): Flow<List<AppLimit>>
```

- [ ] **Step 4: Derle**

Run: `.\gradlew.bat :app:compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL (BlockingSettingsScreen'deki AppLimit constructor'ı named-arg kullandığı ve yeni alanın default'u olduğu için kırılmaz).

- [ ] **Step 5: Commit (kullanıcı onayı varsa)**

```bash
git add app/src/main/java/com/example/traccerapp/data/
git commit -m "feat: AppLimit.isSessionPromptEnabled + Room migration 3-4"
```

---

### Task 3: Servis kablolaması — oturum durumu, giriş/çıkış, süre dolumu

**Files:**
- Modify: `app/src/main/java/com/example/traccerapp/service/AppAccessibilityService.kt`

- [ ] **Step 1: Import + state alanları ekle**

Import bloğuna (mevcut `reeldetection` importlarının yanına):

```kotlin
import com.example.traccerapp.service.sessionprompt.MAX_SESSION_MINUTES
import com.example.traccerapp.service.sessionprompt.MIN_SESSION_MINUTES
import com.example.traccerapp.service.sessionprompt.SESSION_PRESET_MINUTES
import com.example.traccerapp.service.sessionprompt.clampSessionMinutes
import com.example.traccerapp.service.sessionprompt.deductSessionBudget
import com.example.traccerapp.service.sessionprompt.isSessionExpired
import com.example.traccerapp.service.sessionprompt.shouldPromptForSession
```

`// ─── Reels/Shorts Engelleme State ───` bloğunun altına:

```kotlin
    // ─── Oturum Sorusu ("girişte süre sor") State — in-memory, spec: 2026-07-17 ─
    private val sessionRemainingMs = ConcurrentHashMap<String, Long>()
    private val sessionLastExitMs  = ConcurrentHashMap<String, Long>()
    @Volatile private var activeSessionPackage: String? = null
    @Volatile private var activeSessionStartMs: Long = 0L
    private var sessionExpiryJob: Job? = null
```

- [ ] **Step 2: onAccessibilityEvent akışını güncelle** — mevcut blok:

```kotlin
        if (newPackage != currentPackage) commitReelSession()

        if (shouldIgnorePackage(newPackage)) return
        if (newPackage == currentPackage) return

        val now = System.currentTimeMillis()
        commitSessionAt(now)

        currentPackage = newPackage
        sessionStartMs = now
        Log.d(TAG, "▶ Session started: $newPackage")

        checkAndBlockIfNeeded(newPackage, now)
```

şu hale gelir:

```kotlin
        val now = System.currentTimeMillis()
        if (newPackage != currentPackage) {
            commitReelSession()
            // Oturum bütçesi, ignore edilen pakete (launcher) geçişte de duraklamalı —
            // aksi halde Home'da geçen süre bütçeden düşerdi (reel-commit ile aynı gerekçe).
            pauseActiveSession(switchingTo = newPackage, now = now)
        }

        if (shouldIgnorePackage(newPackage)) return
        if (newPackage == currentPackage) return

        commitSessionAt(now)

        currentPackage = newPackage
        sessionStartMs = now
        Log.d(TAG, "▶ Session started: $newPackage")

        val blocked = checkAndBlockIfNeeded(newPackage, now)
        if (!blocked) maybeHandleSessionEntry(newPackage, now)
```

- [ ] **Step 3: checkAndBlockIfNeeded'i Boolean döndür** — imza `: Boolean`; `return` noktaları:
  - `if (blockedOverlayPackage == packageName) return` → `return true` (zaten bloklu)
  - debounce dalı → `return false`
  - `val limit = limitsCache[packageName] ?: return` → `return false`
  - `if (!limit.isTimeLimitEnabled && !limit.isScheduleEnabled) return` → `return false`
  - günlük limit dolunca `triggerBlock(...); return` → `return true`
  - zamanlama dalında `if (blocked) triggerBlock(...)` → `if (blocked) { triggerBlock(...); return true }`
  - fonksiyon sonu → `return false`

- [ ] **Step 4: Oturum yönetim fonksiyonlarını ekle** — `startInAppLimitCheck`'in altına:

```kotlin
    // ═══════════════════════ Oturum Sorusu (girişte süre sor) ══════════════════

    /** Aktif oturumu duraklatır: geçen süreyi bütçeden düşer, çıkış anını işaretler. */
    private fun pauseActiveSession(switchingTo: String?, now: Long) {
        val pkg = activeSessionPackage ?: return
        if (pkg == switchingTo) return
        sessionExpiryJob?.cancel()
        val remaining = sessionRemainingMs[pkg] ?: 0L
        sessionRemainingMs[pkg] = deductSessionBudget(remaining, now - activeSessionStartMs)
        sessionLastExitMs[pkg] = now
        activeSessionPackage = null
        Log.d(TAG, "⏸ Oturum duraklatıldı: $pkg, kalan=${sessionRemainingMs[pkg]}ms")
    }

    /** Girişte: soru göster ya da grace içindeki oturuma kaldığı yerden devam et. */
    private fun maybeHandleSessionEntry(pkg: String, now: Long) {
        if (blockedOverlayPackage != null) return
        val limit = limitsCache[pkg] ?: return
        if (!limit.isSessionPromptEnabled) return
        if (activeSessionPackage == pkg) return

        if (shouldPromptForSession(sessionRemainingMs[pkg], sessionLastExitMs[pkg], now)) {
            sessionRemainingMs.remove(pkg)
            sessionLastExitMs.remove(pkg)
            val appName = AppInfoUtils.getAppName(this, pkg)
            mainHandler.post { showSessionPromptOverlay(pkg, appName) }
        } else {
            resumeSession(pkg, now)
        }
    }

    private fun resumeSession(pkg: String, now: Long) {
        activeSessionPackage = pkg
        activeSessionStartMs = now
        scheduleSessionExpiry(pkg, sessionRemainingMs[pkg] ?: 0L)
        Log.d(TAG, "▶ Oturum aktif: $pkg, kalan=${sessionRemainingMs[pkg]}ms")
    }

    /** "Başla" butonundan: seçilen dakika ile yeni oturum. */
    private fun startSession(pkg: String, minutes: Int) {
        sessionRemainingMs[pkg] = clampSessionMinutes(minutes) * 60_000L
        sessionLastExitMs.remove(pkg)
        resumeSession(pkg, System.currentTimeMillis())
    }

    /** Kalan süre dolduğu anda blok gelsin diye tam süreye zamanlanmış tek atımlık kontrol. */
    private fun scheduleSessionExpiry(pkg: String, remainingMs: Long) {
        sessionExpiryJob?.cancel()
        if (isSessionExpired(remainingMs)) { expireSession(pkg); return }
        sessionExpiryJob = serviceScope.launch {
            delay(remainingMs + 1_000L)
            checkActiveSessionExpiry(System.currentTimeMillis())
        }
    }

    private fun checkActiveSessionExpiry(now: Long) {
        val pkg = activeSessionPackage ?: return
        val liveRemaining = (sessionRemainingMs[pkg] ?: 0L) - (now - activeSessionStartMs)
        if (isSessionExpired(liveRemaining)) expireSession(pkg)
    }

    /** Süre doldu: state temizlenir (yeni oturum = bilinçli yeniden giriş), mevcut blok yolu tetiklenir. */
    private fun expireSession(pkg: String) {
        sessionExpiryJob?.cancel()
        activeSessionPackage = null
        sessionRemainingMs.remove(pkg)
        sessionLastExitMs.remove(pkg)
        triggerBlock(pkg, AppInfoUtils.getAppName(this, pkg), "Oturum süresi doldu")
    }
```

- [ ] **Step 5: SCREEN_OFF + 30sn tick + onDestroy entegrasyonu**

`screenReceiver`'da `commitCurrentSession()` satırının altına (currentPackage null'lanmadan önce):

```kotlin
                pauseActiveSession(switchingTo = null, now = System.currentTimeMillis())
```

`startInAppLimitCheck` döngüsünde `val pkg = currentPackage ?: continue` satırının altına:

```kotlin
                checkActiveSessionExpiry(System.currentTimeMillis())
```

`onDestroy`'da `inAppCheckJob?.cancel()` satırının altına:

```kotlin
        sessionExpiryJob?.cancel()
```

- [ ] **Step 6: Derle**

Run: `.\gradlew.bat :app:compileDebugKotlin --console=plain`
Expected: HATA — `showSessionPromptOverlay` henüz yok (Task 4'te gelir). Task 4 ile birlikte derlenir; istenirse bu adım Task 4 sonuna ertelenir.

---

### Task 4: Soru overlay'i (UI + göster/kapat)

**Files:**
- Modify: `app/src/main/java/com/example/traccerapp/service/AppAccessibilityService.kt`

- [ ] **Step 1: showSessionPromptOverlay ekle** — `showBlockingOverlay`'in altına. Mevcut window-params/lifecycle kalıbının birebir kopyası, yalnız içerik farklı; `blockedOverlayPackage = packageName` set edilir (tek-overlay değişmezi: reel taraması ve tekrar-blok bu sayede sussun):

```kotlin
    /** Girişte "bu oturumda kaç dakika?" sorusu — blok overlay'i ile aynı pencere altyapısı. */
    private fun showSessionPromptOverlay(packageName: String, appName: String) {
        if (overlayView != null) removeOverlayInternal()

        blockedOverlayPackage = packageName

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        val lifecycleOwner = ServiceLifecycleOwner()
        overlayOwner = lifecycleOwner

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)

            setContent {
                SessionPromptOverlayContent(
                    appName = appName,
                    packageName = packageName,
                    onStart = { minutes ->
                        startSession(packageName, minutes)
                        removeOverlay()
                    },
                    onDismiss = {
                        removeOverlay()
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }
                )
            }
        }

        try {
            lifecycleOwner.init()
            windowManager?.addView(composeView, params)
            overlayView = composeView
            Log.d(TAG, "❓ Oturum sorusu gösterildi: $appName")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Oturum sorusu overlay'i eklenemedi", e)
            lifecycleOwner.destroy()
            overlayOwner = null
            blockedOverlayPackage = null
        }
    }
```

Not: `removeOverlayInternal` `currentPackage`'ı null'lar — "Başla" sonrası uygulamanın ilk window-state event'i takibi yeniden başlatır (`activeSessionPackage == pkg` olduğu için soru tekrar gelmez). Bilinen, kabul edilmiş küçük boşluk; kullanım süresi OS reconcile ile tamamlanır.

- [ ] **Step 2: SessionPromptOverlayContent composable'ı ekle** — `BlockingOverlayContent`'in altına:

```kotlin
    @Composable
    private fun SessionPromptOverlayContent(
        appName: String,
        packageName: String,
        onStart: (Int) -> Unit,
        onDismiss: () -> Unit
    ) {
        var selectedMinutes by remember { mutableIntStateOf(15) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF0A0A12), Color(0xFF12091F))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(300.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3B0764).copy(alpha = 0.25f))
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                RealAppIcon(
                    packageName  = packageName,
                    appName      = appName,
                    size         = 72.dp,
                    cornerRadius = 18.dp
                )

                Text(
                    text       = appName,
                    color      = Color.White,
                    fontSize   = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center
                )

                Text(
                    text      = "Bu oturumda ne kadar kullanacaksın?",
                    color     = Color(0xFF9CA3AF),
                    fontSize  = 14.sp,
                    textAlign = TextAlign.Center
                )

                // Hazır seçenekler
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SESSION_PRESET_MINUTES.forEach { mins ->
                        val isSelected = selectedMinutes == mins
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) Color(0xFF7C3AED) else Color(0xFF7C3AED).copy(alpha = 0.15f))
                                .clickable { selectedMinutes = mins }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text       = "${mins}dk",
                                color      = if (isSelected) Color.White else Color(0xFFC084FC),
                                fontSize   = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Özel değer (stepper — klavye yok, FLAG_NOT_FOCUSABLE ile çakışmaz)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        selectedMinutes = clampSessionMinutes(selectedMinutes - 5)
                    }) { Text("−", color = Color(0xFFC084FC), fontSize = 22.sp) }
                    Text(
                        text       = "$selectedMinutes dk",
                        color      = Color.White,
                        fontSize   = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier   = Modifier.padding(horizontal = 12.dp)
                    )
                    TextButton(onClick = {
                        selectedMinutes = clampSessionMinutes(selectedMinutes + 5)
                    }) { Text("+", color = Color(0xFFC084FC), fontSize = 22.sp) }
                }

                Button(
                    onClick  = { onStart(selectedMinutes) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape    = RoundedCornerShape(14.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                ) {
                    Text(
                        text       = "Başla",
                        fontSize   = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color      = Color.White
                    )
                }

                TextButton(onClick = onDismiss) {
                    Text(
                        text     = "Kullanmayacağım",
                        fontSize = 13.sp,
                        color    = Color(0xFF9CA3AF)
                    )
                }
            }
        }
    }
```

Gerekli ek importlar (dosyada yoksa): `androidx.compose.foundation.clickable`.

- [ ] **Step 3: Derle**

Run: `.\gradlew.bat :app:compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit (kullanıcı onayı varsa)**

```bash
git add app/src/main/java/com/example/traccerapp/service/AppAccessibilityService.kt
git commit -m "feat: giriste sure soran oturum overlay'i + servis oturum yonetimi"
```

---

### Task 5: Limitler ekranı entegrasyonu

**Files:**
- Modify: `app/src/main/java/com/example/traccerapp/ui/screens/BlockingSettingsScreen.kt`

- [ ] **Step 1: "Aktif Limitler" filtresi** — `val active = activeLimits.filter { it.isTimeLimitEnabled || it.isScheduleEnabled }` → `... || it.isSessionPromptEnabled }`.

- [ ] **Step 2: ActiveLimitRow rozeti** — `if (limit.isScheduleEnabled)` bloğunun altına:

```kotlin
                if (limit.isSessionPromptEnabled) {
                    Icon(Icons.Default.HourglassEmpty, null, tint = StatusBlue, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Oturum", color = StatusBlue, fontSize = 11.sp)
                }
```

(`StatusBlue` `ui.theme.*` yıldız importuyla zaten kapsamda.)

- [ ] **Step 3: LimitSettingsContent'e switch** — state bloğuna (`var endMin ...` altına):

```kotlin
    var isSessionPromptEnabled by remember { mutableStateOf(existingLimit?.isSessionPromptEnabled ?: false) }
```

"Zaman Planı" bölümünün kapanışından sonra, kaydet butonundan önceki `Spacer(modifier = Modifier.height(32.dp))` üstüne:

```kotlin
        Spacer(modifier = Modifier.height(24.dp))

        SectionTitle("Girişte Süre Sor", isSessionPromptEnabled) { isSessionPromptEnabled = it }
        if (isSessionPromptEnabled) {
            Text(
                "Uygulamayı her açtığında o oturum için süre seçersin; süre dolunca engellenir.",
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
```

Kaydet çağrısındaki `AppLimit(...)` constructor'ına `blockEndMinute = endMin` satırının altına:

```kotlin
                        isSessionPromptEnabled = isSessionPromptEnabled
```

- [ ] **Step 4: Derle + tüm testler**

Run: `.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --max-workers=1 --console=plain`
Expected: BUILD SUCCESSFUL, tüm testler PASS.

- [ ] **Step 5: Commit (kullanıcı onayı varsa)**

```bash
git add app/src/main/java/com/example/traccerapp/ui/screens/BlockingSettingsScreen.kt
git commit -m "feat: Limitler ekranina 'Giriste Sure Sor' modu"
```

---

### Task 6: Dokümantasyon + son doğrulama

**Files:**
- Modify: `CLAUDE.md` (yeni madde 29)

- [ ] **Step 1: CLAUDE.md'ye madde 29 ekle** — özelliğin özeti: AppLimit v4 kolonu, in-memory oturum durumu, tek-overlay değişmezi, grace penceresi, cihaz test protokolü (spec'teki 7 senaryo).
- [ ] **Step 2: Tam derleme + testler** — `.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --max-workers=1 --console=plain` → BUILD SUCCESSFUL.
- [ ] **Step 3: Commit (kullanıcı onayı varsa)** — `git add CLAUDE.md docs/ && git commit -m "docs: oturum bazli izin ozelligi (madde 29)"`

---

## Cihaz test protokolü (kullanıcıda — emülatör yok)

1. Limitler → bir uygulama → "Girişte Süre Sor" aç → uygulamaya gir → soru overlay'i gelmeli.
2. 5 dk seç + Başla → 5 dk sonunda blok overlay ("Oturum süresi doldu").
3. Oturum içinde çık, <5 dk içinde dön → soru gelmemeli, kalan süre devam etmeli.
4. Çık, >5 dk bekle, dön → yeni soru gelmeli.
5. "Kullanmayacağım" → ana ekrana dönmeli, oturum başlamamalı.
6. Aynı uygulamada günlük limit de dolu ise → soru yerine direkt blok.
7. Regresyon: normal AppLimit/zamanlama blokları ve reel-blocker davranışı değişmemeli.
