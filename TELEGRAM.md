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

```bash
gh secret set TELEGRAM_BOT_TOKEN -R F0Rextasy/bigbullapp-cs
gh secret set TELEGRAM_CHAT_ID   -R F0Rextasy/bigbullapp-cs
```

## 4. Test

```bash
gh workflow run bildirim.yml -R F0Rextasy/bigbullapp-cs --ref master
```

Secrets yoksa workflow sessizce atlar (hata vermez).

## Notlar

- `github.token` ile yapılan push'lar **başka workflow'u tetiklemez**. Bu yüzden domain botu,
  commit'ten sonra `gh workflow run bildirim.yml` çağırır (`actions: write` izniyle).
- Domain botu bilinçli olarak **GH_PAT kullanmaz**: PAT push'u `push` olayını tetikler ve
  Build/Bildirim iki kez çalışırdı. Tek yol: GITHUB_TOKEN + açık `workflow run`.
- Kullanıcı push'ları `push` olayıyla bildirimi kendiliğinden tetikler.
- Mesaj içeriği: kısa SHA, commit başlığı, yazar, değişen dosya sayısı, commit bağlantısı.

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
