# Raporlar Ekranı — Haftalık Grafik Tasarımı

## Amaç

Mevcut Raporlar ekranı (`ReportsScreen.kt`), uygulama kullanım sürelerini "Bugün / Haftalık / Aylık" sekmelerinde düz bir liste olarak gösteriyor. Bu tasarım, Digital Wellbeing / Screen Time tarzı bir görünüme geçiyor: üstte kaydırılabilir bir tarih şeridi, seçili günü içeren haftanın yığılmış (stacked) çubuk grafiği, ve grafiğin altında seçili günde kullanılan uygulamaların listesi.

Kapsam dışı: bildirim sayısı / kilit açma sayısı grafikleri (Traccer bu veriyi tutmuyor, ayrı bir özellik olarak ele alınmadı). Dashboard sekmesindeki ayrı "Zaman Raporu" akışı (`UsageReportScreen.kt` / `UsageDetailScreen.kt`) bu işin dışında, değiştirilmeyecek.

## Mevcut durum

- `ReportsScreen()`: `TabRow` (Bugün/Haftalık/Aylık) + `ReportContent()` — seçilen aralık için `UsageLog` kayıtlarını paket bazında gruplayıp süreye göre azalan sırayla düz liste (`ReportAppRow`) gösteriyor.
- Veri kaynağı: `AppUsageDao.getUsageLogsForDate(date)` ve `getUsageLogsBetween(start, end)` — ikisi de mevcut, değişiklik gerekmiyor.
- Renk paleti: `UsageReportScreen.kt` içindeki `AppColors` listesi ve `colorForApp(appName)` fonksiyonu zaten var, yeniden kullanılacak.
- Grafik kütüphanesi yok; kod tabanındaki diğer görselleştirmeler (`SummaryHeroCard` progress bar vb.) elle `Box`/`Canvas` ile çiziliyor — bu tasarım da aynı konvansiyonu izleyecek, yeni bağımlılık eklenmeyecek.

## Yeni tasarım

### Veri akışı

- `ReportsScreen()` içinde `selectedDate: Long` state'i (gün başlangıcı, ms), varsayılan bugün.
- `selectedDate`'i kapsayan hafta (Pazartesi-Pazar) hesaplanır → `weekStartMs`, `weekEndMs`.
- `db.appUsageDao().getUsageLogsBetween(weekStartMs, weekEndMs)` ile o haftanın tüm `UsageLog` kayıtları çekilir (`collectAsState`).
- Bu kayıtlardan iki türev hesaplanır:
  1. **Haftalık grafik verisi**: gün → (appName → durationMs) haritası, 7 gün için.
  2. **Seçili gün uygulama listesi**: sadece `selectedDate` gününe ait kayıtlar, paket bazında gruplanıp süreye göre azalan sırada.

### Bileşenler

1. **`DateScrollStrip`**
   - Yatay kaydırılabilir (`LazyRow`) gün numaraları listesi.
   - Aralık: bugünden geriye sabit 180 gün (DB sorgusuyla "en eski kayıt" aranmıyor — basitlik için sabit sınır). Bugünden ileri gidilemez.
   - Seçili gün vurgulanır (dolgulu daire, mevcut tasarımdaki `FilterChip`/seçili chip stiliyle tutarlı).
   - Bir güne dokunmak `selectedDate`'i değiştirir.
   - İlk açılışta bugüne otomatik ortalanır/kaydırılır.

2. **`WeeklyUsageChartCard`**
   - Üstte büyük puntoyla seçili günün toplam süresi (örn. "4 sa 8 dk") + "Toplam ekran süresi" alt yazısı.
   - Compose `Canvas` ile elle çizilmiş 7 çubuklu yığılmış bar chart:
     - Her çubuk bir günü temsil eder (Pzt..Paz kısaltmaları alt etiket).
     - Her çubuk, o gün kullanılan uygulamaların süresine göre üst üste renkli segmentlere bölünür (`colorForApp` rengi).
     - Y ekseni, haftanın en yüksek günlük toplamına göre dinamik ölçeklenir (üstte 1-2 yardımcı çizgi, yuvarlatılmış üst sınır — örn. en yakın saat).
     - Seçili günün alt etiketi vurgulanır (görseldeki gibi daire içinde).
   - Grafiğin altında: seçili günde kullanılan uygulamalar, renkli nokta + isim + süre, azalan sırada; ilk birkaçı (örn. 5) gösterilip "Tümünü Göster" ile geri kalanı açılır (mevcut `AnimatedVisibility` deseni, `UsageReportScreen.kt`'deki `ActiveHourRow` genişleme mantığıyla tutarlı).
   - Seçili günde hiç veri yoksa: "Bu gün için veri yok" boş durumu.

### Kaldırılanlar

- `TabRow` (Bugün/Haftalık/Aylık), `indexToRange()`, eski `ReportContent()`/`ReportAppRow()` düz liste görünümü tamamen kaldırılacak.

### Dosya değişiklikleri

- `app/src/main/java/com/example/traccerapp/ui/screens/ReportsScreen.kt`: yeniden yazılacak. Yeni composable'lar aynı dosyada (`DateScrollStrip`, `WeeklyUsageChartCard`, grafik çizim yardımcıları) — mevcut kod tabanı konvansiyonuyla tutarlı (bir ekran = bir dosya, içinde birden fazla composable).
- Yeni tablo/DAO/entity gerekmiyor; mevcut `getUsageLogsBetween` yeterli.

## Test / doğrulama kısıtı

Bu ortamda Android emülatörü/fiziksel cihaz yok. Doğrulama `./gradlew :app:compileDebugKotlin` ile derleme/tip hatası kontrolüyle sınırlı. Görsel doğrulama kullanıcının kendi cihazında yapılacak; ekran görüntüsüne göre gerekirse ince ayar yapılacak.
