package com.example.traccerapp.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.traccerapp.data.AppDatabase
import com.example.traccerapp.data.AppLimit
import com.example.traccerapp.data.UsageLog
import com.example.traccerapp.ui.components.RealAppIcon
import com.example.traccerapp.utils.AppInfoUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import com.example.traccerapp.data.ReelBlockMode
import com.example.traccerapp.data.UserPreferences
import com.example.traccerapp.service.reeldetection.ReelDetectorRegistry
import com.example.traccerapp.service.reeldetection.hasExceededReelBudget
import com.example.traccerapp.service.sessionprompt.SESSION_PRESET_MINUTES
import com.example.traccerapp.service.sessionprompt.clampSessionMinutes
import com.example.traccerapp.service.sessionprompt.deductSessionBudget
import com.example.traccerapp.service.sessionprompt.isSessionExpired
import com.example.traccerapp.service.sessionprompt.shouldPromptForSession
import androidx.compose.foundation.clickable

class AppAccessibilityService : AccessibilityService() {

    // ─── Companion ────────────────────────────────────────────────────────────
    companion object {
        private const val TAG               = "AppAccessibilityService"
        private const val FLUSH_INTERVAL_MS = 10 * 60 * 1000L
        private const val BLOCK_COOLDOWN_MS = 800L
        private const val REEL_CHECK_THROTTLE_MS = 500L
        /** "Ana Sayfaya Dön" sonrası reel tespitinin susturulacağı grace süresi. App-içi Home'a
         *  geçiş anlık değil (~1s animasyon); bu pencere olmadan reel hâlâ ağaçtayken tespit edilip
         *  anında yeniden bloklanır → sonsuz döngü (cihaz teşhisiyle doğrulandı). Grace bitince
         *  normal tespit döner: reels'e yeniden girilirse yine bloklanır. */
        private const val REEL_HOME_GRACE_MS = 2500L
        /** Foreground'daki app'i periyodik limit kontrolü için tik aralığı (madde 25/13). */
        private const val IN_APP_CHECK_INTERVAL_MS = 30 * 1000L

        private val _liveUsageFlow = MutableStateFlow<Map<String, Long>>(emptyMap())
        val liveUsageFlow: StateFlow<Map<String, Long>> = _liveUsageFlow.asStateFlow()
    }

    // ─── Custom LifecycleOwner for ComposeView inside WindowManager ───────────
    /**
     * Android'in WindowManager'a eklenen View'lar için Lifecycle sağlamaz.
     * ComposeView, Composition'ı düzgün başlatmak için bir LifecycleOwner'a ihtiyaç duyar.
     * Bu sınıf Accessibility Service içinde bu ihtiyacı karşılar.
     */
    private inner class ServiceLifecycleOwner :
        LifecycleOwner,
        SavedStateRegistryOwner,
        ViewModelStoreOwner
    {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()

        override val lifecycle: Lifecycle
            get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry
        override val viewModelStore: ViewModelStore
            get() = store

        fun init() {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun destroy() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            store.clear()
        }
    }

    // ─── Coroutine Scope ──────────────────────────────────────────────────────
    private val serviceJob   = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private val mainHandler  = Handler(Looper.getMainLooper())

    // ─── DB ───────────────────────────────────────────────────────────────────
    private lateinit var db: AppDatabase
    private lateinit var userPrefs: UserPreferences

    // ─── In-Memory Tracking ───────────────────────────────────────────────────
    private val dailyUsageMs          = ConcurrentHashMap<String, Long>()
    @Volatile private var currentPackage: String? = null
    @Volatile private var sessionStartMs: Long    = 0L
    /** dailyUsageMs birikiminin ait olduğu günün başlangıcı — gece yarısı sızıntısını önler (madde 27). */
    @Volatile private var usageDayStart: Long     = 0L

    // ─── Limit Cache ──────────────────────────────────────────────────────────
    private val limitsCache = ConcurrentHashMap<String, AppLimit>()

