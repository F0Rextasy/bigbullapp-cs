#!/usr/bin/env python3
"""Cloudflare Worker Webhook Ayarlayici

Kullanim:
  # Webhook durumunu sorgula:
  python tools/telegram-bot/setup_webhook.py --info

  # Webhook'u Cloudflare Worker URL'ine bagla:
  python tools/telegram-bot/setup_webhook.py --set https://bigbull-bot.senin-adın.workers.dev

  # Webhook'u kaldir (polling moduna geri don):
  python tools/telegram-bot/setup_webhook.py --delete
"""
import argparse
import hashlib
import json
import os
import sys
import urllib.parse
import urllib.request

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def call_api(token: str, method: str, data: dict = None) -> dict:
    url = f"https://api.telegram.org/bot{token}/{method}"
    headers = {"Content-Type": "application/json"}
    body = json.dumps(data).encode("utf-8") if data else None
    req = urllib.request.Request(url, data=body, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as res:
            return json.loads(res.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        return json.loads(exc.read().decode("utf-8"))
    except Exception as exc:
        return {"ok": False, "description": str(exc)}


def main():
    parser = argparse.ArgumentParser(description="Telegram Webhook Ayarlayici")
    parser.add_argument("--token", default=os.environ.get("TELEGRAM_BOT_TOKEN", "").strip())
    parser.add_argument("--set", dest="webhook_url", help="Cloudflare Worker URL'i")
    parser.add_argument("--secret", default=os.environ.get("SECRET_TOKEN", "").strip())
    parser.add_argument("--delete", action="store_true", help="Webhook'u sil")
    parser.add_argument("--info", action="store_true", help="Webhook durumunu goster")
    args = parser.parse_args()

    token = args.token
    if not token:
        sys.exit("Hata: TELEGRAM_BOT_TOKEN ortam degiskeni veya --token gerekli.")

    if args.delete:
        res = call_api(token, "deleteWebhook", {"drop_pending_updates": True})
        print(f"Webhook silindi: {res}")
    elif args.webhook_url:
        secret = args.secret or hashlib.sha256(
            f"{token}:webhook-secret".encode("utf-8")
        ).hexdigest()[:32]
        payload = {
            "url": args.webhook_url,
            "allowed_updates": ["message", "callback_query", "chat_member", "chat_join_request"],
            "drop_pending_updates": True,
            "secret_token": secret,
        }
        res = call_api(token, "setWebhook", payload)
        print(f"Webhook baglandi: {res}")
    else:
        # Varsayilan olarak bilgi goster
        res = call_api(token, "getWebhookInfo")
        print(json.dumps(res, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
