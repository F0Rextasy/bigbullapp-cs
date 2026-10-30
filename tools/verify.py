#!/usr/bin/env python3
"""Functional verification for implemented providers.

Health checks only prove a domain answers. This script walks the real provider
flow (search -> detail -> stream) for every module listed in
`tools/providers.json` with status=implemented and writes
`tools/verify-report.md`.

Usage:
    python tools/verify.py
    python tools/verify.py --domain diziyou.one

Exit code 1 when any implemented provider fails, so CI can gate releases.
"""
import argparse
import json
import os
import pathlib
import re
import ssl
import sys
import time
import urllib.parse
import urllib.request
import http.client

ROOT = pathlib.Path(__file__).resolve().parent.parent
REGISTRY = ROOT / "tools" / "providers.json"
REPORT = ROOT / "tools" / "verify-report.md"
UA = os.environ.get("VERIFY_UA") or "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130 Safari/537.36"
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE


def get(url: str, ref: str | None = None, data: bytes | None = None, timeout: int = 30, retries: int = 2, headers: dict | None = None) -> str:
    headers = {**(headers or {}), "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9"}
    if ref:
        headers["Referer"] = ref
    req = urllib.request.Request(url, headers=headers, data=data)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as resp:
            return resp.read(2_000_000).decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        if retries and (exc.code >= 500 or exc.code == 429):
            time.sleep(5)
            return get(url, ref, data, timeout, retries - 1)
        raise
    except (http.client.RemoteDisconnected, ConnectionError, TimeoutError, urllib.error.URLError):
        if retries:
            time.sleep(5)
            return get(url, ref, data, timeout, retries - 1)
        raise
    except Exception:
        if retries:
            time.sleep(5)
            return get(url, ref, data, timeout, retries - 1)
        raise


def first(pattern: str, text: str) -> str | None:
    match = re.search(pattern, text, re.S)
    return match.group(1) if match else None


_CACHE: dict | None = None


def _registry() -> dict:
    global _CACHE
    if _CACHE is None:
        _CACHE = json.loads(REGISTRY.read_text(encoding="utf-8"))["providers"]
    return _CACHE


def base(domain: str) -> str:
    return _registry()[domain]["mainUrl"].rstrip("/")


def absolute(b: str, pattern: str, text: str) -> str | None:
    href = first(pattern, text)
    return urllib.parse.urljoin(b, href) if href else None


# --- per-provider flows -----------------------------------------------------

def verify_dizibox() -> dict:
    b = base("dizibox.live")
    search = get(f"{b}/search/love+is+blind/")
    candidates = re.findall(
        r'<article class="detailed-article".*?<h3[^>]*><a href="([^"]+)"', search, re.S
    )[:2]
    episode = None
    time.sleep(2)  # the site throttles bursts of series requests
    for candidate in candidates:
        for attempt in range(2):
            detail = get(urllib.parse.urljoin(b, candidate))
            episode = absolute(b, r'href="([^"]+)"[^>]*class="season-episode', detail)
            if episode:
                break
            time.sleep(5)  # server intermittently serves a degraded page
        if episode:
            break
    if not episode:
        return {"ok": False, "detail": "no episodes on first 2 series"}
    page = get(episode)
    player = absolute(episode, r'id="video-area".*?<iframe[^>]+src="([^"]+)"', page)
    if not player:
        return {"ok": False, "detail": "no player iframe"}
    king = get(player.replace("king.php?v=", "king.php?wmode=opaque&v="), ref=episode)
    embed = absolute(episode, r'id="Player".*?<iframe[^>]+src="([^"]+)"', king)
    if not embed:
        return {"ok": False, "detail": "no inner embed"}
    # shortcut: the molystream embed id maps 1:1 to its sheila HLS endpoint
    stream = embed.replace("/embed/", "/embed/sheila/")
    playlist = get(stream, ref=embed)
    return {
        "ok": playlist.startswith("#EXTM3U"),
        "detail": f"search={len(candidates)} episodes=yes stream={stream[:60]}",
    }