    // ─── Blocking Cooldown ────────────────────────────────────────────────────
    @Volatile private var lastBlockedPackage: String? = null
    @Volatile private var lastBlockedTime: Long       = 0L

    // ─── Reels/Shorts Engelleme State ──────────────────────────────────────────
    private val reelUsageMs = ConcurrentHashMap<String, Long>()
    @Volatile private var reelContentStartMs: Long? = null
    @Volatile private var reelContentPackage: String? = null
    @Volatile private var reelUsageDayStart: Long = 0L
    @Volatile private var lastReelCheckMs: Long = 0L
    /** "Ana Sayfaya Dön" sonrası reel tespitinin susturulacağı zaman damgası (bkz. REEL_HOME_GRACE_MS). */
    @Volatile private var reelHomeGraceUntilMs: Long = 0L

    // ─── Oturum Sorusu ("girişte süre sor") State — in-memory, spec: 2026-07-17 ─
    private val sessionRemainingMs = ConcurrentHashMap<String, Long>()
    private val sessionLastExitMs  = ConcurrentHashMap<String, Long>()
    @Volatile private var activeSessionPackage: String? = null
    @Volatile private var activeSessionStartMs: Long = 0L
    private var sessionExpiryJob: Job? = null

    // ─── Overlay State ────────────────────────────────────────────────────────
    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView?     = null
    private var overlayOwner: ServiceLifecycleOwner? = null
    /** Şu an overlay ile engellenen paketin adı */
    @Volatile private var blockedOverlayPackage: String? = null

    // ─── Flush Job ────────────────────────────────────────────────────────────
    private var flushJob: Job? = null
    private var inAppCheckJob: Job? = null

