#!/usr/bin/env python3
"""Provider health check.

Reads tools/providers.json, probes every mainUrl, updates the registry and
writes tools/health-report.md. Stdlib only, no external deps.

Usage:
    python tools/check.py                # full check
    python tools/check.py --limit 20     # quick smoke
    python tools/check.py --only dead    # re-check only currently failing ones
"""
import argparse
import concurrent.futures as cf
import datetime
import json
import pathlib
import re
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
REGISTRY = ROOT / "tools" / "providers.json"
REPORT = ROOT / "tools" / "health-report.md"
HISTORY = ROOT / "tools" / "history.jsonl"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130 Safari/537.36"
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE
TIMEOUT = 12


def probe(url: str, retries: int = 1, redirects: int = 3) -> dict:
    started = time.time()
    for scheme in ("https://", "http://"):
        target = url if url.startswith("http") else scheme + url
        try:
            req = urllib.request.Request(
                target, headers={"User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9"}
            )
            with urllib.request.urlopen(req, timeout=TIMEOUT, context=CTX) as resp:
                body = resp.read(80000).decode("utf-8", "replace")
                title = re.search(r"<title[^>]*>(.*?)</title>", body, re.S | re.I)
                return {
                    "http": resp.status,
                    "final": resp.geturl(),
                    "title": re.sub(r"\s+", " ", title.group(1)).strip()[:120] if title else "",
                    "ms": int((time.time() - started) * 1000),
                }
        except urllib.error.HTTPError as exc:
            location = exc.headers.get("Location")
            if exc.code in (301, 302, 303, 307, 308) and location and redirects:
                nxt = location if location.startswith("http") else urllib.parse.urljoin(target, location)
                return probe(nxt, retries, redirects - 1)
            return {"http": exc.code, "ms": int((time.time() - started) * 1000)}
        except Exception as exc:  # noqa: BLE001 - health probe, any failure is data
            err = f"{type(exc).__name__}: {exc}"[:140]
    if retries:
        time.sleep(1.5)
        return probe(url, retries - 1)
    return {"http": None, "err": err, "ms": int((time.time() - started) * 1000)}


def health_of(result: dict) -> str:
    http = result.get("http")
    if http == 200:
        return "ok"
    if http in (401, 403, 429):
        return "blocked"
    if http:
        return "error"
    return "dead"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--only", choices=["dead", "blocked", "all"], default="all")
    parser.add_argument("--workers", type=int, default=24)
    args = parser.parse_args()

    data = json.loads(REGISTRY.read_text(encoding="utf-8"))
    providers = data["providers"]

    selected = [
        (domain, entry)
        for domain, entry in providers.items()
        if args.only == "all" or entry.get("health", "dead") == args.only
    ]
    if args.limit:
        selected = selected[: args.limit]

    def work(item):
        domain, entry = item
        return domain, probe(entry["mainUrl"])

    results = {}
    with cf.ThreadPoolExecutor(max_workers=args.workers) as pool:
        for domain, result in pool.map(work, selected):
            results[domain] = result

    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    counts = {"ok": 0, "blocked": 0, "error": 0, "dead": 0}
    for domain, result in results.items():
        entry = providers[domain]
        entry["http"] = result.get("http")
        entry["health"] = health_of(result)
        entry["checked"] = now
        entry["latencyMs"] = result.get("ms")
        entry["title"] = result.get("title") or entry.get("title", "")
        counts[entry["health"]] += 1

    REGISTRY.write_text(
        json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8"
    )

    rows = sorted(
        (d for d in results),
        key=lambda d: (providers[d]["health"] != "ok", providers[d]["health"], d),
    )
    lines = [
        "# Provider health report",
        "",
        f"Checked at: {now} · providers: {len(results)}",
        f"ok: **{counts['ok']}** · blocked: {counts['blocked']} · error: {counts['error']} · dead: {counts['dead']}",
        "",
        "| Domain | Health | HTTP | Latency | Title |",
        "|---|---|---|---|---|",
    ]
    for domain in rows:
        entry = providers[domain]
        lines.append(
            f"| {domain} | {entry['health']} | {entry['http'] or '-'} | "
            f"{entry.get('latencyMs') or '-'} ms | {entry.get('title', '')[:60]} |"
        )
    lines += [
        "",
        "`blocked` = Cloudflare/401/403: site is alive but needs a browser-grade client.",
        "`dead` = DNS/timeout: registry needs a new mirror (`tools/revive.py`).",
    ]
    REPORT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    with HISTORY.open("a", encoding="utf-8") as handle:
        handle.write(
            json.dumps(
                {
                    "ts": now,
                    "total": len(results),
                    "ok": counts["ok"],
                    "blocked": counts["blocked"],
                    "error": counts["error"],
                    "dead": counts["dead"],
                }
            )
            + "\n"
        )
    print(f"checked {len(results)}: {counts}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
