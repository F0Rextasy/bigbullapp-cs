# BigBullApp

[Cloudstream](https://github.com/recloudstream/cloudstream) için Türkçe film, dizi, anime, çizgi film ve
canlı TV kaynakları. Her site ayrı bir Gradle modülü ve ayrı bir `.cs3` eklentisidir.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-SDK%2035-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Cloudstream](https://img.shields.io/badge/Cloudstream-pre--release-1E88E5)](https://github.com/recloudstream/cloudstream)
[![Lisans](https://img.shields.io/badge/Lisans-MIT-green)](LICENSE)
[![Modül](https://img.shields.io/badge/mod%C3%BCl-65-blue)](providers)
[![X](https://img.shields.io/badge/X-@BIGBULLapp-000000?logo=x&logoColor=white)](https://x.com/BIGBULLapp)
[![Telegram](https://img.shields.io/badge/Telegram-BigBull__App-26A5E4?logo=telegram&logoColor=white)](https://t.me/BigBull_App)

> **Kurulum:** Cloudstream → **Ayarlar → Eklentiler → Depo Ekle**:
>
> ```
> https://raw.githubusercontent.com/F0Rextasy/bigbullapp-cs/master/repo.json
> ```
>
> Kısa kod: **`bigbullapp`** — Depo Ekle alanına yazabilirsin.

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
| `AniArsiv` **beta** | aniarsiv.com | Dizi | — | — | — | — | — | — |
| `AnimeciX` **beta** | animecix.tv | Anime | — | — | — | — | — | ✓ |
| `Animeler` **beta** | animeler.live | Anime | — | — | — | — | — | — |
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
| `Ddizi` **beta** | ddizi.tel | Dizi | ✓ | — | — | — | — | — |
| `DDizimag` **beta** | ddizimag.com | Dizi | — | — | — | — | — | — |
| `Deokwave` **beta** | deokwave.com | Dizi | — | — | — | — | — | — |
| `DiziAbi` **beta** | diziabi.net | Film | — | — | — | — | — | — |
| `DiziBal` **beta** | dizibal.org | Dizi/Film | ✓ | — | ✓ | ✓ | film | ✓ |
| `DiziBol` **beta** | dizibol.org | Dizi | — | — | — | — | — | — |
| `DiziBoxTv` **beta** | diziboxtv.com | Dizi | — | — | — | — | — | — |
| `DiziCaN` **beta** | dizican.cc | Dizi | — | — | — | — | — | — |
| `DiziFilmIzle` **beta** | dizifilmizle.to | Film | — | — | — | — | — | — |
| `Dizifon` **beta** | dizifon.cc | Dizi | — | — | — | — | — | — |
| `Dizigom` **beta** | dizigom.news | Film | — | — | — | — | — | — |
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
| `DramaDizilerim` **beta** | dramadizilerim.com | Dizi | ✓ | ✓ | — | — | — | ✓ |
| `FilmEkseni` **beta** | filmekseni.vip | Film | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `Filmizleya` **beta** | filmizleya.com | Film | — | — | — | — | — | — |
| `Filmizyon` **beta** | filmizyon.net | Film | — | — | — | — | — | — |
| `FilmModu` **beta** | filmmodu.news | Film | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `FullHDFilm` **beta** | fullhdfilmizlebox.vip | Film | — | — | — | — | — | ✓ |
| `FullHDFilmizlesene` **beta** | fullhdfilmizlesene.now | Film | — | — | ✓ | — | — | ✓ |
| `HDFilmCehennemi` **beta** | hdfilmcehennemi.nl | Film | — | — | — | — | — | — |
| `HDFilmDelisi` **beta** | hdfilmdelisi.one | Film | ✓ | — | — | — | ✓ | ✓ |
| `HDFilmDiziIzle` **beta** | hdfilmdiziizle.com | Dizi | — | — | — | — | — | — |
| `HDFilmizle` **beta** | hdfilmizle.ink | Dizi/Film | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `HDFilmizleBest` **beta** | hdfilmizle.best | Dizi | — | — | — | — | — | — |
| `JetFilmizle` **beta** | jetfilmizle.app | Film | — | — | ✓ | ✓ | — | ✓ |
| `KanalD` **beta** | kanald.com.tr | Dizi | — | — | — | — | — | — |
| `KoreanTurk` **beta** | koreanturk.tv | Dizi | ✓ | — | — | — | — | — |
| `Kultfilmler` | kultfilmler.net | Film | ✓ | — | — | ✓ | ✓ | ✓ |
| `LoveFilm` **beta** | lovefilmizle.net | Film | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `MinikaGO` | minikago.com.tr | Çocuk | — | — | — | — | — | — |
| `NetFilmizle` **beta** | netfilmizle.com | Film | — | — | — | — | — | — |
| `Onepacetr` | onepacetr.net | Anime | ✓ | — | — | — | ✓ | — |
| `SelcukFlix` **beta** | selcukflix.app | Dizi/Film | — | — | — | — | ✓ | ✓ |
| `SetFilmIzle` | setfilmizle.ltd | Dizi/Film | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `Sezonlukdizi` **beta** | sezonlukdizi.cc | Dizi | — | — | ✓ | ✓ | ✓ | ✓ |
| `ShowTV` **beta** | showtv.com.tr | Dizi | — | — | — | — | — | — |
| `SinemaCX` **beta** | sinemacc.com | Film | — | — | — | ✓ | ✓ | ✓ |
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

Bu tablo ve özellik matrisi **her zaman güncel tutulur**: her sağlayıcı değişikliğinde
`README.md`, `CHANGELOG.md` ve modül sürümü birlikte güncellenir.

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
  Yabancidizi/          78 modül
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
