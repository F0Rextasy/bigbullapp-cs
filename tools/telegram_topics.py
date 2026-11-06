#!/usr/bin/env python3
"""BigBullApp Telegram forum konularını ve grup açıklamasını kurar / günceller.

Bot API konu listelemez; oluşturulan thread id'leri `tools/telegram-topics.json`
dosyasında tutulur ve bildirim iş akışı oradan okur. Kapalı konuya yalnız
yöneticiler yazabildiği için Duyurular ve Genel kapatılır. Betik idempotenttir:
var olan konu yeniden açılmaz, adı güncellenir, gönderilmiş tanıtım tekrarlanmaz.

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

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

STATE = pathlib.Path(__file__).with_name("telegram-topics.json")
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

CHAT_DESCRIPTION = (
    "BigBullApp resmi topluluğu. Cloudstream, Nuvio, Web UI ve açık kaynak projelerimiz.\n\n"
    "🌐 https://bigbullapp.com\n"
    "🐙 https://github.com/F0Rextasy"
)

TOPICS = [
    {
        "key": "genel_duyurular",
        "name": "📢 Duyurular",
        "icon": "📢",
        "closed": True,
        "welcome": (
            "📢 <b>Resmi Duyurular</b>\n\n"
            "BigBull ekosistemi, bigbullapp.com ve projelerimize dair tüm resmi açıklamalar burada paylaşılır.\n\n"
            "🌐 <b>Web:</b> https://bigbullapp.com\n"
            "🐙 <b>GitHub:</b> https://github.com/F0Rextasy"
        ),
    },
    {
        "key": "duyuru",
        "name": "🔄 Eklenti Güncellemeleri",
        "icon": "🔄",
        "closed": True,
        "welcome": (
            "🔄 <b>Eklenti Güncellemeleri (CS & Nuvio)</b>\n\n"
            "Cloudstream ve Nuvio sağlayıcı güncellemeleri, sürüm notları ve otomatik bot raporları burada yer alır.\n\n"
            "Sohbet ve sorular için 💬 Genel Sohbet konusuna geçebilirsiniz."
        ),
    },
    {
        "key": "sohbet",
        "name": "💬 Genel Sohbet",
        "icon": "💬",
        "closed": False,
        "welcome": "💬 <b>Genel Sohbet</b>\n\nTanış, soru sor, aklındakini yaz. Burası serbest alan.",
    },
    {
        "key": "gelistirici",
        "name": "🛠️ Geliştirici & API",
        "icon": "🛠️",
        "closed": False,
        "welcome": (
            "🛠️ <b>Geliştirici & API Araçları</b>\n\n"
            "Bulk API Key araçları, botlar, scriptler ve teknik yazılım geliştirme konuları için serbest alan.\n\n"
            "🐙 <b>Projeler:</b> https://github.com/F0Rextasy"
        ),
    },
    {
        "key": "hata",
        "name": "❗️ Hata & Sorun",
        "icon": "❗️",
        "closed": False,
        "welcome": (
            "❗️ <b>Bir şey çalışmıyor mu?</b>\n\n"
            "Sağlayıcı veya proje adı, karşılaştığın hata ve varsa ekran görüntüsü.\n"
            "Ayrıntılı yazarsan hızlı çözülür."
        ),
    },
    {
        "key": "istek",
        "name": "💡 İstek & Öneri",
        "icon": "💡",
        "closed": False,
        "welcome": "💡 <b>İstek ve Öneriler</b>\n\nYeni site/sağlayıcı veya özellik isteklerinizi buradan iletebilirsiniz.",
    },
]


def api(token, method, **params):
    """Tek Bot API çağrısı; 4xx gövdesi fırlatılmaz, çağıran karar verir."""
    url = f"https://api.telegram.org/bot{token}/{method}"
    try:
        data = urllib.parse.urlencode(params).encode()
        req = urllib.request.Request(url, data=data)
        return json.loads(OPENER.open(req, timeout=25).read())
    except urllib.error.HTTPError as exc:
        try:
            return json.loads(exc.read())
        except Exception:
            return {"ok": False, "description": str(exc)}
    except Exception as exc:
        return {"ok": False, "description": str(exc)}


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
    chat = str(args.chat_id or state.get("chat_id") or "").strip()
    if not token and not args.dry_run:
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

        # Grup açıklamasını / hakkında kısmını güncelle
        desc_res = api(token, "setChatDescription", chat_id=chat, description=CHAT_DESCRIPTION)
        if desc_res.get("ok") or "CHAT_NOT_MODIFIED" in str(desc_res.get("description", "")):
            print("Grup açıklaması güncellendi.")
        else:
            print(f"Grup açıklaması uyarısı: {desc_res.get('description')}")

    icons = {} if args.dry_run else icon_ids(token)
    for topic in TOPICS:
        entry = dict(state["topics"].get(topic["key"]) or {})
        thread_id = entry.get("thread_id")

        if not thread_id:
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
                print(f"HATA {topic['name']} açılamadı: {created.get('description')}")
                continue
            thread_id = (created.get("result") or {}).get("message_thread_id")
            entry["thread_id"] = thread_id
            state["topics"][topic["key"]] = entry
            save_state(state)
            print(f"Açıldı: {topic['name']} -> thread {thread_id}")
        else:
            # Mevcut konunun adını güncelle (örn: Duyuru -> 🔄 Eklenti Güncellemeleri)
            if args.dry_run:
                print(f"[kuru] adı güncellenecek: thread {thread_id} -> {topic['name']}")
            else:
                edit_res = api(
                    token,
                    "editForumTopic",
                    chat_id=chat,
                    message_thread_id=thread_id,
                    name=topic["name"],
                )
                if edit_res.get("ok") or "TOPIC_NOT_MODIFIED" in str(edit_res.get("description", "")):
                    print(f"Konu adı güncellendi: thread {thread_id} -> {topic['name']}")
                else:
                    print(f"Konu adı güncelleme uyarısı ({topic['name']}): {edit_res.get('description')}")

        if not entry.get("welcomed"):
            if args.dry_run:
                print(f"[kuru] tanıtım atılacak: {topic['name']}")
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
                    print(f"HATA tanıtım {topic['name']}: {sent.get('description')}")
                else:
                    message_id = (sent.get("result") or {}).get("message_id")
                    api(
                        token,
                        "pinChatMessage",
                        chat_id=chat,
                        message_id=message_id,
                        message_thread_id=entry["thread_id"],
                        disable_notification="true",
                    )
                    entry["welcomed"] = True
                    state["topics"][topic["key"]] = entry
                    save_state(state)

        if topic["closed"] and not entry.get("closed"):
            if args.dry_run:
                print(f"[kuru] kapatılacak: {topic['name']}")
            elif entry.get("thread_id"):
                res = api(token, "closeForumTopic", chat_id=chat, message_thread_id=entry["thread_id"])
                if res.get("ok") or "TOPIC_NOT_MODIFIED" in str(res.get("description", "")):
                    entry["closed"] = True
                    state["topics"][topic["key"]] = entry
                    save_state(state)
                    print(f"Kapatıldı (yalnız yönetici): {topic['name']}")
                else:
                    print(f"HATA kapatma {topic['name']}: {res.get('description')}")

    if not state.get("general_closed"):
        if args.dry_run:
            print("[kuru] Genel kapatılacak")
        else:
            res = api(token, "closeGeneralForumTopic", chat_id=chat)
            if res.get("ok") or "TOPIC_NOT_MODIFIED" in str(res.get("description", "")):
                state["general_closed"] = True
                save_state(state)
                print("Kapatıldı: Genel")
            else:
                print(f"HATA kapatma Genel: {res.get('description')}")

    print("İşlem tamamlandı. Konu durumu:", json.dumps({
        "chat_id": state["chat_id"],
        "threads": {k: v.get("thread_id") for k, v in state["topics"].items()},
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
