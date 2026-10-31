#!/usr/bin/env python3
"""MissRose ayarlarını gruba uygular: komut başına tek mesaj.

Ön koşul (insan hesabı, bir kez): Rose gruba yönetici olarak eklendikten sonra
grupta `/bot2bot admin` ve `/bot2botskipreview on` gönderilmiş olmalı; Rose
varsayılan olarak bot komutlarını yok sayar.

Doğrulama getUpdates ile yapılmaz (aynı kuyruğu x runtime poller'ı çekiyor);
doğrulama grupta Rose'un görünen yanıtıyla yapılır.

Kullanım:
    TELEGRAM_BOT_TOKEN=... python tools/rose_setup.py
    python tools/rose_setup.py --dry-run
"""
import argparse
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

STATE = pathlib.Path(__file__).with_name("telegram-topics.json")
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
ROSE_ID = 609517172

# Onaylanan set: captcha + bot kilidi. Kalan satırlar seçilmeyen ayarları
# varsayılana döndürür; betik her çalıştırmada bu duruma yakınsar.
COMMANDS = [
    ("/welcome on", "karşılama (captcha için zorunlu)"),
    ("/captcha on", "captcha"),
    ("/captchamode math", "matematik captcha"),
    ("/captchakick on", "çözmeyeni at"),
    ("/captchakicktime 1h", "1 saat süre"),
    ("/lock bot", "üyeler gruba bot ekleyemez"),
    ("/unlock invitelink", "davet linki kilidi kapalı"),
    ("/setwarnmode ban", "uyarı cezası varsayılana"),
    ("/setwarntime off", "uyarı süresi varsayılana"),
    ("/setflood off", "antiflood kapalı"),
]


def api(token, method, **params):
    url = f"https://api.telegram.org/bot{token}/{method}"
    try:
        return json.loads(OPENER.open(url, data=urllib.parse.urlencode(params).encode(), timeout=25).read())
    except urllib.error.HTTPError as exc:
        return json.loads(exc.read())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--chat-id", help="durum dosyasındaki chat_id varsayılan")
    parser.add_argument("--thread", type=int, help="komutların gideceği konu (varsayılan: sohbet)")
    parser.add_argument("--delay", type=float, default=2.0)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    token = os.environ.get("TELEGRAM_BOT_TOKEN", "").strip()
    if not token:
        sys.exit("TELEGRAM_BOT_TOKEN yok")

    state = json.loads(STATE.read_text(encoding="utf-8")) if STATE.exists() else {}
    chat = str(args.chat_id or state.get("chat_id") or "")
    thread = args.thread or (state.get("topics", {}).get("sohbet") or {}).get("thread_id")
    if not chat:
        sys.exit("chat_id yok: --chat-id ver")

    if args.dry_run:
        for command, note in COMMANDS:
            print(f"[kuru] {command:24} {note}")
        return

    rose = api(token, "getChatMember", chat_id=chat, user_id=ROSE_ID)
    status = (rose.get("result") or {}).get("status")
    if status != "administrator":
        sys.exit(f"Rose gruba yönetici eklenmemiş (status={status}); önce ekle ve yetki ver")
    me = api(token, "getMe")["result"]["id"]
    mine = (api(token, "getChatMember", chat_id=chat, user_id=me).get("result") or {}).get("status")
    if mine != "administrator":
        sys.exit(f"bot yönetici değil (status={mine})")

    for index, (command, note) in enumerate(COMMANDS):
        sent = api(token, "sendMessage", chat_id=chat, text=command,
                   **({"message_thread_id": thread} if thread else {}),
                   disable_notification="true")
        print(f"{'gonderildi' if sent.get('ok') else 'HATA'} {command:24} {note}"
              + ("" if sent.get("ok") else f" -> {sent.get('description')}"))
        if index < len(COMMANDS) - 1:
            time.sleep(args.delay)

    print("\nRose her komuta tek tek yanıt vermeli. Yanıt yoksa /bot2bot admin + "
          "/bot2botskipreview on gönderilmemiştir ya da Rose yönetici değildir.")


if __name__ == "__main__":
    main()
