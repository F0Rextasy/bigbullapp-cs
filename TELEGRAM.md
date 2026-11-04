# Telegram Bildirimi

Her `master` commit'i Telegram kanalına düşer. Kanal ve bot **senin Telegram hesabından**
oluşturulmalı; token/chat_id repoya yazılmaz, GitHub Secrets'ta tutulur.

## 1. Bot oluştur

1. Telegram'da **@BotFather** ile konuş.
2. `/newbot` → bot adı ve kullanıcı adı ver.
3. Verilen **HTTP API token**'ı sakla (`123456:ABC…`).

## 2. Kanal oluştur

1. Yeni bir kanal aç (public önerilir, örn. `@BIGBULLapp`).
2. Kanal → **Yöneticiler → Yönetici Ekle** → botu ekle, "Mesaj gönderme" iznini ver.
3. Public kanalda `chat_id` = `@KanalKullaniciAdi`. Private kanalda sayısal id gerekir; botu
   kanala ekledikten sonra `https://api.telegram.org/bot<TOKEN>/getUpdates` yanıtından alınır.

## 3. GitHub Secrets

Yalnız bot token'ı gerekiyor; `chat_id` ve duyuru konusu
`tools/telegram-topics.json`'dan okunur (`bildirim.yml`).

```bash
gh secret set TELEGRAM_BOT_TOKEN -R F0Rextasy/bigbullapp-cs
```

## 4. Test / zorla gönder

```bash
gh workflow run bildirim.yml -R F0Rextasy/bigbullapp-cs --ref master --field force=true
```

- Secrets yoksa workflow sessizce atlar (hata vermez).
- `force=true` iki kapıyı da atlar: sağlayıcı dosyası değişmese ve CHANGELOG sürümü
  değişmese de bildirim gider.

## Notlar

- `github.token` ile yapılan push'lar **başka workflow'u tetiklemez**. Kullanıcı push'ları
  `push` olayıyla bildirimi kendiliğinden yürütür; bot ya da başka token iten otomasyon
  varsa bildirimi açıkça `gh workflow run` ile çağırmak gerekir.
- Bildirim, CHANGELOG'un en üst sürümünü ve o sürüm bölümündeki satırları
  `Duyuru` forum konusuna gönderir (hedef: `tools/telegram-topics.json`).
- Aynı CHANGELOG sürümünü taşıyan ikinci sağlayıcı commit'i sessiz kalır; kaçan
  bildirimleri `force=true` ile gönder.

## Güvenlik

- Token **asla** repoya, koda veya sohbete yazılmaz; yalnızca GitHub Secrets'ta durur.
- Token bir kez paylaşıldıysa **derhal iptal et**: @BotFather → `/revoke` → yeni token.
- Yeni token'ı kimseye gönderme; doğrudan `gh secret set` ile gir.

## Yerel test (Actions olmadan)

```bash
export TELEGRAM_BOT_TOKEN='...'   # yeni token, sohbete yazma
export TELEGRAM_CHAT_ID='@KanalKullaniciAdi'
curl -s -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
  --data-urlencode "chat_id=${TELEGRAM_CHAT_ID}" \
  --data-urlencode "text=BigBullApp test bildirimi"
```
