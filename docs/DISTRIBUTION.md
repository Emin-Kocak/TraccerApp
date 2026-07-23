# Dağıtım ve Uzaktan Güncelleme (Firebase App Distribution)

Bu rehber, TraccerApp'i arkadaşlarına test için dağıtmayı ve sonradan yeni sürüm gönderebilmeyi anlatır.

> **En kritik kural:** Uygulamayı ilk imzaladığın anahtarı (keystore) **asla kaybetme ve değiştirme.**
> Android, bir uygulamayı yalnızca **aynı anahtarla** imzalanmış yeni sürümle günceller. Anahtar
> değişirse arkadaşların uygulamayı silip yeniden kurmak zorunda kalır (izinler dahil her şey sıfırlanır).

---

## Bölüm A — Bir kereye mahsus kurulum

### A1. Release imza anahtarı (keystore) oluştur

Repo kökünde (`TraccerApp/`) şu komutu çalıştır:

```bash
keytool -genkeypair -v -keystore traccer-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias traccer
```

- Bir **keystore şifresi** ve bir **anahtar şifresi** soracak (aynısını kullanabilirsin). Bunları not al.
- İsim/kurum sorularını boş geçebilir ya da doldurabilirsin.
- Oluşan `traccer-release-key.jks` dosyasını **güvenli bir yerde yedekle** (Google Drive, şifre yöneticisi vb.). Bu dosya `.gitignore`'da — git'e gitmez.

### A2. keystore.properties oluştur

`keystore.properties.template` dosyasını `keystore.properties` adıyla kopyala ve şifreleri doldur:

```properties
storeFile=traccer-release-key.jks
storePassword=SENIN_KEYSTORE_SIFREN
keyAlias=traccer
keyPassword=SENIN_ANAHTAR_SIFREN
```

Bu dosya da git'e girmez. Artık `./gradlew assembleRelease` **imzalı** APK üretir.

### A3. Firebase projesi ve testçiler

1. https://console.firebase.google.com → **Add project** ile bir proje oluştur (Google hesabınla).
2. Projede **Android uygulaması** ekle:
   - **Android package name:** `com.example.traccerapp`
   - (SHA / google-services.json adımları App Distribution için **zorunlu değil**, atlayabilirsin.)
3. Sol menü → **Run** (veya App Distribution) → **App Distribution**'ı aç.
4. **Testers & Groups** sekmesinde bir grup oluştur, ör. adı **`friends`** olsun. Arkadaşlarının e-postalarını bu gruba ekle.
5. **App ID**'yi al: **Project settings (⚙️) → General → Your apps → Android app → App ID.**
   Format şuna benzer: `1:1234567890:android:abcdef123456`. Bunu bir yere kaydet.

### A4. Firebase CLI kur ve giriş yap

Node.js kuruluysa:

```bash
npm install -g firebase-tools
firebase login
```

(Node yoksa: https://firebase.google.com/docs/cli adresinden standalone binary indirilebilir.)

---

## Bölüm B — Her yeni sürüm gönderirken (uzaktan güncelleme)

### B1. Sürüm numarasını artır

`app/build.gradle.kts` içinde **her yeni build için `versionCode`'u 1 artır** (güncelleme algılaması buna bağlı). `versionName`'i de okunur tut:

```kotlin
versionCode = 2        // önceki 1'di
versionName = "1.1"
```

### B2. İmzalı release APK üret

```bash
./gradlew assembleRelease
```

Çıktı: `app/build/outputs/apk/release/app-release.apk`

### B3. Testçilere dağıt

Bu projenin gerçek değerleriyle (PowerShell, tek satır — repo kökünde çalıştır):

```powershell
firebase appdistribution:distribute app/build/outputs/apk/release/app-release.apk --app 1:751093979110:android:32e7dd27428da92d826827 --groups "arkadaşlar" --release-notes "Değişiklik notu"
```

Bu kadar. Testçiler **bildirim** alır ve **App Tester** uygulamasından tek dokunuşla günceller.

> **Grup gotcha'sı:** `--groups` grubun **alias**'ını kullanır (görünen adını değil). Mevcut grup alias'ı `arkadaşlar`.
> Yeni grup eklersen alias'ı `firebase appdistribution:group:list --project traccerapp-cc1ec` ile öğren.
> Grup yerine doğrudan e-postalara göndermek istersen: `--testers "a@x.com,b@y.com"` (grup gerekmez).

> İlk seferde her arkadaşın: davet e-postasındaki linke tıklar → **App Tester**'ı kurar → uygulamayı oradan indirir.
> Sonraki güncellemelerde sadece bildirim gelir, "Update" derler.

---

## Notlar ve sınırlar

- **Sessiz (otomatik) güncelleme yok.** Play Store dışı (sideload) uygulamalarda Android, her kurulum/güncellemede kullanıcının onayını ister. "Uzaktan güncelleme" = sen yeni build yayınlarsın, arkadaşın bildirimi görüp onaylar.
- **Aynı keystore şart.** B adımlarındaki her build A1'deki `keystore.properties` ile otomatik imzalanır — anahtarı değiştirme.
- **Accessibility izni** genelde güncellemede korunur (aynı paket + aynı imza). İmza değişirse sıfırlanır.
- Bu uygulama AccessibilityService + UsageStats kullandığı için Google Play politikaları zordur; arkadaş grubuna App Distribution ile sideload en pratik yoldur.
- İleride tek komuta indirmek istersen Firebase App Distribution **Gradle plugin**'i de eklenebilir (build + upload tek `./gradlew` görevi). Şimdilik CLI yolu build'i sade tutuyor.
