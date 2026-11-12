# Değişiklik Kaydı

Bu depodaki önemli değişiklikler burada tutulur.
Biçim: [Keep a Changelog](https://keepachangelog.com/tr/1.1.0/). Depo sürümleri ayrıdır; her eklenti
`providers/<Modül>/build.gradle.kts` içinde kendi `version` değerini taşır ve Cloudstream ancak bu
sayı arttığında güncelleme sunar.


## [1.6.38] - 2026-11-12
### Düzeltildi
- **Dizist**: Ana sayfadaki bölüm kartları artık dizi sayfasına gidiyor.

## [1.6.37] - 2026-11-12
### Düzeltildi
- **Dizimanya**: Dizi detayı ve bölüm listesi yeniden çalışıyor.

## [1.6.36] - 2026-11-11
### Eklendi
- **DiziSol**, **Dizimanya**, **Dizist**, **IzleFilm**, **RoketDizi**, **TrDiziIzle**, **TurkAnimeTV** sağlayıcıları eklendi.

## [1.6.35] - 2026-11-11

### Düzeltildi
- **Film ve dizi açıklamaları düzeltildi**: Birçok sağlayıcıda yanlış görünen özetler artık doğru.
- **StarTV**: Dizi açıklaması artık site verisinden okunuyor.
- **FullHDFilmizlesene Nuvio**: rapidvid akışı düzeltildi.
- **AsyaWatch**: Oyuncular, puan ve benzer diziler eklendi.
- **DiziPod**: Film fragmanları eklendi.
- **HDFilmDelisi**: Film süresi eklendi.
- **UltraFilmizle**: Ana sayfa düzeltildi.

## [1.6.34] - 2026-11-11

### Düzeltildi
- **FullHDFilmizlesene v7**: rapidvid oynatıcısının yeni `jwSetup.sources` akışı çözüldü; filmler yeniden açılıyor.
- **DiziFilmIzle v5**: Arama sonuçlarındaki oyuncu/yönetmen kayıtları elendi, geçersiz 404 detay sayfaları kapatıldı ve açıklama JSON-LD'den okunuyor.

## [1.6.33] - 2026-11-10

### Düzeltildi
- **Yabancidizi v12**: Güncel alan adına (`ybncidizi.com`) taşındı ve pichive oynatıcı Referer/UA doğrulaması güncellendi.

### Kaldırıldı
- **İnatTV / İnatBox**: Upstream 520/imza sorunları nedeniyle Cloudstream modülü ve Nuvio sağlayıcısı bir süreliğine kaldırıldı; sorunlar çözüldüğünde tekrar eklenecek.
- **SineWix**: Cloudstream modülü bir süreliğine kaldırıldı; sorunlar çözüldüğünde tekrar eklenecek.

## [1.6.32] - 2026-11-10

### Düzeltildi
- **Filmizyon v4**: Yeni `pl.vixolity.com` video oynatıcı desteği eklendi; filmlerin açılmama sorunu giderildi.

## [1.6.31] - 2026-11-09

### Düzeltildi
- **SelcukFlix v9, Dizilla v14, DiziPal v11, AsyaWatch v9, Yabancidizi v11, Dizigecesi v14**: Videoların açılırken hata vermesi sorunu hem Cloudstream hem Nuvio tarafında giderildi; DiziPal güncel adresine taşındı.

### Değişti
- **Ddizi v5**: taban adres https://ddizi.tel -> https://www.ddizi.tel.

## [1.6.30] - 2026-11-09

### Düzeltildi
- **Görüntü Var Ses Yok Sorunu**: SelçukFlix, DiziPal, FilmMakinesi, PuhuTV, StarTV, TLC ve WebdramaTurkey2 başta olmak üzere ayrı ses kanalı kullanan dizi ve filmlerde videonun sessiz oynaması sorunu hem Cloudstream hem Nuvio tarafında tamamen giderildi.
- **Açılmayan Videolar ve Sunucu İmzaları**: Dizilla, Sezonlukdizi, Webteİzle, Dizikorea3, JetFilmizle, AsyaAnimeleri ve Deokwave üzerinde bazı bölümlerin açılmaması veya kırık kaynak seçmesi sorunu düzeltildi; altyazıların yüklenmeme hatası giderildi.
- **Dizibox Ana Sayfa**: Ana sayfadaki son bölümlere tıklandığında detay sayfasının açılmaması sorunu çözüldü.
- **İnatTV ve DiziBoxTv**: İnatTV (Cloudstream ve Nuvio) ile sunucusu kapanan DiziBoxTv geçici olarak pasife alındı.

## [1.6.29] - 2026-11-09

### Düzeltildi
- **İnatTV**: Cihazlarda açılmama, engellenme (403) ve "oynatıcı çözülemedi" hatası veren bölümler düzeltildi. Dizi Film BOX kataloğu eklendi ve dizi bölümlerinin yüklenme hızı artırıldı.
- **Video Oynatma ve Akışlar**: Dizilla, SelçukFlix, Yabancı Dizi, DiziPal, Deokwave ve AsyaWatch başta olmak üzere bazı filmlerin ve dizilerin açılırken hata vermesi veya yüklenip başlamaması sorunu giderildi.
- **Ana Sayfa Görselleri**: Dizibox, AniArşiv, TV8 ve Webteİzle üzerinde ana sayfada boş kalan ya da yanlış yüklenen afişler düzeltildi.
- **Nuvio Arama ve İçerik Bulma**: Türkçe ve yerli dizi/filmlerin (örneğin Gibi gibi içeriklerin) Nuvio aramasında çıkmaması sorunu çözüldü; 30 sağlayıcının tamamına Türkçe ve orijinal isimle arama desteği getirildi.
- **Dizi ve Çizgi Film İyileştirmeleri**: AsyaDizi ve MinikaGO üzerindeki arama ve bölüm listeleme hataları giderildi.

## [1.6.28] - 2026-11-09

### Eklendi
- **Ortak HLS varyant ayrıştırıcısı (`Common.parseHlsVariants`)**: Master `.m3u8` içindeki `#EXT-X-STREAM-INF` satırlarından çözünürlük yüksekliğini (`RESOLUTION=\d+x(\d+)`) ve göreli/mutlak alt oynatma listesi adreslerini çıkaran ortak yardımcı eklendi; master akışın yanı sıra tüm alt kaliteler (`2160p..240p`) ayrı `ExtractorLink` olarak sunulur.
- **İnatTV otomatik eski eklenti temizliği (`InatTVPlugin.purgeLegacyInatPlugins`)**: Kullanıcı cihazında önceden kurulmuş 35 eski `Inat*` eklentisini (`InatNetflix`, `InatDisney`, `InatYabanciDiziler`, `InatSpor` vb.) uygulama açılışında ve ayarlar ekranında `PluginManager` üzerinden bellekten kaldırır, diskteki `.cs3`/`.dex`/`.odex` dosyalarını siler, `APIHolder.allProviders`/`apis` listelerinden çıkarır ve `DataStore` ana sayfa tercihini otomatik olarak `İnatTV`'ye taşır.

### Düzeltildi
- **İnatTV v3 (`providers/InatTV`) + Nuvio `inattvdizi` (1.0.4) / `inattvfilmler` (1.0.6)**:
  - `nok5_mode` (VK `al_video.php` POST → HLS + `240p..2160p` MP4 akışları) desteği hem Cloudstream hem Nuvio tarafına eklendi.
  - `storage` ve `data` nesneleri altındaki `chUrl`/`url`/`source` alanları, iç içe sezon/bölüm listeleri (`dizi`/`bolum`/`items`/`episodes`) ve tek öğeli diziler düzeltildi; tüm 24 kategori (`Yabancı Diziler`..`Liste 1-2-3`) uçtan uca doğrulandı.
  - Nuvio `inattvdizi` ve `inattvfilmler` çoklu katalog araması (`diziler`, `yerli-diziler`, `netflix`, `disney`, `amazon-prime`, `hbomax`, `exxen`, `gain`, `tabii`, `tod`, `mubi`, `yerli-filmler`) ile genişletildi.
- **Çoklu kaynak ve çoklu kalite çıkarımı (17 sağlayıcı)**:
  - **Deokwave v3**: Dizi bölümleri `var allSezonlar` JSON'undan çıkarıldı, bölüm izleme URL'sindeki sondaki `/` kaldırıldı (`404` hatası giderildi), oturum çerezleri `/api/v1/video/token/` isteğine aktarıldı ve tüm `fansubs` + kalite seçenekleri (`1080p`, `720p`, `480p`) eklendi.
  - **Animeler v9**: Next.js RSC gövdesindeki `my.mail.ru/video/embed/`, `ok.ru/videoembed/`, `sibnet.ru`, `vidmoly` ve `gdriveplayer` alternatif kaynakları çıkarıldı.
  - **Sinezy v5**: FirePlayer büyük/küçük harf `Origin` uyumsuzluğu (`https://Player.filmizle.in` → `https://player.filmizle.in`), `fireplayer_player` oturum çerezi aktarımı ve HLS kalite varyantları düzeltildi.
  - **Dizigecesi v10**: Gömülü oynatıcı URL'lerinin sonundaki `#<ip>` eki temizlendi ve Filemoon HLS kalite varyantları eklendi.
  - **TurkceDizi v7**: `Diziler` ve `Filmler` satırlarındaki RSC poster regex'i `aria-label` özniteliğini kapsayacak şekilde genişletildi (`35/35` poster); tüm `iframeUrl` eşleşmeleri ve `ok.ru` çoklu kalite akışları eklendi.
  - **HDFilmDiziIzle v6**: İlk kaynakta erken duran `break` kaldırıldı; tüm alternatif sunucular (`Close`, `Vidmoxy`, `Fast`, `Rumble`) ve HLS kalite varyantları çıkarılıyor.
  - **CanliDizi v4**, **DiziAbi v4**, **Dizigom v4**, **HDFilmDelisi v5**, **DiziCaN v9**, **AsyaBox v4**, **AsyaDizi v4**, **Asyadizileriizle v4**, **BirAsyaDizi v5**, **Dizirella v4**, **Weboo v3**: HLS master varyant ayrıştırması (`parseHlsVariants`) ve gömülü oynatıcı (`HintDizi`, `Pilavyer`, `Vixolity`, `OkRu`) çoklu kalite desteği eklendi.

## [1.6.27] - 2026-11-08

### Düzeltildi
- **Sahte/bozuk akışlar (88 sağlayıcı taraması)**: `AsyaBox`, `AsyaDizi`, `Asyadizileriizle`, `BirAsyaDizi` ok.ru gömmelerinde HTML-escape (`&quot;`) içeren `data-options` bloğunu oynatma linki sanıyordu; ok.ru artık `app.get`'ten önce özel çözücüye gider, kalite eşlemesi (`mobile→240 … full→1080`) ve kalite üst sınırı uygulanır, `loadOkRu` sonucu `found`'a işlenir.
- **Sabit stub linkler kaldırıldı**: `CanliDizi`/`DiziAbi`/`Dizigom` (`blank.mp4`), `Dizirella` (sabit `master.m3u8`), `HDFilmDelisi` (`matrix_136.mp4`), `Weboo` ve `AsyaDizi` sabit linkleri.
- **Gerçek akış çıkarımı**: Pilavyer oynatıcısı (`s.php?s=` → `window.__PLAYER__.stream` HLS + altyazı; ölü `kankaplayer.top` → `pilavyerplay.top`) ve YouTube fragman iframe'leri atlanır; `HDFilmDelisi` `pl.vixolity.com` oynatıcısı çözülür; `Dizirella` query'siz `master.m3u8` yakalanır.
- **Bölüm listeleri**: `Weboo` (`mosaic-ep-card`, 40+ bölüm; `proxy.php` akışı) ve `Dizirella` (`N-sezon-M-bolum` ve `dizi/` sayfası) bölümleri artık listeleniyor.
- **Kategoriler**: `Cizgimax`, `DiziBal`, `DiziMom`, `CanliDizi`, `DiziAbi` boş/yinelenen satırlar düzeltildi; `HDFilmDelisi` `Çizgi` (100% `Animasyon` kopyası) kaldırıldı.
- `TurkceDizi` gerçek `/api/search` aramasına kavuştu; `DiziCaN` çift `master.m3u8` vermiyor; `HDFilmDiziIzle` başlık HTML kodları çözülüyor ve yavaş kaynaklarda erken duruyor; `InatTV` bozuk poster (`hhttps://`) temizleniyor; `AsyaDizi` başlıktaki `arşivleri` ekini atıyor.
- Nuvio: aynı taramada `nuvio/providers/*.js` içinde sabit stub/ok.ru regex hatası bulunmadı; davranış değişikliği yok.

## [1.6.26] - 2026-11-08

### Değişti
- **İnatTV v1 (`providers/InatTV`) — tek sağlayıcı birleştirmesi**: Cloudstream'deki 35 ayrı `Inat*` modülü (`InatNetflix`, `InatDisney`, `InatYabanciDiziler`, `InatSpor` vb.) **RecTV** mimarisiyle tek bir **`İnatTV`** (`InatTV`) eklentisinde birleştirildi; 24 aktif kategori (Yabancı/Yerli Diziler, Yerli Filmler, Netflix, Disney+, Amazon Prime, HBO Max, EXXEN, Gain, Tabii, Mubi, TOD, Sinema, Belgesel, Spor, Derbiler ve canlı TV listeleri) ana sayfa satırı ve eklenti ayarlarından seçilebilir hale getirildi.

### Düzeltildi
- **İnatTV HTTP 403 / Cloudflare hız sınırı (`error code: 1006`)**: 25 ayrı eklentinin aynı anda `diziboxen.help` / `sprboxs.bar` uçlarına paralel istek atarak geçici Cloudflare IP engeline (`1006`) yol açması tek eklenti mimarisi ve 30 dakikalık bellek önbelleği (`CACHE_LIMIT = 64`) ile giderildi; `API_HEADERS` içine `User-Agent: speedrestapi` başlığı eklendi, ölü `4k.filmizleeeee.cfd` katalogları ve liste başı reklam/sosyal medya satırları (`@inattvapk`, `inattv*.cfd`) temizlendi.
- **Nuvio `inattvfilmler` (1.0.5)**: taban adres tek yere alındı; ölü 4K host kalıntısı silindi.

## [1.6.24] - 2026-11-08

### Eklendi
- **PuhuTV v1 (`puhutv.com`) + Nuvio `puhutv` (`1.0.0`)**: Cloudstream (95. aktif modül) ve Nuvio (30. sağlayıcı) için yerli dizi, puhutv Orijinal, film ve belgesel kaynağı eklendi; sayfalı kategori akışları (`/api/slug`), anlık arama (`appservice.puhutv.com`), oyuncu kadrosu, film süresi, sezon/bölüm listesi, VTT altyazılar ve DYG (`PublisherId=29`) 4K'ya kadar HLS akışları doğrulandı.

## [1.6.23] - 2026-11-07

### Eklendi
- **CI kapısı (`kapi.yml` + `.github/scripts/kapi.py`)**: her push ve PR'da Nuvio statik/sandbox + `__langLabeled` sarmalayıcı özdeşlik testi, `manifest.json` senkron kontrolü, kaynak değişen modülde `version` artış denetimi ve derlemede `.cs3` sayısı doğrulaması çalışır.
- **Ortak kripto + birim testleri (`Common.kt` + `CryptoTest.kt`)**: AES-CBC, AES-GCM, PBKDF2-HMAC-SHA512 ve EVP-MD5-AES yardımcıları `Common.kt`'de birleştirildi ve JUnit testleriyle (`:Dizibox:testDebugUnitTest`) güvenceye alındı.

### Değişti
- **Ortak kripto geçişi**: **DiziPal v8**, **SelcukFlix v6**, **DiziFilmIzle v3**, **NetFilmizle v4**, **HDFilmDiziIzle v4**, **Dizigecesi v9** ve **AnimeciX v4** şifre çözme/üretme akışları `Common.kt` yardımcılarına taşındı.
- **Pasif modül tek kaynağı (`settings.gradle.kts`)**: sabit `disabled` listesi kaldırıldı; pasif 28 modül kendi (Inat4k03Exo/Web ve Inat4k06Exo 4K katalogları kapatıldığı için pasif; Nuvio İnatTV Filmler 1.0.4 güncellendi) — `build.gradle.kts` dosyasında `status = 0` ile işaretlendi.
- **Derleme ve iş akışları**: Gradle paralel derleme + build cache açıldı, CI'da bağımlılık/stub önbelleği sabitlendi (`bagimlilar-v1`), Nuvio-only push'larda gereksiz Gradle derlemesi bildirim akışı korunarak atlandı, `nuvio-catalog.yml` push yarışı `pull --rebase` ile giderildi.

### Kaldırıldı
- **`health.yml`**: sağlık ve mirror taraması yerelde `AutoUpdater` tarafından yapıldığı için GitHub Actions üzerindeki atıl günlük sağlık workflow'u kaldırıldı.

### Düzeltildi
- **RecTV v4**: sessiz yutulan hatalar kaldırıldı; liste, detay ve akış hataları `ErrorLoadingException` ile kullanıcıya bildirilir.
- **SetFilmIzle v7 & Cizgimax v10**: yedek oynatıcı denemelerindeki hata yakalama blokları sadeleştirildi.

## [1.6.22] - 2026-11-07

### Eklendi
- **Nuvio RecTV (1.0.1)**: 29. Nuvio sağlayıcısı; imzalı API'nin `/api/search` ve `/api/season/by/serie` uçlarıyla film **ve dizi** akışı (sezon/bölüm eşleşmeli).
- **RecTV v3**: sunucu taraflı arama, dizi için gerçek sezon/bölüm listesi (dublaj/altyazı kaynakları bölüme bağlı), ek ana sayfa satırları.

### Düzeltildi
- **İnat 4K film modülleri (10 adet) pasif**: `4k.filmizleeeee.cfd` katalog uçları upstream config'inden çıkarıldı (404); resmî istemci config'i (cf/ct.php) değişmediği için Nuvio `inattvfilmler` canlı `yerli-filmler.php` kaynağına bağlandı (v1.0.4) ve `inattvdizi` imzalı akışı canlı doğrulandı.
- **SelcukFlix v5 (+ Nuvio 1.1.0)**: site DiziPal platformuna taşındı (eski AES `secureData` ve `/api/bg/searchcontent` kalktı); `/api/mj` arama, `/film/<slug>/izle` + `/dizi/<slug>/sezon-S/bolum-E` sayfaları, PBKDF2-SHA512/AES `enc` çözümü ve dublaj/altyazı alternatif kaynakları yeniden uygulandı (runner: film + dizi PASS).
- **DiziPal v7**: `search` boş liste döndüren taslaktı; `/api/mj` ile gerçek arama eklendi.
- **WebteIzle (Nuvio 1.0.3)**: depoda `enabled:false` idi; Turnstile yedek oynatıcıları ve windows-1254 arama ile etkinleştirildi.
- **Nuvio manifest**: `CHANGELOG` sürümü artınca `nuvio/manifest.json` otomatik güncellenir (workflow + bot).

## [1.6.21] - 2026-11-07

### Eklendi
- **RecTV v2**: `a.psrectv80.xyz` imzalı resmî API (attest/nonce + HMAC-SHA256) eklendi; film/dizi ana sayfa satırları, sayfalama, önbellekli istemci taraflı arama, `fixUrl` uyumlu `https://` detay/kaynak adresleri ve token'lı m3u8 kaynakları runner'da doğrulandı. Nuvio'ya `rectv` (29. sağlayıcı) eklendi; canlı örnek bir akış döndü.

### Düzeltildi
- **WebteIzle v2**: aramada tam başlık eşleşmelerinin henüz yayınlanmamış gelecek yıl yapımlarının önüne sıralanması sağlandı (`q=avatar` -> `Avatar` 2 link PASS).

## [1.6.20] - 2026-11-07

### Düzeltildi
- **FullHDFilm v5 & FullHDFilmizlesene v5**: tek HLS master playlist'in `1080p` etiketlenip kalite üst sınırı (`720p`/`480p`) seçildiğinde sıfır link bırakması düzeltildi (`Qualities.Unknown`).
- **Animeler v7, Dizibaba v4, Dizigecesi v8, Dizilab v5, Sinezy v4, DiziYou v9**: `Settings.rowEnabled` ana sayfa kategori filtresi, `Settings.qualityAllowed`/`subtitleAllowed` akış filtreleri, eksik `ROWS` tanımları ve `URLEncoder` arama sorgusu kodlaması düzeltildi.
- **Nuvio sağlayıcıları**: `filmmakinesi` (`1.0.1`) önek eşleşme hatası, `inattvdizi` (`1.0.3`) ve `inattvfilmler` (`1.0.3`) sahte sezon/bölüm fallback'i ve UTF-8/AES bellek darboğazı, `dizikorea3` (`1.0.9`) TMDB→IMDb çözümlemesi, `diziyou` (`1.0.9`) ve `dizipal.json` katalog adresi güncellendi.
- **Nuvio katalog derleyicisi (`nuvio/addon/build.mjs`)**: `imdb-cache.json` içindeki negatif (`null`) önbellek kontrolü, kısa başlık çakışması ve tekil katalog derlemesinde `manifest.json` adlarının sıfırlanması düzeltildi.
- **Telegram & CI otomasyonu**: `telegram-setup.yml` webhook `secret_token` eksikliği (`403 Unauthorized`), `bildirim.yml` `workflow_run` öncesi SHA tespiti, `worker.js` süreli/tek kullanımlık doğrulama imzası ile `verify.py`, `revive.py` ve `scaffold.py` araçları düzeltildi.

### Değişti
- **Ortak Ayarlar (`BaseSettings`)**: 85 modülde tekrarlanan ~7.600 satırlık `Settings.kt` UI kodu `Common` modülündeki `BaseSettings` sınıfında birleştirildi; `Inat*Plugin` sınıfları doğrudan kendi `Inat*Provider` sınıflarını kaydedecek şekilde güncellendi.

## [1.6.19] - 2026-11-07

### Eklendi
- **Nuvio +4 sağlayıcı** (24 → 28): film `FilmModu`, `JetFilmizle`; dizi `DiziMom`, `DiziPod`. Hepsi sıkı başlık/sezon-bölüm eşleşmesi kullanır, kanıtsız `[]` döner.

### Düzeltildi
- **Yabancidizi v8**: ölü `yabancidizi.live` yerine `yabancidizi.watch` (Next.js RSC, PBKDF2/AES, pichive `NP_CONFIG`); dizi ve film canlı doğrulandı.
- **DiziPod v6**: packed JS regex'i uzun yüklerde `StackOverflowError` veriyordu; yığın-güvenli hale getirildi.
- **HDFilmCehennemi v9 / Nuvio 1.0.11**: embed `.split("#")` ayracına geçti ve başa sahte `p9k` (ölü `playmix.uno`) çağrısı koydu; ayraç artık tek karakterli herhangi biri, `sources:[{file: değişken}]` ile bağlanan çağrı önceliklidir.
- **AsyaLuna v4 (pasif)**: sunucu silindi (`Index of /`), 0 mirror; `disabled` listesine alındı. Aktif modül sayısı 96.
- **Nuvio verify-all**: Node/undici TLS parmak izine 403 veren siteler (`pichive`, `dplayer82`, `fullhdfilmizlesene`) için `curl` yedeği; sonuçlar cihaz gerçeğini yansıtır.

## [1.6.18] - 2026-11-07

### Düzeltildi
- **Nuvio dil etiketi**: etiket artık kartta görünen `quality` alanına yazılıyor (`1080p • TR Dublaj`, `720p • Altyazılı`); önceden yalnız `title`'a ekleniyordu ve kartta hiç görünmüyordu. Yalnız altyazılı siteler (AnimeciX, AsyaAnimeleri, WebdramaTurkey2, Dizikorea3) `Altyazılı` taşır.
- **Nuvio sahte eşleşme**: 24 sağlayıcıda kör fallback (ilk arama sonucu, ilk bölüm, tek kelime çakışması) kaldırıldı; başlık ya da sezon/bölüm bulunamazsa `[]` döner. Türk dizisi aranınca anime/yabancı sitelerin alakasız akışları artık listelenmez.
- **SetFilmIzle / SezonlukDizi**: başlık eşleşmesi baştaki `The/A/An` makalesini yok sayar (Cinemeta `The Matrix` ↔ site `Matrix`).

## [1.6.17] - 2026-11-06

### Eklendi
- **FilmMakinesi v1**: `filmmakinesi.co` eklendi (film); oynatloload secure-hls zinciri runner'da doğrulandı (1080p/480p + Türkçe VTT).
- **WebteIzle v1**: `webteizle.info` eklendi (film); dil seçimli alternatifler (dublaj/altyazı) + vidmoly/ok.ru/filemoon yolları; Cloudflare sandığı nedeniyle pass kanıtı cihazda doğrulanacak.

## [1.6.16] - 2026-11-06

### Değişti
- **Nuvio dil etiketleri**: seçilmiş 22 ana sağlayıcının tümünde akış başlığının kalitesinin yanına sunucunun desteklediği dil (`Altyazılı` / `TR Dublaj`) yazılır; etiket kanıt yoksa yazılmaz.

## [1.6.15] - 2026-11-06

### Değişti
- **Nuvio Performans Optimizasyonu**: Nuvio istemcisindeki yükü azaltmak için sağlayıcı listesi seçilmiş **22 ana sağlayıcıya** (`Anizium`, `AnimeciX`, `AsyaAnimeleri`, `Dizibox`, `Dizilla`, `DiziPal`, `SelcukFlix`, `Ddizi`, `WebdramaTurkey2`, `Dizikorea3`, `Sezonlukdizi`, `DiziYou`, `DiziBal`, `İnatTV Dizi`, `HDFilmCehennemi`, `FullHDFilmizlesene`, `SetFilmIzle`, `YesilCamTv`, `İnatTV Filmler`, `BelgeselX`, `DMAX`, `TLCtr`) indirgendi; İnat dizi ve film katalogları `inattvdizi` ve `inattvfilmler` altında birleştirildi.

## [1.6.14] - 2026-11-06

### Düzeltildi
- **UltraFilmizle v6**: `hotstream.club` gömülü oynatıcısının yeni `id="be-player-config" data-config="..."` JSON yapılandırma biçimi (Cloudstream + Nuvio) eklendi.
- **ATV v4**: güncel (`/diziler`) ve klasik (`/klasik-diziler`) dizi katalogları, `/arama?query=` araması ve `/<slug>/<bölüm>-bolum/izle` doğrudan VOD MP4/HLS akış çözümü yenilenerek modül yeniden aktif edildi.
- **MinikaCocuk v8**: `/webtv` ve `/programlar` dizi/bölüm akışları doğrulanarak modül yeniden aktif edildi.

## [1.6.13] - 2026-11-05

### Değişti
- **DiziLife v8**: taban adres https://dizi83.life -> https://dizi84.life.

## [1.6.12] - 2026-11-05

### Düzeltildi
- **Animeler v6**: `videoapi.my.mail.ru` gömülü oynatıcı sayfalarından `/video/meta/<id>` kimliği çıkarılarak MailRu akış çözümü düzeltildi.

## [1.6.11] - 2026-11-05

### Değişti
- **HDFilmDelisi v3**: taban adres https://hdfilmdelisi.one -> https://hdfilmdelisi1.com.

## [1.6.10] - 2026-11-05

### Değişti
- **DDizimag v4**: taban adres https://ddizimag.com -> https://ddizimag.icu.

## [1.6.9] - 2026-11-05

### Düzeltildi
- **AsyaWatch v5**: pichive gömülü oynatıcısında `openPlayer` → `source2.php` akışı eklendi.
- **DiziCaN v7**: bölüm şablonundaki bozuk `<iframe<div` etiketi nedeniyle yutulan oynatıcı çerçeveleri ham HTML regex ile birleştirildi; sorgu parametresi olmayan HLS/MP4 kalıbı ve kök logo kartı filtresi düzeltildi.
- **YesilCamTv v5**: gömülü çerçeve yerine doğrudan Rumble izleme bağlantısı verilen sayfalarda oEmbed üzerinden HLS çözümü eklendi.
- **TurkceDizi v5**: bölüm bağlantılarındaki `?sezon=S&bolum=E` sorgu parametrelerinin ayrıştırılması ve ana sayfa logo kartı filtresi düzeltildi.
- **HDFilmCehennemi v8**: arama sorgusunda Türkçe karakterlerin URL kodlaması (`URLEncoder`) düzeltildi.

## [1.6.8] - 2026-11-04

### Düzeltildi
- **DiziPal v6**: yeni domain (dizipal1594) ve arama sonuçları sahte liste döndürdüğü için arama boşa alındı (site JS aramaya geçti).
- **TurkceDizi v4**: site araması JS tabanlı olup kazıma alakasız liste döndürüyordu; arama boşa alındı (ana sayfa ve oynatma çalışıyor).

- **Dizifon v4**: site araması sorguyu yok sayıp sabit liste döndürüyordu; eşleşme olmayan sorgularda artık boş sonuç dönüyor (cihazda oynatma doğrulandı).
- **UltraFilmizle v5**: arama temanın "son eklenenler" sızıntısından wp-json gerçek aramaya taşındı (cihazda Ponette 1080p oynatıldı).
- **DiziCaN v6**: bölüm listesi düzeltildi (site bölüm kalıpları okunamıyordu) ve ok.ru oynatıcısı özel çözücüyle cihazda oynatılır hale geldi (Snowdrop 720p).
- **YesilCamTv v4**: çok kelimeli aramada site boş dönüyordu; ilk kelimeyle tekrar deneme eklendi (cihazda Gelinlik Kızlar oynatıldı).

### Düzeltildi
- **DiziBoxTv v2, TurkceDizi v2, DiziCaN v3, Filmizyon v3, SonAnime v2**: denetimde yakalanan test/ölü `known` akış kalıntıları silindi; bölümler artık yalnız gerçek çıkarım zinciri dönüyor.
- **Dizibox v10**: poster istekleri tarayıcı UA ile gidiyor (`posterHeaders` geri geldi).
- **UA rulajı**: modül altyapısı ortak `browserHeaders` kaynağına taşındı; **Anizm v9, Ddizi v4, DiziBal v5, DiziBol v2, DramaDizilerim v6, FilmModu v6, HDFilmizle v6, JetFilmizle v6, LoveFilm v5, NetFilmizle v3, SetFilmIzle v6, ShowTV v2, Sinezy v3, Sinemakolik v2, SinemaCX v4, TLCtr v6, UltraFilmizle v3** güncel Chrome/130 UA'sına geçti; **Dizibaba v3, Dizigecesi v7, Dizilab v4, MinikaGO v5** haritaları da 130'a oturttu; **AsyaLog v7** isteklerine UA eklendi.
- **TurkceDizi v3**: oynatıcı zinciri Nuvio'dan Kotlin'e portlandı (iframeUrl → ters-base64 + OpenSSL AES → HLS master, varyant kaliteleri ve tr/en altyazılar).

- **DiziBoxTv v3**: bölüm sayacı "Bölüm/Sezon" etiketlerinden sentezleniyor; oynatıcı kimliği doğrudan HLS master'a çözülüyor (varyant kaliteler + altyazılar).
- **DiziCaN v4**: arama uç noktası siteye göre düzeltildi (`?s=`); çok kelimeli sorgularda tek-kelime ikinci deneme eklendi; Nuvio canlı PASS, runner links kanıtı dalgalı (vidmoly extractor sınıfı — cihaz kanıtı bekliyor).
- **SonAnime v3**: api.sonanime.com sezon/bölüm listesi ile gerçek seri yapısına geçti; 1080/720/480/360 MP4 kademeleri göreceli yollarla da çalışıyor.

### Düzeltildi
- **Dizifon v3**: arama ASCII-katlanmış sorguyla site sıralamasına göre; süresi geçmiş ok.ru sabit linki kaldırıldı; ok.ru kalite adları düzeltildi.
- **Diziboxizle v8**: arama `wp-json` AND-tuzaklamasına takılmıyor; site başlık filtresi (`/dizi-arsivi/?kelime=`) ve tekilleştirme eklendi; runner PASS 11 arama/6 link.
- **YesilCamTv v3**: sabit rumble linki kaldırıldı; rumble id listesi embed+sayfa metninden toplanıp kalite başına HLS üretiyor (2 link 200).
- **UltraFilmizle v4**:Hotstream master `Accept: */*` gerektiriyor (yoksa 404); link başlıklarına stream header seti eklendi, kalite tespiti 1080p düzeldi.
- **HDFilmDiziIzle v3**: playhd sayfasındaki XOR gizli master (`master.txt`) çözücü eklendi; vidrame origin referer ile 1080p HLS.
- **InatYerliFilmler v2**: MODE_SINGLE token artık headers/params taşıyor (8→10 alan, geriye uyumlu); Inat ortak motoruna tek-satır filmlerde doğru resolver bilgisi gidiyor.

### Eklendi
- **Nuvio sağlayıcı sürümleri**: manifest sürümleri artık CHANGELOG'daki `vN` numaralarından üretiliyor; kod cache'i davranış değişikliklerini yakalar.
- **Nuvio kataloğu**: addon listeleri yeniden üretildi, manifest 1.6.7 tabanına güncellendi.

## [1.6.7] - 2026-11-04

### Eklendi
- **Nuvio TMDB kimlikleri**: tüm sağlayıcılar Nuvio ana sayfasındaki sayısal TMDB kimliklerini IMDb'ye çeviriyor; kaynaklar bu kimliklerle de açılıyor.

### Düzeltildi
- **Anizm v8**: 15 saniyeyi aşan çevirmen/ayna taraması sınırlandırıldı.
- **AsyaLuna v3**: süresi dolmuş gömülü ok.ru adresi kaldırıldı; arama tabanı mainUrl'den okunuyor.
- **BirAsyaDizi v3**: 404 veren arama RSS ile değiştirildi; bölüm listesi düzeltildi.
- **DDizimag**: Nuvio'da aynı adlı film yerine dizi seçiliyor.
- **Dizikorea3 v6**: playerdkorea CDN'i tek kez yoklanıyor; 10 saniyelik gecikme kaldırıldı.
- **DiziPal v5**: alan adı dizipal1593.com; oynatıcı UA engeli ve TMDB kimliği düzeltildi.
- **FullHDFilmizlesene v4**: HLS master sesleri korunuyor; Türkçe/özgün ad eşleşmesi düzeltildi.
- **HDFilmizle v5**: TMDB kimliği ve çok parçalı film oynatıcısı düzeltildi.
- **Yabancidizi v6**: bölüm listesi ve TMDB kimliği düzeltildi.
- **Nuvio Inat**: liste çözümü hızlandırıldı; engellenen ağlarda hata artık günlüğe yazılıyor.

## [1.6.6] - 2026-11-03

### Düzeltildi
- **Nuvio CizgiSite**: VK oynatıcısı yeniden çalışıyor; çizgi film bölümleri açılıyor.
- **Nuvio MinikaGO**: çizgi film arama ve bölüm oynatma düzeltildi.
- **Nuvio MinikaCocuk**: etkinleştirildi; çizgi film bölümleri izlenebiliyor.
- **Nuvio ÇizgiMax**: çizgi film bölümleri ve Dzen/kaynak akışları düzeltildi.
- **Nuvio DiziLife**: yeni alan adı `dizi83.life` ile diziler yeniden açılıyor.
- **Nuvio Inat 4K 03 Web**: film çözümlemesi belirgin şekilde hızlandı.
- **Dizilla v10**: yeni alan adı dizilla.la; oynatıcı referer'ı sabitlendi.
- **MinikaGO v4**: ana sayfa çizgi film kartları ve arama düzeltildi.
- **DDizimag v3**: pilavyer oynatıcı akışı, film kartları ve çok sezonlu bölümler düzeltildi.
- **DiziLife v6**: yeni alan adı dizi83.life.

## [1.6.5] - 2026-11-03

### Düzeltildi
- **Dizilla v9**: boşluk içeren dizi adlarında arama `+` yerine `%20` kullanıyor; "Breaking Bad", "The Office" gibi diziler artık bulunuyor.

## [1.6.4] - 2026-11-03

### Düzeltildi
- **Nuvio ATV**: iki ardışık canlı testte MP4 döndürdüğü için etkinleştirildi.

## [1.6.3] - 2026-11-03

### Düzeltildi
- **Nuvio HDFilmCehennemi**: arama sonucu tam başlıkla eşleştiriliyor; kaçışlı oynatıcı adresi çözülüyor.
- **Nuvio MinikaGO**: arama ve bölüm isteklerine tarayıcı/XHR başlıkları eklendi; MP4 akışı dönüyor.
- **Nuvio doğrulayıcı**: sağlayıcı başına zaman aşımı ve boş sonuçta tek tekrar eklendi.
- **Aktif sağlayıcılar**: runner'da pass olan 58 modülün beta durumu kaldırıldı.

## [1.6.2] - 2026-11-02

### Düzeltildi
- **Kategoriler**: 28 aktif sağlayıcıya gerçek tür sayfaları eklendi (DiziAbi, DDizimag, Dizigom, DiziMom, Dizigecesi, Dizibaba, CanliDizi, BirAsyaDizi, HDFilmCehennemi, FilmModu, Cizgimax, JetFilmizle, UltraFilmizle, YesilCamTv, WebdramaTurkey2, DiziboxIzle, DiziYou, KoreanTurk, Sezonlukdizi, Filmizyon, Dizirella, Animeler, Anizm, Dizilab, DiziCaN, AsyaDizi, Asyadizileriizle, Sinezy).
- **DiziPal v4**: arama Next.js `/arama-yap?q=` akışına geçirildi; bölüm oynatıcısı doğrulandı.
- **HDFilmizle v4**: yeni `div.playex` oynatıcı seçicisi; 1080p HLS + Türkçe/İngilizce altyazı.
- **CizgiSite v6**: VK embed yanıtındaki kaçışlı URL'ler çözülüyor; MP4 kaliteleri (144p–1080p) dönüyor; VK reddinde başlıksız yedek istek deniyor.
- **KoreanTurk v5**: yeni `a.dz-card` kartları ve tür sayfaları.
- **MinikaGO v3**: bölüm URL'sindeki eksik `/` düzeltildi; tarayıcı ve XHR başlıkları eklendi.
- **MinikaCocuk v4**: Programlar kategorisi ve tarayıcı/XHR başlıkları eklendi.
- **MinikaCocuk pasife alındı**: VOD bölüm endpoint'i timeout verdiği için modül `disabled` listesine alındı; kaynak korunuyor.
- **ATV pasife alındı**: `/izle` VOD endpoint'i timeout verdiği için modül `disabled` listesine alındı; kaynak korunuyor.
- **AsyaWatch v4**: Next.js API'si yerine `/ara` ve `/dizi-izle` HTML akışı; JSON-LD bölümleri ve pichive HLS master'ı kullanılıyor.
- **Runner**: `/run-all` eşzamanlılık çökmesi giderildi; `/main?row=` ile kategori doğrulaması eklendi.

## [1.6.1] - 2026-11-02

### Düzeltildi
- **DMAX v2**: arama sayfası ve bölüm listesi güncellendi.
- **HDFilmCehennemi2 v2**: arama `/api/search` üzerinden yapılıyor; film ve dizi sonuçları ayrılıyor.
- **AniArsiv v3**: bölüm başına 52 kaynak yerine ilk 12 kaynak deneniyor; runner zaman aşımı giderildi.
- **CizgiSite v3/v4**: Brotli sıkıştırılmış sayfalar çözülüyor; çözüm başarısız olursa sessiz boş sonuç yerine gerçek hata gösteriliyor.
- **Inat**: runner’da pass olmayan 24 platform `settings.gradle.kts` pasif listesine alındı; kaynaklar korunuyor.
- **Nuvio**: plugin deposu ve katalog addon'u aktif; 91/102 sağlayıcı açık, “Tümü - Filmler” ve “Tümü - Diziler” satırları yayında. Meta için Cinemeta şartı README’ye eklendi.
- **SelcukSports kaldırıldı**: P2P/WebRTC oynatıcı doğrudan HLS vermediği için sağlayıcı kaynağı repodan çıkarıldı; ileride relay kanıtı olursa yeniden eklenebilir.
- **CanliYayinKanallari v4**: HTTP 403/404 veren 7 ölü URL listeden çıkarıldı; **46 HLS adresi HTTP 200** ile doğrulandı (oynatma ayrıca test edilmedi).
- Telegram: yalnız sağlayıcı değişikliklerinde tek bildirim gönderilir; katalog/docs commit'leri sessiz kalır.

## [1.6.0] - 2026-11-01

### Eklendi
- **Nuvio desteği**: `nuvio/` altında eklenti deposu — 102 sağlayıcı, sağlayıcı başına tek dosya,
  cihazda çalışır. 91'i varsayılan açık; 11'i Cloudflare/İnat CDN engeli nedeniyle kapalı gelir.
- Yalnız canlı kanal yayınlayan modüller ve iki ölü site (CanliDizi, Dizigecesi) Nuvio kapsamı
  dışında: eklenti başlık (IMDb) → akış modelinde çalışır, canlı kanal araması yoktur.
- **Nuvio katalog addon'u**: ana sayfaya sağlayıcı satırları (79 katalog) — statik Stremio
  addon'u, sunucu gerekmez, günde bir yenilenir.

## [1.5.29] - 2026-11-01

### Düzeltildi
- **CizgiSite v2**: bölüm sayfası engel/boş yanıtında retry ve ilk bölüm fallback'i eklendi.

## [1.5.28] - 2026-11-01

### Değişti
- **InatBox**: 35 platform ayrı modüle bölündü; her platform kendi sağlayıcısı ve kategorisiyle yayınlanıyor.

### Düzeltildi
- Inat 4K platformlarında kalite etiketi 2160p olarak düzeltildi.

## [1.5.27] - 2026-11-01

### Düzeltildi
- **Dizigecesi v5**: Filemoon attestasyonu ve base64url imzası düzeltildi.

## [1.5.26] - 2026-11-01

### Kaldırıldı
- **Filmizleya** kaldırıldı; domain askıya alınmış durumda.

## [1.5.25] - 2026-11-01

### Düzeltildi
- **Animeler v4**: Mail.ru oynatıcı kaynakları düzeltildi.

## [1.5.24] - 2026-11-01

### Düzeltildi
- **AnimeciX v3**: oynatıcı zaman aşımı ve TAU kaynakları doğrulandı.

## [1.5.23] - 2026-11-01

### Düzeltildi
- **Animeler v3**: oynatıcı denemeleri sınırlandırıldı.

## [1.5.22] - 2026-11-01

### Düzeltildi
- **AniArsiv v2**: TAU Video kaynakları ve kısa önizleme filtresi düzeltildi.

## [1.5.21] - 2026-11-01

### Düzeltildi
- **KanalD v2** ve **NetFilmizle v2**: oynatıcı kaynakları doğrulandı.

## [1.5.20] - 2026-11-01

### Düzeltildi
- **Dizilab v2**: oynatıcı kaynağı doğrulandı.

## [1.5.19] - 2026-11-01

### Düzeltildi
- **InatBox v7**: 4K tek içerikleri ve Web/Exo yedekleri düzeltildi.

## [1.5.18] - 2026-11-01

### Eklendi
- **InatBox v6**: 4K Web/Exo yedek kaynakları eklendi.

### Düzeltildi
- **DramaDizilerim v5**: yeni arama ve oynatıcı API'si düzeltildi.
- **HDFilmDiziIzle v2**: arama ve gerçek oynatıcı kaynağı düzeltildi.
- **Dizigecesi v4** ve **Dizilab v2**: oynatıcı yedek çözümü eklendi.

## [1.5.17] - 2026-11-01

### Eklendi
- **InatBox** eklendi: 35 alt sağlayıcı (Liste 1/2/3, Spor, Derbiler, Sinema, Belgesel ve diğerleri).

### Düzeltildi
- 4K Web hedefi ve 4K host fallback düzeltildi.

## [1.5.16] - 2026-10-31

### Düzeltildi
- **Dizigecesi v3**: Filemoon kaynağı yayınlanmadan önce `Settings.qualityAllowed()` kontrolü yapılıyor; kalite üst sınırı artık uygulanıyor.

## [1.5.15] - 2026-10-31

### Eklendi
- **Dizigecesi v2**: `dizigecesi.com` eklendi; `/ajax/embed` → Filemoon `embed/details` → PoW captcha → AES-256-GCM playback → HLS master. Cihazda oynatma doğrulandı.

## [1.5.14] - 2026-10-31

### Eklendi
- **Dizibaba v1**: `dizibaba.net` eklendi; Pilavyer `__PLAYER__.stream` HLS master.

## [1.5.13] - 2026-10-31

### Eklendi
- **Dizilab v1**: `dizilab.cc` eklendi; bölüm iframe'i doğrudan HLS master veriyor.

## [1.5.12] - 2026-10-31

### Eklendi
- **Sinezy v1**: `sinezy.si` eklendi; FirePlayer `securedLink` HLS ve altyazı.

## [1.5.11] - 2026-10-31

### Eklendi
- **CizgiSite v1**: `www.cizgi.site` eklendi.

## [1.5.10] - 2026-10-31

### Değişti
- **MinikaCocuk v2**: boş dönen "Canlı Yayın" ana sayfa satırı kaldırıldı.

## [1.5.9] - 2026-10-31

### Eklendi
- **MinikaCocuk v1**: `minikacocuk.com.tr` eklendi.

## [1.5.8] - 2026-10-31

### Eklendi
- **HDFilmCehennemi2 v1**: `hdfilmcehennemi2.biz` eklendi.

## [1.5.7] - 2026-10-31

### Eklendi
- **HDFilmizleBest v1**: `hdfilmizle.best` eklendi.

## [1.5.6] - 2026-10-31

### Eklendi
- **Deokwave v1**: `deokwave.com` eklendi.

## [1.5.5] - 2026-10-31

### Değişti
- **DiziFilmIzle v2**: gerçek `/api/search` uç noktası ve tüm bölümlere ait vidmixi/dosyaload `bePlayer` Çözümleyicisi kullanılıyor.

## [1.5.4] - 2026-10-31

### Eklendi
- **DiziFilmIzle v1**: `dizifilmizle.to` eklendi.

## [1.5.3] - 2026-10-31

### Eklendi
- **ATV v1**: `atv.com.tr` eklendi.

## [1.5.2] - 2026-10-31

### Eklendi
- **DMAX v1**: `dmax.com.tr` eklendi.

- **TV8 v1**: `tv8.com.tr` eklendi.

- **KanalD v1**: `kanald.com.tr` eklendi.

- **ShowTV v1**: `showtv.com.tr` eklendi.

- **UltraFilmizle v1**: `ufilmizle.com` eklendi.

- **NetFilmizle v1**: `netfilmizle.com` eklendi.

- **Sinemakolik v1**: `sinemakolik.com` eklendi.

- **DiziBol v1**: `dizibol.org` eklendi.

## [1.5.1] - 2026-10-30

### Eklendi

- **AniArsiv v1**: `aniarsiv.com` eklendi.

- **MinikaGO v1**: `minikago.com.tr` çocuk VOD'u eklendi; bölüm sayfasındaki doğrudan MP4
  kaynakları kullanılıyor. Runner: arama 1, bağlantı 1.
- **StarTV v2**: arama yalnızca gerçek `/dizi/` sayfası olan sonuçları döndürür; `Star Haber`
  gibi haber kayıtları elenir. HLS ve bölüm akışı doğrulandı: arama 1, bağlantı 2.





- **HDFilmDiziIzle v1**: `hdfilmdiziizle.com` sıfırdan eklendi; film; runner doğrulaması: arama 1, bağlantı 1. Bot (autopilot) tarafından üretildi.

- **Dizigom v1**: `dizigom.news` sıfırdan eklendi; film; runner doğrulaması: arama 1, bağlantı 1. Bot (autopilot) tarafından üretildi.

- **DiziAbi v1**: `diziabi.net` sıfırdan eklendi; film; runner doğrulaması: arama 10, bağlantı 1. Bot (autopilot) tarafından üretildi.

- **Filmizyon v1**: `filmizyon.net` sıfırdan eklendi; film; runner doğrulaması: arama 2, bağlantı 4. Bot (autopilot) tarafından üretildi.

- **BirAsyaDizi v1**: `birasyadizi.cx` sıfırdan eklendi; dizi; runner doğrulaması: arama 1, bağlantı 2. Bot (autopilot) tarafından üretildi.

- **AsyaDizi v1**: `asyadizi.com` sıfırdan eklendi; dizi; runner doğrulaması: arama 20, bağlantı 1. Bot (autopilot) tarafından üretildi.

- **Dizifon v1**: `dizifon.cc` sıfırdan eklendi; dizi; runner doğrulaması: arama 21, bağlantı 1. Bot (autopilot) tarafından üretildi.

- **Weboo v1**: `weboo.com.tr` sıfırdan eklendi; dizi; runner doğrulaması: arama 2, bağlantı 1. Bot (autopilot) tarafından üretildi.

- **Filmizleya v1**: `filmizleya.com` sıfırdan eklendi; film; runner doğrulaması: arama 1, bağlantı 1. Bot (autopilot) tarafından üretildi.

- **CanliYayinKanallari v1**: `tabii.com` sıfırdan eklendi; canlı TV; runner doğrulaması: arama 24, bağlantı 1. Bot (autopilot) tarafından üretildi.

- **CanliDizi v1**: `canlidizi.sbs` sıfırdan eklendi; dizi; runner doğrulaması: arama 1, bağlantı 1. Bot (autopilot) tarafından üretildi.











- **Asyadizileriizle v1**: `asyadizileriizle.com` sıfırdan eklendi; dizi; runner doğrulaması: arama 1, bağlantı 7. Bot (autopilot) tarafından üretildi.

- **TurkceDizi v1**: `turkcedizi.com` sıfırdan eklendi; film; runner doğrulaması: arama 12, bağlantı 4. Bot (autopilot) tarafından üretildi.

- **DDizimag v1**: `ddizimag.com` sıfırdan eklendi; dizi; runner doğrulaması: arama 1, bağlantı 1. Bot (autopilot) tarafından üretildi.

- **DiziBoxTv v1**: `diziboxtv.com` sıfırdan eklendi; dizi; runner doğrulaması: arama 2, bağlantı 5. Bot (autopilot) tarafından üretildi.

- **AsyaBox v1**: `asyabox.co` sıfırdan eklendi; dizi; runner doğrulaması: arama 21, bağlantı 1. Bot (autopilot) tarafından üretildi.

- **Dizirella v1**: `dizirella.one` sıfırdan eklendi; dizi; runner doğrulaması: arama 20, bağlantı 1. Bot (autopilot) tarafından üretildi.

- **DiziCaN v1**: `dizican.cc` sıfırdan eklendi; dizi; runner doğrulaması: arama 12, bağlantı 2. Bot (autopilot) tarafından üretildi.

- **AsyaLuna v1**: `asyaluna.com` sıfırdan eklendi; dizi; runner doğrulaması: arama 10, bağlantı 1. Bot (autopilot) tarafından üretildi.

- **SonAnime v1**: `sonanime.com` sıfırdan eklendi; dizi; runner doğrulaması: arama 2, bağlantı 2. Bot (autopilot) tarafından üretildi.

- **YesilCamTv v1**: `yesilcamtv.com.tr` sıfırdan eklendi; film; runner doğrulaması: arama 1, bağlantı 1. Bot (autopilot) tarafından üretildi.

- **HDFilmDelisi v1**: `hdfilmdelisi.one` sıfırdan eklendi; film; runner doğrulaması: arama 4, bağlantı 1. Bot (autopilot) tarafından üretildi.

### Değişti
- **Dizifon v2**: taban adres https://dizifon.cc -> https://www.dizifon.cc; runner doğrulaması: arama 21, bağlantı 2. Bot (autopilot) tarafından üretildi.
- **AsyaAnimeleri v9**: site menüsündeki 49 tür satırı (`/genres/<slug>/`) ana sayfa ve
  ayarlara eklendi; "Son Eklenenler" korunur.
- **CanliYayinKanallari v3**: TV100 denemesi runner'da `extractor-unsupported` verdiği için
  listeden çıkarıldı; doğrulanmış HLS kanalları korunuyor.
- **CanliTVMe kaldırıldı**: eski HLS oynatıcısı Tabii yönlendirmesine döndü; runner'da
  doğrulanamadığı için sağlayıcı listeden çıkarıldı.

- **Asyadizileriizle v2**: taban adres https://asyadizileriizle.com -> https://asyadizileriizle.com; runner doğrulaması: arama 1, bağlantı 7. Bot (autopilot) tarafından üretildi.

- **AsyaLuna v2**: taban adres https://asyaluna.com -> https://asyaluna.com; runner doğrulaması: arama 10, bağlantı 2. Bot (autopilot) tarafından üretildi.

- **AsyaBox v2**: taban adres https://asyabox.co -> https://asyabox.co; runner doğrulaması: arama 21, bağlantı 1. Bot (autopilot) tarafından üretildi.

- **CanliTV v3**: M3U kanal URL'si doğrudan `loadLinks` ile HLS olarak verilir; eski
  `loadLinks returned false` hatası giderildi.
- **HDFilmDelisi v2**: site kategorilerinin tamamı (22 satır) ana sayfa seçicisine eklendi;
  runner doğrulaması: arama 4, bağlantı 1.

- **AnimeciX v3**: sürüm ve runner/kategori doğrulaması güncellendi.
- **AsyaLog v6**: sürüm ve runner/kategori doğrulaması güncellendi.
- **AsyaWatch v3**: sürüm ve runner/kategori doğrulaması güncellendi.
- **DiziBal v4**: sürüm ve runner/kategori doğrulaması güncellendi.
- **DiziLife v5**: sürüm ve runner/kategori doğrulaması güncellendi.
- **DiziYou v6**: sürüm ve runner/kategori doğrulaması güncellendi.
- **Dizikorea3 v5**: sürüm ve runner/kategori doğrulaması güncellendi.
- **Dizilla v8**: sürüm ve runner/kategori doğrulaması güncellendi.
- **DramaDizilerim v4**: sürüm ve runner/kategori doğrulaması güncellendi.
- **FilmEkseni v4**: sürüm ve runner/kategori doğrulaması güncellendi.
- **FilmModu v4**: sürüm ve runner/kategori doğrulaması güncellendi.
- **FullHDFilmizlesene v3**: sürüm ve runner/kategori doğrulaması güncellendi.
- **HDFilmizle v3**: sürüm ve runner/kategori doğrulaması güncellendi.
- **KoreanTurk v4**: sürüm ve runner/kategori doğrulaması güncellendi.
- **Kultfilmler v6**: sürüm ve runner/kategori doğrulaması güncellendi.
- **LoveFilm v3**: sürüm ve runner/kategori doğrulaması güncellendi.
- **SelcukFlix v3**: sürüm ve runner/kategori doğrulaması güncellendi.
- **SetFilmIzle v5**: sürüm ve runner/kategori doğrulaması güncellendi.
- **Sezonlukdizi v6**: sürüm ve runner/kategori doğrulaması güncellendi.
- **SinemaCX v3**: sürüm ve runner/kategori doğrulaması güncellendi.
- **TLCtr v4**: sürüm ve runner/kategori doğrulaması güncellendi.
- **WebdramaTurkey2 v4**: sürüm ve runner/kategori doğrulaması güncellendi.
- **Yabancidizi v5**: sürüm ve runner/kategori doğrulaması güncellendi.

## [1.5.0] - 2026-10-29

### Eklendi
- **Animeler v1**: `animeler.live` sıfırdan eklendi; `/filtre?search=` arama, bölüm listesi ve
  ok.ru `videoPlayerMetadata` ile 4 HLS/MP4 linki. Bot keşif zincirinden üretildi.
- **Animeler v2**: bölüm sayfasında JS ile enjekte edilen ok.ru iframe'i için ham HTML'deki
  `videoembed` kimliği doğrudan çözülür; eski oynatıcı 0 link hatası giderildi.

- Altı yeni beta sağlayıcı (host zinciri canlı doğrulandı; cihaz testi diğer ajanda):
  **AsyaWatch**, **DiziBal**, **DramaDizilerim**, **FilmEkseni**, **HDFilmizle**, **LoveFilm**.
- **DramaDizilerim**: `/search?q=` + `/izle/...?s=&e=` → `embed.php` → HLS/mp4; Türkçe WebVTT.
- **DiziBal**: `/ara?q=` arama, LD+JSON künye, `s.php` oynatıcı (Referer zorunlu) → master HLS + TR/EN VTT.
  Filmler (`/movie/<slug>`, `/filmler?page=N`) da destekli: Movie künyesi + aynı oynatıcı; kaynağı
  olmayan filmlerde "kaynak hazırlanıyor" hatası.
- **AsyaWatch**: `searchContent` API + NP/legacy oynatıcı; Kore/Asya web dramaları.
- **FilmEkseni**: `/api/search` + player JSON → m3u8.
- **HDFilmizle**: film + dizi; mobil UA ile oynatıcı zinciri.
- **LoveFilm**: film arşivi; çok kaynaklı oynatıcı döngüsü (vidmoly vb.).

### Kaldırıldı

- TR olmayan 21 sağlayıcı deposu kaldırıldı (içerik dili de yabancı doğrulandı):
  AllCalidad, AnimeIndo, AnimeWorld, AnimeYTX, AreaDocumental, Cinecalidad, CineHdPlus,
  DoramasYT, Flixlatam, Hdfilme, HiAnime, JKAnime, LaMovie, Latanime, Monoschinos,
  MoviesDrive, Serienstream, Supercartoons, TioAnime, UHDmovies, UltraCine.

### Değişti

- **QA runner**: `loadLinks` çağrıları 90 sn timeout ve tek kullanımlık thread havuzuyla
  izole edildi; takılan bir extractor sonraki modüllerin link isteklerini kilitlemiyor.
- **Dizilla** README kaynağı `dizilla.now` olarak düzeltildi (modül zaten bu adresi kullanıyordu).
- **AsyaAnimeleri v4**: Rumble embed isteğine tarayıcı UA ve site Referer'ı eklendi; Rumble
  mirror'ı yeniden HLS üretiyor (`playlist.m3u8`).
- **Ddizi v2**: oynatıcıdaki `sources` dizisi artık tam regex ile okunuyor; göreli
  `redirector.googlevideo.com` MP4 kaynağı yeniden çıkarılıyor.
- **Anizm v2**: fragman girdileri bölüm listesinden çıkarıldı; çevirmen API çağrıları
  paralelleştirildi, böylece tek yavaş çevirmen tüm linkleri sırayla bekletmiyor.
- **Cizgimax v6**: `tau-video` SPA'sı yerine `tau-video.xyz/api/video/<id>` doğrudan okunuyor;
  göreli `resolveUrl` mutlak adrese çevrildi, 1080p/720p/480p MP4 kaynakları dönüyor.
- **Anizium v5**: `api.anizium.co/anime/source` isteğine tarayıcı User-Agent'ı eklendi;
  kaynak API'si yeniden `success=true` dönüyor, 480/720/1080 MP4 linkleri çıkıyor.
- **AsyaAnimeleri v6**: Rumble UA/Referer; ok.ru `videoPlayerMetadata` JSON `ondemandHls`;
  GDrive `drive.usercontent.google.com/download?...confirm=t` doğrudan MP4; streamtape
  `#ideoolink` yalnız gerçek medya dönerse yayınlanıyor.
- **Ddizi v2**: göreli `/player/oynat/...` iframe adresi mutlak URL'ye çevrildi; runner'ın
  thread havuzunda `fixUrl` tabanı taşınarak link üretimi düzeltildi.
- **Anizm v4**: `/player/{id}` yönlendirmesi `Location` yoksa yanıtın son URL'sinden okunuyor;
  ayna sayısı çevirmen başına ilk 10 ve toplam ilk 4 link ile sınırlı, `/links` ~26 sn'de 4 HLS.
- **JetFilmizle v2**: videopark/jetembed kaynakları için moviesapi/vidora üzerinden doğrudan
  HLS akışı ve VTT altyazıları eklendi.
- **DiziMom v5 / HDFilmCehennemi v4**: `hasMainPage = true`; iki sağlayıcı da ana sayfa
  seçiminde görünür ve site kartlarından gerçek ana sayfa satırı üretir.
- **Kategori ayarları (27 modül)**: AnimeciX v2, Anizm v5, AsyaAnimeleri v7, AsyaWatch v2,
+  BelgeselX v3, Ddizi v3, DiziBal v2, DiziMom v6, DiziPal v3, DiziPod v3, DramaDizilerim v2,
+  FilmEkseni v2, FilmModu v2, FullHDFilm v2, FullHDFilmizlesene v2, HDFilmCehennemi v5,
+  HDFilmizle v2, JetFilmizle v3, KoreanTurk v2, LoveFilm v2, SelcukFlix v2, SetFilmIzle v3,
+  SinemaCX v2, TLCtr v3, WebdramaTurkey2 v3, CanliTV v2, CanliTVMe v3: eklenti ayarlarına
+  ana sayfa/kanal kategori satırı aç/kapat anahtarları eklendi; kalite/altyazı seçimleri korundu.
+- **FullHDFilm v4**: ana sayfa kart seçicisi `group/poster` sınıfına göre düzeltildi; 24 kart
+  gerçek başlık ve posterle geliyor.
- **DiziboxIzle v6**: ok.ru oynatıcısı HTML kazıma yerine `videoPlayerMetadata` JSON'undan
+  `videos` dizisiyle okunuyor; copyright'lu bölümlerde dürüst hata, çalışan bölümlerde 6 link.
- **Poster düzeltmesi (21 modül)**: Anizm, AsyaAnimeleri, AsyaLog, BelgeselX, Cizgimax,
+  DiziBal, DiziMom, DiziPod, DiziYou, Dizibox, DiziboxIzle, Dizilla, DramaDizilerim,
+  FilmEkseni, FilmModu, FullHDFilm, HDFilmCehennemi, JetFilmizle, KoreanTurk, Kultfilmler,
+  SetFilmIzle: kart posterleri `data-src` lazy kaynağından okunuyor; placeholder azaltıldı.
- **Dizibox v8**: ana sayfa bölüm kartlarında lazy `data-src` posterleri kullanılıyor; placeholder
+  yerine gerçek afiş yükleniyor.
+- **FullHDFilm v2**: `/filmler/` ana sayfası, puan sıralaması ve tür satırları eklendi.
- **Onepacetr v5**: Vidguard/gettsu (site kendi gizliyor, alan adları 522/park) atlanıyor;
  Google Drive player'ı `drive.usercontent.google.com` üzerinden doğrudan MP4 olarak veriliyor.
- **CanliTVMe v2**: `canlitv.me` BTK/SNI engeli nedeniyle aynı yayıncının erişilebilir aynası
  `blog.canlitvizle.tv` üzerine taşındı; `yayinim.php` oynatıcısı ve kanal sekmeleri destekli.

Bu depodaki önemli değişiklikler burada tutulur.
Biçim: [Keep a Changelog](https://keepachangelog.com/tr/1.1.0/). Depo sürümleri ayrıdır; her eklenti
`providers/<Modül>/build.gradle.kts` içinde kendi `version` değerini taşır ve Cloudstream ancak bu
sayı arttığında güncelleme sunar.

## [1.4.1] - 2026-10-29

### Değişti

- **DiziPal v2, DiziPod v2, SetFilmIzle v2, TLCtr v2, WebdramaTurkey2 v2**: `status = 1`
  (doğrulanmış). Beş sağlayıcıda da emülatörde arama → detay → bölüm → oynatma zinciri
  `Rendered first frame` + `state=PLAYING` ile kanıtlandı.

## [1.4.0] - 2026-10-29

### Eklendi

- Yeni beta sağlayıcılar (host zinciri canlı doğrulandı; cihaz testi yok): **DiziPal**,
  **DiziPod**, **SetFilmIzle**, **TLCtr**, **WebdramaTurkey2**.
- **DiziPal**: AES ile şifrelenmiş bölüm verisi çözülüyor; HLS master + altyazı (WebVTT).
- **DiziPod**: `admin-ajax` oynatıcısı Dean Edwards packer çözülerek m3u8'e iniliyor.
- **SetFilmIzle**: iki katmanlı `SPG.cerceve`/`STF_KOPRU` köprüsü; HLS master + TR/EN VTT.
- **TLCtr**: `POST /ajax/more` program/bölüm akışı + `dygvideo` yönlendirmesi; kalite etiketli HLS.
- **WebdramaTurkey2**: `POST /ajax/embed` → FirePlayer `securedLink` akışı; tek kaynakta duran
  hız sınırı koruması.

### Düzeltildi

- **DiziPod v1**: arama kartları `<li>` kapsamından okunuyor — kart içindeki tür bağlantıları
  jsoup'ta iç içe `<a>` üretip dış bağlantıyı boşalttığı için arama 0 sonuç dönüyordu.

## [1.3.0] - 2026-10-29

### Eklendi

- Doğrulanmış yeni sağlayıcılar: **AllCalidad**, **DiziMom**, **HDFilmCehennemi**,
  **Monoschinos**, **MoviesDrive** (beta; tek içerik oynatma doğrulaması).
- `tools/qa-ledger.json`: modül bazlı test durumu ve kanıt kaydı.

## [1.2.4] - 2026-10-29

### Düzeltildi

- **MoviesDrive v2**: arama sunucusu new1.moviesdrive.cyou'ye taşındı.
- **HDFilmCehennemi v2**: arama JSON'u org.json ile çözülüyor; yeni kart markup'u.
- **AreaDocumental v2**: PHPSESSID yoksa ana sayfa ile cookie tazeleniyor, yoksa açık hata.

## [1.2.3] - 2026-10-29

### Düzeltildi

- **HDFilmCehennemi v3**: dinamik `juq8/ev5u` JS decoder'ı çalışma anında sabitlerini okuyup Kotlin'de çözüyor; c6rbn fallback korundu.
- **Diziasia v5**: arama kartlarında başlık `img[alt]` üzerinden okunuyor (yeni kart yapısı).
- **DiziMom v2**: oynatıcı hostu `pilavyerplay.top` olarak güncellendi.
- **BelgeselX v2**: yeni `/belgeseldizi/<slug>` detay sayfalarında bölüm satırı yoksa ilk
  `a.px-ep-card` bölüm sayfasına geçilip bölümler oradan okunuyor; başlık `h1.px-hero-title`
  fallback'i eklendi.

## [1.2.2] - 2026-10-29

### Düzeltildi

- **DiziBox v7**: v6'daki gereksiz yeniden deneme geri alındı; bölüm listesi tek istekten okunuyor.
- **Doğrulama aracı**: DiziBox verifier'ı gerçek `/search/` akışını kullanıyor; 429 yanıtları 5 sn
  bekleyip yeniden deneniyor. Böylece DiziBox ve AsyaAnimeleri yanlış FAIL vermiyor.

## [1.2.1] - 2026-10-29

### Eklendi

- Kalan 8 modülde gerçek kategori satırları ve satır başına aç/kapat anahtarları:
  **Dizibox** (Dram/Komedi/Korku), **DiziboxIzle** (Aksiyon/Dram/IMDb 7+),
  **Dizikorea3** (Çin/Japon/Tayland), **Dizilla** (Tüm Diziler/Anime/Türkçe Dublaj),
  **Kultfilmler** (Korku/Bilim Kurgu/Aksiyon), **Sezonlukdizi** (Bilim Kurgu/Korku/Aksiyon),
  **Yabancidizi** (Aksiyon/Bilim Kurgu/Korku), **Onepacetr** (yalnız switch; gerçek kategori yok).

## [1.2.0] - 2026-10-29

### Düzeltildi

- **AsyaLog**: bölünmüş bölümlerin (`#f_part`) tüm part sayfaları okunuyor; her partın kendi videosu çıkarılıyor.
- **Cizgimax**: Mail.ru `qualitiesUrl` renditions (1080p/360p) ayrı ayrı veriliyor; dublaj/altyazı sunucuları etiketlendi.
- **DiziLife**: master playlist'teki Türkçe/İngilizce altyazı renditions `SubtitleFile` olarak ekleniyor.
- **Dizibox**: `moly.php`/`haydi.php` sarmalayıcıları çözülüp VidMoly/ok.ru kaynakları çıkarılıyor (önceden sessizce ölüyordu).
- **Dizilla**: dublaj/altyazı aynı iframe'i paylaşsa da etiketli tek link; tüm altyazılar dedupe.
- **Kultfilmler**: `#kf-srcdata` içindeki altyazılı + Türkçe dublaj aynaları ayrı ayrı çıkarılıyor.
- **Sezonlukdizi**: altyazı ve dublaj varyantları birlikte deneniyor; Filemoon `tracks` altyazıları eklendi.
- **Yabancidizi**: HLS master'daki 720p/1080p varyantları ve TR/EN altyazıları ayrı link olarak veriliyor.
- **Dizikorea3**: `playerdkorea` `getVideo` zinciri yalnız playlist `#EXTM3U` dönerse ekleniyor.
- Tüm modüllerde link/altyazı URL dedupe; tek master HLS linkleri `Qualities.Unknown` (kalite cap'i linki silmesin).

### Eklendi

- Ana sayfa kategori satırları: AsyaLog (Aksiyon/Çin/Devam Eden), Cizgimax (Anime/Popüler),
  DiziLife (Aksiyon/Romantik/Bilim Kurgu/Komedi), DiziYou (Aksiyon/Dram), Diziasia (Çin/Japon/Tayland).
- Eklenti ayarlarına satır başına aç/kapat anahtarları (Anizium, AsyaLog, Cizgimax, DiziLife, DiziYou, Diziasia).

## [1.1.0] - 2026-10-29

### Eklendi

- **Tüm sağlayıcılarda eklenti ayarları** (`Plugin.openSettings`): kalite üst sınırı
  (Sınırsız/1080p/720p/480p/360p) ve altyazı dili (Tümü/Türkçe/İngilizce/Kapalı).
  Ayarlar `bigbull_<modül>` prefs dosyasında saklanır; `loadLinks` çıkışı ve altyazı
  callback'i bu tercihlere göre filtrelenir. Canlı TV modülleri kapsam dışı.

## [1.0.1] - 2026-10-28

### Düzeltildi

- **Dizibox** ve **Cizgimax**: istekler tarayıcı `User-Agent` başlığıyla gönderiliyor; siteler
  varsayılan OkHttp UA'sını 403 ile engelliyordu (ana sayfa boş/eksik, arama boş, detay açılmıyor).
  Modül sürümleri `3`.

## [1.0.0] - 2026-10-28

### Eklendi

- Deponun ilk sürümü: her site için bir modül olmak üzere 15 Cloudstream eklentisi.
- **Dizibox** (`dizibox.live`): `/search/<sorgu>/` araması, dizi/bölüm ayrıştırma, `CryptoJS` ile
  molystream AES çözümü, 1080p HLS.
- **DiziYou** (`diziyou.one`): arama, `.single-item` ana sayfa kartları, bölümler,
  `storage.diziyou.one` HLS master ve Türkçe/İngilizce WebVTT altyazılar.
- **AsyaLog** (`asyalog.co`): Asya dizi/film kataloğu, yerel HTML unescape ile Odnoklassniki
  gömmeleri.
- **AsyaAnimeleri** (`asyaanimeleri.top`): arama, dizi/bölümler, mirror listesi, çekirdek
  çıkarıcıyla VidMoly ve özel Rumble HLS çıkarıcısı.
- **Kultfilmler** (`kultfilmler.net`): arama, film sayfası, vidpapi packer'ın
  `POST /player/index.php?do=getVideo` çağrısına çözülmesi ve token'lı HLS `securedLink`.
- **Anizium** (`anizium.co`): üretilen `Cf-Control` başlığı, JSON API, MP4/HLS + WebVTT.
- **Diziasia** (`diziasia.com`): AJAX arama, ok.ru / VK / VidMoly oynatıcıları.
- **Dizilla** (`dizilla.nl`): Next.js RSC kaynakları, pichive `nm.php` HLS + altyazılar.
- **Onepacetr** (`onepacetr.net`): açık Strapi API, mixdrop / sibnet.
- **Dizikorea3** (`dizikorea3.com`): `?s=` araması, vidmoly.org çıkarıcısı.
- **Cizgimax** (`cizgimax.online`): base64 sunucu blob'u, sitenin HLS proxy'si.
- **Yabancidizi** (`yabancidizi.live`): özel oynatıcı tersine mühendislikle çözüldü (reverse +
  base64, EVP_BytesToKey/MD5 ile CryptoJS AES), TR/EN sesli HLS master.
- **DiziLife** (`dizi82.life`): Laravel site, ortak oynatıcı sunucusu, aynı AES düzeni; dizi ve
  bölümler LD+JSON ile sezon akordeonundan.
- **DiziboxIzle** (`diziboxizle.com`): WordPress REST araması, bölüm → dizi katlama, aynı unescape
  işlemini gerektiren ok.ru oynatıcısı.
- **Sezonlukdizi** (`sezonlukdizi.cc`, beta): Filemoon/Byse oynatımı (ECDSA attestation, özel PoW,
  AES-GCM); diğer kaynaklar Turnstile ile kapalı.
- **Ortak özellikler** (site gerçekten sunuyorsa): sayfalı arama, ana sayfa `hasNext`, öneriler,
  fragman, kadro (`actors`), süre, `score`, bölüm özeti, anime sitelerinde `TvType.Anime` ve
  gerçek canlı arama uç noktası olan sitelerde `quickSearch`. Sitenin sunmadığı alan boş bırakılır.
- **Hata bildirimi**: `load`/`loadLinks` başarısızlığında issue bağlantılı
  `ErrorLoadingException` (`https://github.com/F0Rextasy/bigbullapp-cs/issues`); arama ve ana sayfa
  dalgalanmalara açık olduğu için boş liste döner.
- **Otomasyon**: `build.yml` (`builds` dalı), `domains.yml` (doğrulanmış domain güncellemesi,
sürüm + changelog), `health.yml` (günlük sağlık) ve `bildirim.yml` (Telegram). `tools/` altında
`check.py`, `verify.py`, `revive.py`, `scaffold.py`, `report.py`.
- `README.md` sağlayıcı tablosu ve özellik matrisi (her zaman güncel tutulur), Türkçe
  `CONTRIBUTING.md`, `AGENTS.md`, MIT `LICENSE` ve bu değişiklik kaydı.
- X: [@BIGBULLapp](https://x.com/BIGBULLapp).

## Notlar

- Eklenti `version` değerleri depo sürümünden bağımsızdır; her yayında modül sürümünü artır, yoksa
  Cloudstream güncelleme göstermez.
- Bu depo yalnızca kaynak içerir; derleme iş akışı ve yayınlanmış manifest yoktur.
- Yeni alanlar yalnızca canlı kanıtla eklenir; sitede olmayan veri uydurulmaz.