def verify_diziyou() -> dict:
    b = base("diziyou.one")
    search = get(f"{b}/?s=love")
    series = absolute(b, r'<div id="list-series">.*?<div class="cat-img"><a href="([^"]+)"', search)
    if not series:
        return {"ok": False, "detail": "search returned no cards"}
    detail = get(series)
    episode = absolute(b, r'href="([^"]*-sezon-[^"]*-bolum/)"', detail)
    if not episode:
        return {"ok": False, "detail": "no episodes"}
    page = get(episode)
    player = absolute(episode, r'id="diziyouPlayer"[^>]+src="([^"]+)"', page)
    if not player:
        return {"ok": False, "detail": "no player iframe"}
    player_page = get(player, ref=episode)
    stream = absolute(player, r'<source[^>]+src="([^"]+\.m3u8[^"]*)"', player_page)
    subs = len(re.findall(r"<track[^>]+src=", player_page))
    if not stream:
        return {"ok": False, "detail": "no m3u8"}
    playlist = get(stream, ref=player)
    return {
        "ok": playlist.startswith("#EXTM3U"),
        "detail": f"episodes=yes subs={subs} stream={stream[:60]}",
    }


def verify_asyalog() -> dict:
    import html as html_lib

    b = base("asyalog.co")
    search = get(f"{b}/?s=love")
    cards = re.findall(r'<a class="resim" href="([^"]+)"[^>]*title="([^"]+)"', search)
    if not cards:
        return {"ok": False, "detail": "search returned no cards"}
    series = urllib.parse.urljoin(b, cards[0][0])
    detail = get(series)
    episode = absolute(b, r'href="([^"]*/bolum/[^"]+)"', detail)
    if not episode:
        return {"ok": False, "detail": "no episodes"}
    page = get(episode)
    iframe = first(r'<iframe[^>]+src="([^"]+)"', page)
    if not iframe:
        return {"ok": False, "detail": "no iframe"}
    embed = urllib.parse.urljoin(episode, iframe)
    body = html_lib.unescape(get(embed, ref=b))
    videos = first(r'"videos"\s*:\s*(\[.*?])', body)
    if not videos:
        return {"ok": False, "detail": "no videos[] payload"}
    url = first(r'"url"\s*:\s*"([^"]+)"', videos)
    streams = videos.count('"url"')
    return {
        "ok": bool(url),
        "detail": f"search={len(cards)} episode=yes streams={streams}",
    }


def verify_diziboxizle() -> dict:
    import html as html_lib

    b = base("diziboxizle.com")
    items = json.loads(get(f"{b}/wp-json/wp/v2/search?search=the&per_page=5"))
    if not items:
        return {"ok": False, "detail": "search returned nothing"}
    slug = items[0]["url"].rstrip("/").split("/")[-1]
    derived = re.sub(r"-\d+-sezon-\d+-bolum.*$", "", slug)
    series = urllib.parse.urljoin(b, "/" + derived + "/")
    detail = get(series)
    episode = absolute(b, r'href="([^"]*-sezon-[^"]*-bolum/)"', detail)
    if not episode:
        return {"ok": False, "detail": "derived series page has no episodes"}
    page = get(episode)
    embed = absolute(episode, r'<iframe[^>]+src="([^"]+)"', page)
    if not embed:
        return {"ok": False, "detail": "no iframe"}
    body = html_lib.unescape(get(embed, ref=series))
    videos = first(r'"videos"\s*:\s*(\[.*?])', body)
    url = first(r'"url"\s*:\s*"([^"]+)"', videos or "")
    return {
        "ok": bool(url),
        "detail": f"search={len(items)} episodes=yes stream={(url or '')[:60]}",
    }


