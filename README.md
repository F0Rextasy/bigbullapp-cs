# BigBullApp — Cloudstream + Nuvio

Türkçe film, dizi, anime, çizgi film ve canlı TV kaynakları.
Cloudstream için `.cs3` eklentileri, Nuvio için plugin deposu + iki katalog addon'u.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-SDK%2035-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Cloudstream](https://img.shields.io/badge/Cloudstream-pre--release-1E88E5)](https://github.com/recloudstream/cloudstream)
[![Lisans](https://img.shields.io/badge/Lisans-MIT-green)](LICENSE)
[![Modül](https://img.shields.io/badge/mod%C3%BCl-92-blue)](providers)
[![X](https://img.shields.io/badge/X-@BIGBULLapp-000000?logo=x&logoColor=white)](https://x.com/BIGBULLapp)
[![Telegram](https://img.shields.io/badge/Telegram-BigBull__App-26A5E4?logo=telegram&logoColor=white)](https://t.me/bigbull_app)

## Hızlı kurulum

### Cloudstream (film, dizi, anime, canlı TV)

1. **Ayarlar → Eklentiler → Depo Ekle**
2. Adres: `https://raw.githubusercontent.com/F0Rextasy/bigbullapp-cs/master/repo.json`
   — ya da kısa kod: **`bigbullapp`**
3. Depoyu ekle → istediğin eklentiyi **İndir**
4. **Ayarlar → Extensions** listesinden sağlayıcıları aç

### Nuvio (film/dizi + canlı TV)

Nuvio'da plugin desteği yalnız **sideload** sürümlerde vardır (mağaza sürümünde Plugins bölümü yok).

1. **Ayarlar → Content & Discovery → Plugins → Add Repository → Refresh → Enable plugin providers globally**
2. İstediğin sağlayıcıları tek tek aç
3. **Addons → Add Addon** ile aşağıdaki üç adresi ekle:

| Ne için | Adres |
|---|---|
| Detay/meta (zorunlu) | `https://v3-cinemeta.strem.io/manifest.json` |
| Ana sayfa film/dizi satırları | `https://raw.githubusercontent.com/F0Rextasy/bigbullapp-cs/master/nuvio/addon/manifest.json` |
| Canlı TV (8 satır, 172 kanal) | `https://raw.githubusercontent.com/F0Rextasy/bigbullapp-cs/master/nuvio/addon-live/manifest.json` |

Nuvio plugin deposu: `https://raw.githubusercontent.com/F0Rextasy/bigbullapp-cs/master/nuvio/manifest.json`

## Nuvio'da hangi parça ne yapar

| İş | Sağlayan |
|---|---|
| Film/dizi arama ve oynatma | Plugin deposu (29 sağlayıcı) |
| Film/dizi detayı: özet, kadro, bölümler | **Cinemeta** |
| Ana sayfada film/dizi satırları | **BigBull TR Katalog** addon'u |
| Canlı TV satırları ve kanal oynatma | **BigBull Canlı TV** addon'u |

**Cinemeta neden gerekli?** Katalog addon'u yalnız liste verir (isim, poster, IMDb id'si). Detay
sayfasını Cinemeta, akışları pluginler verir. Canlı TV addon'u kendi meta'sını taşıdığı için canlı
kanallar için Cinemeta şart değildir. Canlı TV plugin olarak eklenemez: plugin sözleşmesi yalnız
film/dizi akışı döndürür, kanal kimliği taşımaz.

## Kurulumdan sonra 5 kontrol

1. Depo listelendi mi?
2. Cloudstream'de sağlayıcılar **Extensions** ekranından açıldı mı?
3. Nuvio'da **Enable plugin providers globally** açık mı?
4. Cinemeta ve iki addon eklendi mi?
5. Ana sayfa satırları eksikse Nuvio'yu tamamen kapatıp aç; addonları **Refresh** et.

## Sık sorulanlar

**Yayın açılmıyor / arama boş dönüyor.** Kaynak site değişmiş olabilir; güncellemeyi bekle veya
[issue aç](https://github.com/F0Rextasy/bigbullapp-cs/issues). Tek bir sağlayıcı bozuksa diğerleri
çalışmaya devam eder.

**Nuvio: "Bu içerik için meta sağlayan eklenti yok".** Cinemeta addon'u kurulu değil.

**Nuvio: ana sayfa satırları eksik/eski.** Addon → **Refresh**; olmazsa uygulamayı tamamen kapatıp
aç. Nuvio addon manifestini önbelleğe alır.

**Canlı kanal açılmıyor.** Kanal listesi günlük taranır; ölü kanallar ayıklanır, bölgesel kilitli
yayınlar açılmaz. Kanal adını bildir.

**Altyazı yok.** Eklenti ayarından **Altyazı dili**ni kontrol et (`Türkçe` seçiliyken yalnız Türkçe
altyazı geçer). Kaynakta altyazı yoksa üretilmez.

**Kalite düşük görünüyor.** Eklenti ayarından **Kalite üst sınırı**nı kontrol et; kaynak daha
yüksekse gizlenir.

**Nuvio'da Plugins bölümü yok.** Mağaza sürümü kullanıyorsun; plugin yalnız sideload sürümde var.

**Android TV / TV Box.** Cloudstream ve NuvioTV'de aynı adresler kullanılır; kumanda ile
**Add Repository** alanına yukarıdaki URL'yi yaz.

**Hata bildirimi.** [GitHub issue](https://github.com/F0Rextasy/bigbullapp-cs/issues) veya
[Telegram](https://t.me/bigbull_app). Hangi sağlayıcı, hangi içerik ve ne olduğunu yaz.

## Özellikler

- Arama → detay → bölüm → oynatma; site destekliyorsa sayfalama
- Öneriler, fragman, kadro, süre, puan, bölüm özeti — yalnız sitede gerçekten varsa
- Altyazı (VTT) ve kalite etiketleri
- Anime sitelerinde `TvType.Anime`
- Hata durumunda boş ekran yerine açıklayıcı mesaj ve issue bağlantısı

## Eklenti ayarları

Her Cloudstream eklentisinin kendi ayar ekranı vardır (**Ayarlar → Extensions → eklenti → dişli**):

- **Kalite üst sınırı**: Sınırsız / 1080p / 720p / 480p / 360p
- **Altyazı dili**: Tümü / Türkçe / İngilizce / Kapalı

## Canlı TV

- **Cloudstream:** tek sağlayıcı `CanliTV` — 159 kanal (seçilmiş HLS + iptv-org). "Doğrulanmış"
  yalnız HTTP 200 HLS adresi demektir; oynatma garantisi değildir.
- **Nuvio:** `BigBull Canlı TV` addon'u — 8 satır, 172 kanal, doğrudan HLS. Kanal listesi Cloudstream
  `CanliTV` kaynağından üretilir; ölü kanallar ayıklanır.
- Yeni kanal önerisi için site/kanal adını issue olarak bırak.

## Desteklenen Cloudstream eklentileri

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
| `AsyaLog` | asyalog.co | Asya dizi/film | ✓ | ✓ | — | — | ✓ | ✓ |
| `AsyaLuna` **pasif** | asyaluna.com | Dizi | — | — | — | — | — | — |
| `AsyaWatch` | asyawatch.com | Asya dizi/film | ✓ | ✓ | — | ✓ | — | ✓ |
| `ATV` | atv.com.tr | Dizi | — | — | — | — | — | — |
| `BelgeselX` | belgeselx.com | Belgesel | — | — | — | — | — | — |
| `BirAsyaDizi` | birasyadizi.cx | Dizi | — | — | — | — | — | — |
| `CanliDizi` | canlidizi.sbs | Dizi | — | — | — | — | — | — |
| `CanliTV` | iptv-org TR M3U + sabit HLS | Canlı TV | — | — | — | — | — | — |
| `Cizgimax` | cizgimax.online | Çizgi | ✓ | ✓ | — | — | — | ✓ |
| `CizgiSite` | www.cizgi.site | Dizi | — | — | — | — | — | — |
| `Ddizi` | ddizi.tel | Dizi | ✓ | — | — | — | — | — |
| `DDizimag` | ddizimag.icu | Dizi | — | — | — | — | — | — |
| `Deokwave` | deokwave.com | Anime | — | — | — | — | — | ✓ |
| `DiziAbi` | diziabi.net | Film | — | — | — | — | — | — |
| `Dizibaba` | dizibaba.net | Film | — | — | — | — | — | — |
| `DiziBal` | dizibal.org | Dizi/Film | ✓ | — | ✓ | ✓ | film | ✓ |
| `DiziBol` | dizibol.org | Dizi | — | — | — | — | — | — |
| `DiziBoxTv` **pasif** | diziboxtv.com | Dizi | — | — | — | — | — | — |
| `DiziCaN` | dizican.cc | Dizi | — | — | — | — | — | — |
| `DiziFilmIzle` | dizifilmizle.to | Film | — | — | — | — | — | — |
| `Dizifon` **pasif** | dizifon.cc (alan adı park) | Dizi | — | — | — | — | — | — |
| `Dizigecesi` | dizigecesi.com | Film | — | — | — | — | — | — |
| `Dizigom` | dizigom.news | Film | — | — | — | — | — | — |
| `Dizilab` | dizilab.cc | Dizi | — | — | — | — | — | — |
| `DiziLife` | dizi85.life | Dizi/Film | ✓ | ✓ | — | ✓ | film | ✓ |
| `Dizimanya` | dizimanya.com | Dizi | — | — | — | — | — | ✓ |
| `Dizirella` | dizirella.one | Dizi | — | — | — | — | — | — |
| `DiziSol` | dizisol.com | Film/Dizi | ✓ | — | — | — | ✓ | — |
| `Dizist` | dizist.live | Dizi | — | — | — | — | — | ✓ |
| `DiziYou` | diziyou.one | Dizi | — | — | ✓ | ✓ | — | ✓ |
| `Diziasia` | diziasia.com | Asya dizi | ✓ | ✓ | — | — | — | — |
| `Dizibox` | dizibox.live | Dizi | — | — | ✓ | ✓ | — | ✓ |
| `DiziboxIzle` | diziboxizle.com | Dizi | ✓ | — | ✓ | ✓ | — | ✓ |
| `Dizikorea3` | dizikorea3.com | Dizi/Film | ✓ | ✓ | ✓ | ✓ | film | ✓ |
| `Dizilla` | dizilla.la | Dizi | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `DiziMom` | ddizimom.net | Dizi | — | — | — | — | — | — |
| `DiziPal` | dizipal1595.com | Dizi/Film | — | ✓ | — | ✓ | ✓ | ✓ |
| `DiziPod` | dizipod.com | Dizi/Film | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `DMAX` | dmax.com.tr | Dizi | — | — | — | — | — | — |
| `DramaDizilerim` | dramadizilerim1.com | Dizi | ✓ | ✓ | — | — | — | ✓ |
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
| `HDFilmizleBest` | hdfilmizle.best | Film | — | — | — | — | — | — |
| `IzleFilm` | izlefilm.net | Film | ✓ | — | — | — | ✓ | ✓ |
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
| `Rectv` | a.psrectv80.xyz (imzalı API) | Film/Dizi | ✓ | — | — | — | ✓ | ✓ |
| `RoketDizi` | roketdizi.live | Dizi | ✓ | — | — | — | — | — |
| `SelcukFlix` | selcukflix.app | Dizi/Film | — | — | — | — | ✓ | ✓ |
| `SetFilmIzle` | setfilmizle.ltd | Dizi/Film | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `Sezonlukdizi` | sezonlukdizi.cc | Dizi | — | — | ✓ | ✓ | ✓ | ✓ |
| `ShowTV` | showtv.com.tr | Dizi | — | — | — | — | — | — |
| `SinemaCX` | sinemacc.com | Film | — | — | — | ✓ | ✓ | ✓ |
| `Sinemetrik` | sinemetrik.com | Film/Dizi | ✓ | — | — | ✓ | ✓ | ✓ |
| `Sinezy` | sinezy.si | Film | — | — | — | — | — | — |
| `Sinemakolik` | sinemakolik.com | Film | — | — | — | — | — | — |
| `SonAnime` | sonanime.com | Dizi | — | — | — | — | — | — |
| `StarTV` | startv.com.tr | Dizi | — | — | — | — | — | — |
| `TLCtr` | tlctv.com.tr | Dizi | ✓ | — | — | — | — | — |
| `TrDiziIzle` | trdiziizle.tv | Dizi | ✓ | — | — | — | — | — |
| `TurkAnimeTV` | turkanime.live | Anime | ✓ | — | — | — | — | — |
| `TurkceDizi` | turkcedizi.com | Film | — | — | — | — | — | ✓ |
| `TV8` | tv8.com.tr | Dizi | — | — | — | — | — | — |
| `UltraFilmizle` | ufilmizle.com | Film | — | — | — | — | — | — |
| `WebdramaTurkey2` | webdramaturkey2.com | Asya dizi/film | ✓ | — | — | ✓ | ✓ | — |
| `Weboo` | weboo.com.tr | Dizi | — | — | — | — | — | — |
| `WebteIzle` | webteizle.info | Film | ✓ | — | ✓ | ✓ | ✓ | — |
| `Yabancidizi` | ybncidizi.com | Dizi/Film | — | ✓ | — | ✓ | film | ✓ |
| `YesilCamTv` | yesilcamtv.com.tr | Film | — | — | — | — | — | — |

**Kapsam notu:** Depoda toplam **92 aktif modül** var; `AsyaLuna`, `DiziBoxTv`, `Dizifon`
`status = 0` ile pasif. İnatTV/İnatBox ve SineWix upstream sorunları nedeniyle kaldırıldı; düzeldiğinde
tekrar eklenir. Bu tablo ve özellik matrisi her sağlayıcı değişikliğinde güncellenir.

## Nuvio sağlayıcıları (29)

- **Anime (3):** `Anizium`, `AnimeciX`, `AsyaAnimeleri`
- **Dizi (12):** `Dizibox`, `Dizilla`, `DiziPal`, `SelcukFlix`, `Ddizi`, `WebdramaTurkey2`, `Dizikorea3`, `Sezonlukdizi`, `DiziYou`, `DiziBal`, `DiziMom`, `DiziPod`
- **Film (8):** `HDFilmCehennemi`, `FullHDFilmizlesene`, `SetFilmIzle`, `YesilCamTv`, `FilmMakinesi`, `WebteIzle`, `FilmModu`, `JetFilmizle`
- **Film & Dizi (3):** `PuhuTV`, `RecTV`, `Sinemetrik`
- **Belgesel & TV (3):** `BelgeselX`, `DMAX`, `TLCtr`

## Geliştiriciler

```bash
# Windows
set JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot
set ANDROID_HOME=C:\Android\Sdk
gradlew.bat make makePluginsJson

# Linux / macOS
JAVA_HOME=/path/to/jdk17 ./gradlew make makePluginsJson
```

Çıktılar: `providers/<Modül>/build/<Modül>.cs3` ve `build/plugins.json`.
Tek modül: `./gradlew Dizibox:make` · cihaza kurulum: `./gradlew Dizibox:deployWithAdb`

```
providers/<Modül>/        Cloudstream modülü (.cs3)
nuvio/providers/<id>.js   Nuvio plugin'i (tek dosya)
nuvio/addon/              Film/dizi satırları addon'u
nuvio/addon-live/         Canlı TV addon'u
```

- [`CONTRIBUTING.md`](CONTRIBUTING.md) — yeni eklenti, kod kuralları, doğrulama
- [`CHANGELOG.md`](CHANGELOG.md) — sürüm notları
- `AGENTS.md` — ajan/otomasyon notları

Davranış değişikliği = modül `version` artışı + CHANGELOG kaydı. Sürüm artmadan Cloudstream
güncelleme göstermez.

## Yasal

Bu depo yalnızca istemci tarafı kod içerir; içerik barındırmaz. Hedef sitelerin kullanım koşulları
ve bulunduğunuz ülkenin telif mevzuatı sizin sorumluluğunuzdadır. Kaynak siteler üçüncü taraflara
aittir ve önceden bildirim olmadan değişebilir.

## Lisans

Bu depodaki kod **MIT** lisanslıdır — [`LICENSE`](LICENSE).
