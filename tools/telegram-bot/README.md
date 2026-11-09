# BigBullApp Telegram Gatekeeper & Moderasyon Botu

MissRose'a ihtiyaç bırakmayan, Cloudflare Workers **ücretsiz planında** çalışan Telegram doğrulama, anti-spam ve grup yönetim botu. Sunucu, veritabanı veya cron gerekmez.

## Özellikler

### 1. Doğrulama (yalnız Start)
Captcha yoktur. Akış:
1. Yeni üye girer (`chat_member`) → anında susturulur ve grupta **Doğrulamak İçin Tıkla** butonu çıkar.
2. Üye butona basıp bota **Başlat (Start)** der → izinleri **grubun varsayılan üye izinlerine** döner, gruptaki doğrulama mesajı silinir, özelden bilgi gelir.
- Bağlantı HMAC imzalıdır; yalnız o üye kullanabilir. Tek kullanımlıktır: mesaj silinir ve üye artık "kısıtlı" değildir.
- Katılma isteğiyle gelenler (`chat_join_request`) otomatik onaylanır ve yine doğrulamadan geçer. Bir **yönetici** tarafından eklenenler doğrulamaya girmez.
- "X gruba katıldı / gruptan ayrıldı" servis mesajları silinir.
- Süre sınırı yoktur; doğrulamayan üye susturulmuş kalır (yönetici `/unmute` ile açabilir ya da `/kick` atabilir).

### 2. Otomatik Koruma
- **Anti-Bot:** Yönetici olmayan birinin gruba eklediği bot anında banlanır.
- **Anti-Reklam:** Yönetici olmayanların paylaştığı yabancı `t.me/...` bağlantıları (grup, kanal, davet, bot) silinir ve `warn` verilir. İzinli adlar `worker.js` içindeki `ALLOWED_TG_NAMES`.
- **Anti-Flood:** 6 saniyede 6'dan fazla mesaj atan hesap **1 saat** susturulur. Çok fotoğraflı albüm tek mesaj sayılır.

### 3. Yönetici Komutları *(mesaja yanıt vererek)*
Her komut, komutu veren yöneticide ilgili Telegram yetkisini arar. Anonim yönetici tüm haklara sahip sayılır.

| Komut | Gereken yetki | Açıklama |
| :--- | :--- | :--- |
| `/ban [sebep]` | Kullanıcıları kısıtla | Kalıcı yasaklar, mesajı siler. Kanal adına yazılmış mesajda kanalı yasaklar. |
| `/unban` | Kullanıcıları kısıtla | Yasağı kaldırır. |
| `/kick` | Kullanıcıları kısıtla | Gruptan çıkarır (tekrar girebilir). |
| `/mute [30s\|10m\|1h\|1d]` | Kullanıcıları kısıtla | Süreli (30 sn – 366 gün) ya da süresiz susturur; geçersiz süre reddedilir. |
| `/unmute` | Kullanıcıları kısıtla | Susturmayı kaldırır (grubun varsayılan izinleri). |
| `/warn [sebep]` | Kullanıcıları kısıtla | Uyarır; **3/3**'te banlar. |
| `/unwarn` | Kullanıcıları kısıtla | Uyarıları sıfırlar. |
| `/sil` veya `/del` | Mesajları sil | Yanıtlanan mesajı ve komutu siler. |
| `/pin` / `/unpin` | Mesajları sabitle | Sabitler / kaldırır. |

### 4. Herkese Açık Komutlar
`/kurallar` (`/rules`), `/linkler` (`/site`, `/repo`), `/id` (kullanıcı, grup, konu id'si).

---

## Kurulum

### Adım 1: Worker
Cloudflare Dashboard → **Workers & Pages** → **Create Worker** → [worker.js](worker.js) içeriğini yapıştırıp **Deploy** (ya da `npx wrangler deploy`).

### Adım 2: Değişkenler (Settings → Variables and Secrets)
| Ad | Zorunlu | Açıklama |
| :--- | :--- | :--- |
| `TELEGRAM_BOT_TOKEN` | evet | BotFather token'ı (*Secret*). |
| `SECRET_TOKEN` | hayır | Webhook gizli anahtarı. Boşsa token'dan türetilir; `setup_webhook.py` aynı türetmeyi kullanır. |
| `GROUP_ID`, `VERIFY_THREAD_ID` | hayır | Forum grubunda doğrulama mesajının gideceği konu. Varsayılan: `worker.js` başındaki sabitler (`tools/telegram-topics.json` → `sohbet`). |
| `BOT_KV` | önerilir | KV bağlantısı (aşağıda). |

### Adım 3: Uyarı sayacı için KV (ücretsiz, önerilir)
Worker örnekleri bellekte durum tutmaz; KV olmadan `/warn` sayacı örnek yeniden başlayınca sıfırlanabilir ve 3/3 ban güvenilmez olur.
1. **Storage & Databases → KV → Create** (ör. `bigbull-bot`).
2. Worker → **Settings → Bindings → Add → KV namespace**, değişken adı **`BOT_KV`**.

Kullanım çok düşüktür (uyarı başına 1 yazma; ücretsiz sınır günde 1000).

### Adım 4: Webhook
`allowed_updates` ve gizli anahtar şarttır; tarayıcıdan elle `setWebhook` çağrısı **kullanmayın** (gizli anahtarsız kurulursa Worker her isteği 403 ile reddeder).
```bash
TELEGRAM_BOT_TOKEN=<BOT_TOKEN> python tools/telegram-bot/setup_webhook.py --set https://<WORKER_ADRESINIZ>.workers.dev
TELEGRAM_BOT_TOKEN=<BOT_TOKEN> python tools/telegram-bot/setup_webhook.py --info   # durum
```
Worker `SECRET_TOKEN` değişkeniyle özel anahtar kullanıyorsa aynı değeri `--secret` ile verin.

### Adım 5: Grup izinleri
Botu **yönetici** yapın: ✅ Kullanıcıları kısıtla · ✅ Mesajları sil · ✅ Mesajları sabitle · ✅ Kullanıcı davet et. Doğrulanan üyeye verilen izinler **Grup ayarları → İzinler** bölümündeki varsayılanlardır.

---

## Test
```bash
node tools/telegram-bot/test.mjs
```
Telegram API taklit edilerek doğrulama akışı, yetkiler, flood, reklam, mute süresi, uyarı/KV ve hata dayanıklılığı sınanır (ağ ve hesap gerekmez). `kapi.yml` her push'ta çalıştırır.
