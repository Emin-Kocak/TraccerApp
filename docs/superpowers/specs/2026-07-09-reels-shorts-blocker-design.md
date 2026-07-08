# Sonsuz Kaydırma (Reels/Shorts) Engelleme Tasarımı

## Amaç

Instagram Reels ve YouTube Shorts'ta geçirilen süreyi, mevcut AppLimit (uygulama bazlı günlük dakika limiti) mekanizmasından **bağımsız** olarak sınırlamak. AppLimit "Instagram'ı X dakika kullan" der; bu özellik "Instagram'ı istediğin kadar kullan ama Reels'e girince engelle/sınırla" der — Instagram'ın Home/DM/Profil kısımları etkilenmez, sadece Reels/Keşfet video oynatıcısı.

Kapsam dışı: TikTok (ana akışı zaten tamamen kısa-video olduğu için "sadece reels'i ayırma" pratikte anlamsız — mevcut AppLimit ile kontrol edilecek, kullanıcı kararı). Mimari yine de TikTok'u sonradan eklemeye açık (bkz. Genişletilebilirlik).

## Araştırma notu

Kullanıcının cihazında kurulu bir rakip uygulama (`com.mindefy.phoneaddiction.mobilepe`, Reel Blocker özelliği) kullanıcı tarafından JADX ile decompile edildi ve tespit tekniği incelendi (kişisel/eğitim amaçlı, kendi cihazına kurulu kendi APK'sı üzerinde). **Onların kodu kopyalanmadı** — sadece genel teknik (view-id tabanlı tespit + hangi view-id'lerin kullanıldığı, bunlar Instagram/YouTube'un kendi iç kod isimleri, üçüncü tarafın değil) referans alınıp orijinal Kotlin implementasyonu yazılacak. Rakip, tam kapsamlı bir uzamsal (spatial) node-gruplama + 50+ dilli anahtar kelime sınıflandırıcısı da içeriyordu (çoklu platform/dil desteği için); bu proje tek kullanıcılı olduğu için o katman **bilinçli olarak dışarıda bırakıldı** (YAGNI) — v1 sadece view-id eşleşmesine dayanıyor, yanlış-pozitif görülürse sonradan eklenir.

## Mevcut durum

- `AppAccessibilityService.kt`: `onAccessibilityEvent` yalnızca `TYPE_WINDOW_STATE_CHANGED` (paket değişimi) dinliyor. `checkAndBlockIfNeeded`/`triggerBlock`/`showBlockingOverlay` AppLimit için var, overlay `TYPE_ACCESSIBILITY_OVERLAY` + Compose (madde 2/25).
- `accessibility_service_config.xml`: `android:accessibilityEventTypes="typeWindowStateChanged"` — içerik değişikliği (aynı uygulama içinde sekme geçişi) event'i hiç gelmiyor.
- `UserPreferences.kt`: SharedPreferences wrapper, `hiddenPackages`/`minimumUsageMs` gibi düz key-value ayarlar deseni var.
- `BlockingSettingsScreen.kt` (Limitler tab): AppLimit CRUD ekranı, yeni bir ayar bölümü eklemeye uygun.

## Yeni tasarım

### 1. Tespit katmanı — yeni `service/reeldetection/` paketi

- **`ReelDetector.kt`** (interface): `fun isReelContent(root: AccessibilityNodeInfo): Boolean`
- **`InstagramReelDetector.kt`**: node ağacında recursive arama, şu `viewIdResourceName` parçalarından biri varsa `true`: `clips_viewer_container`, `clips_video_container`, `clips_video_view_pager` (Instagram'ın Reels oynatıcısına özgü, kendi iç kod isimleri — metin/dilden bağımsız).
- **`YouTubeShortsDetector.kt`**: aynı desen, `reel_player_page_container` / `reels_viewer_container` / `shorts_main_container`.
- **`ReelDetectorRegistry.kt`**: `packageName -> ReelDetector?` eşlemesi. `com.instagram.android`, `com.instagram.lite` → Instagram; `com.google.android.youtube` → YouTube. Eşleşme yoksa `null` (ilgisiz paket, erken çık).
- Node arama, `AccessibilityNodeInfo.recycle()` disiplinine uyar (mevcut `dumpNode`/child-recycle deseniyle tutarlı, leak önlenir). Tab-seçili kontrolü veya kelime doğrulaması **yok** — sadece view-id varlığı.

### 2. Event akışı ve throttle — `AppAccessibilityService.kt`

- `accessibility_service_config.xml`'e `typeWindowContentChanged` eklenir (mevcut `typeWindowStateChanged` yanına).
- `onAccessibilityEvent`'e yeni bir dal: event tipi content-changed VE `currentPackage` registry'de karşılık buluyorsa (Instagram/YouTube), throttle kontrolünden geçip (`REEL_CHECK_THROTTLE_MS = 500L`, mevcut `lastProcessedTime` deseniyle aynı ruhta ayrı bir alan) `reelDetector.isReelContent(rootInActiveWindow)` çağrılır. Registry'de karşılığı olmayan her paket için bu dal en baştan atlanır — pil maliyeti diğer uygulamalarda sıfır.
- `rootInActiveWindow` çağrısı ve `recycle()` bu yeni dalda da disiplinli yapılır.

### 3. İki blok modu — kullanıcı platform başına seçer

`UserPreferences.kt`'ye eklenecek (mevcut `hiddenPackages` deseniyle aynı stil):

```kotlin
enum class ReelBlockMode { INSTANT, BUDGET }

var instagramReelBlockEnabled: Boolean   // varsayılan false
var instagramReelBlockMode: ReelBlockMode // varsayılan INSTANT
var instagramReelBudgetMinutes: Int      // varsayılan 15, yalnızca BUDGET modunda kullanılır

var youtubeShortsBlockEnabled: Boolean
var youtubeShortsBlockMode: ReelBlockMode
var youtubeShortsBudgetMinutes: Int
```

**INSTANT**: Reels/Shorts tespit edilir edilmez hemen blokla (süre biriktirme yok).
**BUDGET**: Instagram/YouTube'un `dailyUsageMs` (madde 25) ile aynı ruhta ayrı bir sayaç (`reelUsageMs: ConcurrentHashMap<String, Long>`) — Reels görünür kaldığı sürece biriktirilir (`reelContentStartMs` başlangıç, içerik kaybolunca veya paket değişince commit edilir, tıpkı `commitSessionAt` gibi). Günlük toplam `budgetMinutes`'ı aşınca blokla. Gün dönümünde sıfırlanır (yalnızca in-memory, DB'ye yazılmaz — v1'de servis yeniden başlarsa bütçe sıfırlanır, kabul edilebilir risk, madde 13'teki `dailyUsageMs` kadar kritik değil).

Her iki modda da: paket için `reelSuppressUntilMs[pkg] > now` ise blok atlanır (aşağıya bkz).

### 4. Blok UI — mevcut overlay'i genişlet

- `BlockingOverlayContent`'e opsiyonel `onAllowTemporarily: (() -> Unit)?` parametresi eklenir. `null` ise (mevcut AppLimit blokları) davranış **değişmez** — yalnızca "Ana Ekrana Dön" var. Reel-blok çağrılarında bu parametre dolu gelir → ikinci bir buton: **"1 saat izin ver"**.
- `triggerReelBlock(packageName, appName)`: `triggerBlock`'a benzer ama `reason = "Sonsuz kaydırma sınırı"` (veya BUDGET modunda `"$budgetMinutes dk sınırı doldu"`) ve overlay'i `onAllowTemporarily` dolu çağırır.
- "1 saat izin ver" tıklanınca: `reelSuppressUntilMs[pkg] = now + 3_600_000L`, overlay kapanır. Bu, hem INSTANT hem BUDGET modunda aynı şekilde çalışır (BUDGET modunda bütçe aşılmış olsa bile 1 saatliğine görmezden gelinir — basit, tek davranış, iki ayrı override mantığı yok).

### 5. Ayarlar ekranı — `BlockingSettingsScreen.kt`'ye yeni bölüm

"Sonsuz Kaydırma Engelleme" başlıklı yeni kart, iki alt bölüm (Instagram Reels, YouTube Shorts). Her biri:
- Aç/kapa switch (`instagramReelBlockEnabled` vb.)
- Açıksa: mod seçici (`UsageAnalysisScreen.kt`'deki `PeriodSelector` segmented-control desenine benzer iki seçenekli: "Anında" / "Süre Sınırı")
- BUDGET seçiliyse: dakika girişi (basit sayısal alan/stepper, `AppSettingsScreen.kt`'deki minimumUsageMs stepper deseniyle tutarlı)

### Genişletilebilirlik

TikTok/Facebook sonradan eklenmek istenirse: yeni bir `ReelDetector` implementasyonu + `ReelDetectorRegistry`'ye bir satır + `UserPreferences`'e üç yeni alan + ayarlar ekranına bir bölüm. Mevcut üç bileşene (tespit/blok/ayar) dokunulmaz.

### Dosya değişiklikleri

- Yeni: `service/reeldetection/ReelDetector.kt`, `InstagramReelDetector.kt`, `YouTubeShortsDetector.kt`, `ReelDetectorRegistry.kt`
- Değişecek: `AppAccessibilityService.kt` (event dalı + state + `triggerReelBlock`), `accessibility_service_config.xml` (event tipi), `UserPreferences.kt` (6 yeni alan + enum), `BlockingSettingsScreen.kt` (yeni ayar kartı)
- Şema/migration gerekmiyor — tüm yeni state SharedPreferences (`UserPreferences`) veya in-memory (`AppAccessibilityService` içinde), Room'a dokunulmuyor.

## Test / doğrulama kısıtı

Bu ortamda fiziksel cihaz erişimi sınırlı (kullanıcı kendi cihazında test ediyor). Doğrulama önce `./gradlew :app:compileDebugKotlin` ile derleme kontrolü, ardından kullanıcı cihazında: Instagram Reels'e girince INSTANT modda anında blok, BUDGET modda süre dolunca blok, "1 saat izin ver"in gerçekten 1 saat susturması, Instagram'ın Home/DM/Profil kısımlarının hiç etkilenmemesi, aynı senaryoların YouTube Shorts için de geçerli olması test edilmeli.