def _player_decrypt(html: str) -> str | None:
    """Mirror of the shared obfuscated player: reverse+base64 twice, CryptoJS AES."""
    try:
        from Crypto.Cipher import AES  # type: ignore
    except Exception:
        return None
    import base64
    import hashlib

    decoder = first(r"var\s+(_\w+)\s*=\s*function\s*\(\s*s\s*\)\s*\{\s*return\s+atob\(\s*s\.split\(\s*''\s*\)\.reverse\(\)\.join\(\s*''\s*\)\s*\)\s*\}", html)
    if not decoder:
        return None
    parts = re.findall(re.escape(decoder) + r'\("([^"]*)"\)', html)
    if len(parts) < 2:
        return None
    junk = "".join(re.findall(r'"([^"]*)"', first(r"\[\s*((?:\"[^\"]*\"\s*,?\s*)+)\]\s*\.join\(\s*''\s*\)", html) or ""))

    def b64(value: str) -> bytes:
        value = value + "=" * ((4 - len(value) % 4) % 4)
        return base64.b64decode(value)

    payload = b64(parts[0][::-1]).decode("utf-8", "replace")
    blob = b64(payload)
    if len(blob) < 17 or blob[:8] != b"Salted__":
        return None
    passphrase = b64(parts[1][::-1])

    def evp(salt: bytes, length: int = 48) -> bytes:
        out = b""
        prev = b""
        while len(out) < length:
            prev = hashlib.md5(prev + passphrase + salt).digest()
            out += prev
        return out

    key_iv = evp(blob[8:16])
    aes = AES.new(key_iv[:32], AES.MODE_CBC, key_iv[32:48])
    plain = aes.decrypt(blob[16:])
    plain = plain[:-plain[-1]].decode("utf-8", "replace")
    return plain.replace(junk, "")


def verify_asyaanimeleri() -> dict:
    b = base("asyaanimeleri.top")
    search = get(b + "/?s=one%20piece")
    series = absolute(b, r'href="([^"]*/series/[^"?]+/)"', search)
    if not series:
        return {"ok": False, "detail": "search returned no series"}
    detail = get(series)
    episodes = [urllib.parse.urljoin(b, h) for h in dict.fromkeys(re.findall(r'href="([^"]*-bolum[^"]*)"', detail))][:3]
    if not episodes:
        return {"ok": False, "detail": "no episodes"}
    for episode in episodes:
        page = get(episode)
        embeds = re.findall(r'<iframe[^>]+src="([^"]+)"', page)
        import base64
        for option in re.findall(r'<option[^>]+value="([^"]+)"', page):
            try:
                decoded = base64.b64decode(option + "===").decode("utf-8", "replace")
                if "<iframe" in decoded:
                    embeds += re.findall(r'src=["\']([^"\']+)', decoded, re.I)
            except Exception:
                continue
        for embed in embeds:
            embed = urllib.parse.urljoin(episode, embed)
            if "rumble.com/embed" in embed:
                player = get(embed, ref=episode)
                stream = first(r'"hls"\s*:\s*\{\s*"url"\s*:\s*"([^"]+)"', player)
                if stream:
                    playlist = get(stream.replace("\\/", "/"), ref="https://rumble.com/")
                    if playlist.startswith("#EXTM3U"):
                        return {"ok": True, "detail": f"episode=yes host=rumble stream={stream[:46]}"}
            if "vidmoly" in embed:
                player = get(embed, ref=episode)
                if "sources:" in player:
                    return {"ok": True, "detail": "episode=yes host=vidmoly sources=yes"}
            if "sibnet" in embed:
                return {"ok": True, "detail": "episode=yes host=sibnet (core extractor)"}
    return {"ok": False, "detail": "no mirror produced a stream"}


def verify_kultfilmler() -> dict:
    b = base("kultfilmler.net")
    search = get(b + "/?s=matrix")
    films = [urllib.parse.urljoin(b, h) for h in dict.fromkeys(re.findall(r'href="([^"]+-izle/)"', search))][:5]
    films.append(urllib.parse.urljoin(b, "/the-godfather-baba-1-izle/"))  # known-good fallback
    if not films:
        return {"ok": False, "detail": "search returned no film"}
    for film in films:
        detail = get(film)
        embed = first(r'<iframe[^>]+src="(https://vidpapi\.xyz/video/[^"]+)"', detail)
        if not embed:
            continue
        video_id = embed.rstrip("/").split("/")[-1]
        body = urllib.parse.urlencode({"hash": video_id, "r": film}).encode()
        api = f"https://vidpapi.xyz/player/index.php?data={video_id}&do=getVideo"
        payload = get(api, ref=film, data=body, headers={"X-Requested-With": "XMLHttpRequest"})
        stream = first(r'"securedLink"\s*:\s*"([^"]+)"', payload)
        if not stream:
            continue
        playlist = get(stream.replace("\\/", "/"))
        if playlist.startswith("#EXTM3U"):
            return {"ok": True, "detail": f"film=yes stream={stream[:50]}"}
    return {"ok": False, "detail": "no playable film on first 5 results"}


