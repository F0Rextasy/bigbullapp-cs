# BigBullApp — Cloudstream + Nuvio (bigbullapp-cs-nuvio)

[Cloudstream](https://github.com/recloudstream/cloudstream) ve
[Nuvio](https://github.com/NuvioMedia/NuvioMobile) için Türkçe film, dizi, anime, çizgi film ve
canlı TV kaynakları. Her site Cloudstream'de ayrı bir Gradle modülü/`.cs3` eklentisi, Nuvio'da
`nuvio/providers/` altında ayrı bir plugin dosyasıdır. Nuvio resmi istemcileri:
[NuvioMobile](https://github.com/NuvioMedia/NuvioMobile) ve
[NuvioTV](https://github.com/NuvioMedia/NuvioTV).

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-SDK%2035-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Cloudstream](https://img.shields.io/badge/Cloudstream-pre--release-1E88E5)](https://github.com/recloudstream/cloudstream)
[![Lisans](https://img.shields.io/badge/Lisans-MIT-green)](LICENSE)
[![Modül](https://img.shields.io/badge/mod%C3%BCl-88-blue)](providers)
[![X](https://img.shields.io/badge/X-@BIGBULLapp-000000?logo=x&logoColor=white)](https://x.com/BIGBULLapp)
[![Telegram](https://img.shields.io/badge/Telegram-BigBull__App-26A5E4?logo=telegram&logoColor=white)](https://t.me/bigbull_app)

> **Kurulum:** Cloudstream → **Ayarlar → Eklentiler → Depo Ekle**:
>
> ```
> https://raw.githubusercontent.com/F0Rextasy/bigbullapp-cs/master/repo.json
> ```
>
> Kısa kod: **`bigbullapp`** — Depo Ekle alanına yazabilirsin.
>
> **Nuvio:** Nuvio → **Ayarlar → Content & Discovery → Plugins → Add Repository**:
>
> ```
> https://raw.githubusercontent.com/F0Rextasy/bigbullapp-cs/master/nuvio/manifest.json
> ```
>
> Yalnız **sideload** edilen Nuvio sürümleri eklenti destekler (mağaza sürümlerinde bu bölüm
> yoktur). Eklentiler yalnız **akış** verir; katalog/meta için **Cinemeta** addon’unu da ekle:
> `https://v3-cinemeta.strem.io/manifest.json`.
>
> **Nuvio katalog (Addons):** Addons → Add Addon:
> `https://raw.githubusercontent.com/F0Rextasy/bigbullapp-cs/master/nuvio/addon/manifest.json`.

## Kısa kod

`bigbullapp`

Cloudstream kısa kodları `cutt.ly/<kod>` yönlendirmesiyle çözer. `cutt.ly/bigbullapp` →
`repo.json` yönlendirmesi tanımlı; **Depo Ekle** alanına `bigbullapp` yazman yeterli. Kısa kod
bazı ISS'lerde engelli olabileceği için alternatif olarak tam URL de kullanılabilir.

## Özellikler

- Arama → detay → bölüm → oynatma akışı; site destekliyorsa **sayfalama**
- **Öneriler**, **fragman**, **oyuncu**, **süre**, **puan**, **bölüm özeti** — yalnızca sitede
  gerçekten varsa, asla uydurulmaz
- Altyazı (VTT) ve kalite etiketleri (`Qualities.*`)
- Anime sitelerinde `TvType.Anime`
- Hata durumunda boş ekran yerine açıklayıcı mesaj ve
  [issue bağlantısı](https://github.com/F0Rextasy/bigbullapp-cs/issues)
- Windows / Linux fark etmeksizin Gradle wrapper ile tek komut derleme

## Kaynaktan derleme

Gereksinimler: **JDK 17**, **Android SDK 35** (`platforms;android-35`, `build-tools;35.0.0`).

```bash
# Windows
set JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot
set ANDROID_HOME=C:\Android\Sdk
gradlew.bat make makePluginsJson

# Linux / macOS
JAVA_HOME=/path/to/jdk17 ./gradlew make makePluginsJson
```

`local.properties` (repoya girmez):

```properties
sdk.dir=C\:\\Android\\Sdk
```

Çıktılar: `providers/<Modül>/build/<Modül>.cs3` ve `build/plugins.json`.

Tek modül derleme ve cihaza kurulum:

```bash
./gradlew Dizibox:make
./gradlew Dizibox:deployWithAdb   # Android 11+: All files access izni
```

## Eklentiler

`✓` var · `—` sitede yok · `film` yalnız filmlerde

| Eklenti | Kaynak | Tür | Sayfalama | Öneri | Fragman | Kadro | Süre | Puan |
|---|---|---|---|---|---|---|---|---|
| `AniArsiv` | aniarsiv.com | Dizi | — | — | — | — | — | — |
| `AnimeciX` | animecix.tv | Anime | — | — | — | — | — | ✓ |
| `Animeler` | animeler.live | Anime | — | — | — | — | — | — |
| `Anizm` | turkanime.info | Anime | — | — | ✓ | — | — | ✓ |
| `Anizium` | anizium.co | Anime | ✓ | ✓ | ✓ | — | ✓ | ✓ |
| `AsyaAnimeleri` | asyaanimeleri.top | Anime | ✓ | ✓ | ✓ | — | ✓ | ✓ |
| `AsyaBox` | asyabox.co | Dizi | — | — | — | — | — | — |
| `AsyaDizi` | asyadizi.com | Dizi | — | — | — | — | — | — |
| `Asyadizileriizle` | asyadizileriizle.com | Dizi | — | — | — | — | — | — |
| `AsyaLog` | asyalog.co | Asya dizi/film | ✓ | ✓ | — | ✓ | ✓ | ✓ |
| `AsyaLuna` **pasif** | asyaluna.com | Dizi | — | — | — | — | — | — |
| `AsyaWatch` | asyawatch.com | Asya dizi/film | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `ATV` | atv.com.tr | Dizi | — | — | — | — | — | — |
| `BelgeselX` | belgeselx.com | Belgesel | — | — | — | — | — | — |
| `BirAsyaDizi` | birasyadizi.cx | Dizi | — | — | — | — | — | — |
| `CanliDizi` | canlidizi.sbs | Dizi | — | — | — | — | — | — |
| `CanliTV` | iptv-org TR M3U | Canlı TV | — | — | — | — | — | — |
| `CanliYayinKanallari` | tabii.com | Canlı TV | — | — | — | — | — | — |
| `Cizgimax` | cizgimax.online | Çizgi | ✓ | ✓ | — | — | — | ✓ |
| `CizgiSite` | www.cizgi.site | Dizi | — | — | — | — | — | — |
| `Ddizi` | ddizi.tel | Dizi | ✓ | — | — | — | — | — |
| `DDizimag` | ddizimag.icu | Dizi | — | — | — | — | — | — |
| `Deokwave` | deokwave.com | Dizi | — | — | — | — | — | — |
| `DiziAbi` | diziabi.net | Film | — | — | — | — | — | — |
| `Dizibaba` | dizibaba.net | Film | — | — | — | — | — | — |
| `DiziBal` | dizibal.org | Dizi/Film | ✓ | — | ✓ | ✓ | film | ✓ |
| `DiziBol` | dizibol.org | Dizi | — | — | — | — | — | — |
| `DiziBoxTv` | diziboxtv.com | Dizi | — | — | — | — | — | — |
| `DiziCaN` | dizican.cc | Dizi | — | — | — | — | — | — |
| `DiziFilmIzle` | dizifilmizle.to | Film | — | — | — | — | — | — |
| `Dizifon` | dizifon.cc | Dizi | — | — | — | — | — | — |
| `Dizigecesi` | dizigecesi.com | Film | — | — | — | — | — | — |
| `Dizigom` | dizigom.news | Film | — | — | — | — | — | — |
| `Dizilab` | dizilab.cc | Dizi | — | — | — | — | — | — |
| `DiziLife` | dizi84.life | Dizi/Film | ✓ | ✓ | — | ✓ | film | ✓ |
| `Dizirella` | dizirella.one | Dizi | — | — | — | — | — | — |
| `DiziYou` | diziyou.one | Dizi | — | — | ✓ | ✓ | — | ✓ |
| `Diziasia` | diziasia.com | Asya dizi | ✓ | ✓ | — | — | — | — |
| `Dizibox` | dizibox.live | Dizi | — | — | ✓ | ✓ | — | ✓ |
| `DiziboxIzle` | diziboxizle.com | Dizi | ✓ | — | ✓ | ✓ | — | ✓ |
| `Dizikorea3` | dizikorea3.com | Dizi/Film | ✓ | ✓ | ✓ | ✓ | film | ✓ |
| `Dizilla` | dizilla.la | Dizi | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `DiziMom` | ddizimom.net | Dizi | — | — | — | — | — | — |
| `DiziPal` | dizipal1594.com | Dizi/Film | — | ✓ | — | ✓ | ✓ | ✓ |
| `DiziPod` | dizipod.com | Dizi/Film | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `DMAX` | dmax.com.tr | Dizi | — | — | — | — | — | — |
| `DramaDizilerim` | dramadizilerim.com | Dizi | ✓ | ✓ | — | — | — | ✓ |
| `FilmEkseni` | filmekseni.vip | Film | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `FilmMakinesi` | filmmakinesi.co | Film | ✓ | — | — | — | ✓ | ✓ |
| `Filmizyon` | filmizyon.net | Film | — | — | — | — | — | — |
| `FilmModu` | filmmodu.news | Film | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `FullHDFilm` | fullhdfilmizlebox.vip | Film | — | — | — | — | — | ✓ |
| `FullHDFilmizlesene` | fullhdfilmizlesene.now | Film | — | — | ✓ | — | — | ✓ |
| `HDFilmCehennemi` | hdfilmcehennemi.nl | Film | — | — | — | — | — | — |
| `HDFilmCehennemi2` | hdfilmcehennemi2.biz | Film | — | — | — | — | — | — |
| `HDFilmDelisi` | hdfilmdelisi1.com | Film | ✓ | — | — | — | ✓ | ✓ |
| `HDFilmDiziIzle` | hdfilmdiziizle.com | Dizi | — | — | — | — | — | — |
| `HDFilmizle` | hdfilmizle.ink | Dizi/Film | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `HDFilmizleBest` | hdfilmizle.best | Dizi | — | — | — | — | — | — |
| `InatTV` | diziboxen.help (imzalı API) | Dizi/Film/Canlı TV | — | — | — | — | — | — |
| `JetFilmizle` | jetfilmizle.app | Film | — | — | ✓ | ✓ | — | ✓ |
| `KanalD` | kanald.com.tr | Dizi | — | — | — | — | — | — |
| `KoreanTurk` | koreanturk.tv | Dizi | ✓ | — | — | — | — | — |
| `Kultfilmler` | kultfilmler.net | Film | ✓ | — | — | ✓ | ✓ | ✓ |
| `LoveFilm` | lovefilmizle.net | Film | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `MinikaCocuk` | minikacocuk.com.tr | Çocuk/Dizi | — | — | — | — | — | — |
| `MinikaGO` | minikago.com.tr | Çocuk | — | — | — | — | — | — |
| `NetFilmizle` | netfilmizle.com | Film | — | — | — | — | — | — |
| `Onepacetr` | onepacetr.net | Anime | ✓ | — | — | — | ✓ | — |
| `PuhuTV` | puhutv.com | Dizi/Film | ✓ | — | — | ✓ | film | — |
| `RecTV` | a.psrectv80.xyz (imzalı API) | Film/Dizi | ✓ | — | — | — | ✓ | ✓ |
| `SelcukFlix` | selcukflix.app | Dizi/Film | — | — | — | — | ✓ | ✓ |
| `SetFilmIzle` | setfilmizle.ltd | Dizi/Film | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `Sezonlukdizi` | sezonlukdizi.cc | Dizi | — | — | ✓ | ✓ | ✓ | ✓ |
| `ShowTV` | showtv.com.tr | Dizi | — | — | — | — | — | — |
| `SinemaCX` | sinemacc.com | Film | — | — | — | ✓ | ✓ | ✓ |
| `Sinezy` | sinezy.si | Film | — | — | — | — | — | — |
| `Sinemakolik` | sinemakolik.com | Film | — | — | — | — | — | — |
| `SonAnime` | sonanime.com | Dizi | — | — | — | — | — | — |
| `StarTV` | startv.com.tr | Dizi | — | — | — | — | — | — |
| `TLCtr` | tlctv.com.tr | Dizi | ✓ | — | — | — | — | — |
| `TurkceDizi` | turkcedizi.com | Film | — | — | — | — | — | ✓ |
| `TV8` | tv8.com.tr | Dizi | — | — | — | — | — | — |
| `UltraFilmizle` | ufilmizle.com | Film | — | — | — | — | — | — |
| `WebdramaTurkey2` | webdramaturkey2.com | Asya dizi/film | ✓ | — | — | ✓ | ✓ | — |
| `Weboo` | weboo.com.tr | Dizi | — | — | — | — | — | — |
| `WebteIzle` | webteizle.info | Film | ✓ | — | ✓ | ✓ | ✓ | — |
| `Yabancidizi` | yabancidizi.watch | Dizi/Film | — | ✓ | — | ✓ | film | ✓ |
| `YesilCamTv` | yesilcamtv.com.tr | Film | — | — | — | — | — | — |

**İnatTV notu:** İnat'ın tüm aktif dizi, film, dijital platform (Netflix, Disney+, Amazon Prime, HBO Max, EXXEN, Gain, Tabii, Mubi, TOD) ve canlı yayın kategorileri **RecTV** mimarisiyle tek bir **`InatTV`** (`İnatTV`) eklentisi altında birleştirilmiştir; satırlar eklenti ayarlarından açılıp kapatılabilir. Depoda toplam **88 aktif modül** var (`AsyaLuna` sunucusu silindiği için `status = 0` ile pasif).

Bu tablo ve özellik matrisi **her zaman güncel tutulur**: her sağlayıcı değişikliğinde
`README.md`, `CHANGELOG.md` ve modül sürümü birlikte güncellenir.

## Nuvio

Nuvio eklentileri cihazda (QuickJS) çalışır; depo `nuvio/manifest.json` dosyasıdır ve GitHub raw
üzerinden yayınlanır. Her sağlayıcı `nuvio/providers/<id>.js` **tek dosyasıdır** — derleme adımı
yoktur, Nuvio'nun indirdiği dosya depodaki dosyanın aynısıdır.

Nuvio ana repo: <https://github.com/NuvioMedia/NuvioMobile>

Ekleme: Nuvio → **Ayarlar → Content & Discovery → Plugins → Add Repository** →
`https://raw.githubusercontent.com/F0Rextasy/bigbullapp-cs/master/nuvio/manifest.json` →
**Refresh** → sağlayıcıları tek tek aç. Tam liste: [`nuvio/manifest.json`](nuvio/manifest.json).

**Kapsam**: Uygulama performansını korumak ve kasmayı önlemek amacıyla Nuvio tarafında yalnızca seçilmiş **30 ana sağlayıcı** yayınlanır:
- **Anime (3):** `Anizium`, `AnimeciX`, `AsyaAnimeleri`
- **Dizi (13):** `Dizibox`, `Dizilla`, `DiziPal`, `SelcukFlix`, `Ddizi`, `WebdramaTurkey2`, `Dizikorea3`, `Sezonlukdizi`, `DiziYou`, `DiziBal`, `İnatTV Dizi`, `DiziMom`, `DiziPod`
- **Film (9):** `HDFilmCehennemi`, `FullHDFilmizlesene`, `SetFilmIzle`, `YesilCamTv`, `İnatTV Filmler`, `FilmMakinesi`, `WebteIzle`, `FilmModu`, `JetFilmizle`
- **Film & Dizi (2):** `PuhuTV`, `RecTV`
- **Belgesel & TV (3):** `BelgeselX`, `DMAX`, `TLCtr`

Repo eklemek sağlayıcıları çalıştırmaz: Plugins ekranında **Enable plugin providers globally** ve
istediğin sağlayıcıların anahtarı ayrıca açılır.

**Meta sağlayıcı şart:** Plugin sağlayıcıları yalnız akış verir; film/dizi detayı için Nuvio’da
**Cinemeta** addon’u da kurulu olmalı. Yoksa “Bu içerik için meta sağlayan eklenti yok” hatası
çıkar. Cinemeta: `https://v3-cinemeta.strem.io/manifest.json`.

### Ana sayfa satırları (katalog addon'u)

Plugin'ler yalnız **akış** verir; Nuvio ana sayfasına satır koyamazlar. Satırlar için ayrı,
sunucusuz bir **katalog addon'u** var:

0. Önce **Addons → Add Addon** ile Cinemeta’yı ekle:
   `https://v3-cinemeta.strem.io/manifest.json`
1. Nuvio → **Settings → Content & Discovery → Addons → Add Addon**
2. `https://raw.githubusercontent.com/F0Rextasy/bigbullapp-cs/master/nuvio/addon/manifest.json`
3. **Install Addon** → ana sayfada seçili sağlayıcıların satırları ile **"Tümü - Filmler"** ve **"Tümü - Diziler"** satırları görünür.

Addon yalnız `catalog` kaynağı sunar: item'lar IMDb id'siyle yazılır, detay meta'sını Cinemeta,
akışları yukarıdaki plugin sağlayıcıları verir. İçerik günde bir
`.github/workflows/nuvio-catalog.yml` ile yenilenir; tarifler `nuvio/addon/sources/<id>.json`,
üretici `node nuvio/addon/build.mjs`. Statik katalog **arama** ve sonsuz kaydırma (`skip`)
desteklemez; her satır ilk sayfayı gösterir. JS ile render edilen siteler (ör. Anizium) ve
İnat CDN sağlayıcıları satır üretemez.

Başlık çözümü anahtarsız Cinemeta üzerinden yapılır, TMDB anahtarı gerekmez. Yalnız **sideload**
edilen Nuvio sürümleri plugin destekler; mağaza sürümlerinde Plugins bölümü yoktur.

## Canlı TV

Canlı yayın modülleri `TvType.Live` kullanır; `loadLinks` yoktur, kanal URL'si doğrudan
`newLiveStreamLoadResponse` ile verilir.

| Eklenti | Kaynak | Kanal | Not |
|---|---|---|---|
| `CanliTV` | iptv-org TR M3U | 172 | 5 ana grup + arama; M3U listesi her gün güncellenir |
| `CanliYayinKanallari` | sabit HLS | 46 | 403/404 veren 7 kayıt çıkarıldı |
| `TLCtr` | TLC | 1 | TLC Canlı; 720/480/360 HLS adresleri HTTP 200 |

`CanliYayinKanallari` için “doğrulanmış” yalnız **HTTP 200 HLS adresi** anlamındadır; oynatma
tek başına bu testle garanti edilmez.

## Doğrulama (2026-11-08)

- Cloudstream host runner: 88 aktif modül; DiziPod v6, Yabancidizi v8 ve HDFilmCehennemi v9 canlı doğrulandı.
- Emülatör smoke: DiziPal, AsyaWatch ve MinikaGO arama → detay → oynatma PASS.
- Nuvio host: **30 sağlayıcı** (`manifest.json`) ve **20 katalog** (`addon/manifest.json`); otomatik güncelleme botu her turda Nuvio'yu da tarar (İnat CDN egress engeli ve Node TLS'ine kapalı `WebteIzle` ayrı sayılır).

## Eklenti ayarları

Her sağlayıcının kendi ayar ekranı vardır (**Ayarlar → Extensions → eklenti → dişli**):

- **Kalite üst sınırı**: Sınırsız / 1080p / 720p / 480p / 360p. Kaynak daha yüksekse gizlenir.
- **Altyazı dili**: Tümü / Türkçe / İngilizce / Kapalı.

Tercihler `bigbull_<modül>` altında saklanır; canlı TV modüllerinde uygulanmaz.

## Depo yapısı

```
build.gradle.kts        ortak Android/Kotlin + Cloudstream yapılandırması
settings.gradle.kts     providers/ altındaki her modülü otomatik dahil eder
providers/
  Anizium/              modül = eklenti
  Dizibox/
  ...
  Yabancidizi/          88 aktif modül
nuvio/                  Nuvio eklenti deposu (30 sağlayıcı + 20 katalog kaynağı)
```

## Geliştirme

- [`CONTRIBUTING.md`](CONTRIBUTING.md) — yeni eklenti, kod kuralları, doğrulama zorunluluğu
- [`CHANGELOG.md`](CHANGELOG.md) — sürüm notları

Sürümleme: her modülün `build.gradle.kts` içindeki `version` alanı artmadıkça Cloudstream
güncelleme göstermez. Davranış değişikliği = sürüm artışı + changelog kaydı.

## Yasal

Bu depo yalnızca istemci tarafı kod içerir; içerik barındırmaz. Hedef sitelerin kullanım koşulları
ve bulunduğunuz ülkenin telif mevzuatı sizin sorumluluğunuzdadır. Kaynak siteler üçüncü taraflara
aittir ve önceden bildirim olmadan değişebilir.

## Lisans

Bu depodaki kod **MIT** lisanslıdır — [`LICENSE`](LICENSE).
