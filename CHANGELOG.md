# Değişiklik Kaydı

Bu depodaki önemli değişiklikler burada tutulur.
Biçim: [Keep a Changelog](https://keepachangelog.com/tr/1.1.0/). Depo sürümleri ayrıdır; her eklenti
`providers/<Modül>/build.gradle.kts` içinde kendi `version` değerini taşır ve Cloudstream ancak bu
sayı arttığında güncelleme sunar.

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
