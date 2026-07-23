# Oturum Bazlı Kullanım İzni ("Girişte süre sor") — Tasarım

Tarih: 2026-07-17
Durum: Onaylandı (kullanıcı ile soru-cevap sonrası, Yaklaşım A seçildi)

## Amaç

Kullanıcı seçtiği uygulamalara (örn. YouTube) girdiğinde önce tam ekran bir soru gelir:
"Bu oturumda ne kadar kullanacaksın?" Kullanıcının seçtiği dakika o oturumun bütçesi olur;
bütçe dolunca uygulama mevcut engelleme overlay'i ile bloklanır.

## Kullanıcı kararları (soru-cevap sonucu)

| Konu | Karar |
|---|---|
| Kapsam | Uygulama başına seçilebilir — Limitler ekranında üçüncü mod |
| Oturum tanımı | Kısa çıkışlar bozmaz: 5 dk grace penceresi; süre dolunca veya 5+ dk uzak kalınca oturum biter |
| Süre dolunca | Mevcut blok overlay'i; yeni oturum ancak bilinçli yeniden girişle |
| Seçim UI | Hazır seçenekler (5/10/15/30/60 dk) **+** serbest giriş (stepper, 5–180 dk) |
| Günlük limit ilişkisi | Bağımsız: günlük limit dolmuşsa soru sorulmaz, direkt blok; ikisi de aktifse önce dolan engeller |

## Veri modeli

- `AppLimit` tablosuna yeni kolon: `isSessionPromptEnabled: Boolean = false`.
- Additive `Migration(3,4)`: `ALTER TABLE app_limits ADD COLUMN isSessionPromptEnabled INTEGER NOT NULL DEFAULT 0`. Veri kaybı yok (CLAUDE.md madde 14/18 geleneği).
- `AppUsageDao.getActiveLimits()` sorgusuna `OR isSessionPromptEnabled = 1` eklenir —
  servisin `limitsCache`'i bu uygulamaları da görmeli.
- Oturum durumu DB'ye YAZILMAZ — servis içi in-memory (reel bütçesi deseni, madde 26):
  - `sessionRemainingMs: ConcurrentHashMap<String, Long>` — kalan bütçe
  - `sessionLastExitMs: ConcurrentHashMap<String, Long>` — uygulamadan son çıkış anı
  - Servis restart'ında kaybolur → sonraki girişte yeniden sorar (kabul edilmiş risk).

## Oturum kuralları

1. **Girişte** (paket değişimi, `onAccessibilityEvent`): mod açık ve günlük limit dolmamışsa —
   - Aktif oturum YOKSA (hiç başlamadı / kalan ≤ 0 / son çıkıştan bu yana > `SESSION_GRACE_MS` = 5 dk) → soru overlay'i gösterilir.
   - Aktif oturum VARSA → sorusuz devam, kalan bütçeyle.
2. **Bütçe düşümü** yalnızca uygulama gerçekten öndeyken: sayaç "Başla"ya basılınca başlar
   (soru ekranında geçen süre sayılmaz). Uygulamadan çıkışta o girişte geçen süre bütçeden
   düşülür ve `sessionLastExitMs[pkg] = now` işaretlenir. Ekran kilitlenmesi de çıkış sayılır
   (SCREEN_OFF yolu; madde 28 fix'i `currentPackage`'ı zaten null'lıyor).
3. **Süre dolunca**: mevcut `triggerBlock` yolu, gerekçe "Oturum süresi doldu". Oturum state'i
   temizlenir; yeni oturum = uygulamadan çıkıp yeniden girmek (soru tekrar gelir).
4. **Günlük limit bağımsız**: `checkAndBlockIfNeeded` önce çalışır; blokladıysa soru sorulmaz.
5. **Hassasiyet**: 30 sn'lik `startInAppLimitCheck` döngüsü oturum bütçesini de denetler; ayrıca
   "Başla" anında kalan süreye zamanlanmış tek seferlik kontrol coroutine'i kurulur (blok,
   sürenin dolduğu saniyede gelir). Paket değişince/oturum bitince bu job iptal edilir.

## Soru overlay'i (UI)

Mevcut engelleme overlay altyapısı: `TYPE_ACCESSIBILITY_OVERLAY` + ComposeView +
`ServiceLifecycleOwner`, `FLAG_NOT_FOCUSABLE` KORUNUR (madde 3 self-trigger fix'i bozulmaz).
Görsel dil `BlockingOverlayContent` ile aynı (koyu gradyan, app ikonu, mor aksan).

- Başlık: app ikonu + "Bu oturumda ne kadar kullanacaksın?"
- Preset chip'ler: 5 / 10 / 15 / 30 / 60 dk — dokunmak seçer, onaylamaz.
- Özel değer: `[−] N dk [+]` stepper, 5'er dk adım, 5–180 dk aralık (klavye yok —
  `FLAG_NOT_FOCUSABLE` ile çakışmaz).
