# YzInsta

[Instaloader](https://instaloader.github.io/) tabanlı Android Instagram indiricisi.
Instagram'a uygulama içindeki WebView ile giriş yaparsınız, gönderi/reel bağlantısını
yapıştırırsınız (ya da Instagram'dan **Paylaş → YzInsta** dersiniz), fotoğraf ve
videolar galeriye kaydedilir.

- Kotlin + Jetpack Compose (Material 3), tek Activity, MVVM (ViewModel + coroutines)
- Python tarafı: [Chaquopy](https://chaquo.com/chaquopy/) 17.1 + Python 3.13 + Instaloader
- minSdk 26, targetSdk/compileSdk 36, ABI: `arm64-v8a`, `x86_64`
- Arayüz İngilizce (varsayılan) ve Türkçe

## Nasıl çalışır

```
WebView (instagram.com/accounts/login)
   │  giriş bitince CookieManager → sessionid, csrftoken, ds_user_id, …
   ▼
SessionRepository ── EncryptedSharedPreferences (şifreli, yedeklemeye kapalı)
   │
   ▼
ChaquopyBridge (Dispatchers.IO + Mutex) ──► igbridge.py ──► Instaloader
   │                                           load_session(cookie dict) + test_login()
   ▼                                           Post.from_shortcode → download_post
DownloadRepository: cache/downloads/<kod>/ → MediaStore
   Pictures/InstaloaderApp, Movies/InstaloaderApp → cache silinir
```

- **Şifre uygulamaya hiç girmez.** Giriş, Instagram'ın kendi sayfasında yapılır (2FA dahil).
  Uygulama JavaScript enjekte etmez; yalnızca sayfa yüklemelerinden sonra
  `CookieManager`'dan instagram.com çerezlerini okur.
- `igbridge.py` içindeki her fonksiyon `{"ok": true, "data": …}` ya da
  `{"ok": false, "error": {"code": …, "detail": …}}` biçiminde JSON döndürür. Python
  istisnaları Kotlin'e hiç ulaşmaz. Hata kodları (`SESSION_EXPIRED`, `CHALLENGE_REQUIRED`,
  `PRIVATE`, `NOT_FOUND`, `RATE_LIMITED`, `NETWORK`, `INVALID_COOKIES`, `NOT_LOGGED_IN`,
  `UNKNOWN`) Kotlin'de `AppError`'a ve oradan yerelleştirilmiş mesajlara eşlenir.
- Oturum süresi dolmuşsa ya da Instagram doğrulama istiyorsa: şifreli kayıt, Python oturumu
  ve WebView çerezleri silinir, kullanıcı giriş ekranına döner. Ağ hatası ya da rate limit
  durumunda oturum korunur ve uyarıyla birlikte "Tekrar dene" gösterilir.

### Kod haritası

| Yol | İçerik |
| --- | --- |
| `app/src/main/python/igbridge.py` | Instaloader sarmalayıcısı: çerez yükleme, oturum doğrulama, indirme, hata sınıflandırma |
| `data/session/` | `InstagramCookies` (çerez ayrıştırma), `EncryptedSessionStore`, `AndroidWebCookieJar`, `SessionRepository` |
| `data/python/` | `InstaloaderBridge` arayüzü, `ChaquopyBridge`, `BridgeEnvelope` (JSON → `Outcome`/`AppError`) |
| `data/download/` | `ShortcodeParser`, `DownloadRepository`, `MediaStoreExporter` |
| `ui/` | `LoginScreen` (WebView), `WelcomeScreen`, `HomeScreen`, `DownloadSection`, ViewModel'ler |
| `tools/check_cookies.py` | Çerez aktarımını telefonsuz, gerçek Instagram'a karşı test eden araç |
| `tools/check_story.py` | Hikâye/öne çıkan indirmeyi uygulamaya eklemeden önce Instaloader ile deneyen araç |

## Kurulum ve derleme

Gerekenler:

- JDK 17 veya üstü
- Android SDK; platform `android-36` ve build-tools (Android Studio ile ya da `sdkmanager` ile kurulabilir)
- **Python 3.13**, `PATH` üzerinde `python3.13` (Windows'ta `py -3.13`) olarak. Chaquopy, pip
  paketlerini derleme sırasında bu Python ile kurar. Sürüm, uygulamanın Python sürümüyle
  aynı olmak zorunda (bkz. `gradle/libs.versions.toml` → `python`). Farklı bir yoldaysa
  `app/build.gradle.kts` içindeki `chaquopy { defaultConfig { buildPython(...) } }` ile verin.

```bash
./gradlew :app:assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # Kotlin birim testleri
pip install "instaloader==4.15.4" pytest && pytest app/src/test/python   # Python testleri
```

Android Studio'da projeyi açıp çalıştırmanız da yeterli. Emülatör kullanacaksanız
`x86_64` imajı seçin.

### CI

`.github/workflows/android.yml` her push'ta pytest'i, Kotlin birim testlerini ve
`assembleDebug`'ı çalıştırır. Debug APK, çalıştırmanın **Artifacts** bölümünden
(`app-debug-apk`) indirilebilir.

### Çerez aktarımını tek başına test etme

Projenin en kırılgan yeri burası. İki yoldan test edilebilir:

1. **Testlerle (ağ gerekmez):** `app/src/test/python/test_session.py`, çerezlerin
   Instaloader oturumuna yüklendiğini ve Instagram'a giden isteğin `Cookie` /
   `X-CSRFToken` başlıklarında birebir (URL-encoded değerler bozulmadan) gittiğini, sahte bir
   HTTP katmanıyla doğrular. Kotlin tarafını `InstagramCookiesTest` ve `SessionRepositoryTest` kapsar.
2. **Gerçek Instagram'a karşı:** Tarayıcıda instagram.com'a giriş yapın; geliştirici
   araçlarından çerezleri bir JSON dosyasına kopyalayın, sonra:
   ```bash
   python3 tools/check_cookies.py --cookies cookies.json
   # veya
   python3 tools/check_cookies.py --header "csrftoken=…; sessionid=…; ds_user_id=…"
   # veya yalnızca sessionid (uygulamadaki çerezle girişle aynı kurallar)
   python3 tools/check_cookies.py --sessionid "…"
   ```
   Bu araç, uygulamanın kullandığı `igbridge.py` kodunun aynısını çalıştırır. Dosya canlı
   bir oturum içerir; işiniz bitince silin.

### Hikâye indirmeyi deneme

Hikâye desteği henüz uygulamada yok. Instaloader'ın hikâye fonksiyonları eski GraphQL
sorgularını kullandığı için bugün çalışıp çalışmadığı belirsiz. Önce bilgisayarda deneyin:

```bash
pip install "instaloader==4.15.4"
# tek hikâye (hikâyede Paylaş → Bağlantıyı kopyala)
python3 tools/check_story.py --sessionid "…" https://www.instagram.com/stories/KULLANICI/1234567890123456789/
# kullanıcının tüm aktif hikâyeleri
python3 tools/check_story.py --sessionid "…" KULLANICI
# öne çıkanları listele, sonra birini indir
python3 tools/check_story.py --sessionid "…" KULLANICI --highlights
python3 tools/check_story.py --sessionid "…" https://www.instagram.com/stories/highlights/1791234…/ --user KULLANICI
```

Betik her adımı ayrı yazdırır (çerez yükleme, oturum doğrulama, profil, hikâyeler,
indirme). Bir adım başarısız olursa hata kodunu (`RATE_LIMITED`, `SESSION_EXPIRED`, …)
gösterir. Dosyalar `story-test-output/` klasörüne iner. `--debug` tam hata çıktısını verir.

### Instaloader'ı güncelleme

Sürüm tek bir yerde tutulur: `gradle/libs.versions.toml` → `instaloader = "…"`. Gradle
(Chaquopy `pip install`), CI'daki pytest ve uygulamadaki `BuildConfig.INSTALOADER_VERSION`
hepsi bu değeri okur. Güncelledikten sonra `pytest` ve `./gradlew assembleDebug`
çalıştırın. `igbridge.py` Instaloader'ın şu iç ayrıntılarına dayanır; yeni sürümde
bunları kontrol edin:

- `InstaloaderContext.load_session()` sözlükte `csrftoken` bekler.
- `test_login()` ağ hatalarını yutar; neden, `context.error()` çağrısı sırasında yakalanır.
- `RateController.sleep` / `handle_429` override edilir (uzun beklemek yerine hızlıca hata verir).
- Bulunamayan gönderi için `BadResponseException("Fetching Post metadata failed.")` atılır.

## Bilinen sınırlamalar

- **Gayriresmî API.** Instaloader, Instagram'ın web API'sini kullanır. Instagram bunu
  sık sık değiştirir; değiştirdiğinde Instaloader güncellenene kadar indirme bozulabilir.
  Hesabınız için risk olabilir; kendi hesabınızla, makul sıklıkta kullanın.
- **Rate limit.** Instagram istekleri sınırladığında (429 ya da "Please wait a few minutes")
  uygulama dakikalarca beklemez, hemen "biraz bekleyin" der. 30 saniyeden uzun bekleme
  gerektiren durumlar hata olarak döner (`igbridge.DEFAULT_MAX_WAIT_SECONDS`).
- **Gizli gönderi / bulunamadı ayrımı.** Instagram, erişiminiz olmayan gizli bir gönderi için
  de boş yanıt döndürür; bu yüzden "bulunamadı" mesajı "takip etmediğiniz gizli bir hesaba
  ait olabilir" der.
- **WebView girişi.** Instagram, gömülü tarayıcılardan gelen girişlere ek doğrulama
  (checkpoint) isteyebilir ya da sayfayı düzgün göstermeyebilir. WebView ayarları Seal'in
  çerez sayfasıyla aynı: tüm http(s) gezinmelerine izin verilir, `X-Requested-With`
  başlığı kapatılır. Giriş ekranının ⋮ menüsünde **Yenile** ve **Masaüstü sürümü**
  seçenekleri var. Ana sayfa yüklenemezse hata ekranda gösterilir; WebView konsol
  mesajları logcat'e `LoginWebView` etiketiyle yazılır.
- **Yedek giriş: çerez yapıştırma.** Giriş sayfası hiç çalışmazsa, karşılama ekranındaki
  "Çerezle giriş yap (gelişmiş)" ile bilgisayardaki tarayıcıdan kopyalanan `sessionid`
  değeri (ya da Cookie başlığının tamamı) yapıştırılabilir. Şifre gerekmez. `ds_user_id`
  değeri `sessionid` içinden çıkarılır. `csrftoken` verilmezse rastgele bir değer üretilir;
  Instaloader bu değeri yalnızca başlıkta geri gönderir. Bu akış gerçek bir hesapla test
  edilmedi.
- **Oturum ömrü.** Çerezlerin süresi Instagram'a bağlıdır. Süre dolunca uygulama yeniden
  giriş ister.
- **Kapsam (MVP).** Yalnızca tek gönderi, reel ve IGTV. Story, highlight ve profil indirme
  yok (mimari hazır: `InstaloaderBridge`'e metot, `igbridge.py`'ye fonksiyon eklemek
  yeterli). İndirme ViewModel kapsamında çalışır; uygulama arka plana atılıp sistem
  tarafından öldürülürse yarıda kalır. Foreground service ve indirme geçmişi henüz yok.
- **Android 8–9.** Dosyalar galeriye `WRITE_EXTERNAL_STORAGE` izniyle doğrudan kopyalanır
  (izin ilk indirmede istenir). Android 10+ izin gerektirmez.
- **Uygulama boyutu.** Gömülü Python yüzünden debug APK ~49 MB'tır (iki ABI; CI artifact zip'i ~30 MB).
- **`EncryptedSharedPreferences`.** `androidx.security:security-crypto` Google tarafından
  kullanımdan kaldırıldı (deprecated), ancak çalışmaya devam ediyor. İleride Keystore +
  DataStore'a geçilebilir. Bu değişiklik `SessionStore` arayüzünün arkasında kalır.
- **Test durumu.** Kod; birim testleri (35 Kotlin, 36 Python) ve hem yerel hem CI derlemesiyle
  doğrulandı. Cihaz/emülatör üzerinde ve gerçek bir Instagram hesabıyla uçtan uca test
  yapılamadı. İlk kurulumda giriş → doğrulama → indirme akışını bir kez elle deneyin.
