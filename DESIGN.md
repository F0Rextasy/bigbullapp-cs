# BigBull QA Web Tasarımı

Bu doküman, `tools/jvm-runner/web/` altındaki yerel QA arayüzünün tasarım
sistemini tanımlar. Yeni sayfalar bu sistemi kullanır; yeni renk, gölge veya
bileşen dili eklenmez.

## Kaynak dosyalar

| Dosya | Rol |
|---|---|
| `tools/jvm-runner/web/index.html` | Eski tasarımlı sağlayıcı konsolu (Ana Sayfa, Ara, Kitaplık, Ayarlar) |
| `tools/jvm-runner/web/design.css` | Ortak tasarım sistemi: tokenlar, shell, kart, tablo, buton, badge |
| `tools/jvm-runner/web/auto.html` | Auto Update sayfası; aynı shell ve bileşenler |
| `tools/jvm-runner/web/add.html` | Site Ekle sayfası; aynı shell ve bileşenler |

`design.css`, eski `index.html` içindeki tasarımın birebir çıkarılmış halidir.
Yeni sayfalar `index.html`'in tasarımını değiştirmez; yalnızca aynı tokenları
kullanır.

## Kimlik

- Görünüm: koyu/açık "Dusk" teması, soğuk karbon zemin, cyan-magenta vurgu.
- Dil: teknik konsol; süsleme yok, yoğun bilgi ve net durum.
- Yerleşim: sabit üst bar + sabit sol menü + `shell-panel` içerik paneli.
- Tipografi: Inter/system sans; sayılar `tabular-nums`; kod ve loglar monospace.

## Tokenlar

Renk ve ölçüler `design.css` içindeki CSS değişkenlerinden gelir. Yeni sayfada
sabit hex renk kullanılmaz.

### Yüzeyler

| Token | Kullanım |
|---|---|
| `--bg` | Sayfa zemini |
| `--panel` | `shell-panel` |
| `--card` | Kart ve tablo zemini |
| `--raised` | İkincil buton / etiket |
| `--line-1`, `--line-2` | İnce ve belirgin kenarlık |
| `--hover`, `--press` | Etkileşim durumları |

### Metin

| Token | Kullanım |
|---|---|
| `--ink-1` | Ana metin |
| `--ink-2` | Açıklama |
| `--ink-3` | İkincil / pasif |
| `--ink-inverse` | Vurgu butonu üstü metin |

### Durum

| Token | Kullanım |
|---|---|
| `--ok` / `--ok-soft` | PASS, tamam, düzeldi |
| `--warn` / `--warn-soft` | Uyarı, link değişimi |
| `--bad` / `--bad-soft` | Hata, durdu |
| `--live` | Canlı / çalışıyor |
| `--accent`, `--accent-gradient` | Ana eylem, aktif sayfa |

### Ölçü

`--space-2xs`, `--space-xs`, `--space-sm`, `--space-md`, `--space-lg`,
`--space-xl`; yarıçap için `--r-control`, `--r-card`, `--r-panel`; süre için
`--dur-1`, `--dur-2`, `--dur-3`, `--ease`.

## Shell

Her sayfa aynı iskeleti kullanır:

```html
<header class="topbar">…</header>
<nav class="sidebar bottomnav" id="sidebarNav">…</nav>
<main class="shell-panel">
  <div class="shell-content">
    <section class="view">
      <div class="page-header">…</div>
      …
    </section>
  </div>
</main>
```

- Üst bar: marka, `/`, sayfa adı, durum chip'i, tema butonu.
- Sol menü: `Gözlem` (Sağlayıcılar) ve `Yönetim` (Auto Update, Site Ekle).
- İçerik: `page-header` + `page-title` + `page-description`; eylemler `page-actions`.
- Mobil: `sidebarToggle` + `drawer-scrim`; `data-open` ile açılır.

## Bileşenler

| Sınıf | Kullanım |
|---|---|
| `omp-card` | Kart/kutu; başlık için `card-header`, `card-title`, `card-description` |
| `btn` | Standart buton; ana eylem `data-variant="accent"` |
| `ghost` | Sessiz ikincil eylem |
| `chip`, `badge` | Durum etiketi; `data-tone="ok|warn|bad|accent"` |
| `live-chip`, `dot` | Canlı/çalışıyor göstergesi; nokta `data-tone`, `data-pulse` |
| `table-wrap` + `table` | Veri tablosu |
| `input`, `select` | Form alanları |
| `auto-metrics`, `auto-metric` | Özet metrik kutuları |
| `auto-grid`, `auto-side` | İçerik + yan panel yerleşimi |
| `auto-console`, `auto-list`, `auto-changes` | Konsol, olay listesi, fark tablosu |
| `auto-checks` | Adım listesi |
| `code-block` | JSON / log çıktısı |

## Durumlar

- Yükleniyor: `empty` metni yerine gerçek işlem durumu; boş buton yok.
- Boş: açıklayıcı tek satır (`auto-empty` veya `empty`).
- Hata: kırmızı `badge[data-tone="bad"]` + ayrıntı metni.
- Çalışıyor: `live-chip` + `dot[data-tone="live"]`; butonlar kilitli.
- Tamam: `badge[data-tone="ok"]`; sonuç ve süre görünür.

## Kurallar

1. Yeni sayfa `design.css`'i linkler; kendi renk paletini kurmaz.
2. Her buton gerçek bir istek/eylem çalıştırır; boş veya dekoratif buton yoktur.
3. Her işlemin çıktısı aynı sayfada görünür: konsol, tablo veya durum etiketi.
4. Hata mesajı kullanıcıya görünür; sessizce yutulmaz.
5. Eski `index.html` tasarımı değiştirilmez; yeni sayfalar aynı shell'i kullanır.
6. Metin Türkçe, kod tanımlayıcıları İngilizce.
7. Yeni özellik eklenirse `DESIGN.md` ve ilgili sayfa birlikte güncellenir.

## Auto Update yerleşimi

- `auto-metrics`: toplam / PASS / hata / son tarama.
- Sol: canlı konsol; sağ: push/commit, Telegram, hata.
- Alt: önceki taramaya göre değişiklik tablosu.
- Boş durumlar gerçek mesajla doldurulur; “yükleniyor” iskeleti yok.

## Site Ekle yerleşimi

- Sol: URL, sorgu, modül adı; Keşif / Test / Onayla ve commit.
- Sağ: 4 adımlı durum + bot konsolu.
- Alt: keşif JSON çıktısı.
- Commit yalnızca açık kullanıcı onayıyla çalışır.
