# BigBullApp Telegram Gatekeeper & Tam Moderasyon Botu (v2.0)

MissRose'a ihtiyaç bırakmayan, Cloudflare Workers üzerinde **%100 ücretsiz, sunucusuz (serverless), 7/24 kesintisiz** çalışan Telegram doğrulama, anti-spam ve grup yönetim botu.

## Özellikler (MissRose Tam Karşılığı)

### 1. Güvenlik & Doğrulama (Captcha)
- **4 Şıklı Matematik Captcha:** Tek butona otomatik tıklayan userbot'ları engeller (Örn: `7 + 5 = ?`).
- **Yanlış Cevap Koruması:** Yanlış şıkka basan hesabı otomatik gruptan çıkarır (`kick` — tekrar katılıp deneyebilir).
- **15 Dakika Süre Sınırı (Timeout):** Doğrulama yapmayanları ve eski captcha mesajlarını gruptan otomatik temizler.
- **Tam Medya İzinleri (Bot API 6.5+):** Doğrulanan üyenin fotoğraf, ekran görüntüsü, video ve dosya gönderme izinlerini eksiksiz açar.
- **Çift Giriş Algılama:** Hem normal girişleri (`new_chat_members`) hem de büyük gruplardaki davet linki girişlerini (`chat_member`) yakalar.
- **Servis Mesajı Temizliği:** *"X gruba katıldı"* ve *"X gruptan ayrıldı"* mesajlarını otomatik siler.

### 2. Otomatik Koruma
- **Anti-Bot (`/lock bot`):** Admin olmayan bir üye gruba bot eklerse botu anında banlar.
- **Anti-Reklam:** Admin olmayanların paylaştığı yabancı Telegram grup/kanal davet linklerini (`t.me/...`) anında siler ve uyarı (`warn`) verir.
- **Anti-Flood:** 6 saniye içinde 6'dan fazla mesaj gönderen spam hesapları otomatik **1 saat** susturur.

### 3. Admin Moderasyon Komutları *(Mesaja yanıt vererek / Reply ile)*
| Komut | Açıklama |
| :--- | :--- |
| `/ban [sebep]` | Kullanıcıyı kalıcı yasaklar ve mesajını siler. |
| `/unban` | Kullanıcının yasağını kaldırır. |
| `/kick` | Kullanıcıyı gruptan çıkarır (tekrar girebilir) ve mesajını siler. |
| `/mute [10m\|1h\|1d]` | Kullanıcıyı belirtilen süre boyunca (veya süresiz) susturur. |
| `/unmute` | Kullanıcının susturmasını kaldırır ve tüm medya izinlerini açar. |
| `/warn [sebep]` | Kullanıcıyı uyarır (**3/3** uyarıya ulaşanı otomatik banlar). |
| `/unwarn` | Kullanıcının uyarı sayacını sıfırlar. |
| `/sil` veya `/del` | Yanıtlanan mesajı ve komut mesajını sessizce siler. |
| `/pin` / `/unpin` | Yanıtlanan mesajı sabitler veya sabitlemesini kaldırır. |

### 4. Herkese Açık Topluluk Komutları
- `/kurallar` veya `/rules` — Topluluk kurallarını gösterir.
- `/linkler` veya `/site` — `bigbullapp.com` ve GitHub bağlantılarını gösterir.
- `/id` — Kullanıcı, Grup ve Forum Konu (Thread) ID bilgisini gösterir.

---

## 3 Adımda Kurulum

### Adım 1: Cloudflare Worker Oluşturma
1. [Cloudflare Dashboard](https://dash.cloudflare.com)'a giriş yapın.
2. Sol menüden **Workers & Pages** -> **Create application** -> **Create Worker** butonuna tıklayın (veya GitHub reponuzu doğrudan bağlayın).
3. [worker.js](worker.js) içeriğini yapıştırıp **Deploy** deyin.

### Adım 2: Bot Token'ını Tanımlama
Worker sayfanızda **Settings** -> **Variables and Secrets** sekmesinden:
- **Variable name:** `TELEGRAM_BOT_TOKEN`
- **Value:** BotFather token'ınız (*Secret / Encrypt* olarak kaydedin).

### Adım 3: Webhook'u Bağlama
> **Önemli:** Büyük gruplarda yeni üye girişlerini (`chat_member`) yakalamak için `allowed_updates` parametresi şarttır.

**A) Python Aracı ile (Önerilen):**
```bash
python setup_webhook.py --token <BOT_TOKEN> --set https://<WORKER_ADRESINIZ>.workers.dev
```

**B) Tarayıcıdan Tek Tıkla:**
```text
https://api.telegram.org/bot<BOT_TOKEN>/setWebhook?url=https://<WORKER_ADRESINIZ>.workers.dev&drop_pending_updates=true&allowed_updates=%5B%22message%22,%22callback_query%22,%22chat_member%22,%22chat_join_request%22%5D
```

---

## Telegram Grup İzinleri
MissRose'u gruptan çıkardıktan sonra kendi botunuza **Yönetici (Admin)** olarak şu izinleri verin:
- ✅ **Kullanıcıları Kısıtla / Yasakla** (`can_restrict_members`)
- ✅ **Mesajları Sil** (`can_delete_messages`)
- ✅ **Mesajları Sabitle** (`can_pin_messages`)
- ✅ **Kullanıcı Davet Et** (`can_invite_users`)
