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
import com.example.traccerapp.service.reeldetection.isReelBlockSuppressed

class AppAccessibilityService : AccessibilityService() {

    // ─── Companion ────────────────────────────────────────────────────────────
    companion object {
        private const val TAG               = "AppAccessibilityService"
        private const val FLUSH_INTERVAL_MS = 10 * 60 * 1000L
        private const val BLOCK_COOLDOWN_MS = 800L
        private const val REEL_CHECK_THROTTLE_MS = 500L
        private const val REEL_SUPPRESS_DURATION_MS = 60 * 60 * 1000L
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

    // ─── Limit Cache ──────────────────────────────────────────────────────────
    private val limitsCache = ConcurrentHashMap<String, AppLimit>()

    // ─── Blocking Cooldown ────────────────────────────────────────────────────
    @Volatile private var lastBlockedPackage: String? = null
    @Volatile private var lastBlockedTime: Long       = 0L

    // ─── Reels/Shorts Engelleme State ──────────────────────────────────────────
    private val reelUsageMs = ConcurrentHashMap<String, Long>()
    @Volatile private var reelContentStartMs: Long? = null
    @Volatile private var reelContentPackage: String? = null
    private val reelSuppressUntilMs = ConcurrentHashMap<String, Long>()
    @Volatile private var reelUsageDayStart: Long = 0L
    @Volatile private var lastReelCheckMs: Long = 0L

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
                commitCurrentSession()
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

    private fun checkAndBlockIfNeeded(packageName: String, now: Long) {
        // Bu paket için overlay zaten gösteriliyorsa tekrar tetikleme
        if (blockedOverlayPackage == packageName) return
        // Çok kısa aralıklı yinelenen event'lere karşı debounce (gerçek çıkış-giriş bunu aşar)
        if (packageName == lastBlockedPackage && now - lastBlockedTime < BLOCK_COOLDOWN_MS) {
            Log.d(TAG, "Debounce aktif: $packageName")
            return
        }
        val limit = limitsCache[packageName] ?: return
        if (!limit.isTimeLimitEnabled && !limit.isScheduleEnabled) return

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
                return
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
                if (blocked) triggerBlock(packageName, limit.appName, "Zamanlama engeli aktif")
            }
        }
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
        onAllowTemporarily: (() -> Unit)? = null
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
        onGoHome: () -> Unit,
        onAllowTemporarily: (() -> Unit)? = null
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

                // Go Home button
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
            }
        }
    }

    // ═══════════════════════ DB Initialization ═════════════════════════════════

    private fun loadTodayUsageFromDB() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val logs = db.appUsageDao().getUsageLogsForDate(getTodayStartMs()).first()
                logs.forEach { dailyUsageMs[it.packageName] = it.durationMs }
                _liveUsageFlow.value = HashMap(dailyUsageMs)
                Log.d(TAG, "📥 DB'den ${logs.size} kayıt yüklendi")
            } catch (e: Exception) { Log.e(TAG, "DB yükleme hatası", e) }
        }
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
                if (limitsCache[pkg] == null) continue     // limitsiz app, iş yok
                checkAndBlockIfNeeded(pkg, System.currentTimeMillis())
            }
        }
    }

    private suspend fun flushToDBSuspend() {
        if (dailyUsageMs.isEmpty()) return
        try {
            val today = getTodayStartMs()
            val logs  = dailyUsageMs.entries.map { (pkg, ms) ->
                UsageLog(
                    packageName = pkg,
                    appName     = AppInfoUtils.getAppName(this@AppAccessibilityService, pkg),
                    date        = today,
                    durationMs  = ms
                )
            }
            db.appUsageDao().upsertDurations(logs)
            Log.d(TAG, "💾 DB flush: ${logs.size} kayıt")
        } catch (e: Exception) { Log.e(TAG, "DB flush hatası", e) }
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
