# Katkı Rehberi

Cloudstream eklentileri için kaynak deposu. Her site, tek bir `.cs3` üreten tek bir Gradle
modülüdür.

## Gereksinimler

- JDK 17
- Android SDK 35 (`platforms;android-35`, `build-tools;35.0.0`)
- `local.properties` içinde `sdk.dir` (repoya girmez)

```bash
./gradlew make makePluginsJson      # tüm modüller
./gradlew Dizibox:make             # tek modül
./gradlew Dizibox:deployWithAdb    # bağlı cihaza kur
```

Çıktılar `providers/<Modül>/build/<Modül>.cs3` ve `build/plugins.json` altına gider.

## Yeni sağlayıcı ekleme

1. `providers/` altındaki bir modülü kopyala (`providers/DiziYou` en sade örnektir).
2. Klasör adını, Gradle modül adını, kaynak paketini (`com.<modül>`) ve plugin/provider
   sınıflarını yeniden adlandır.
3. `settings.gradle.kts`, `providers/` altındaki her klasörü otomatik bulur; ayrıca kayıt
   gerekmez.
4. Seçicileri gerçek HTML üzerinde yaz. Tahminle selector yazma.
5. Modülü derle ve bir içeriği cihazda oynatmadan PR açma.
6. `providers/<Modül>/build.gradle.kts` içindeki `version` değerini artır, `CHANGELOG.md`'ye
   giriş ekle ve `README.md` sağlayıcı tablosunu güncelle.

## Özellik standardı

Her sağlayıcı, site gerçekten sunuyorsa aşağıdaki alanları uygular:

- **Sayfalı arama**: `search(query, page): SearchResponseList`; tek sayfalık `search(query)` buna delege eder.
- **Ana sayfa sayfalaması**: `newHomePageResponse(..., hasNext = …)` sitenin kendi "sonraki" bağlantısından türetilir.
- **Detay zenginliği**: `recommendations`, `addTrailer`, `actors`, `duration`, `score` (`Score.from10(...)`), bölüm `description`.
- **Anime siteleri**: `TvType.Anime` (hem `supportedTypes` hem load yanıtı).
- **Hızlı arama**: yalnız sitenin gerçek bir canlı arama uç noktası varsa `quickSearch`.
- **Canlı TV**: ayrı modül, `TvType.Live` + `newLiveSearchResponse` /
  `newLiveStreamLoadResponse`; canlı kanalda `loadLinks` kullanılmaz.
- **Eklenti ayarları**: her modülde `Settings.kt` + `Plugin.openSettings` ile kalite üst sınırı ve
  altyazı dili sunulur; `loadLinks` çıkışı ve altyazı callback'i filtrelenir.
- **Hata bildirimi**: `load`/`loadLinks` başarısızlığında `ErrorLoadingException` + issue bağlantısı;
  en az bir link üretildiyse sessiz kalınır.

Sitenin sunmadığı alan **boş bırakılır** ve README matrisinde `—` olarak işaretlenir. Uydurma veri
yasaktır. Her yeni alan canlı istekle (URL + çıkan değer) kanıtlanır; kanıt yoksa alan eklenmez.

## Kurallar

- **Stub yok.** `search` her zaman boş liste döndüren veya `loadLinks` her zaman `false` dönen
  sağlayıcı yayınlanmaz. Engellenen kaynağı raporla.
- **Tek taban adres.** Site adresini tek sabitte tut. Mirror'lar dokümantasyonda, kodun içine
  dağıtılmış hâlde değil.
- **Kopyalanmış kaynak yok.** Davranışı gözlemleyerek yeniden uygula; başka depodan dosya kopyalama.
- **Sessiz hata yok.** `load` ve `loadLinks` içindeki ağ/ayrıştırma hatalarını
  `ErrorLoadingException` ile bildir; boş liste veya `false` ile geçiştirme. Arama ve ana sayfa
  dalgalanmalara açık olduğu için boş liste döner, hata diyaloğu açmaz.
- **Beta durumu.** Uçtan uca doğrulanmamış sağlayıcı `status = 3`, doğrulanmış sağlayıcı
  `status = 1` kullanır.
- Davranış değişikliği sürüm artışı ve `CHANGELOG.md` güncellemesiyle birlikte gelir.
- `README.md` sağlayıcı tablosu ve özellik matrisi **her zaman güncel tutulur**; eski bilgi bırakılmaz.

## Kod stili

- Kotlin; mevcut plugin/provider ayrımı ve `CloudstreamPlugin` + `MainAPI` yapısı izlenir.
- Yeni bağımlılık yerine `Regex` ve `substringAfter/Before` tercih edilir.
- Yorum yalnızca kodun gösteremediği nedeni tek satırda anlatır.

## Bot ve doğrulama

- `python tools/verify.py` — tüm doğrulanmış sağlayıcılar; `--domain a.com,b.com` ile seçim.
  Taban adres `tools/providers.json` → `mainUrl`'den okunur, kodda sabit değildir.
- `python tools/revive.py --domain <domain>` — yalnız öneri; `--apply --patch-code` ile uygular.
  Kod yaması başarısızsa registry değişmez. Verifier'ı olmayan domain otomatik uygulanmaz.
- `python tools/check.py` — domain sağlığı.
- Yeni sağlayıcı eklerken `verify.py` içindeki `VERIFIERS` sözlüğüne bir doğrulayıcı ekle; yoksa
  bot o domaini asla otomatik güncellemez.

CI: `build.yml` `builds` dalını üretir; `domains.yml` doğrulanmış mirror güncellemesini doğrudan
`master`'a yazar, sürümü artırır ve `CHANGELOG.md`'ye patch başlığı ekler; `bildirim.yml` commit'i
Telegram'a gönderir.

## Commit

- Her commit tek mantıksal değişiklik olsun; mesaj **Türkçe** yazılır.
- Mesajda etkilenen modül belirtilir (`Dizibox: yeni oynatıcı sunucusu`).
