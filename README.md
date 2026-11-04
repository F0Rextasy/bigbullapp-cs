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
[![Modül](https://img.shields.io/badge/mod%C3%BCl-93-blue)](providers)
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

`Inat*` platformlarından runner’da pass olmayan 24 tanesi pasif; kaynakları korunuyor.

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
| `AsyaLuna` | asyaluna.com | Dizi | — | — | — | — | — | — |
| `AsyaWatch` | asyawatch.com | Asya dizi/film | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `ATV` **(pasif)** | atv.com.tr | Dizi | — | — | — | — | — | — |
| `BelgeselX` | belgeselx.com | Belgesel | — | — | — | — | — | — |
| `BirAsyaDizi` | birasyadizi.cx | Dizi | — | — | — | — | — | — |
| `CanliDizi` | canlidizi.sbs | Dizi | — | — | — | — | — | — |
| `CanliTV` | iptv-org TR M3U | Canlı TV | — | — | — | — | — | — |
| `CanliYayinKanallari` | tabii.com | Canlı TV | — | — | — | — | — | — |
| `Cizgimax` | cizgimax.online | Çizgi | ✓ | ✓ | — | — | — | ✓ |
| `CizgiSite` | www.cizgi.site | Dizi | — | — | — | — | — | — |
| `Ddizi` | ddizi.tel | Dizi | ✓ | — | — | — | — | — |
| `DDizimag` | ddizimag.com | Dizi | — | — | — | — | — | — |
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
| `DiziLife` | dizi83.life | Dizi/Film | ✓ | ✓ | — | ✓ | film | ✓ |
| `Dizirella` | dizirella.one | Dizi | — | — | — | — | — | — |
| `DiziYou` | diziyou.one | Dizi | — | — | ✓ | ✓ | — | ✓ |
| `Diziasia` | diziasia.com | Asya dizi | ✓ | ✓ | — | — | — | — |
| `Dizibox` | dizibox.live | Dizi | — | — | ✓ | ✓ | — | ✓ |
| `DiziboxIzle` | diziboxizle.com | Dizi | ✓ | — | ✓ | ✓ | — | ✓ |
| `Dizikorea3` | dizikorea3.com | Dizi/Film | ✓ | ✓ | ✓ | ✓ | film | ✓ |
| `Dizilla` | dizilla.la | Dizi | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `DiziMom` | ddizimom.net | Dizi | — | — | — | — | — | — |
| `DiziPal` | dizipal1593.com | Dizi/Film | ✓ | ✓ | — | ✓ | ✓ | ✓ |
| `DiziPod` | dizipod.com | Dizi/Film | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `DMAX` | dmax.com.tr | Dizi | — | — | — | — | — | — |
| `DramaDizilerim` | dramadizilerim.com | Dizi | ✓ | ✓ | — | — | — | ✓ |
| `FilmEkseni` | filmekseni.vip | Film | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `Filmizyon` | filmizyon.net | Film | — | — | — | — | — | — |
| `FilmModu` | filmmodu.news | Film | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `FullHDFilm` | fullhdfilmizlebox.vip | Film | — | — | — | — | — | ✓ |
| `FullHDFilmizlesene` | fullhdfilmizlesene.now | Film | — | — | ✓ | — | — | ✓ |
| `HDFilmCehennemi` | hdfilmcehennemi.nl | Film | — | — | — | — | — | — |
| `HDFilmCehennemi2` | hdfilmcehennemi2.biz | Film | — | — | — | — | — | — |
| `HDFilmDelisi` | hdfilmdelisi.one | Film | ✓ | — | — | — | ✓ | ✓ |
| `HDFilmDiziIzle` | hdfilmdiziizle.com | Dizi | — | — | — | — | — | — |
| `HDFilmizle` | hdfilmizle.ink | Dizi/Film | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `HDFilmizleBest` | hdfilmizle.best | Dizi | — | — | — | — | — | — |
| `InatListe1Tr` **pasif** | diziboxen.help | Canlı TV | — | — | — | — | — | — |
| `InatListe2Glb` **pasif** | diziboxen.help | Canlı TV | — | — | — | — | — | — |
| `InatListe3Tr` **pasif** | diziboxen.help | Canlı TV | — | — | — | — | — | — |
| `InatSpor` **pasif** | sprboxs.bar | Canlı TV | — | — | — | — | — | — |
| `InatDerbiler` **pasif** | sprboxs.bar | Canlı TV | — | — | — | — | — | — |
| `InatPremium` **pasif** | inattv257.cfd | Canlı TV | — | — | — | — | — | — |
| `InatSinema` **pasif** | diziboxen.help | Film | — | — | — | — | — | — |
| `InatBelgesel` **pasif** | diziboxen.help | Belgesel | — | — | — | — | — | — |
| `InatUlusal` **pasif** | diziboxen.help | Canlı TV | — | — | — | — | — | — |
| `InatHaber` **pasif** | diziboxen.help | Canlı TV | — | — | — | — | — | — |
| `InatEbaTv` **pasif** | diziboxen.help | Canlı TV | — | — | — | — | — | — |
| `InatCocuk` **pasif** | diziboxen.help | Çocuk | — | — | — | — | — | — |
| `InatDini` **pasif** | diziboxen.help | Canlı TV | — | — | — | — | — | — |
| `InatExxen` | diziboxen.help | Dizi | — | — | — | — | — | — |
| `InatGain` | diziboxen.help | Dizi | — | — | — | — | — | — |
| `InatNetflix` | diziboxen.help | Dizi | — | — | — | — | — | — |
| `InatHboMax` | diziboxen.help | Dizi | — | — | — | — | — | — |
| `InatDisney` | diziboxen.help | Dizi | — | — | — | — | — | — |
| `InatAmazonPrime` | diziboxen.help | Dizi | — | — | — | — | — | — |
| `InatTabii` | diziboxen.help | Dizi | — | — | — | — | — | — |
| `InatMubi` **pasif** | diziboxen.help | Film | — | — | — | — | — | — |
| `InatTod` **pasif** | sprboxs.bar | Dizi | — | — | — | — | — | — |
| `InatYabanciDiziler` **pasif** | diziboxen.help | Dizi | — | — | — | — | — | — |
| `InatYerliDiziler` **pasif** | diziboxen.help | Dizi | — | — | — | — | — | — |
| `InatYerliFilmler` | diziboxen.help | Film | — | — | — | — | — | — |
| `Inat4k01Exo` **pasif** | 4k.filmizleeeee.cfd | Film | — | — | — | — | — | — |
| `Inat4k01Web` **pasif** | 4k.filmizleeeee.cfd | Film | — | — | — | — | — | — |
| `Inat4k02Web` **pasif** | 4k.filmizleeeee.cfd | Film | — | — | — | — | — | — |
| `Inat4k03Exo` | 4k.filmizleeeee.cfd | Film | — | — | — | — | — | — |
| `Inat4k03Web` | 4k.filmizleeeee.cfd | Film | — | — | — | — | — | — |
| `Inat4k04Exo` **pasif** | 4k.filmizleeeee.cfd | Film | — | — | — | — | — | — |
| `Inat4k04Web` **pasif** | 4k.filmizleeeee.cfd | Film | — | — | — | — | — | — |
| `Inat4k06Exo` | 4k.filmizleeeee.cfd | Film | — | — | — | — | — | — |
| `Inat4k06Web` **pasif** | 4k.filmizleeeee.cfd | Film | — | — | — | — | — | — |
| `Inat4k07Web` **pasif** | 4k.filmizleeeee.cfd | Film | — | — | — | — | — | — |
| `JetFilmizle` | jetfilmizle.app | Film | — | — | ✓ | ✓ | — | ✓ |
| `KanalD` | kanald.com.tr | Dizi | — | — | — | — | — | — |
| `KoreanTurk` | koreanturk.tv | Dizi | ✓ | — | — | — | — | — |
| `Kultfilmler` | kultfilmler.net | Film | ✓ | — | — | ✓ | ✓ | ✓ |
| `LoveFilm` | lovefilmizle.net | Film | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `MinikaCocuk` **(pasif)** | minikacocuk.com.tr | Dizi | — | — | — | — | — | — |
| `MinikaGO` | minikago.com.tr | Çocuk | — | — | — | — | — | — |
| `NetFilmizle` | netfilmizle.com | Film | — | — | — | — | — | — |
| `Onepacetr` | onepacetr.net | Anime | ✓ | — | — | — | ✓ | — |
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
| `Yabancidizi` | yabancidizi.live | Dizi/Film | — | — | — | — | film | ✓ |
| `YesilCamTv` | yesilcamtv.com.tr | Film | — | — | — | — | — | — |

**Inat notu:** Inat'ın **35 platformu ayrı modül** olarak tutulur; runner’da pass olan **11 platform aktif**, pass olmayan **24 platform pasif**. Aktif platformlar: Liste 4K 03 Exo/Web, 4K 06 Exo, Exxen, Gain, Netflix, HBO Max, Disney+, Amazon Prime, Tabii ve Yerli Filmler. Depoda toplam **93 aktif modül** var; `ATV` ve `MinikaCocuk` upstream oynatıcı endpoint'leri timeout verdiği için pasif, kaynakları korunuyor. Pasif platformlar `settings.gradle.kts` `disabled` listesinde; kaynak dosyaları korunuyor.

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

**Kapsam**: Cloudstream'deki VOD sağlayıcılarının tamamı Nuvio'da da var — **102 sağlayıcı**.
90'ı canlı doğrulanmış; manifest'te `"enabled": true` ile gelir. Kalan 12'si (`AsyaWatch`,
`SelcukFlix`, `DiziPal`, `FullHDFilmizlesene`, `Inat 4K 01/02/04/07`, `Inat Mubi`, `MinikaCocuk`)
Cloudflare/İnat CDN engeli veya upstream 502/404 nedeniyle doğrulanamadı; `"enabled": false` ile
yayınlanır. **ATV** Nuvio'da iki ardışık canlı testte geçtiği için açıktır; Cloudstream ATV modülü
`/izle` VOD endpoint'i timeout verdiği için ayrıca pasiftir.
Repo eklemek sağlayıcıları çalıştırmaz: Plugins ekranında **Enable plugin providers globally** ve
istediğin sağlayıcıların anahtarı ayrıca açılır.

**Meta sağlayıcı şart:** Plugin sağlayıcıları yalnız akış verir; film/dizi detayı için Nuvio’da
**Cinemeta** addon’u da kurulu olmalı. Yoksa “Bu içerik için meta sağlayan eklenti yok” hatası
çıkar. Cinemeta: `https://v3-cinemeta.strem.io/manifest.json`.

Kapsam dışı 17 modül: yalnız **canlı kanal** yayınlayanlar (`Canlı TV`, `Canlı Yayın Kanalları`,
`Inat Spor/Haber/Ulusal/EBA TV/Derbiler/Liste 1-2-3/Belgesel/Çocuk/Dini/Sinema/Premium`) —
Nuvio eklentisi başlık (IMDb) → akış modelinde çalışır, bunlarda başlık araması yoktur — ve iki
ölü site: `CanliDizi` (oynatıcı sunucusu kaldırılmış), `Dizigecesi` (attest duvarı + sunucu
taraflı arama yok).

### Ana sayfa satırları (katalog addon'u)

Plugin'ler yalnız **akış** verir; Nuvio ana sayfasına satır koyamazlar. Satırlar için ayrı,
sunucusuz bir **katalog addon'u** var:

0. Önce **Addons → Add Addon** ile Cinemeta’yı ekle:
   `https://v3-cinemeta.strem.io/manifest.json`
1. Nuvio → **Settings → Content & Discovery → Addons → Add Addon**
2. `https://raw.githubusercontent.com/F0Rextasy/bigbullapp-cs/master/nuvio/addon/manifest.json`
3. **Install Addon** → ana sayfada "Dizibox - Series", "Kultfilmler - Movie",
   **"Tümü - Filmler"** ve **"Tümü - Diziler"** satırları görünür.

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

## Doğrulama (2026-11-03)

- Cloudstream host runner: **82/82 PASS**, 93 aktif modül.
- Emülatör smoke: DiziPal, AsyaWatch ve MinikaGO arama → detay → oynatma PASS.
- Nuvio host: 102 sağlayıcı tarandı; **90/90 aktif** tek tek doğrulandı. HDFilmCehennemi iki
  ardışık canlı testte PASS; MinikaGO, Dizikorea3 ve Inat 4K 03 Exo/Web düzeltildi/doğrulandı;
  Inat 4K 03 Web 120 sn'ye kadar sürebiliyor.
- HDFilmCehennemi Android emülatöründe Cloudflare/TLS nedeniyle arama yanıtı JSON dönmedi;
  host runner PASS. Bu cihaz sınırı README’de etiketle gizlenmez.

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
  Yabancidizi/          93 modül
nuvio/                  Nuvio eklenti deposu (102 sağlayıcı, sağlayıcı başına tek dosya)
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
