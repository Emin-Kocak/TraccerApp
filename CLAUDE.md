# TraccerApp — Mimari ve Kararlar

Android uygulaması. Ekran süresi takibi + AccessibilityService tabanlı uygulama engelleme. Kotlin + Jetpack Compose, tek Activity (`MainActivity`), DI framework yok (Hilt/Koin yok, manuel constructor injection).

Bu dosya gelecekteki Claude Code oturumları için sistemin **şu an nasıl çalıştığını** ve **neden böyle karar verildiğini** anlatır. Kod okumadan önce buraya bak.

## Build

- `compileSdk 37`, `minSdk 32` (Android 12+), `targetSdk 36`, Java/Kotlin `VERSION_21`.
- Compose derleyici plugin default versiyonuyla, ayrı Kotlin compiler extension pin yok.
- Room (KSP), WorkManager. **Vico chart kütüphanesi bağımlı ama kullanılmıyor** — Raporlar ekranındaki grafik elle Box/Column ile çizildi, Vico'ya dokunulmadı. Kaldırmak istenirse `app/build.gradle.kts`'den 3 satır sil.
- DI yok, network/Retrofit yok, Accompanist yok.
- `./gradlew.bat :app:compileDebugKotlin --console=plain` — hızlı derleme kontrolü için bu yeterli, tam build/test şart değil.

## Katmanlar

```
MainActivity (tek activity, DI yok)
 └─ MainScreen.kt — tab state burada tutuluyor (sealed Screen/nav-graph YOK, basit state)
     ├─ DashboardTab (Bugünkü özet — hero kart artık `SummaryHeroCard`, bkz. madde 12)
     ├─ ReportsScreen.kt (haftalık grafik)
     ├─ HourlyTrackingTab (Takip — saatlik zaman çizelgesi, bkz. madde 12; eski UsageScreen.kt silindi)
     └─ Limitler → BlockingSettingsScreen.kt

service/
 ├─ AppAccessibilityService.kt — GERÇEK engelleme mantığı burada (foreground app izleme + block)
 ├─ TrackingService.kt — SADECE foreground-service bildirimi göstermek için var, iş yapmıyor
 ├─ DailySyncWorker.kt — WorkManager, günlük UsageStatsManager senkronu
 └─ BlockingNotificationReceiver.kt — "FORCE_STOP_APP" broadcast'i BlockingActivity'yi açar

data/
 ├─ AppDatabase.kt (Room, tek DB "traccer_database_v2", şu an v2 — bkz. madde 14)
 ├─ AppUsageDao.kt (UsageLog + AppLimit + UnlockEvent sorguları)
 ├─ AppUsageEntity.kt (UsageLog, AppLimit, UnlockEvent entity'leri)
 ├─ AppUsageRepository.kt (ince pass-through, çoğu ekran bunu atlayıp DAO'ya direkt gidiyor)
 └─ UserPreferences.kt (SharedPreferences wrapper — tema, hedef, gizli app'ler, min kullanım filtresi)

ui/screens/DashboardCharts.kt — `CategoryDonutChart` + `HourlyBarChart` (Dashboard'da kullanılan, elle çizilmiş Canvas grafikleri; Vico'ya dokunulmadı, bkz. madde 14)

BlockingActivity.kt — engelleme UI'ı: tam ekran Activity (overlay DEĞİL)
```

## Kritik kararlar / gotcha'lar

### 1. Engelleme = AppAccessibilityService, tracking service sadece kabuk
`TrackingService` foreground service zorunluluğunu karşılamak için var, içi boş (`START_STICKY`, bildirim gösterip duruyor). Gerçek "hangi app önde, ne zaman blokla" mantığının tamamı `AppAccessibilityService`'de. Yeni engelleme özelliği eklerken oraya bak, `TrackingService`'e değil.

### 2. Blok UI = tam ekran Activity, overlay değil
`BlockingActivity` bir `TYPE_ACCESSIBILITY_OVERLAY` window değil, gerçek bir Activity (`excludeFromRecents`, `noHistory`, `showWhenLocked`, `turnScreenOn`, `singleTop`). `BlockingNotificationReceiver` → `"FORCE_STOP_APP"` broadcast → bu Activity açılır. Geri tuşu ana ekrana yönlendirir, kapatmaz.

### 3. Overlay self-trigger bug fix (çözüldü)
Zamanlama bazlı engelleme eskiden anında kayboluyordu. Kök neden: overlay window'da `FLAG_NOT_FOCUSABLE` eksikti → kendi kendine accessibility event üretip "kullanıcı app değiştirdi" sanıyordu. Fix: flag eklendi + `AppAccessibilityService.onAccessibilityEvent`'te `if (newPackage == this.packageName) return` guard'ı.

### 4. Exit-reopen bypass fix (çözüldü)
Eskiden 5 saniyelik blanket cooldown (`BLOCK_COOLDOWN_MS`) yüzünden app'ten hızlı çıkıp girince blok atlanıyordu. Fix: birincil kontrol artık "bu paket için overlay şu an gösteriliyor mu" (`blockedOverlayPackage == packageName`), zaman bazlı debounce sadece 800ms'e indirildi (ikincil koruma).

### 5. DAO sorgularında ORDER BY yok — sıralama Kotlin tarafında
`AppUsageDao` sorguları (`getUsageLogsForDate`, `getUsageLogsBetween`, `getAllLimits`, `getActiveLimits`) SQL'de sıralamıyor. Bunun bir kez gerçek bug'a yol açtığı görüldü: Dashboard'da "En Çok Kullanılan" ve top-6 liste, SQLite'ın insertion-order döndürdüğü sırayla gösteriliyordu. Fix `MainScreen.kt`'de `.sortedByDescending { it.durationMs }` eklemekle yapıldı (SQL'e dokunulmadı). **Yeni bir ekran/özellik `logs` çekiyorsa, sıralamayı SQL'de değil çağrı noktasında elle yapman gerekiyor — DAO bunu garanti etmiyor.**