- **Başla** butonu: seçili süreyle oturumu başlatır, overlay kalkar.
- **Kullanmayacağım** text button: `GLOBAL_ACTION_HOME`, oturum başlamaz.
- Kullanıcı seçim yapmadan Home ile çıkarsa: paket değişimi overlay'i kaldırır (mevcut mantık),
  oturum başlamaz, sonraki girişte yine sorar.

## Limitler ekranı

- `LimitSettingsContent` bottom sheet'ine üçüncü bölüm: "Girişte süre sor" switch'i
  (açıklama: "Uygulamayı her açtığında o oturum için süre seçersin"). Kayıt mevcut
  `insertOrUpdateLimit` yolundan.
- `ActiveLimitRow`: mod açıkken mor `Timer` rozeti + "Oturum" etiketi.
- "Aktif Limitler" filtresi `|| isSessionPromptEnabled` içerecek şekilde güncellenir
  (BlockingSettingsScreen'deki `active` filtresi).

## Dosya yapısı

- `service/sessionprompt/SessionPromptLogic.kt` — saf, servis-bağımsız karar fonksiyonları:
  - `shouldPromptForSession(remainingMs: Long?, lastExitMs: Long?, nowMs: Long): Boolean`
  - `deductSessionBudget(remainingMs: Long, elapsedMs: Long): Long`
  - `isSessionExpired(remainingMs: Long): Boolean`
  - sabitler: `SESSION_GRACE_MS`, `MIN_SESSION_MINUTES = 5`, `MAX_SESSION_MINUTES = 180`,
    `SESSION_PRESET_MINUTES = [5, 10, 15, 30, 60]`
- `app/src/test/java/.../sessionprompt/SessionPromptLogicTest.kt` — JUnit (ReelBlockLogic deseni).
- Servis kablolaması + `SessionPromptOverlayContent` composable → `AppAccessibilityService.kt`
  (mevcut düzen: overlay composable'ları serviste).
- `AppDatabase.kt` (v4 + migration), `AppUsageEntity.kt`, `AppUsageDao.kt`,
  `BlockingSettingsScreen.kt` güncellenir.

## Edge case'ler

- **Servis restart / süreç ölümü**: oturumlar kaybolur → yeniden sorar. Kabul edildi.
- **Gece yarısı**: oturum bütçesi güne bağlı değildir; gece yarısını aşan oturum kalan
  bütçesiyle devam eder (günlük limit kendi yolundan sıfırlanır).
- **Soru overlay'i açıkken gelen blok koşulları**: soru overlay'i görünürken
  `blockedOverlayPackage` set edilmez (bu bir blok değil); ancak `checkReelContent` gibi diğer
  overlay tetikleyicileriyle çakışmayı önlemek için servis tek "overlay görünür" durumunu ortak
  kontrol eder (mevcut `overlayView != null` denetimi).
- **Reel-blocker ile etkileşim**: bağımsız katmanlar; oturum içinde reel bloğu yine tetiklenebilir.
- **Grace penceresi içinde süre dolmuş oturuma dönüş**: kalan ≤ 0 ise "aktif oturum yok"
  sayılır → yeni soru (blok değil; kullanıcı bilinçli girmiş, yeni oturum hakkı var).

## Test planı

- **Unit**: SessionPromptLogic — grace penceresi sınırları (tam 5 dk, ±1 ms), bütçe düşümü,
  negatife düşme, sıfır/negatif elapsed, preset/stepper sınır değerleri (5, 180).
- **Derleme**: `gradlew :app:compileDebugKotlin` + `:app:testDebugUnitTest`.
- **Cihaz (kullanıcıda)**: (1) mod açık uygulamaya giriş → soru geliyor; (2) "Başla" → seçilen
  süre kadar kullanım, dolunca blok; (3) kısa çıkış-dönüş (<5 dk) → soru gelmiyor, kalan süre
  devam; (4) uzun çıkış (>5 dk) → yeni soru; (5) "Kullanmayacağım" → ana ekran; (6) günlük
  limit dolu uygulamada soru yerine direkt blok; (7) mevcut AppLimit/zamanlama/reel-blocker
  regresyonu yok.

## Kapsam dışı (YAGNI)

- Oturum geçmişinin DB'ye yazılması / raporlanması.
- "Ek süre iste" butonu (bilinçli yeniden giriş yeterli).
- Bildirimle "1 dk kaldı" uyarısı.
- Oturum bütçesinin servis restart'ına dayanıklı persist edilmesi.
