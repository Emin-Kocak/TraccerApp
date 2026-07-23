# TraccerApp

Ekran süresini takip eden ve dijital alışkanlıkları kontrol altına almaya yardımcı olan bir Android uygulaması. Uygulama kullanım sürelerini ölçer, günlük limitler koyar ve **Instagram Reels / YouTube Shorts gibi sonsuz kaydırma ekranlarını** engelleyebilir.

Kotlin + Jetpack Compose ile yazılmıştır; engelleme mantığı bir `AccessibilityService` üzerine kuruludur.

---

## Özellikler

### 📊 Ekran süresi takibi
- **Dashboard** — Bugünkü toplam kullanım, en çok kullanılan uygulama ve telefon açılma sayısı.
- **Raporlar** — Haftalık kullanım grafiği, kategori bazlı dağılım.
- **Takip** — Günün saatlik zaman çizelgesi (hangi saatte ne kadar kullanım).
- **Analiz** — Günlük / haftalık / aylık trend görünümleri.
- Kullanım verisi Android `UsageStatsManager`'dan alınır ve `AccessibilityService`'in canlı ölçümüyle uzlaştırılır (gece yarısı taşması, kilit ekranı gibi kenar durumlar ele alınmıştır).

### 🛡️ Uygulama engelleme (Limitler)
Uygulama başına üç bağımsız engelleme modu:

1. **Günlük süre limiti** — Belirlenen dakika dolunca uygulama tam ekran bir engelleme ekranıyla kilitlenir. Uygulama açık kalırken bile periyodik kontrol ile yakalanır.
2. **Zaman planı** — Seçilen gün ve saat aralıklarında uygulama engellenir (ör. hafta içi 22:00–08:00).
3. **Girişte süre sor** — Uygulamayı her açtığında "bu oturumda kaç dakika?" sorulur; seçilen süre dolunca engellenir. 5 dakikalık grace penceresi ile kısa çıkış/kilit oturumu bozmaz.

### ♾️ Sonsuz kaydırma engelleme (Reels / Shorts)
Instagram Reels ve YouTube Shorts oynatıcıları algılanıp engellenebilir:

- **Anında mod** — Reels/Shorts açılır açılmaz engelleme ekranı gelir.
- **Süre sınırı modu** — Günlük toplam reel/shorts bütçesi (ör. 15 dk) dolunca engellenir; sekme değiştirip geri dönsen bile süre birikir.
- Engelleme ekranındaki **"Ana Sayfaya Dön"** butonu kullanıcıyı Android ana ekranına değil, **o an açık uygulamanın kendi ana sayfa akışına** geri gönderir — böylece uygulamayı normal kullanmaya devam edebilir, sadece sonsuz kaydırma yüzeyinden çıkarılır.

> Tespit, oynatıcıya özgü erişilebilirlik açıklamalarına dayanır (Instagram/YouTube view'lara sabit id atamadığı için). Ayrıntı için "Bilinen sınırlar" bölümüne bakın.

---

## Nasıl çalışır?

Engellemenin tamamı `AppAccessibilityService` içinde gerçekleşir:

- `TYPE_WINDOW_STATE_CHANGED` event'leri ile hangi uygulamanın önde olduğu izlenir.
- `TYPE_WINDOW_CONTENT_CHANGED` event'leri ile uygulama içi geçişler (ör. Reels sekmesine girme) yakalanır.
- Engelleme ekranı bir `TYPE_ACCESSIBILITY_OVERLAY` penceresidir (`SYSTEM_ALERT_WINDOW` iznine gerek yoktur).
- Reels/Shorts'tan çıkış, sistem "geri" aksiyonu (`GLOBAL_ACTION_BACK`) ile yapılır — bu, uygulamayı ana sayfa akışına güvenilir şekilde döndürür.

---

## Teknoloji yığını

| Alan | Kullanılan |
|------|-----------|
| Dil | Kotlin (JVM 21) |
| UI | Jetpack Compose (Material 3) |
| Mimari | Tek Activity, manuel constructor injection (DI framework yok) |
| Veritabanı | Room (KSP) |
| Arka plan işi | WorkManager (günlük kullanım senkronu) |
| Sistem API'leri | AccessibilityService, UsageStatsManager |
| minSdk / targetSdk | 32 (Android 12) / 36 |

---

## Kurulum

```bash
git clone https://github.com/Emin-Kocak/TraccerApp.git
cd TraccerApp
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Hızlı derleme kontrolü için:

```bash
./gradlew :app:compileDebugKotlin
```

### Gerekli izinler
Uygulamanın çalışması için cihazda şu izinlerin verilmesi gerekir:

- **Erişilebilirlik (Accessibility)** — Ayarlar → Erişilebilirlik → TraccerApp servisini aç. Uygulama engelleme ve reels/shorts tespiti bu servise bağlıdır.
- **Kullanım erişimi (Usage Access)** — Ekran süresi istatistikleri için.

---

## Proje yapısı (özet)

```
app/src/main/java/com/example/traccerapp/
├─ MainActivity.kt            # Tek Activity
├─ ui/screens/                # Compose ekranları (Dashboard, Raporlar, Takip, Limitler, Analiz)
├─ service/
│  ├─ AppAccessibilityService.kt      # Engelleme + takip mantığının tamamı
│  ├─ reeldetection/                  # Reels/Shorts tespiti (detector'lar + saf mantık)
│  └─ sessionprompt/                  # "Girişte süre sor" saf mantığı
├─ data/                      # Room DB, DAO, entity'ler, UserPreferences
└─ utils/                     # Kullanım istatistiği ve uygulama ikonu yardımcıları
```

> Mimari kararlar ve gotcha'lar için `CLAUDE.md` dosyasına bakın.

---

## Bilinen sınırlar

- **Reels/Shorts tespiti cihaz dilinin Türkçe olduğunu varsayar.** Tespit, oynatıcının erişilebilirlik açıklamalarındaki Türkçe metinlere (`"Reels videosu. Oynatmak"`, `"Bu sesi kullanan daha fazla video izleyin"`) dayanır. Cihaz dili değişirse bu metinler de değişeceği için tespit çalışmayabilir.
- Reels/Shorts'a ilk girişte engelleme ekranı, içerik erişilebilirlik ağacında görünür hâle geldikten sonra (genelde 1–2 saniye) gelir — bu, event tabanlı mimarinin doğal bir sınırıdır.
- Instagram ve YouTube dışındaki uygulamalar (ör. TikTok) sonsuz kaydırma engellemesi kapsamında değildir.

---

## Lisans

Kişisel proje. Aksi belirtilmedikçe tüm hakları saklıdır.