### 6. `fallbackToDestructiveMigration()` aktif
`AppDatabase` şema değişikliğinde migration yazmak yerine veriyi siliyor. DB adı zaten bir kez `"traccer_database_v2"` yapılarak eski şema terk edilmiş. Yeni entity/kolon eklerken bunu bilerek yap — kullanıcı verisi (UsageLog geçmişi) sessizce silinebilir.

### 7. Tema sistemi: CompositionLocal, global refactor değil
`ui/theme/Color.kt`: `LocalIsDarkTheme` (`staticCompositionLocalOf { true }`) + `@Composable get()` property'ler (`DarkBg`, `TextPrimary` vb.) tema bazlı renk döndürüyor. Aksan/durum/kategori renkleri (`PurplePrimary`, `StatusGreen`, `CategorySocial` vb.) her iki temada da sabit — Composable değil, düz `val`.
- **Gotcha**: Composable-only property'ler non-composable fonksiyonlardan çağrılamaz. İki yerde bu patladı: `Type.kt` (ölü kod olduğu için düzeltme = silme) ve `UsageReportScreen.kt`'deki `fetchTodayHourBlocks` (suspend fun) — çözüm: rengi composable scope'ta yakalayıp parametre olarak geçmek (`idleColor: Color` pattern).
- Yeni bir yerde tema rengine ihtiyaç olursa ve o yer composable değilse, aynı "parametre olarak geçir" desenini kullan.

### 8. Kategori bazlı renklendirme sadece Raporlar grafiğinde
`AppCategoryUtils.categorize()` (hardcoded ~40 paket + `ApplicationInfo.category` API 26+ fallback) sadece `ReportsScreen.kt`'nin haftalık grafiğinde kullanılıyor, uygulama genelinde değil. Diğer yerlerde uygulama rengi/ikon farklı mantıkla belirleniyor (`AppIconUtils`, `AppColors` hash-listesi).

### 9. Navigasyon: sealed Screen / nav-graph yok
`MainScreen.kt` içinde basit tab state (`currentScreen` gibi) ile geziniliyor. Compose Navigation kütüphanesi kullanılmıyor. Yeni ekran eklerken bu deseni takip et, ayrı bir NavHost kurma.

### 10. `UsageViewModel` yarı-kullanılıyor
`UsageScreen.kt` render için ViewModel'in `StateFlow`'unu değil, Room'dan direkt ayrı bir Flow'u kullanıyor. ViewModel sadece `refreshUsageStats()` side-effect'ini tetiklemek için var. Kafa karıştırıcı ama şu an kasıtlı değişmedi — yeni state ihtiyacı olursa muhtemelen doğrudan DAO Flow'una bağlanmak, bu ViewModel'i genişletmekten daha tutarlı.

### 11. Bilinen ama dokunulmamış tuhaflıklar (temizlik değil, bilgi amaçlı)
- `RECEIVE_BOOT_COMPLETED` izni manifest'te var ama karşılık gelen `BroadcastReceiver` yok — ölü izin.
- `AppIconUtils.invalidateCache()` hiçbir yerden çağrılmıyor.
- `FileProvider` tanımlı ama kod tarafında referans yok.
- Bunlar "temizle" denmeden silinmedi — kullanıcı onayı olmadan silinmeyecek.