    // ─── Screen OFF Receiver ──────────────────────────────────────────────────
    // NOT: unlock/oturum takibi artık burada DEĞİL — PhoneActivitySync (OS verisi) tek yazar
    // (bkz. CLAUDE.md madde 24). Bu receiver yalnızca app-kullanım süresini flush eder.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                Log.d(TAG, "Screen OFF → flushing to DB")
                commitReelSession()
                commitCurrentSession()
                // Ekran kilidi de "uygulamadan çıkış" sayılır: bütçe düşülür, grace penceresi başlar
                pauseActiveSession(switchingTo = null, now = System.currentTimeMillis())
                // Ekran kapandı = foreground oturumu bitti. currentPackage açık bırakılırsa
                // periyodik flush (10dk) kilitli geçen tüm süreyi foreground kullanım sanıp
                // saymaya devam eder (gece boyu +saatler); DailySyncWorker'ın maxOf-reconcile'ı
                // bu şişkin değeri asla düşüremez. Kilit açılınca app'in window-state event'i
                // oturumu yeniden başlatır (eksik kalırsa OS reconcile tamamlar — güvenli yön).
                currentPackage = null
                sessionStartMs = 0L
                flushToDB()
            }
        }
    }

    // ═══════════════════════ Lifecycle ════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        db            = AppDatabase.getDatabase(this)
        userPrefs     = UserPreferences(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        reelUsageDayStart = getTodayStartMs()
        usageDayStart     = getTodayStartMs()

        loadTodayUsageFromDB()
        observeLimitsCache()
        // API 33+ (targetSdk 36 burada): context-registered receiver'lar için
        // RECEIVER_EXPORTED/RECEIVER_NOT_EXPORTED zorunlu, yoksa SecurityException atar (madde 17).
        // ACTION_SCREEN_OFF system_server'dan gelir → NOT_EXPORTED ile sorunsuz teslim edilir
        // (madde 22'deki "Exported Denial" sorunu systemui'den gelen ACTION_USER_PRESENT içindi;
        // o action artık dinlenmiyor — unlock/oturum verisi PhoneActivitySync'ten, madde 24).
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        startPeriodicFlush()
        startInAppLimitCheck()
        Log.d(TAG, "✅ AppAccessibilityService STARTED (Event-Driven + Overlay)")
    }

    override fun onDestroy() {
        flushJob?.cancel()
        inAppCheckJob?.cancel()
        sessionExpiryJob?.cancel()
        unregisterReceiver(screenReceiver)
        commitCurrentSession()
        commitReelSession()
        removeOverlay()   // WindowLeaked önle
        runBlocking(Dispatchers.IO) { flushToDBSuspend() }
        serviceJob.cancel()
        Log.d(TAG, "AppAccessibilityService STOPPED")
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ═══════════════════════ Core Event Handler ════════════════════════════════

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

        // Reel oturumu, ignore edilen pakete (launcher/Home) geçişte de kapanmalı — bu yüzden
        // shouldIgnorePackage guard'ından ÖNCE commit et. currentPackage değişmediyse hâlâ aynı
        // reel'deyiz, kapatma (aksi halde her state event'inde oturum yanlışlıkla biterdi).
        val now = System.currentTimeMillis()
        if (newPackage != currentPackage) {
            commitReelSession()
            // Oturum bütçesi de ignore edilen pakete (launcher) geçişte duraklamalı —
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
    }

    // ═══════════════════════ Session Management ════════════════════════════════

    private fun commitSessionAt(now: Long) {
        val pkg   = currentPackage ?: return
        val start = sessionStartMs
        if (start <= 0L) return
        val elapsed = now - start
        if (elapsed < 500L) return
        dailyUsageMs.merge(pkg, elapsed, Long::plus)
        _liveUsageFlow.value = HashMap(dailyUsageMs)
        Log.d(TAG, "⏱ $pkg +${elapsed}ms → total: ${dailyUsageMs[pkg]}ms")
    }

    private fun commitCurrentSession() {
        commitSessionAt(System.currentTimeMillis())
    }

    // ═══════════════════════ Blocking Logic ════════════════════════════════════

    /** @return true ise bu paket bloklu (overlay zaten görünüyor ya da şimdi tetiklendi). */
    private fun checkAndBlockIfNeeded(packageName: String, now: Long): Boolean {
        // Bu paket için overlay zaten gösteriliyorsa tekrar tetikleme
        if (blockedOverlayPackage == packageName) return true
        // Çok kısa aralıklı yinelenen event'lere karşı debounce (gerçek çıkış-giriş bunu aşar)
        if (packageName == lastBlockedPackage && now - lastBlockedTime < BLOCK_COOLDOWN_MS) {
            Log.d(TAG, "Debounce aktif: $packageName")
            return false
        }
        val limit = limitsCache[packageName] ?: return false
        if (!limit.isTimeLimitEnabled && !limit.isScheduleEnabled) return false

        if (limit.isTimeLimitEnabled && limit.dailyLimitMinutes > 0) {
            // Henüz commit edilmemiş (mevcut oturumdaki) süreyi de say — yoksa periyodik
            // kontrol dailyUsageMs güncellenene kadar (paket değişimi/10dk flush) hep eski
            // değeri görür ve limiti aşan kesintisiz oturumu asla yakalayamaz (madde 13/25).
            val liveElapsed = if (packageName == currentPackage && sessionStartMs > 0L) {
                (now - sessionStartMs).coerceAtLeast(0L)
            } else 0L
            val usedMinutes = (((dailyUsageMs[packageName] ?: 0L) + liveElapsed) / 60_000L).toInt()
            Log.d(TAG, "${limit.appName}: $usedMinutes/${limit.dailyLimitMinutes} dk")
            if (usedMinutes >= limit.dailyLimitMinutes) {
                triggerBlock(packageName, limit.appName, "Günlük limit (${limit.dailyLimitMinutes}dk) doldu")
                return true
            }
        }

        if (limit.isScheduleEnabled && limit.blockedDays.isNotEmpty()) {
            val cal        = Calendar.getInstance()
            val currentDay = listOf("SUN","MON","TUE","WED","THU","FRI","SAT")[cal.get(Calendar.DAY_OF_WEEK) - 1]
            if (currentDay in limit.blockedDays.split(",").map { it.trim() }) {
                val cur   = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                val start = limit.blockStartHour * 60 + limit.blockStartMinute
                val end   = limit.blockEndHour   * 60 + limit.blockEndMinute
                val blocked = if (start <= end) cur in start..end else cur >= start || cur <= end
                if (blocked) {
                    triggerBlock(packageName, limit.appName, "Zamanlama engeli aktif")
                    return true
                }
            }
        }
        return false
    }

    private fun triggerBlock(packageName: String, appName: String, reason: String) {
        Log.d(TAG, "🚫 BLOCKING via Overlay: $appName ($packageName) — $reason")
        lastBlockedPackage = packageName
        lastBlockedTime    = System.currentTimeMillis()
        mainHandler.post { showBlockingOverlay(packageName, appName, reason) }
    }

    // ═══════════════════════ Reels/Shorts Engelleme ════════════════════════════

    private fun checkReelContent() {
        val pkg = currentPackage ?: return
        if (blockedOverlayPackage != null) return // overlay zaten gösteriliyor
        val detector = ReelDetectorRegistry.detectorFor(pkg) ?: return

        // Throttle, pahalı işlerden (SharedPreferences okuması + node ağacı taraması) ÖNCE —
        // hızlı kaydırmada content-changed event'i çok sık ateşlenir, gereksiz işi keser.
        val now = System.currentTimeMillis()
        // "Ana Sayfaya Dön" grace penceresi: app home'a geçerken reel hâlâ ağaçta olabilir; tespit
        // etmeyip döngüyü önle (bkz. REEL_HOME_GRACE_MS).
        if (now < reelHomeGraceUntilMs) return
        if (now - lastReelCheckMs < REEL_CHECK_THROTTLE_MS) return
        lastReelCheckMs = now

        if (!isReelBlockEnabledFor(pkg)) return

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
        when (reelBlockModeFor(pkg)) {
            ReelBlockMode.INSTANT -> triggerReelBlock(pkg)
            ReelBlockMode.BUDGET -> {
                resetReelUsageIfNewDay()
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
        resetReelUsageIfNewDay()
        reelUsageMs.merge(pkg, elapsed, Long::plus)
    }

    /** Gün dönümünde bütçe sayacını sıfırlar. Not: gece yarısını aşan tek bir kesintisiz reel
     *  oturumu, tüm süresiyle bittiği güne yazılır (madde 21'deki oturum-güne-atama ruhuyla aynı,
     *  best-effort in-memory sayaç — bölme yapılmadı, kabul edilebilir edge-case). */
    private fun resetReelUsageIfNewDay() {
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
                onGoToAppHome = { navigateToAppHome(packageName) }
            )
        }
    }

    /** Reel bloğundan çıkış: kullanıcıyı reels/shorts yüzeyinden çıkarmak için sistem GERİ event'i
     *  gönderir. Instagram/YouTube back-stack'i Home sekmesinde köklendiğinden reels/shorts sekmesinden
     *  GERİ → uygulamanın Ana Sayfa feed'ine iner (home feed reel-oynatıcı dizesiyle eşleşmez → döngü yok).
     *
     *  Neden ACTION_CLICK/gesture-tap DEĞİL: ACTION_CLICK Instagram'da no-op (Litho sanal node'lar),
     *  alt-nav node bounds'una gesture-tap ise iki app'te de güvenilmez çıktı (reel sabit kaldı / yanlış
     *  video açıldı) — cihaz teşhisiyle doğrulandı. GLOBAL_ACTION_BACK gerçek sistem event'i, ikisinde de
     *  onurlandırılır ve koordinat/tıklanabilirlik sorunlarını bypass eder.
     *
     *  currentPackage'ı geri yükler: removeOverlayInternal onu null'lar (app-limit bloğu için: uygulamadan
     *  çıkış → yeni giriş event'i sıfırlar). Ama app-içi geçişte paket sınırı geçilmez, WINDOW_STATE_CHANGED
     *  tetiklenmez → null kalırsa checkReelContent hep erken döner, kullanıcı reels'e geri girince
     *  bloklanmazdı (cihaz teşhisiyle doğrulandı). */
    private fun navigateToAppHome(pkg: String) {
        performGlobalAction(GLOBAL_ACTION_BACK)
        val now = System.currentTimeMillis()
        commitReelSession(now)              // reels'ten çıkıldı → oturumu kapat (bütçe modu için)
        reelHomeGraceUntilMs = now + REEL_HOME_GRACE_MS
        currentPackage = pkg
        sessionStartMs = now
    }

    // ═══════════════════════ Accessibility Overlay ════════════════════════════

    /**
     * TYPE_ACCESSIBILITY_OVERLAY: SYSTEM_ALERT_WINDOW iznine gerek duymaz.
     * AccessibilityService tarafından doğrudan çizilebilir.
     *
     * ComposeView'ı WindowManager'a eklemek için:
     * 1. ServiceLifecycleOwner oluştur ve ViewTree'ye set et
     * 2. ComposeView.setContent { } ile UI çiz
     * 3. WindowManager.addView() ile ekrana ekle
     */
    private fun showBlockingOverlay(
        packageName: String,
        appName: String,
        reason: String,
        onGoToAppHome: (() -> Unit)? = null
    ) {
        // Zaten bir overlay varsa önce kaldır
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
            // ComposeView'ın Composition'ı başlatması için gerekli ViewTree owner'lar
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)

            setContent {
                // Reel bloğunda (onGoToAppHome != null) buton kullanıcıyı uygulamanın kendi Ana
                // Sayfa'sına gönderir. App-limit/oturum bloğunda tüm uygulama engelli olduğu için
                // Android launcher'a (GLOBAL_ACTION_HOME) gönderir — orada app-içi Home anlamsız.
                val goToAppHome = onGoToAppHome
                BlockingOverlayContent(
                    appName     = appName,
                    packageName = packageName,
                    reason      = reason,
                    primaryLabel = if (goToAppHome != null) "Ana Sayfaya Dön" else "Ana Ekrana Dön",
                    onPrimary   = {
                        if (goToAppHome != null) {
                            removeOverlayInternal()  // main thread'deyiz; sync kaldır → node ağacı app'e döner
                            goToAppHome()
                        } else {
                            removeOverlay()
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        }
                    }
                )
            }
        }

        try {
            lifecycleOwner.init()
            windowManager?.addView(composeView, params)
            overlayView = composeView
            Log.d(TAG, "✅ Overlay gösterildi: $appName")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Overlay eklenemedi", e)
            lifecycleOwner.destroy()
            overlayOwner = null
            blockedOverlayPackage = null
        }
    }

    /** Girişte "bu oturumda kaç dakika?" sorusu — blok overlay'i ile aynı pencere altyapısı.
     *  blockedOverlayPackage set edilir (tek-overlay değişmezi): reel taraması ve tekrar-blok
     *  soru görünürken susar. Not: removeOverlayInternal currentPackage'ı null'lar — "Başla"
     *  sonrası uygulamanın ilk window-state event'i takibi yeniden başlatır
     *  (activeSessionPackage == pkg olduğu için soru tekrar gelmez). */
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

    /** Public — dışarıdan (button click, onDestroy) güvenle çağrılabilir */
    private fun removeOverlay() {
        mainHandler.post { removeOverlayInternal() }
    }

    /** Main thread'de çalışmalı */
    private fun removeOverlayInternal() {
        val view  = overlayView  ?: return
        val owner = overlayOwner ?: return
        try {
            owner.destroy()        // Composition'ı düzgün kapat → WindowLeaked önle
            windowManager?.removeView(view)
        } catch (e: Exception) {
            Log.e(TAG, "Overlay kaldırma hatası", e)
        } finally {
            overlayView           = null
            overlayOwner          = null
            blockedOverlayPackage = null
            currentPackage        = null  // Bir sonraki açılışta yeniden bloklanabilsin
            Log.d(TAG, "🗑️ Overlay kaldırıldı")
        }
    }

    // ═══════════════════════ Overlay Compose UI ════════════════════════════════

    @Composable
    private fun BlockingOverlayContent(
        appName: String,
        packageName: String,
        reason: String,
        primaryLabel: String,
        onPrimary: () -> Unit
    ) {
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
            // Arka plan dekoratif çember
            Box(
                modifier = Modifier
                    .size(300.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3B0764).copy(alpha = 0.25f))
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.padding(horizontal = 40.dp)
            ) {
                // App Icon
                RealAppIcon(
                    packageName  = packageName,
                    appName      = appName,
                    size         = 88.dp,
                    cornerRadius = 22.dp
                )

                // "ENGELLENDİ" etiketi
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF7C3AED).copy(alpha = 0.2f))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text       = "ENGELLENDİ",
                        color      = Color(0xFFC084FC),
                        fontSize   = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp
                    )
                }

                // App name
                Text(
                    text       = appName,
                    color      = Color.White,
                    fontSize   = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center
                )

                // Reason
                Text(
                    text      = reason,
                    color     = Color(0xFF9CA3AF),
                    fontSize  = 14.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Ana Sayfa/Ana Ekran butonu
                Button(
                    onClick  = onPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape    = RoundedCornerShape(14.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF7C3AED)
                    )
                ) {
                    Text(
                        text       = primaryLabel,
                        fontSize   = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color      = Color.White
                    )
                }
            }
        }
    }

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
            // Arka plan dekoratif çember (blok overlay'i ile aynı görsel dil)
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
                                .background(
                                    if (isSelected) Color(0xFF7C3AED)
                                    else Color(0xFF7C3AED).copy(alpha = 0.15f)
                                )
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
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF7C3AED)
                    )
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

    // ═══════════════════════ DB Initialization ═════════════════════════════════

    private fun loadTodayUsageFromDB() {
        serviceScope.launch(Dispatchers.IO) {
            val today = getTodayStartMs()
            usageDayStart = today
            seedDailyUsageFromDB(today)
        }
    }

    /** dailyUsageMs'i verilen günün DB kayıtlarından (DailySyncWorker OS reconcile) yeniden tohumlar. */
    private suspend fun seedDailyUsageFromDB(dayStart: Long) {
        try {
            val logs = db.appUsageDao().getUsageLogsForDate(dayStart).first()
            dailyUsageMs.clear()
            logs.forEach { dailyUsageMs[it.packageName] = it.durationMs }
            _liveUsageFlow.value = HashMap(dailyUsageMs)
            Log.d(TAG, "📥 DB'den ${logs.size} kayıt yüklendi (gün=$dayStart)")
        } catch (e: Exception) { Log.e(TAG, "DB yükleme hatası", e) }
    }

    private fun observeLimitsCache() {
        serviceScope.launch(Dispatchers.IO) {
            db.appUsageDao().getActiveLimits().collect { limits ->
                limitsCache.clear()
                limits.forEach { limitsCache[it.packageName] = it }
                Log.d(TAG, "🔄 Limits cache: ${limitsCache.size} aktif")
            }
        }
    }

    // ═══════════════════════ Periodic DB Flush ═════════════════════════════════

    private fun startPeriodicFlush() {
        flushJob = serviceScope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                val now = System.currentTimeMillis()
                commitSessionAt(now)
                flushToDBSuspend()
                if (currentPackage != null) sessionStartMs = System.currentTimeMillis()
            }
        }
    }

    private fun flushToDB() {
        serviceScope.launch(Dispatchers.IO) { flushToDBSuspend() }
    }

    // ═══════════════════════ Periodic In-App Limit Check (madde 25) ═══════════

    /**
     * Eskiden checkAndBlockIfNeeded YALNIZCA paket değişince (foreground app girişinde)
     * tetikleniyordu (madde 13'te bilinen boşluk). Kullanıcı limit dolduktan sonra aynı
     * uygulamada kesintisiz kalırsa hiç bloklanmıyordu. Bu job foreground'daki paketi
     * 30 sn'de bir kontrol eder — yalnızca aktif limiti olan bir paket foreground'dayken
     * anlamlı iş yapar (limitsCache boşsa/paket limitsizse tek satır kontrol edip döner),
     * pil maliyeti ihmal edilebilir düzeyde kalır.
     */
    private fun startInAppLimitCheck() {
        inAppCheckJob = serviceScope.launch {
            while (isActive) {
                delay(IN_APP_CHECK_INTERVAL_MS)
                val pkg = currentPackage ?: continue
                if (blockedOverlayPackage == pkg) continue // zaten bloklu, tekrar tetikleme
                checkActiveSessionExpiry(System.currentTimeMillis()) // oturum bütçesi emniyet ağı
                if (limitsCache[pkg] == null) continue     // limitsiz app, iş yok
                checkAndBlockIfNeeded(pkg, System.currentTimeMillis())
            }
        }
    }

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

    /** Kalan süre dolduğu anda blok gelsin diye tam süreye zamanlanmış tek atımlık kontrol
     *  (30 sn'lik in-app tick tek başına blok'u ortalama 15 sn geciktirirdi). */
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

    private suspend fun flushToDBSuspend() {
        val today = getTodayStartMs()
        // Birikim, usageDayStart gününe aittir (henüz başlatılmadıysa bugüne). Sabit "today" yazmak
        // gece yarısını aşan servis için dünün toplamını bugünün kovasına sızdırırdı (madde 27).
        val bucketDay = usageDayStart.takeIf { it > 0L } ?: today

        if (dailyUsageMs.isNotEmpty()) {
            try {
                val logs = dailyUsageMs.entries.map { (pkg, ms) ->
                    UsageLog(
                        packageName = pkg,
                        appName     = AppInfoUtils.getAppName(this@AppAccessibilityService, pkg),
                        date        = bucketDay,
                        durationMs  = ms
                    )
                }
                db.appUsageDao().upsertDurations(logs)
                Log.d(TAG, "💾 DB flush: ${logs.size} kayıt (gün=$bucketDay)")
            } catch (e: Exception) { Log.e(TAG, "DB flush hatası", e) }
        }

        // Gece yarısı geçildiyse önceki günün birikimi yukarıda kendi gününe yazıldı; şimdi birikimi
        // sıfırla ve yeni günü DB'den (DailySyncWorker OS reconcile) yeniden tohumla ki dünün toplamı
        // bugüne taşınmasın. seedDailyUsageFromDB clear() de yapar.
        if (bucketDay != today) {
            usageDayStart = today
            seedDailyUsageFromDB(today)
            Log.d(TAG, "🌅 Gün döndü ($bucketDay → $today): birikim sıfırlandı")
        }
    }

    // ═══════════════════════ Helpers ══════════════════════════════════════════

    private fun getTodayStartMs(): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun shouldIgnorePackage(pkg: String): Boolean {
        if (pkg == this.packageName) return true
        if (pkg.startsWith("com.android.systemui")) return true
        if (pkg.startsWith("android")) return true
        return pkg in setOf(
            "com.android.launcher", "com.android.launcher3",
            "com.miui.home", "com.samsung.android.app.launcher",
            "com.google.android.apps.nexuslauncher", "com.oneplus.launcher"
        )
    }
}
