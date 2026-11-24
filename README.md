# BigBullApp — Cloudstream + Nuvio

Türkçe film, dizi, anime, çizgi film ve canlı TV kaynakları.
Cloudstream için `.cs3` eklentileri, Nuvio için **111 sağlayıcılı plugin deposu** + **BigBullApp Katalog** + **BigBull Canlı TV** addon'u.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-SDK%2035-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Cloudstream](https://img.shields.io/badge/Cloudstream-pre--release-1E88E5)](https://github.com/recloudstream/cloudstream)
[![Modül](https://img.shields.io/badge/mod%C3%BCl-113-blue)](providers)
[![Nuvio](https://img.shields.io/badge/Nuvio-111%20plugin%20%2B%20Canl%C4%B1%20TV-7C3AED)](nuvio/manifest.json)
[![Lisans](https://img.shields.io/badge/Lisans-MIT-green)](LICENSE)
[![X](https://img.shields.io/badge/X-@BIGBULLapp-000000?logo=x&logoColor=white)](https://x.com/BIGBULLapp)
[![Telegram](https://img.shields.io/badge/Telegram-BigBull__App-26A5E4?logo=telegram&logoColor=white)](https://t.me/bigbull_app)
[![Kreosus](https://img.shields.io/badge/Kreosus-Destek%20Ol-E91E63)](https://kreosus.com/f0rextasy)

---

## Hızlı Kurulum

### 1. Cloudstream (Film, Dizi, Anime, Canlı TV)

1. **Ayarlar → Eklentiler → Depo Ekle**
2. Adres olarak `https://raw.githubusercontent.com/F0Rextasy/bigbullapp-cs/master/repo.json` gir — ya da kısa kod **`bigbullapp`** yaz.
3. Depoyu ekle → istediğin eklentileri **İndir**.
4. **Ayarlar → Extensions** altından eklentilerin açık olduğundan emin ol.

### 2. Nuvio (Plugin Deposu + Katalog + Canlı TV)

> **Önemli:** Nuvio'da plugin desteği yalnızca **sideload** sürümlerde bulunur (mağaza sürümünde *Plugins* menüsü yoktur).

1. **Ayarlar → Content & Discovery → Plugins → Add Repository** ile plugin deposunu ekle → **Refresh** → **Enable plugin providers globally** seçeneğini aç.
2. İstediğin sağlayıcıları aç.
3. **Addons → Add Addon** ekranından ana sayfa kataloglarını ve Canlı TV'yi ekle:

| Bileşen | Ne sağlar? | Adres |
|---|---|---|
| **Plugin Deposu** | Nuvio için 111 film, dizi, anime ve TV sağlayıcısı | `https://raw.githubusercontent.com/F0Rextasy/bigbullapp-cs/master/nuvio/manifest.json` |
| **BigBullApp Katalog** | Ana sayfa film ve dizi satırları | `https://raw.githubusercontent.com/F0Rextasy/bigbullapp-cs/master/nuvio/addon/manifest.json` |
| **BigBull Canlı TV** | Canlı TV (8 satır, 151 kanal) | `https://raw.githubusercontent.com/F0Rextasy/bigbullapp-cs/master/nuvio/addon-live/manifest.json` |

### Nuvio'da Hangi Parça Ne Yapar?

| İş | Sağlayan |
|---|---|
| Film/dizi arama ve oynatma | Plugin deposu (111 sağlayıcı; 111 aktif) |
| Özet, kadro, afiş ve bölüm listesi | **Zorunlu metadata addon'u**: Cinemeta (`https://v3-cinemeta.strem.io/manifest.json`) veya AIOMetadata/TMDB |
| Ana sayfada film/dizi satırları | **BigBullApp Katalog** addon'u |
| Canlı TV satırları ve akışları | **BigBull Canlı TV** addon'u |

> **Meta kaynağı zorunludur:** Katalog addon'u yalnızca başlık, poster ve IMDb kimliği verir; detay sayfası, üst afiş ve oynatma eşleşmesi için en az bir metadata addon'u (Cinemeta / AIOMetadata) kurulu olmalıdır. Yerleşik TMDB zenginleştirmesi tek başına yeterli değildir; kurulmazsa Nuvio *"No addon provides meta for this content"* hatası verir. Canlı TV addon'u kendi meta verisini taşır.

---

## Desteklenen Sağlayıcılar

`✅` aktif · `✅ Addon` Nuvio Canlı TV addon'u · `—` yok · `✓` özellik var · `film` yalnız filmlerde

| Sağlayıcı | Kaynak | Tür | Cloudstream | Nuvio | Sayfalama | Öneri | Fragman | Kadro | Süre | Puan |
|---|---|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| `AniArsiv` | aniarsiv.com | Dizi | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `AnimeciX` | animecix.tv | Anime | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `AnimeTR` | animetr.co | Anime | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `Animeler` | animeler.live | Anime | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `Anizm` | turkanime.info | Anime | ✅ | ✅ | ✓ | — | ✓ | — | — | ✓ |
| `Anizium` | anizium.co | Anime | ✅ | ✅ | ✓ | ✓ | — | — | ✓ | ✓ |
| `AsyaAnimeleri` | asyaanimeleri.top | Anime | ✅ | ✅ | ✓ | ✓ | — | — | ✓ | ✓ |
| `AsyaDizi` | asyadizi.com | Dizi | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `Asyadizileriizle` | asyadizileriizle.com | Dizi | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `AsyaFanatikleri` | asyafanatikleri.vip | Asya dizi/film | ✅ | ✅ | ✓ | ✓ | ✓ | — | ✓ | ✓ |
| `AsyaMax` | asyamax.com | Asya dizi/film | ✅ | ✅ | ✓ | — | — | — | — | — |
| `AsyaWatch` | asyawatch.com | Asya dizi/film | ✅ | ✅ | ✓ | ✓ | — | — | — | ✓ |
| `ATV` | atv.com.tr | Dizi | ✅ | ✅ | ✓ | — | — | — | — | — |
| `BelgeselX` | belgeselx.com | Belgesel | ✅ | ✅ | — | — | — | — | — | — |
| `BiCaps` | bicaps.live | Film | ✅ | ✅ | — | — | ✓ | ✓ | — | ✓ |
| `BirAsyaDizi` | birasyadizi.cx | Dizi | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `CanliDizi` | canlidizi.sbs | Dizi | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `CanliTV` | iptv-org TR M3U + sabit HLS | Canlı TV | ✅ | ✅ Addon | ✓ | — | — | — | — | — |
| `Cizgimax` | cizgimax.online | Çizgi | ✅ | ✅ | ✓ | ✓ | — | — | — | ✓ |
| `CizgiSite` | cizgi.site | Dizi | ✅ | ✅ | ✓ | — | — | — | — | — |
| `CizgiVeDizi` | cizgivedizi.net | Çizgi/Anime/Dizi/Film | ✅ | — | ✓ | ✓ | — | — | — | — |
| `Ddizi` | ddizi.tel | Dizi | ✅ | ✅ | ✓ | — | — | — | — | — |
| `DDizimag` | ddizimag2.icu | Dizi | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `Deokwave` | deokwave.com | Anime | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `DiziAbi` | diziabi.net | Film | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `Dizibaba` | dizibaba.icu | Film | ✅ | ✅ | — | — | — | — | — | — |
| `DiziBal` | dizibal.org | Dizi/Film | ✅ | ✅ | ✓ | — | ✓ | ✓ | film | ✓ |
| `DiziBol` | dizibol.org | Dizi | ✅ | ✅ | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `DiziBoxTv` | diziboxtv.com | Dizi | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `DiziCaN` | dizican.cc | Dizi | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `DiziFilmIzle` | dizifilmizle.to | Film | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `Dizifon` | dizifon.cc | Dizi | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `Dizigecesi` | dizigecesi.com | Film | ✅ | ✅ | — | — | — | — | — | — |
| `Dizigom` | dizigom.news | Film | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `Diziizle` | diziizle2.icu | Dizi | ✅ | ✅ | — | — | ✓ | ✓ | — | ✓ |
| `DiziKutusu` | dizikutusu.com | Dizi | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `Dizilab` | dizilab.cc | Dizi | ✅ | ✅ | — | — | — | — | — | — |
| `DiziLife` | dizi87.life | Dizi/Film | ✅ | ✅ | ✓ | ✓ | — | — | film | ✓ |
| `Dizimanya` | dizimanya.com | Dizi | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `Dizirella` | dizirella.one | Dizi | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `DiziSol` | dizisol.com | Film/Dizi | ✅ | ✅ | — | — | — | — | — | ✓ |
| `Dizist` | dizist.live | Dizi | ✅ | ✅ | ✓ | — | — | ✓ | — | ✓ |
| `DiziYou` | diziyou.love | Dizi | ✅ | ✅ | ✓ | — | ✓ | — | — | ✓ |
| `Diziasia` | diziasia.com | Asya dizi | ✅ | ✅ | ✓ | ✓ | — | — | — | — |
| `Dizibox` | dizibox.live | Dizi | ✅ | ✅ | ✓ | — | ✓ | — | — | ✓ |
| `DiziboxIzle` | diziboxizle.com | Dizi | ✅ | ✅ | ✓ | — | ✓ | — | — | ✓ |
| `Dizikorea3` | dizikorea3.com | Dizi/Film | ✅ | ✅ | ✓ | ✓ | ✓ | — | film | ✓ |
| `Dizilla` | dizilla.la | Dizi | ✅ | ✅ | ✓ | ✓ | ✓ | — | ✓ | ✓ |
| `DiziMom` | ddizimom.icu | Dizi | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `DiziPal` | dizipal1597.com | Dizi/Film | ✅ | ✅ | ✓ | ✓ | — | ✓ | ✓ | ✓ |
| `DiziPod` | dizipod.com | Dizi/Film | ✅ | ✅ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `DMAX` | dmax.com.tr | Dizi | ✅ | ✅ | ✓ | — | — | — | — | — |
| `DramaDizilerim` | dramadizilerim1.com | Dizi | ✅ | ✅ | ✓ | ✓ | — | — | — | ✓ |
| `FilmDozu` | filmdozu.com | Film | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `FilmEkseni` | filmekseni.pro | Film | ✅ | ✅ | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `Filmgecesi` | filmgecesi.vip | Film/Dizi | ✅ | ✅ | ✓ | — | — | — | ✓ | ✓ |
| `FilmMakinesi` | filmmakinesi.co | Film | ✅ | ✅ | ✓ | — | — | — | ✓ | ✓ |
| `Filmizyon` | filmizyon.net | Film | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `FilmKovasi` | filmkovasi.co | Film | ✅ | ✅ | ✓ | — | — | ✓ | — | — |
| `FilmModu` | filmmodu.icu | Film | ✅ | ✅ | — | — | ✓ | ✓ | ✓ | ✓ |
| `FullHDFilm` | fullhdfilmizlebox.vip | Film | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `FullHDFilmizlesene` | fullhdfilmizlesene.now | Film | ✅ | ✅ | ✓ | — | ✓ | — | — | ✓ |
| `HDFilmCehennemi` | hdfilmcehennemi.nl | Film | ✅ | ✅ | ✓ | — | — | — | ✓ | ✓ |
| `HDFilmCehennemi2` | hdfilmcehennemi2.biz | Film | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `HDFilmDelisi` | hdfilmdelisi1.com | Film | ✅ | ✅ | ✓ | — | — | — | ✓ | ✓ |
| `HDFilmDiziIzle` | hdfilmdiziizle.com | Dizi | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `HDFilmizle` | hdfilmizle.ink | Dizi/Film | ✅ | ✅ | — | — | ✓ | ✓ | ✓ | ✓ |
| `HDFilmizleBest` | hdfilmizle.best | Film | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `Izle7` | izle7.com | Dizi/Film | ✅ | ✅ | — | — | — | — | ✓ | — |
| `IzleFilm` | izlefilm.net | Film | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `JetFilmizle` | jetfilmizle.app | Film | ✅ | ✅ | — | — | ✓ | — | — | ✓ |
| `KanalD` | kanald.com.tr | Dizi | ✅ | ✅ | ✓ | — | — | — | — | — |
| `KoreanTurk` | koreanturk.tv | Dizi | ✅ | ✅ | ✓ | — | — | — | — | — |
| `Kultfilmler` | kultfilmler.net | Film | ✅ | ✅ | ✓ | — | — | ✓ | ✓ | ✓ |
| `LoveFilm` | lovefilmizle.net | Film | ✅ | ✅ | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `MinikaCocuk` | minikacocuk.com.tr | Çocuk/Dizi | ✅ | ✅ | ✓ | — | — | — | — | — |
| `MinikaGO` | minikago.com.tr | Çocuk | ✅ | ✅ | — | — | — | — | — | — |
| `NetFilmizle` | netfilmizle.com | Film | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `NowTV` | nowtv.com.tr | Dizi/Film | ✅ | ✅ | ✓ | — | — | — | — | — |
| `Onepacetr` | onepacetr.net | Anime | ✅ | ✅ | ✓ | — | — | — | — | — |
| `OnlineDizi` | onlinedizi.sbs | Dizi | ✅ | ✅ | ✓ | — | — | ✓ | — | — |
| `P720pizle` | 720pizle.my | Film | ✅ | ✅ | ✓ | — | — | ✓ | ✓ | ✓ |
| `PuhuTV` | puhutv.com | Dizi/Film | ✅ | ✅ | ✓ | — | — | ✓ | film | — |
| `Rectv` | a.psrectv80.xyz (imzalı API) | Film/Dizi | ✅ | ✅ | ✓ | — | ✓ | — | ✓ | ✓ |
| `RoketDizi` | roketdizi.life | Dizi | ✅ | ✅ | ✓ | ✓ | — | — | ✓ | ✓ |
| `SelcukFlix` | selcukflix.app | Dizi/Film | ✅ | ✅ | ✓ | — | — | ✓ | ✓ | ✓ |
| `SetFilmIzle` | setfilmizle.ltd | Dizi/Film | ✅ | ✅ | ✓ | ✓ | ✓ | — | ✓ | ✓ |
| `Sezonlukdizi` | sezonlukdizi.cc | Dizi | ✅ | ✅ | ✓ | — | ✓ | — | ✓ | ✓ |
| `ShowTV` | showtv.com.tr | Dizi | ✅ | ✅ | ✓ | — | — | — | — | — |
| `SinemaCX` | sinemacc.com | Film | ✅ | ✅ | — | — | — | ✓ | ✓ | ✓ |
| `Sinemetrik` | sinemetrik.com | Film/Dizi | ✅ | ✅ | ✓ | — | — | — | ✓ | ✓ |
| `Sinezy` | sinezy.si | Film | ✅ | ✅ | — | — | — | — | — | — |
| `Sinemakolik` | sinemakolik.com | Film | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `SonAnime` | sonanime.com | Dizi | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `StarTV` | startv.com.tr | Dizi | ✅ | ✅ | ✓ | — | — | — | — | — |
| `TLCtr` | tlctv.com.tr | Dizi | ✅ | ✅ | ✓ | — | — | — | — | — |
| `Toruko` | turkceanime.com | Anime | ✅ | ✅ | ✓ | — | — | — | ✓ | ✓ |
| `TrDiziIzle` | trdiziizle.tv | Dizi | ✅ | ✅ | ✓ | — | ✓ | ✓ | — | ✓ |
| `TRT1` | trt1.com.tr | Dizi | ✅ | ✅ | ✓ | — | — | — | — | — |
| `TurkAnimeTV` | turkanime.live | Anime | ✅ | ✅ | — | — | — | ✓ | — | ✓ |
| `TurkceDizi` | turkcedizi.com | Film | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `TV2` | tv2.com.tr | Dizi/Film | ✅ | ✅ | ✓ | — | — | — | — | — |
| `TV8` | tv8.com.tr | Dizi | ✅ | ✅ | ✓ | — | — | — | — | — |
| `TvDiziler` | tvdiziler.tv | Dizi | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `UltraFilmizle` | ultraiz.net | Film | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `VenusFilmizle` | venusfilmizle.com | Film | ✅ | ✅ | ✓ | — | — | — | ✓ | ✓ |
| `WebdramaTurkey2` | webdramaturkey2.com | Asya dizi/film | ✅ | ✅ | ✓ | — | — | ✓ | ✓ | — |
| `WebFilmizle` | webfilmizle.com | Film | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `Weboo` | weboo.com.tr | Dizi | ✅ | ✅ | ✓ | — | — | — | — | ✓ |
| `WebteIzle` | webteizle.info | Film | ✅ | ✅ | ✓ | — | ✓ | — | ✓ | — |
| `Yabancidizi` | ybncidizi.com | Dizi/Film | ✅ | ✅ | ✓ | ✓ | — | — | film | ✓ |
| `YerliDiziler` | yerlidiziler.com.tr | Yerli Dizi | ✅ | ✅ | ✓ | — | — | — | — | — |
| `YesilCamTv` | yesilcamtv.com.tr | Film | ✅ | ✅ | ✓ | — | — | — | — | ✓ |

**VPN notu:** `FilmModu` WARP/VPN veya HTTP proxy ile çalışır.

**Kapsam notu:** Depoda doğrulanmış **113 modül** (`status = 1`) var.
Nuvio tarafında **111 eklenti** ve **BigBull Canlı TV** addon'u aktiftir (`CizgiVeDizi` istemci kısıtı nedeniyle yalnızca Cloudstream'dedir).
`AsyaBox`, `AsyaLog`, `AsyaLuna`, `DiziModu`, `WFilmIzle`, İnatTV/İnatBox ve SineWix upstream sorunları nedeniyle kaldırıldı; düzeldiğinde tekrar eklenir.
Bu tablo, platform desteği ve özellik matrisi her sağlayıcı değişikliğinde güncellenir.

---

## Canlı TV

- **Cloudstream:** Tek sağlayıcı `CanliTV` (seçilmiş HLS + iptv-org TR). *"Doğrulanmış"* yalnızca HTTP 200 HLS yanıtını ifade eder; bölgesel kısıtlı yayınlar açılmayabilir.
- **Nuvio:** `BigBull Canlı TV` addon'u — 8 satır, 151 kanal, doğrudan HLS (`isLive` akışı nedeniyle ayrı addon olarak sunulur).

---

## Özellikler ve Eklenti Ayarları

- Arama → detay → bölüm → oynatma; site destekliyorsa sayfalama
- Öneriler, fragman, kadro, süre, puan ve bölüm özeti (yalnız kaynak sitede gerçekten varsa)
- Altyazı (VTT) ve kalite etiketleri; anime sitelerinde `TvType.Anime`
- **Cloudstream eklenti ayarları** (**Ayarlar → Extensions → Eklenti → Dişli**):
  - **Kalite üst sınırı:** Sınırsız / 1080p / 720p / 480p / 360p
  - **Altyazı dili:** Tümü / Türkçe / İngilizce / Kapalı

---

## Kurulum Kontrolü ve Sık Sorulanlar

**Kurulumdan sonra 5 hızlı kontrol:**
1. Depo listede görünüyor mu?
2. Cloudstream'de sağlayıcılar **Extensions** ekranından açık mı?
3. Nuvio'da **Enable plugin providers globally** açık mı?
4. İki Nuvio addon'u (**TR Katalog** + **Canlı TV**) ve bir **metadata addon'u** (Cinemeta / AIOMetadata) kurulu mu?
5. Ana sayfa satırları görünmüyorsa Nuvio'yu tamamen kapatıp aç ve addon'ları **Refresh** et.

<details>
<summary><b>Sık karşılaşılan sorunlar ve çözümleri</b></summary>

- **Yayın açılmıyor / arama boş dönüyor:** Kaynak site alan adı veya yapısı değişmiş olabilir; güncellemeyi bekle veya [issue aç](https://github.com/F0Rextasy/bigbullapp-cs/issues). Tek bir sağlayıcı bozuksa diğerleri çalışmaya devam eder.
- **Nuvio — bir dizide tüm sağlayıcılar çıkıyor / alakasız video açılıyor:** Addons → **BigBullApp Katalog** → kaldır → manifest adresini yeniden ekle → **Refresh**; plugin deposunu da **Refresh** et ve metadata addon'unun kurulu olduğundan emin ol.
- **Nuvio — "Bu içerik için meta sağlayan eklenti yok":** Cinemeta (`https://v3-cinemeta.strem.io/manifest.json`) veya AIOMetadata ekle.
- **Nuvio — satır sırası karışık / toplu satırlar altta:** **Ayarlar → Home / Katalog düzenle** ekranından **Son Eklenen/Popüler** satırlarını yukarı sürükle veya addon'u kaldırıp yeniden ekle.
- **Nuvio — Plugins bölümü görünmüyor:** Mağaza sürümü yerine **sideload** Nuvio APK kullanmalısın.
- **Canlı kanal açılmıyor:** Kanal listesi düzenli taranır; ölü bağlantılar ayıklanır, bölgesel kilitli yayınlar açılmaz.
- **Altyazı yok / kalite düşük görünüyor:** Cloudstream eklenti dişli simgesinden **Altyazı dili** ve **Kalite üst sınırı** ayarlarını kontrol et.
- **Android TV / TV Box:** Cloudstream ve NuvioTV'de aynı manifest adresleri kullanılır.
- **Hata bildirimi:** [GitHub Issues](https://github.com/F0Rextasy/bigbullapp-cs/issues) veya [Telegram](https://t.me/bigbull_app) üzerinden sağlayıcı adını, içeriği ve hatayı paylaş.

</details>

---

## Geliştiriciler

```bash
# Windows
set JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot
set ANDROID_HOME=C:\Android\Sdk
gradlew.bat make makePluginsJson

# Linux / macOS
JAVA_HOME=/path/to/jdk17 ./gradlew make makePluginsJson
```

- Çıktılar: `providers/<Modül>/build/<Modül>.cs3` ve `build/plugins.json`
- Tek modül: `./gradlew Dizibox:make` · Cihaza kurulum: `./gradlew Dizibox:deployWithAdb`

```
providers/<Modül>/        Cloudstream modülü (.cs3)
nuvio/providers/<id>.js   Nuvio plugin'i (tek dosya)
nuvio/addon/              Film/dizi satırları katalog addon'u
nuvio/addon-live/         Canlı TV addon'u
```

- [`CONTRIBUTING.md`](CONTRIBUTING.md) — yeni eklenti, kod kuralları, doğrulama
- [`CHANGELOG.md`](CHANGELOG.md) — sürüm notları
- `AGENTS.md` — ajan/otomasyon notları

Davranış değişikliği = modül `version` artışı + `CHANGELOG.md` kaydı. Sürüm artmadan Cloudstream güncelleme göstermez.

---

## Destek Ol

Bu depodaki tüm eklentiler ve topluluk araçları ücretsiz ve reklamsız sunulur. Bakım, test ve otomasyon altyapısının sürmesine katkıda bulunmak isterseniz [**Kreosus üzerinden destek olabilirsiniz**](https://kreosus.com/f0rextasy). Sürüm notları [Telegram kanalımızla](https://t.me/bigbull_app) eş zamanlı olarak Kreosus profilimizde de paylaşılır; destek notuna Telegram `@kullanıcıadını` yazan destekçilere grubumuzda seviyesine uygun üye etiketi otomatik olarak tanımlanır.

## Yasal & Lisans

Bu depo yalnızca istemci tarafı kod içerir; içerik barındırmaz. Hedef sitelerin kullanım koşulları ve bulunduğunuz ülkenin telif mevzuatı sizin sorumluluğunuzdadır. Kaynak siteler üçüncü taraflara aittir ve önceden bildirim olmadan değişebilir.

Kod **MIT** lisanslıdır — [`LICENSE`](LICENSE).