### 12. Dashboard hero kart ↔ Takip sekmesi (2026-07-07 değişikliği)
- Dashboard'daki "Toplam ekran süresi" kutusu kaldırıldı, yerine `UsageReportScreen.kt`'deki `SummaryHeroCard` composable'ı (Zaman Raporu ekranındaki "kalan süre + ilerleme çubuğu + Saatlik detayları gör" kartı) `DashboardTab` içinde de kullanılıyor. **Veri kaynağı DashboardTab'ın kendi `totalMs`'i (DB log'ları, hidden/min-usage filtreli)** — `SummaryHeroCard`'ın Zaman Raporu'ndaki kullanımı `fetchTodayHourBlocks` (UsageStatsManager event'leri) kullanıyor. İki farklı pipeline, kasıtlı olarak birbirine bağlanmadı (bkz. madde 5 — DAO/DB tarafı zaten ayrı sıralanıyordu).
- Kartın `onDetailClick`'i artık Dashboard'da **Takip sekmesine geçiş** (`selectedTab = 2`) yapıyor, "Zaman Raporu" text-button'u (ayrı, `Screen.Report`) değişmedi.
- Eski `UsageScreen.kt` (Takip sekmesi, "Canlı Takip" — düz DB log listesi) **silindi**. Takip sekmesi artık `UsageReportScreen.kt` içindeki yeni `HourlyTrackingTab()` composable'ını render ediyor — bu, `UsageDetailScreen`'in body'siyle aynı saatlik zaman çizelgesini (`fetchTodayHourBlocks` + `buildTimelineItems` + `EmptyRangeRow`/`ActiveHourRow`) gösteriyor ama kendi `TopAppBar`'ı yok (MainScreen'in ortak üst barını kullanıyor), kendi inline yenile butonu var.
- `Screen.Report` → `Screen.Detail` push-navigasyonu (Dashboard → "Zaman Raporu" → "Saatlik detayları gör") **dokunulmadan bırakıldı** — `UsageDetailScreen` hâlâ var ve aynı veriyi ayrıca (geri tuşuyla) gösterebiliyor. Kasıtlı: kullanıcı sadece Takip sekmesinin değişmesini istedi, bu ikincil akışın kaldırılması istenmedi.

### 13. Bilinen mimari boşluk (düzeltilmedi, madde 1/4 ile çelişebilir)
`AppAccessibilityService.checkAndBlockIfNeeded` sadece **paket değişince** (`onAccessibilityEvent`'te `newPackage != currentPackage`) tetikleniyor. Yani kullanıcı limit dolduktan sonra da aynı uygulamada **tek bir kesintisiz oturumda** kalırsa tekrar bloklanmıyor — limit kontrolü sadece uygulamaya *girişte* yapılıyor, içeride geçirilen süre boyunca periyodik kontrol yok. 10 dakikalık `startPeriodicFlush` döngüsü buna bağlanabilir ama bu, ayrı bir "foreground süresince periyodik kontrol" özelliği gerektirir (çok kaba/geç olur, exit-reopen fix'iyle çakışma riski var). **Bilerek bu oturumda dokunulmadı** — kullanıcı onayı olmadan blocking-timing mimarisi değiştirilmeyecek.

### 14. Telefon "açılma" (unlock) sayısı — yeni özellik (2026-07-07)
- Yeni `UnlockEvent` entity (`unlock_events` tablosu, sadece `id` + `timestampMs`). Kaynak: **`AppAccessibilityService`'nin zaten dinlediği `ACTION_USER_PRESENT`** broadcast'i (ekran kilidi açılınca ateşleniyor) — `recordUnlockEvent()` her tetiklendiğinde bir satır ekliyor. `UsageStatsManager` üzerinden (`fetchTodayHourBlocks`'un zaten dolaştığı event akışı) geçmişe dönük/OS seviyesinde bir unlock sinyali alınıp alınamayacağı **cihazda doğrulanamadı** (bu ortamda emülatör yok); OEM'e göre (MIUI/OneUI) güvenilmez olabileceği için tercih edilmedi. **Sonuç**: unlock sayısı yalnızca bu özellik eklendikten sonra, ve sadece Erişilebilirlik servisi açıkken birikir — geçmiş güne dönük veri yok, servis kapalıyken sayım durur (mevcut engelleme özelliğiyle aynı bağımlılık).
- `AppDatabase` v1 → v2: **additive `Migration(1,2)`** eklendi (`CREATE TABLE unlock_events`), `fallbackToDestructiveMigration()` sadece yedek olarak duruyor. Yani mevcut `usage_logs`/`app_limits` verisi **silinmedi** — madde 6'daki genel uyarı (yeni entity eklerken veri silinebilir) bu değişiklik için geçerli değil, çünkü migration yazıldı.
- Kategori→renk eşlemesi (`categoryColor`) `ReportsScreen.kt`'den `ui/theme/Color.kt`'ye taşındı (paylaşılan, Composable olmayan top-level fun) — Dashboard'daki `CategoryDonutChart` da aynı eşlemeyi kullanıyor. Raporlar'daki private kopyası silindi.
- Dashboard'daki saatlik grafikler (`HourlyBarChart` × 2: dakika + unlock sayısı) **iki farklı veri kaynağından** besleniyor: kullanım-dakikası grafiği `fetchTodayHourBlocks` (UsageStatsManager, saat kırılımlı), unlock grafiği Room'daki `UnlockEvent` listesi (saat kırılımı Kotlin tarafında `Calendar` ile). Kategori çemberi ve metrik kartlar ise Dashboard'ın kendi DB tabanlı `logs`'unu kullanıyor. Üç ayrı pipeline aynı ekranda — kasıtlı, birbirine "senkronize" edilmeye çalışılmadı (madde 5'teki DAO-sıralama notuyla aynı ruh).
- Raporlar ekranına (`WeeklyUsageChartCard`) günlük unlock sayısı eklendi (`unlockCountsByDay`, seçili günün toplam ekran süresinin yanına).

### 15. Dashboard saatlik grafikler artık tıklanabilir (2026-07-07)
`DashboardCharts.kt`'deki `HourlyBarChart` her saat çubuğunu ayrı `clickable` yaptı (tıklanan saat state'i composable içinde `remember` ile tutuluyor, dışarı taşınmadı — iki grafik instance'ı birbirinden bağımsız). Seçili saat, grafiğin üstünde sabit yükseklikli bir alanda `"$saat - $değer"` gösteriyor (layout zıplamasın diye seçim yokken de aynı alan boş duruyor). `valueLabel: (Int) -> String` parametresiyle iki farklı birim (dakika / kez) aynı composable'dan besleniyor.

### 16. Raporlar tarih şeridi artık haftalık sayfalama (2026-07-07)
Eski `DateScrollStrip` (180 günlük sürekli kaydırma, gün gün) kaldırıldı. Yerine `HorizontalPager` (Compose Foundation'ın stabil pager API'si, Accompanist değil — madde "Build" bölümündeki "Accompanist yok" kuralına uyar) ile **haftalık sayfalama** geldi: her sayfa `WeekDateRow` ile o haftanın 7 gününü (Pazartesi–Pazar) gösteriyor, kaydırma tam bir hafta (7 gün) ileri/geri atlıyor. `PAST_WEEKS=104` / `FUTURE_WEEKS=52` sabitleriyle sınırlı ama geniş bir sayfa aralığı var; gelecek haftalara sayfalamak mümkün (boş veri gösterir) ama gelecekteki günler yine de seçilemez/soluk (`isFuture` — eski davranışla aynı ruh). Hafta değişince seçili gün otomatik güncelleniyor: yeni hafta bugünü içeriyorsa bugün, içermiyorsa haftanın Pazartesi günü seçiliyor.

### 17. KRİTİK BUG FİX: unlock/telefon-açılma takibi hiç çalışmıyordu (2026-07-07)
Madde 14'te eklenen unlock tracking, `AppAccessibilityService.onCreate()`'te 2-parametreli `registerReceiver(screenReceiver, IntentFilter)` kullanıyordu. **`targetSdk = 36`** (Android 14+ davranışı) altında context-registered receiver'lar için `RECEIVER_EXPORTED`/`RECEIVER_NOT_EXPORTED` flag'i zorunlu — verilmezse `SecurityException` fırlatır. Bu `onCreate()` içinde olduğu için **tüm AccessibilityService çöküyordu** (sadece unlock tracking değil, muhtemelen bloklamayı da etkileme riski var çünkü servis hiç ayağa kalkamıyordu ya da sürekli restart oluyordu) — kullanıcı "telefon açılmayı doğru kaydetmiyor" diye fark etti. **Fix**: `ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)` (androidx.core, zaten bağımlılıkta var). Bu ortamda emülatör/cihaz olmadığı için crash'i loglardan doğrudan gözlemleyemedim; targetSdk + kod okuması üzerinden yüksek güvenle teşhis ettim — **cihazda gerçek doğrulama kullanıcı tarafından yapılmalı** (Erişilebilirlik izni açıkken artık servis onCreate'te çökmemeli, unlock sayısı birikmeli).

### 18. Telefon kullanım oturumları (pickup → hangup) — yeni özellik (2026-07-07)
- Yeni `PhoneSession` entity (`phone_sessions` tablosu: `id`, `startMs`, `endMs`). `AppAccessibilityService`'te `ACTION_USER_PRESENT` oturumu başlatır (`phoneSessionStartMs`), bir sonraki `ACTION_SCREEN_OFF` oturumu kapatıp kaydeder (`commitPhoneSession()`) — 1 saniyeden kısa "oturumlar" gürültü sayılıp atılıyor. `onDestroy()`'da da açık oturum varsa kapatılıyor (madde 3/4'teki commitCurrentSession pattern'iyle aynı ruh).
- `AppDatabase` v2 → v3: yine **additive `Migration(2,3)`** (madde 14'teki gibi, veri kaybı yok).
- Dashboard'a yeni "Kullanım Oturumları" kartı eklendi: `PhoneSessionTimeline` (`DashboardCharts.kt`) — 24 saatlik tek bir yatay şerit üzerinde her oturumu konumuna/süresine göre orantılı bir Canvas `drawRoundRect` segmenti olarak çiziyor (Gantt-tarzı, elle çizilmiş, Vico'ya dokunulmadı). Kart başlığında oturum sayısı + toplam süre özeti var.
- Bu özellik de unlock tracking ile aynı bağımlılığı taşıyor: yalnızca Erişilebilirlik servisi açıkken ve madde 17'deki fix sonrası birikir.

### 19. Paylaşılan tarih yardımcıları (`utils/DateUtils.kt`) — yeni (2026-07-07)
`ReportsScreen.kt`'nin private `startOfDay`/`mondayOfWeek`/`DAY_MS`'i buraya taşındı (+ yeni `startOfMonth`), çünkü `UsageAnalysisScreen.kt` da aynı hesaplara ihtiyaç duydu. Kotlin'de top-level `private` dosya-scope'lu olduğu için iki ekranın aynı mantığı ayrı ayrı yazması yerine tekilleştirildi (madde 15'teki `categoryColor` taşımasıyla aynı gerekçe).

### 20. Kullanım Analizi ekranı — yeni (2026-07-07, sonradan 5. tab'a taşındı)
- Yeni `UsageAnalysisScreen.kt` → `UsageAnalysisTab()` composable'ı. **İlk versiyonda** Dashboard'a gömülü bir satırdan push-screen (`Screen.Analysis`) olarak açılıyordu; kullanıcı bunu "Dashboard/Raporlar/Takip/Limitler gibi ayrı bir ekran" beklediği için **BottomBar'a 5. tab olarak** taşındı (`selectedTab == 4`). Dashboard'daki giriş satırı ve `Screen.Analysis` sealed state'i kaldırıldı — tek erişim yolu artık alt navigasyon.
- Günlük/Haftalık/Aylık sekmeli karşılaştırma: ekran süresi ve telefon açılma sıklığı, **güncel dönemi bir önceki dönemin aynı gün-sayısıyla** kıyaslıyor (ör. bugün haftanın 2. günüyse, bu haftanın ilk 2 günü geçen haftanın ilk 2 günüyle kıyaslanır) — bunun nedeni: "bu hafta (yarım)" ile "geçen hafta (tam)"ı doğrudan kıyaslamak her zaman yapay bir "azalma" gösterir, yanıltıcı olurdu. Günlük kıyaslama ise basitçe "bugün (şimdiye kadar) vs dün (tam gün)" — bu, Digital Wellbeing gibi araçların da kullandığı kabul edilmiş bir basitleştirme, özel bir düzeltme yapılmadı.
- Veri: `UsageLog` (ekran süresi, gün bazlı toplam) + `UnlockEvent` (açılma sıklığı). Aylık karşılaştırma için tek seferde geniş bir aralık (~65 gün) çekilip sekme değişince yalnızca Kotlin tarafında yeniden hesaplanıyor, yeniden sorgu atılmıyor.
- `previous == 0` durumunda yüzde hesaplanamıyor (`percentChange` → `null`), UI "Önceki dönemde veri yok" gösteriyor — sonsuz/anlamsız yüzde yerine.
- Bu ekran da unlock/session verisiyle aynı zincire bağlı: madde 17'deki fix'ten önce üretilmiş hiçbir unlock verisi yoktu, dolayısıyla geçmiş dönemler için "açılma sıklığı" karşılaştırması fix sonrası birikecek veriye ihtiyaç duyacak.
- Diğer tab'lar gibi (`DashboardTab`, `HourlyTrackingTab`) kendi `Scaffold`/`TopAppBar`'ı yok — MainScreen'in ortak "Traccer" üst barını kullanıyor, sadece scroll edilebilir bir `Column`.

### 21. Oturum Davranışı analiz bölümü — yeni (2026-07-08)
- `UsageAnalysisScreen.kt`'ye (5. tab, madde 20) "Oturum Davranışı" başlıklı yeni bir bölüm eklendi: **Açılış Sayısı** ve **Ortalama Oturum Süresi** (ikincil satırda medyan da gösteriliyor) için iki yeni `TrendCard`, ve 4 kovalı (`<1 dk / 1-5 dk / 5-15 dk / 15+ dk`) elle çizilmiş yatay bar dağılım kartı (`SessionDurationDistribution`). Üstte tek cümlelik bir "kullanım tarzı" özeti var (`usageStyleSummary`).
- Veri kaynağı **sadece** mevcut `phone_sessions` tablosu (madde 18) — yeni tablo/migration/izin yok. `UsageAnalysisTab`'ın zaten kullandığı 65 günlük tek seferlik geniş fetch + sekme değişince Kotlin tarafında yeniden hesaplama desenine (madde 20) birebir uyuldu: `getPhoneSessionsBetween(fetchStart, today+DAY_MS)` bir kez çekiliyor, `sessionsInRange()` her dönem için filtreliyor.
- `endMs <= startMs` olan bozuk satırlar `sessionsInRange()` içinde filtreleniyor; gece yarısını aşan oturumlar basitçe **başladığı güne** sayılıyor (bölme yapılmadı, madde 20'deki "basit tut" felsefesiyle aynı).
- "Kullanım tarzı" eşikleri (açılış sayısı ≥30/<15, medyan <2dk/≥5dk) kasıtlı olarak tek fonksiyonda (`usageStyleSummary`) sabit tutuldu — config/enum'a çıkarılmadı, over-engineer edilmedi.
- Bu bölüm de unlock/session verisiyle aynı zincire bağlı: madde 17/18'deki Erişilebilirlik servisi bağımlılığını taşıyor — servis kapalıyken veya geçmişte hiç açılmamışsa "Bu dönemde oturum verisi yok" boş durumu gösteriliyor, çökme/sıfıra bölme riski yok (`medianDurationMs` boş listede erken `0L` döner).
- Derleme (`compileDebugKotlin`) başarıyla geçti; bu ortamda emülatör/cihaz yok — yatay bar oranlarının görsel doğruluğu, boş durumun doğru tetiklenmesi ve gece yarısı sınırındaki oturumların doğru güne sayılması **cihazda kullanıcı tarafından doğrulanmalı**.

### 22. KRİTİK BUG FİX: unlock/oturum sayacı gerçekten hiç birikmiyordu — RECEIVER_NOT_EXPORTED yanlış yöndeydi (2026-07-08)
- Madde 17'deki fix (crash'i önlemek için `RECEIVER_NOT_EXPORTED` eklemek) **yanlış flag'i** kullanmıştı. `AppAccessibilityService.onCreate()`'teki `screenReceiver` hem `ACTION_SCREEN_OFF` hem `ACTION_USER_PRESENT` dinliyor — ama `ACTION_USER_PRESENT` broadcast'i **`com.android.systemui`** uygulaması tarafından (kendi UID'siyle) gönderiliyor, system_server'dan değil. `RECEIVER_NOT_EXPORTED` bu farklı UID'den gelen broadcast'i sessizce reddediyordu (logcat: `BroadcastQueue: Exported Denial: ... action: android.intent.action.USER_PRESENT from com.android.systemui ... not specifying RECEIVER_EXPORTED`) — **hiçbir exception atmıyordu, hiçbir yerde loglanmıyordu**, sadece `phoneSessionStartMs` hiç set edilmiyordu. Sonuç: unlock/telefon-açılma sayısı ve `phone_sessions` her zaman boş kalıyordu, servis çökmediği için madde 17'deki fix "çalışıyor" sanılmıştı.
- **Fix**: `ContextCompat.RECEIVER_NOT_EXPORTED` → `ContextCompat.RECEIVER_EXPORTED`. Her iki action da (`ACTION_SCREEN_OFF`, `ACTION_USER_PRESENT`) korumalı sistem broadcast'i (`android:protectionLevel="signature"` benzeri, yalnızca sistem/systemui gönderebilir) — EXPORTED yapmak güvenlik açığı oluşturmuyor, çünkü üçüncü parti bir uygulama bu action'ları taklit edip gönderemez.
- **Doğrulama (bu oturumda, gerçek cihazda DEĞİL — Android Studio Pixel_6 AVD'sinde yapıldı)**: APK derlenip emülatöre kuruldu, `adb shell settings put secure enabled_accessibility_services` ile servis programatik açıldı, `adb shell input keyevent KEYCODE_POWER/WAKEUP` + swipe ile kilit/aç döngüsü simüle edildi. Fix öncesi logcat'te "Exported Denial" hatası + `phone_sessions` tablosunda hiç satır yoktu; fix sonrası hata kayboldu, `"Screen ON / Unlocked"` logu basıldı ve `sqlite3 databases/traccer_database_v2 'SELECT * FROM phone_sessions'` gerçek bir satır döndürdü (`id=1, startMs=..., endMs=...`, ~16sn'lik simüle oturum). **Bu, madde 21'deki "Oturum Davranışı" analiz UI'sinin de artık gerçek veriyle çalışacağı anlamına geliyor** — önceki test (madde 21 sonu) yalnızca boş-durum yolunu doğrulamıştı çünkü bu bug yüzünden hiç veri birikmiyordu.
- **Not**: AVD test ortamı `x86_64` (host ARM translation yok), APK'nın tek native bağımlılığı olan `lib/arm64-v8a/libandroidx.graphics.path.so` test kurulumu için elle çıkarılıp APK yeniden imzalanarak kuruldu — bu sadece emülatör testi içindi, gerçek derleme/imzalama zincirine dokunulmadı, `gradlew` çıktısı değişmedi. Kullanıcının gerçek cihazında (`arm64-v8a` desteklediği için) bu adım gerekmez, normal `assembleDebug`/Android Studio kurulumu yeterli.
- **Kullanıcıya kalan**: gerçek cihazda Erişilebilirlik izni tekrar açılıp (izin, reinstall sonrası madde bilgisi: reinstall izni sıfırlar) birkaç unlock/lock döngüsü yapılırsa artık `phone_sessions`/`unlock_events`'in gerçekten birikmesi ve Analiz sekmesindeki "Oturum Davranışı" bölümünün dolu veriyle (dağılım barları, medyan/ortalama) doğru göründüğü teyit edilmeli.

### 23. Raporlar ekranı: gün hareket kaydı + tıklanabilir gün seçimi (2026-07-08)
- `WeekDateRow` (üst tarih şeridi) zaten tıklanabiliyordu; asıl eksik `WeeklyBarChart`'taki çubuklar/gün harfleriydi — o ikisi tıklanamıyordu. Her ikisine de `onDaySelected` callback'i eklendi (gelecekteki günler hâlâ tıklanamaz, `today` parametresiyle korunuyor).
- Yeni `DayActivityCard`: seçili günün **ham hareket kaydı** — `unlock_events` (telefon açıldı, tek zaman damgası) ve `phone_sessions` (pickup→hangup oturumu, başlangıç-bitiş + süre) tek kronolojik listede (`DayActivity` sealed class, `ReportsScreen.kt` içinde private). 10'dan fazla hareket varsa "Tümünü Göster" ile açılıyor — `SelectedDayAppList`'teki `COLLAPSED_APP_COUNT` genişlet/daralt deseniyle aynı.
- Veri: `AppUsageDao.getPhoneSessionsBetween` haftalık pencerede (`weekStart..weekEnd+1gün`, madde 18'deki tabloyla aynı), gece yarısını aşan oturumlar **başladığı güne** sayılıyor (madde 21'deki kuralla aynı, bölme yapılmadı). `unlockCountsByDay` zaten vardı, aynı `unlockEvents` flow'undan `selectedDayActivities` için de filtre eklendi — ikinci bir sorgu atılmadı.
- Bu veri de unlock/session zincirine bağlı (madde 17/22) — Erişilebilirlik servisi kapalıyken veya geçmiş günlerde hiç açık değilken "Bu gün için hareket kaydı yok" gösteriliyor.

### 24. BÜYÜK MİMARİ DEĞİŞİKLİK: unlock/oturum takibi OS-only oldu (2026-07-08)
- **`unlock_events` ve `phone_sessions` tablolarının TEK yazarı artık `data/PhoneActivitySync.kt`** (`UsageStatsManager.queryEvents` tabanlı). `AppAccessibilityService` bu veriyi ARTIK YAZMIYOR — madde 14/18'deki receiver-tabanlı `recordUnlockEvent()`/`commitPhoneSession()` kodu ve `ACTION_USER_PRESENT` dinleyicisi silindi. Servis yalnızca engelleme + app-kullanım süresi flush'ı için kaldı (`ACTION_SCREEN_OFF` dinlemeye devam ediyor, flag `RECEIVER_NOT_EXPORTED`'a geri döndü — madde 22'deki "Exported Denial" sorunu yalnız systemui'den gelen USER_PRESENT içindi, o artık dinlenmiyor).
- **Gerekçe**: OS bu veriyi zaten 7/24 kendisi topluyor — uygulama/servis kapalıyken, hatta kurulumdan ÖNCE bile ~7 günlük geçmiş `queryEvents`'ten alınabiliyor. Madde 17/22'deki bug sınıfı (receiver flag'leri, reinstall'da Erişilebilirlik izni sıfırlanması, servis kill → veri boşluğu) bu veri için tamamen ortadan kalktı. **Erişilebilirlik izni bu metrikler için artık GEREKMİYOR** — yalnızca zaten istenen Kullanım Erişimi izni yeterli (engelleme için Erişilebilirlik hâlâ şart).
- **Event eşlemesi**: `KEYGUARD_HIDDEN` → unlock kaydı + oturum başlangıcı (eski `ACTION_USER_PRESENT` karşılığı); `SCREEN_NON_INTERACTIVE` → oturum kapanışı (eski `ACTION_SCREEN_OFF`). 1 sn altı oturumlar gürültü sayılıp atılıyor (madde 18'deki eşik korundu). Devam eden oturum (ekran hâlâ açık) yazılmaz — bir sonraki sync tamamlar.
- **Sync kuralı (idempotent, tek `@Transaction`)**: kapsama penceresi `[OS'ten görülen ilk ilgili event, şimdi]` içindeki TÜM satırlar silinir, OS'ten türetilenler yazılır (`AppUsageDao.replacePhoneActivityWindow`). Pencere dışı (7 günden eski) satırlar donuk arşiv — Room'da kalıcı, 65 günlük Analiz ekranı bu sayede çalışmaya devam eder. Hiç ilgili event gelmezse (izin yok / OEM vermiyor) sync no-op, DB'ye DOKUNULMAZ.
- **Tetikleyiciler**: (1) `MainActivity.onCreate` — anında backfill (`lifecycleScope`, IO dispatcher), app açılır açılmaz son 7 gün dolar; (2) `DailySyncWorker` — zaten var olan startup (30 sn gecikmeli) + gece 00:05 periyodik worker'a 4. adım olarak eklendi. Yeni zamanlama kurulmadı.
- **Şema değişikliği YOK → migration YOK** (v3'te kalındı). DAO'dan tekil `insertUnlockEvent`/`insertPhoneSession` silindi (yazar tekliği); bulk insert + pencere delete + transaction metodları eklendi.
- **UI değişikliği SIFIR**: Dashboard/Raporlar/Analiz zaten yalnızca Room okuyordu, sync yazınca Flow'lar kendiliğinden tazeleniyor.
- Derleme geçti; **cihaz doğrulaması kullanıcıda**: Kullanım Erişimi izni verili herhangi bir cihazda app açılınca son ~7 günün unlock/oturum verisi görünmeli (Erişilebilirlik kapalıyken bile). OEM'lerin `KEYGUARD_HIDDEN`/`SCREEN_NON_INTERACTIVE` event davranış farkları tek gerçek risk — bir cihazda veri gelmiyorsa önce `adb logcat`'te `PhoneActivitySync` tag'ine bak.

### 25. Beş küçük-orta düzeltme: veri güvenliği, tutarlılık, gerçek engelleme (2026-07-08)
Kullanıcı isteğiyle tek oturumda yapılan bağımsız 5 iyileştirme:

1. **`fallbackToDestructiveMigration()` yalnızca debug'da** (`AppDatabase.kt`) — release build'de migration unutulursa artık kullanıcı verisi sessizce silinmez, Room migration hatasıyla çöker (yüksek sesli hata > sessiz veri kaybı). `app/build.gradle.kts`'de `buildFeatures { buildConfig = true }` açıldı (yoktu, `BuildConfig.DEBUG` erişimi için gerekiyordu).
2. **`DailySyncWorker` artık son 7 günü gün gün reconcile ediyor**, eskiden yalnızca bugünü. Servis dün kapalıysa dünün ekran süresi eskiden sonsuza dek eksik kalıyordu — pencere `PhoneActivitySync`'le (madde 24) aynı ~7 günlük OS kapsamına hizalandı. `buildAggregationMap`'e artık `endMs` geçiliyor (bugün için "şimdi", geçmiş günler için o günün sonu) — açık kalan uygulamalar o pencerenin sonuna kadar sayılıyor.
3. **Ölü kod/izin temizliği** (madde 11'in devamı): `RECEIVE_BOOT_COMPLETED` izni silindi (karşılığı hiç yoktu — `TrackingService` yalnızca bildirim kabuğu olduğu için boot receiver yazmaya değmedi, YAGNI), kullanılmayan `FileProvider`/`file_paths.xml` silindi (kod tarafında hiç referans yoktu), `AppIconUtils.invalidateCache()` ölü fonksiyonu silindi.
4. **Ekranlar arası hidden-app/min-usage filtre tutarsızlığı giderildi**: yeni `utils/UsageLogFilters.kt` (`List<UsageLog>.filterVisible(prefs)`) — eskiden yalnızca Dashboard (`MainScreen.kt`) bu filtreyi uyguluyordu, Raporlar ve Analiz ham veri gösteriyordu, aynı günün toplamı sekmeye göre farklı çıkabiliyordu. Artık üçü de aynı yardımcıyı kullanıyor.
5. **Madde 13'teki engelleme deliği kapatıldı**: `AppAccessibilityService`'e `startInAppLimitCheck()` eklendi — foreground'daki paketi 30 sn'de bir kontrol eder (yalnızca `limitsCache`'te aktif limiti olan paket foreground'dayken iş yapar, aksi halde tek satır kontrolle döner — pil maliyeti ihmal edilebilir). `checkAndBlockIfNeeded` düzeltildi: `dailyUsageMs[pkg]` yalnızca commit edilmiş süreyi tutuyordu (paket değişimi/10dk flush'ta güncellenir), periyodik kontrol bu yüzden mevcut oturumdaki commit edilmemiş süreyi görmüyordu — artık `liveElapsed = now - sessionStartMs` (yalnızca `pkg == currentPackage` iken) toplama ekleniyor. Overlay zaten gösteriliyorsa (`blockedOverlayPackage == pkg`) tekrar tetiklenmiyor, madde 3/4'teki debounce/self-trigger korumalarına dokunulmadı.

Derleme (`compileDebugKotlin`) tüm değişikliklerden sonra BUILD SUCCESSFUL. Cihaz doğrulaması kullanıcıda: (2) için servisi bir gün kapalı bırakıp ertesi gün açma senaryosu, (5) için bir uygulamayı limit dolana kadar açık tutup uygulamadan çıkmadan bloklanıp bloklanmadığı test edilmeli.

### 26. Sonsuz Kaydırma (Reels/Shorts) Engelleme — yeni (2026-07-09)
AppLimit'ten (uygulama bazlı günlük dakika limiti) **bağımsız** yeni engelleme katmanı: Instagram'ın Home/DM/Profil'ini etkilemeden yalnızca **Reels**, YouTube'da yalnızca **Shorts** oynatıcısı algılanınca engeller. TikTok kapsam dışı (ana akışı zaten tamamen kısa-video, "sadece reels'i ayırma" anlamsız — mevcut AppLimit yeterli; mimari sonradan eklemeye açık). Spec: `docs/superpowers/specs/2026-07-09-reels-shorts-blocker-design.md`, plan: `docs/superpowers/plans/2026-07-09-reels-shorts-blocker.md`.

- **Tespit** (`service/reeldetection/`): `ReelDetector` arayüzü + `InstagramReelDetector`/`YouTubeShortsDetector` — her biri node ağacında o platformun kendi iç `viewIdResourceName`'lerini arıyor (`NodeSearch.containsViewId`, leak-safe recycle). Instagram: `clips_viewer_container`/`clips_video_container`/`clips_video_view_pager`; YouTube: `reel_player_page_container`/`reels_viewer_container`/`shorts_main_container`. Bunlar platformun kod isimleri → metin/dilden bağımsız. `ReelDetectorRegistry` paket→detector eşlemesi. **Tab-seçili/kelime/uzamsal doğrulama katmanı YOK** (v1 bilinçli sade, YAGNI — yanlış-pozitif görülürse eklenir). Teknik, kullanıcının kendi cihazına kurulu rakip bir app'in (JADX ile decompile, kişisel/eğitim) genel yaklaşımından ilham aldı — **kodu kopyalanmadı**, orijinal Kotlin yazıldı.
- **Event akışı**: `accessibility_service_config.xml`'e `typeWindowContentChanged` eklendi (madde 17/18'deki `typeWindowStateChanged` yanına) — Instagram/YouTube içinde sekme değişimini (paket değişmeden) yakalamak için gerekliydi. `AppAccessibilityService.onAccessibilityEvent` content-changed dalında `checkReelContent()` çağırıyor; 500ms throttle (`REEL_CHECK_THROTTLE_MS`) **pahalı işlerden (SharedPreferences okuması + node taraması) ÖNCE** — hızlı kaydırmada pil/CPU korunuyor. Registry'de karşılığı olmayan pakette hiç node taranmıyor.
- **İki mod, kullanıcı platform başına seçiyor** (`BlockingSettingsScreen` → "Sonsuz Kaydırma Engelleme" kartları; `UserPreferences.instagramReelBlockMode`/`youtubeShortsBlockMode`, `enum ReelBlockMode { INSTANT, BUDGET }`): **INSTANT** tespit anında bloklar; **BUDGET** `dailyUsageMs` (madde 25) ile aynı ruhta ayrı `reelUsageMs` sayacı biriktirir, günlük dakika bütçesini (`*BudgetMinutes`, varsayılan 15) aşınca bloklar. Bütçe sayacı **yalnızca in-memory** (DB'ye yazılmaz) — servis restart'ında sıfırlanır, kabul edilebilir risk (madde 13'teki `dailyUsageMs` kadar kritik değil). Reel oturumu paket değişiminde (ignore edilen launcher/Home dahil) ve içerik reel'den çıkınca commit edilir; gece yarısını aşan tek oturum bittiği güne yazılır (madde 21 ruhu).
- **Overlay**: mevcut `showBlockingOverlay`/`BlockingOverlayContent` (madde 2) opsiyonel `onAllowTemporarily` parametresiyle genişletildi — `null` ise (AppLimit blokları) davranış değişmedi; reel-blok çağrılarında "1 saat izin ver" butonu (`reelSuppressUntilMs[pkg] = now + 1sa`, her iki modda aynı). AppLimit engelleme yolu (`checkAndBlockIfNeeded`/`triggerBlock`) hiç değişmedi.
- **Test**: `ReelDetectorRegistry` + `ReelBlockLogic` (bütçe/susturma saf mantığı) için gerçek JUnit unit testleri — **projedeki ilk `app/src/test/` dosyaları** (zaten var olan `testImplementation(libs.junit)`, yeni bağımlılık yok). `AccessibilityNodeInfo`'ya bağımlı kod (detector'lar, servis kablolama) proje konvansiyonuyla (Robolectric yok) derleme + cihaz testiyle doğrulanır.
- **Şema/migration YOK** — tüm yeni state `UserPreferences` (SharedPreferences) veya servis içinde in-memory. Tam derleme + tüm unit testler BUILD SUCCESSFUL. **Cihaz doğrulaması kullanıcıda** (aşağıya bkz).

**Cihazda test edilecek (emülatör/otomatik cihaz testi yok):**
1. Ayarlar → Limitler → "Sonsuz Kaydırma Engelleme" → Instagram Reels aç, "Anında" → Reels'e gir → anında overlay + geri tuşu.
2. "1 saat izin ver" → 1 saat boyunca Reels bloklanmamalı.
3. Instagram "Süre Sınırı" (ör. 5dk) → Reels'te toplam 5dk (sekme değiştirip dönse bile birikmeli) → blok.
4. Instagram Home/DM/Profil/arama → hiç blok tetiklenmemeli (Reels'e girilmediyse).
5. Aynı 4 senaryo YouTube Shorts için.
6. TikTok bu özellikten etkilenmiyor (ayarlarda seçenek yok).
7. Mevcut AppLimit davranışı hiç değişmedi (regresyon — madde 1/13/25).

## Devam eden / ertelenmiş iş

(Şu an bekleyen/ertelenmiş iş yok — reel-blocker özelliği tamamlandı, cihaz doğrulaması kullanıcıda.)

## Doğrulama notu

Bu ortamda emülatör/cihaz yok. Tema geçişi, kategori renkleri, Raporlar grafiği ve Dashboard'un tek ekrana sığması gibi görsel değişiklikler sadece derleme ile doğrulandı — cihazda gerçek test kullanıcı tarafından yapılmalı.
