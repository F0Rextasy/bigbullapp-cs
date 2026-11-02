# BigBullApp

[Cloudstream](https://github.com/recloudstream/cloudstream) için Türkçe film, dizi, anime, çizgi film ve
canlı TV kaynakları. Her site ayrı bir Gradle modülü ve ayrı bir `.cs3` eklentisidir. Aynı
kaynakların bir kısmı **Nuvio** için `nuvio/` altında eklenti (plugin) olarak da yayınlanır.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-SDK%2035-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Cloudstream](https://img.shields.io/badge/Cloudstream-pre--release-1E88E5)](https://github.com/recloudstream/cloudstream)
[![Lisans](https://img.shields.io/badge/Lisans-MIT-green)](LICENSE)
[![Modül](https://img.shields.io/badge/mod%C3%BCl-85-blue)](providers)
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
> yoktur). Eklentiler yalnız **akış** verir; Nuvio'nun katalogları için Cinemeta gibi bir addon
> gerekir.

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

`✓` var · `—` sitede yok · `film` yalnız filmlerde · `beta` doğrulanmamış sürüm

| Eklenti | Kaynak | Tür | Sayfalama | Öneri | Fragman | Kadro | Süre | Puan |
|---|---|---|---|---|---|---|---|---|
| `AniArsiv` | aniarsiv.com | Dizi | — | — | — | — | — | — |
| `AnimeciX` | animecix.tv | Anime | — | — | — | — | — | ✓ |
| `Animeler` | animeler.live | Anime | — | — | — | — | — | — |
| `Anizm` **beta** | turkanime.info | Anime | — | — | ✓ | — | — | ✓ |
| `Anizium` | anizium.co | Anime | ✓ | ✓ | ✓ | — | ✓ | ✓ |
| `AsyaAnimeleri` | asyaanimeleri.top | Anime | ✓ | ✓ | ✓ | — | ✓ | ✓ |
| `AsyaBox` **beta** | asyabox.co | Dizi | — | — | — | — | — | — |
| `AsyaDizi` **beta** | asyadizi.com | Dizi | — | — | — | — | — | — |
| `Asyadizileriizle` **beta** | asyadizileriizle.com | Dizi | — | — | — | — | — | — |
| `AsyaLog` | asyalog.co | Asya dizi/film | ✓ | ✓ | — | ✓ | ✓ | ✓ |
| `AsyaLuna` **beta** | asyaluna.com | Dizi | — | — | — | — | — | — |
| `AsyaWatch` **beta** | asyawatch.com | Asya dizi/film | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `ATV` **beta** | atv.com.tr | Dizi | — | — | — | — | — | — |
| `BelgeselX` **beta** | belgeselx.com | Belgesel | — | — | — | — | — | — |
| `BirAsyaDizi` **beta** | birasyadizi.cx | Dizi | — | — | — | — | — | — |
| `CanliDizi` **beta** | canlidizi.sbs | Dizi | — | — | — | — | — | — |
| `CanliYayinKanallari` **beta** | tabii.com | Canlı TV | — | — | — | — | — | — |
| `Cizgimax` | cizgimax.online | Çizgi | ✓ | ✓ | — | — | — | ✓ |
| `CizgiSite` **beta** | www.cizgi.site | Dizi | — | — | — | — | — | — |
| `Ddizi` **beta** | ddizi.tel | Dizi | ✓ | — | — | — | — | — |
| `DDizimag` **beta** | ddizimag.com | Dizi | — | — | — | — | — | — |
| `Deokwave` **beta** | deokwave.com | Dizi | — | — | — | — | — | — |
| `DiziAbi` **beta** | diziabi.net | Film | — | — | — | — | — | — |
| `Dizibaba` | dizibaba.net | Film | — | — | — | — | — | — |
| `DiziBal` **beta** | dizibal.org | Dizi/Film | ✓ | — | ✓ | ✓ | film | ✓ |
| `DiziBol` **beta** | dizibol.org | Dizi | — | — | — | — | — | — |
| `DiziBoxTv` **beta** | diziboxtv.com | Dizi | — | — | — | — | — | — |
| `DiziCaN` **beta** | dizican.cc | Dizi | — | — | — | — | — | — |
| `DiziFilmIzle` **beta** | dizifilmizle.to | Film | — | — | — | — | — | — |
| `Dizifon` **beta** | dizifon.cc | Dizi | — | — | — | — | — | — |
| `Dizigecesi` | dizigecesi.com | Film | — | — | — | — | — | — |
| `Dizigom` **beta** | dizigom.news | Film | — | — | — | — | — | — |
| `Dizilab` | dizilab.cc | Dizi | — | — | — | — | — | — |
| `DiziLife` | dizi82.life | Dizi/Film | ✓ | ✓ | — | ✓ | film | ✓ |
| `Dizirella` **beta** | dizirella.one | Dizi | — | — | — | — | — | — |
| `DiziYou` | diziyou.one | Dizi | — | — | ✓ | ✓ | — | ✓ |
| `Diziasia` | diziasia.com | Asya dizi | ✓ | ✓ | — | — | — | — |
| `Dizibox` | dizibox.live | Dizi | — | — | ✓ | ✓ | — | ✓ |
| `DiziboxIzle` | diziboxizle.com | Dizi | ✓ | — | ✓ | ✓ | — | ✓ |
| `Dizikorea3` | dizikorea3.com | Dizi/Film | ✓ | ✓ | ✓ | ✓ | film | ✓ |
| `Dizilla` | dizilla.now | Dizi | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `DiziMom` **beta** | ddizimom.net | Dizi | — | — | — | — | — | — |
| `DiziPal` | dizipal1592.com | Dizi/Film | ✓ | ✓ | — | ✓ | ✓ | ✓ |
| `DiziPod` | dizipod.com | Dizi/Film | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `DMAX` **beta** | dmax.com.tr | Dizi | — | — | — | — | — | — |
| `DramaDizilerim` | dramadizilerim.com | Dizi | ✓ | ✓ | — | — | — | ✓ |
| `FilmEkseni` **beta** | filmekseni.vip | Film | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `Filmizyon` **beta** | filmizyon.net | Film | — | — | — | — | — | — |
| `FilmModu` **beta** | filmmodu.news | Film | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `FullHDFilm` **beta** | fullhdfilmizlebox.vip | Film | — | — | — | — | — | ✓ |
| `FullHDFilmizlesene` **beta** | fullhdfilmizlesene.now | Film | — | — | ✓ | — | — | ✓ |
| `HDFilmCehennemi` **beta** | hdfilmcehennemi.nl | Film | — | — | — | — | — | — |
| `HDFilmCehennemi2` **beta** | hdfilmcehennemi2.biz | Film | — | — | — | — | — | — |
| `HDFilmDelisi` **beta** | hdfilmdelisi.one | Film | ✓ | — | — | — | ✓ | ✓ |
| `HDFilmDiziIzle` | hdfilmdiziizle.com | Dizi | — | — | — | — | — | — |
| `HDFilmizle` **beta** | hdfilmizle.ink | Dizi/Film | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `HDFilmizleBest` **beta** | hdfilmizle.best | Dizi | — | — | — | — | — | — |
| `InatListe1Tr` **beta** | diziboxen.help | Canlı TV | — | — | — | — | — | — |
| `InatListe2Glb` **beta** | diziboxen.help | Canlı TV | — | — | — | — | — | — |
| `InatListe3Tr` **beta** | diziboxen.help | Canlı TV | — | — | — | — | — | — |
| `InatSpor` **beta** | sprboxs.bar | Canlı TV | — | — | — | — | — | — |
| `InatDerbiler` **beta** | sprboxs.bar | Canlı TV | — | — | — | — | — | — |
| `InatPremium` **beta** | inattv257.cfd | Canlı TV | — | — | — | — | — | — |
| `InatSinema` **beta** | diziboxen.help | Film | — | — | — | — | — | — |
| `InatBelgesel` **beta** | diziboxen.help | Belgesel | — | — | — | — | — | — |
| `InatUlusal` **beta** | diziboxen.help | Canlı TV | — | — | — | — | — | — |
| `InatHaber` **beta** | diziboxen.help | Canlı TV | — | — | — | — | — | — |
| `InatEbaTv` **beta** | diziboxen.help | Canlı TV | — | — | — | — | — | — |
| `InatCocuk` **beta** | diziboxen.help | Çocuk | — | — | — | — | — | — |
| `InatDini` **beta** | diziboxen.help | Canlı TV | — | — | — | — | — | — |
| `InatExxen` **beta** | diziboxen.help | Dizi | — | — | — | — | — | — |
| `InatGain` **beta** | diziboxen.help | Dizi | — | — | — | — | — | — |
| `InatNetflix` **beta** | diziboxen.help | Dizi | — | — | — | — | — | — |
| `InatHboMax` **beta** | diziboxen.help | Dizi | — | — | — | — | — | — |
| `InatDisney` **beta** | diziboxen.help | Dizi | — | — | — | — | — | — |
| `InatAmazonPrime` **beta** | diziboxen.help | Dizi | — | — | — | — | — | — |
| `InatTabii` **beta** | diziboxen.help | Dizi | — | — | — | — | — | — |
| `InatMubi` **beta** | diziboxen.help | Film | — | — | — | — | — | — |
| `InatTod` **beta** | sprboxs.bar | Dizi | — | — | — | — | — | — |
| `InatYabanciDiziler` **beta** | diziboxen.help | Dizi | — | — | — | — | — | — |
| `InatYerliDiziler` **beta** | diziboxen.help | Dizi | — | — | — | — | — | — |
| `InatYerliFilmler` **beta** | diziboxen.help | Film | — | — | — | — | — | — |
| `Inat4k01Exo` **beta** | 4k.filmizleeeee.cfd | Film | — | — | — | — | — | — |
| `Inat4k01Web` **beta** | 4k.filmizleeeee.cfd | Film | — | — | — | — | — | — |
| `Inat4k02Web` **beta** | 4k.filmizleeeee.cfd | Film | — | — | — | — | — | — |
| `Inat4k03Exo` | 4k.filmizleeeee.cfd | Film | — | — | — | — | — | — |
| `Inat4k03Web` **beta** | 4k.filmizleeeee.cfd | Film | — | — | — | — | — | — |
| `Inat4k04Exo` **beta** | 4k.filmizleeeee.cfd | Film | — | — | — | — | — | — |
| `Inat4k04Web` **beta** | 4k.filmizleeeee.cfd | Film | — | — | — | — | — | — |
| `Inat4k06Exo` **beta** | 4k.filmizleeeee.cfd | Film | — | — | — | — | — | — |
| `Inat4k06Web` **beta** | 4k.filmizleeeee.cfd | Film | — | — | — | — | — | — |
| `Inat4k07Web` **beta** | 4k.filmizleeeee.cfd | Film | — | — | — | — | — | — |
| `JetFilmizle` **beta** | jetfilmizle.app | Film | — | — | ✓ | ✓ | — | ✓ |
| `KanalD` | kanald.com.tr | Dizi | — | — | — | — | — | — |
| `KoreanTurk` **beta** | koreanturk.tv | Dizi | ✓ | — | — | — | — | — |
| `Kultfilmler` | kultfilmler.net | Film | ✓ | — | — | ✓ | ✓ | ✓ |
| `LoveFilm` **beta** | lovefilmizle.net | Film | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `MinikaCocuk` **beta** | minikacocuk.com.tr | Dizi | — | — | — | — | — | — |
| `MinikaGO` | minikago.com.tr | Çocuk | — | — | — | — | — | — |
| `NetFilmizle` | netfilmizle.com | Film | — | — | — | — | — | — |
| `Onepacetr` | onepacetr.net | Anime | ✓ | — | — | — | ✓ | — |
| `SelcukFlix` **beta** | selcukflix.app | Dizi/Film | — | — | — | — | ✓ | ✓ |
| `SetFilmIzle` | setfilmizle.ltd | Dizi/Film | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `Sezonlukdizi` **beta** | sezonlukdizi.cc | Dizi | — | — | ✓ | ✓ | ✓ | ✓ |
| `ShowTV` **beta** | showtv.com.tr | Dizi | — | — | — | — | — | — |
| `SinemaCX` **beta** | sinemacc.com | Film | — | — | — | ✓ | ✓ | ✓ |
| `Sinezy` | sinezy.si | Film | — | — | — | — | — | — |
| `Sinemakolik` **beta** | sinemakolik.com | Film | — | — | — | — | — | — |
| `SonAnime` **beta** | sonanime.com | Dizi | — | — | — | — | — | — |
| `StarTV` | startv.com.tr | Dizi | — | — | — | — | — | — |
| `TLCtr` | tlctv.com.tr | Dizi | ✓ | — | — | — | — | — |
| `TurkceDizi` **beta** | turkcedizi.com | Film | — | — | — | — | — | ✓ |
| `TV8` **beta** | tv8.com.tr | Dizi | — | — | — | — | — | — |
| `UltraFilmizle` **beta** | ufilmizle.com | Film | — | — | — | — | — | — |
| `WebdramaTurkey2` | webdramaturkey2.com | Asya dizi/film | ✓ | — | — | ✓ | ✓ | — |
| `Weboo` **beta** | weboo.com.tr | Dizi | — | — | — | — | — | — |
| `Yabancidizi` | yabancidizi.live | Dizi/Film | — | — | — | — | film | ✓ |
| `YesilCamTv` **beta** | yesilcamtv.com.tr | Film | — | — | — | — | — | — |

**Inat notu:** Inat'ın **35 platformu ayrı modül** olarak yayınlanır; her modül `Inat — <platform>` adıyla tek sağlayıcı kaydeder. Depoda toplam **119 modül ve 119 Cloudstream sağlayıcısı** var. Platformların upstream durumu ayrı ayrı doğrulanır; erişilemeyen platformlar **beta** ve `status = 3` kalır.

Bot kanıtı olan platformlar: Liste 1/3, Spor, Derbiler, Sinema, Belgesel, Ulusal, Haber, EBA TV,
Çocuk, Dini, Gain, Netflix, HBO Max, Disney+, Amazon Prime, Tabii, 4K Film 03 Exo, 4K Film 04 Web,
4K Film 06 Exo/Web. Kanıtsız kalanlar: Liste 2, Premium, Yerli Filmler, 4K Film 01/02/03-Web/07,
EXXEN, Mubi, TOD, Yabancı/Yerli Diziler (bazıları upstream 520, bazıları handler kanıtı yok).

Bu tablo ve özellik matrisi **her zaman güncel tutulur**: her sağlayıcı değişikliğinde
`README.md`, `CHANGELOG.md` ve modül sürümü birlikte güncellenir.

## Nuvio

Nuvio eklentileri cihazda (QuickJS) çalışır; depo `nuvio/manifest.json` dosyasıdır ve GitHub raw
üzerinden yayınlanır. Her sağlayıcı `nuvio/providers/<id>.js` **tek dosyasıdır** — derleme adımı
yoktur, Nuvio'nun indirdiği dosya depodaki dosyanın aynısıdır.

Ekleme: Nuvio → **Ayarlar → Content & Discovery → Plugins → Add Repository** →
`https://raw.githubusercontent.com/F0Rextasy/bigbullapp-cs/master/nuvio/manifest.json` →
**Refresh** → sağlayıcıları tek tek aç. Tam liste: [`nuvio/manifest.json`](nuvio/manifest.json).

**Kapsam**: Cloudstream'deki VOD sağlayıcılarının tamamı Nuvio'da da var — **102 sağlayıcı**.
91'i varsayılan açık; bu ağdan **91/102** canlı doğrulandı. Kalan 11'i (`AsyaWatch`, `SelcukFlix`,
`DiziPal`, `FullHDFilmizlesene`, `Inat 4K 01/02/04/07`, `Inat Mubi`) Cloudflare/İnat CDN engeli
veya upstream 502/404 nedeniyle canlı doğrulanamadığı için `"enabled": false` ile yayınlanır.

Kapsam dışı 17 modül: yalnız **canlı kanal** yayınlayanlar (`Canlı TV`, `Canlı Yayın Kanalları`,
`Inat Spor/Haber/Ulusal/EBA TV/Derbiler/Liste 1-2-3/Belgesel/Çocuk/Dini/Sinema/Premium`) —
Nuvio eklentisi başlık (IMDb) → akış modelinde çalışır, bunlarda başlık araması yoktur — ve iki
ölü site: `CanliDizi` (oynatıcı sunucusu kaldırılmış), `Dizigecesi` (attest duvarı + sunucu
taraflı arama yok).

Nuvio eklentisi yalnız **akış** üretir: ana sayfa, kategoriler, öneriler ve canlı TV yoktur;
gezinme Nuvio'nun kendi kataloglarındadır (Cinemeta gibi bir addon ile). Başlık çözümü anahtarsız
Cinemeta üzerinden yapılır, TMDB anahtarı gerekmez. Yalnız **sideload** edilen Nuvio sürümleri
eklenti destekler; mağaza sürümlerinde Plugins bölümü yoktur.

## Canlı TV (beta)

Canlı yayın modülleri `TvType.Live` kullanır; `loadLinks` yoktur, kanal URL'si doğrudan
`newLiveStreamLoadResponse` ile verilir.

| Eklenti | Kaynak | Kanal | Not |
|---|---|---|---|
| `CanliTV` **beta** | iptv-org TR M3U | 172 | 5 ana grup + arama; M3U listesi her gün güncellenir |

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
  Yabancidizi/          85 modül
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
