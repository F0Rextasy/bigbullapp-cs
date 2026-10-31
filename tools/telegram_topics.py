#!/usr/bin/env python3
"""BigBullApp Telegram forum konularını kurar.

Bot API konu listelemez; oluşturulan thread id'leri `tools/telegram-topics.json`
dosyasında tutulur ve bildirim iş akışı oradan okur. Kapalı konuya yalnız
yöneticiler yazabildiği için Duyuru ve Genel kapatılır. Betik idempotenttir:
var olan konu yeniden açılmaz, gönderilmiş tanıtım tekrarlanmaz.

Kullanım:
    TELEGRAM_BOT_TOKEN=... python tools/telegram_topics.py --chat-id -1003994138025
    python tools/telegram_topics.py --dry-run
"""
import argparse
import json
import os
import pathlib
import sys
import urllib.error
import urllib.parse
import urllib.request

STATE = pathlib.Path(__file__).with_name("telegram-topics.json")
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

TOPICS = [
    {
        "key": "duyuru",
        "name": "Duyuru",
        "icon": "📰",
        "closed": True,
        "welcome": "📢 <b>Sadece duyurular.</b>\nYeni sağlayıcılar, düzeltmeler ve sürüm notları burada.\nSohbet için 💬 Sohbet konusuna geç.",
    },
    {
        "key": "sohbet",
        "name": "Sohbet",
        "icon": "💬",
        "closed": False,
        "welcome": "💬 <b>Burası serbest alan.</b>\nTanış, soru sor, aklındakini yaz.",
    },
    {
        "key": "hata",
        "name": "Hata & Sorun",
        "icon": "❗️",
        "closed": False,
        "welcome": "❗️ <b>Bir şey çalışmıyor mu?</b>\nSağlayıcı adı, ne olduğu ve varsa ekran görüntüsü.\nÜçünü yazarsan hızlı çözülür.",
    },
    {
        "key": "istek",
        "name": "İstek & Sağlayıcı",
        "icon": "💡",
        "closed": False,
        "welcome": "💡 <b>Sağlayıcı ya da özellik isteği.</b>\nSite linkini ekle; örnek içerik varsa onu da yaz.",
    },
]


def api(token, method, **params):
    """Tek Bot API çağrısı; 4xx gövdesi fırlatılmaz, çağıran karar verir."""
    url = f"https://api.telegram.org/bot{token}/{method}"
    try:
        return json.loads(OPENER.open(url, data=urllib.parse.urlencode(params).encode(), timeout=25).read())
    except urllib.error.HTTPError as exc:
        return json.loads(exc.read())


def load_state():
    if STATE.exists():
        return json.loads(STATE.read_text(encoding="utf-8"))
    return {"chat_id": None, "topics": {}}


def save_state(state):
    tmp = STATE.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, STATE)


def icon_ids(token):
    stickers = api(token, "getForumTopicIconStickers").get("result") or []
    return {s["emoji"]: s["custom_emoji_id"] for s in stickers if s.get("emoji")}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--chat-id", help="grup id'si; durumda varsa gerekmez")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    token = os.environ.get("TELEGRAM_BOT_TOKEN", "").strip()
    state = load_state()
    chat = str(args.chat_id or state.get("chat_id") or "")
    if not token:
        sys.exit("TELEGRAM_BOT_TOKEN yok")
    if not chat:
        sys.exit("--chat-id gerekli (ilk çalıştırma)")

    if not args.dry_run:
        info = api(token, "getChat", chat_id=chat)
        if not info.get("ok"):
            sys.exit(f"getChat basarisiz: {info.get('description')}")
        if not info["result"].get("is_forum"):
            sys.exit(f"'{info['result'].get('title')}' forum degil: Telegram > grup > Duzenle > Konular'i ac")
        state["chat_id"] = chat

    icons = {} if args.dry_run else icon_ids(token)
    for topic in TOPICS:
        entry = dict(state["topics"].get(topic["key"]) or {})
        if not entry.get("thread_id"):
            if args.dry_run:
                print(f"[kuru] acilacak: {topic['name']}")
                continue
            created = api(
                token,
                "createForumTopic",
                chat_id=chat,
                name=topic["name"],
                **({"icon_custom_emoji_id": icons[topic["icon"]]} if icons.get(topic["icon"]) else {}),
            )
            if not created.get("ok"):
                print(f"HATA {topic['name']}: {created.get('description')}")
                continue
            entry["thread_id"] = (created.get("result") or {}).get("message_thread_id")
            state["topics"][topic["key"]] = entry
            save_state(state)
            print(f"acildi: {topic['name']} -> {entry['thread_id']}")

        if not entry.get("welcomed"):
            if args.dry_run:
                print(f"[kuru] tanitim atilacak: {topic['name']}")
            else:
                sent = api(
                    token,
                    "sendMessage",
                    chat_id=chat,
                    message_thread_id=entry["thread_id"],
                    text=topic["welcome"],
                    parse_mode="HTML",
                    disable_web_page_preview="true",
                )
                if not sent.get("ok"):
                    print(f"HATA tanitim {topic['name']}: {sent.get('description')}")
                else:
                    message_id = (sent.get("result") or {}).get("message_id")
                    api(token, "pinChatMessage", chat_id=chat, message_id=message_id,
                        message_thread_id=entry["thread_id"], disable_notification="true")
                    entry["welcomed"] = True
                    state["topics"][topic["key"]] = entry
                    save_state(state)

        if topic["closed"] and not entry.get("closed"):
            if args.dry_run:
                print(f"[kuru] kapatilacak: {topic['name']}")
            elif entry.get("thread_id"):
                res = api(token, "closeForumTopic", chat_id=chat, message_thread_id=entry["thread_id"])
                if res.get("ok") or "TOPIC_NOT_MODIFIED" in str(res.get("description", "")):
                    entry["closed"] = True
                    state["topics"][topic["key"]] = entry
                    save_state(state)
                    print(f"kapatildi: {topic['name']}")
                else:
                    print(f"HATA kapatma {topic['name']}: {res.get('description')}")

    if not state.get("general_closed"):
        if args.dry_run:
            print("[kuru] Genel kapatilacak")
        else:
            res = api(token, "closeGeneralForumTopic", chat_id=chat)
            if res.get("ok") or "TOPIC_NOT_MODIFIED" in str(res.get("description", "")):
                state["general_closed"] = True
                save_state(state)
                print("kapatildi: Genel")
            else:
                print(f"HATA kapatma Genel: {res.get('description')}")

    if args.dry_run or not state.get("chat_id"):
        return
    print("durum:", json.dumps({"chat_id": state["chat_id"],
                                "threads": {k: v.get("thread_id") for k, v in state["topics"].items()},
                                "general_closed": state.get("general_closed")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