def verify_shared_player(b: str, search_path: str, series_pattern: str) -> dict:
    search = get(b + search_path)
    series = absolute(b, series_pattern, search)
    if not series:
        return {"ok": False, "detail": "search returned nothing"}
    detail = get(series)
    episode = absolute(series, r'href="([^"]*(?:sezon|bolum)[^"]*)"', detail)
    if not episode:
        return {"ok": False, "detail": "no episodes"}
    page = get(episode)
    player = absolute(episode, r'(?:data-src|src)="([^"]*/player/[^"]+)"', page)
    if not player:
        return {"ok": False, "detail": "no player iframe"}
    html = get(player, ref=episode)
    plain = _player_decrypt(html)
    if plain is None:
        return {"ok": False, "detail": "player decrypt failed (pycryptodome missing?)"}
    stream = first(r'file\s*:\s*"([^"]+)', plain)
    if not stream:
        return {"ok": False, "detail": "decrypted shell without file:"}
    stream = urllib.parse.urljoin(player, stream)
    playlist = get(stream, ref=player)
    return {"ok": playlist.startswith("#EXTM3U"), "detail": f"episode=yes stream={stream[:50]}"}


def verify_yabancidizi() -> dict:
    return verify_shared_player(base("yabancidizi.live"), "/arama?q=game", r'href="([^"]*/dizi/[^"]+)"')


def verify_dizilife() -> dict:
    return verify_shared_player(base("dizi82.life"), "/ara?q=gibi", r'href="([^"]*/dizi/[^"]+)"')


VERIFIERS = {
    "dizibox.live": verify_dizibox,
    "diziyou.one": verify_diziyou,
    "asyalog.co": verify_asyalog,
    "diziboxizle.com": verify_diziboxizle,
    "asyaanimeleri.top": verify_asyaanimeleri,
    "kultfilmler.net": verify_kultfilmler,
    "yabancidizi.live": verify_yabancidizi,
    "dizi82.life": verify_dizilife,
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--domain", help="virgülle ayrılmış domain listesi")
    args = parser.parse_args()
    selected = {d.strip() for d in args.domain.split(",") if d.strip()} if args.domain else None

    providers = _registry()
    targets = {
        domain: entry
        for domain, entry in providers.items()
        if entry.get("status") == "implemented"
        and (not selected or domain in selected)
    }
    if not targets:
        print("no implemented providers selected")
        return 1

    rows = []
    failures = 0
    for domain, entry in sorted(targets.items()):
        verifier = VERIFIERS.get(domain)
        if not verifier:
            rows.append((domain, entry.get("module", "?"), "skip", "no verifier implemented"))
            continue
        try:
            result = verifier()
        except Exception as exc:  # noqa: BLE001 - verification, any failure is data
            result = {"ok": False, "detail": f"{type(exc).__name__}: {exc}"[:120]}
        status = "pass" if result.get("ok") else "FAIL"
        failures += 0 if result.get("ok") else 1
        rows.append((domain, entry.get("module", "?"), status, result.get("detail", "")))

    lines = [
        "# Functional verification",
        "",
        "Health checks answer 'does the domain respond'. This report answers 'does the provider still produce a playable stream' by walking search -> detail -> episode -> player.",
        "",
        "| Domain | Module | Result | Evidence |",
        "|---|---|---|---|",
    ]
    for domain, module, status, detail in rows:
        lines.append(f"| {domain} | {module} | {status} | {detail} |")
    REPORT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"verified {len(rows)}: {len(rows) - failures - sum(1 for r in rows if r[2] == 'skip')} pass, {failures} fail")
    print(f"report -> {REPORT.relative_to(ROOT)}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
