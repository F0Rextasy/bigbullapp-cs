#!/usr/bin/env python3
"""Mirror revival helper.

For providers whose domain is dead/blocked, probes alternative domains built
from the brand token plus known mirror TLDs, scores the responses and writes
suggestions. Only brand-matching, streaming-looking pages count as mirrors.

Usage:
    python tools/revive.py                        # suggestions for failing entries
    python tools/revive.py --domain dizimag.dev
    python tools/revive.py --apply --patch-code   # write high-confidence mirror to registry + code
"""
import argparse
import datetime
import concurrent.futures as cf
import json
import pathlib
import re
import ssl
import urllib.request

from verify import VERIFIERS

ROOT = pathlib.Path(__file__).resolve().parent.parent
REGISTRY = ROOT / "tools" / "providers.json"
OUT = ROOT / "tools" / "revive-suggestions.md"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130"
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE
TLDS = [
    ".com", ".net", ".tv", ".org", ".one", ".vip", ".top", ".im", ".ac", ".news",
    ".diy", ".site", ".xyz", ".ltd", ".co", ".me", ".cc", ".info", ".io", ".app",
    ".live", ".club", ".online", ".plus", ".work", ".now", ".shop", ".to", ".so",
]
STREAM_WORDS = ("izle", "dizi", "film", "anime", "altyaz", "dublaj", "sinema", "belgesel", "çizgi")
BAD_WORDS = (
    "for sale", "parking", "huge domains", "buy this domain", "domain details",
    "casino", "bet ", "betting", "slot", "escort", "admin panel", "sign in",
    "cloudflare access", "web server's default page", "coming soon", "porn", "parked",
    "park.io", "sedo", "afternic", "efty", "is for sale",
)


def probe(domain: str):
    for scheme in ("https://", "http://"):
        try:
            req = urllib.request.Request(
                scheme + domain, headers={"User-Agent": UA, "Accept-Language": "tr-TR,tr"}
            )
            with urllib.request.urlopen(req, timeout=10, context=CTX) as resp:
                body = resp.read(80000).decode("utf-8", "replace")
                title = re.search(r"<title[^>]*>(.*?)</title>", body, re.S | re.I)
                return (
                    domain,
                    resp.status,
                    re.sub(r"\s+", " ", title.group(1)).strip()[:90] if title else "",
                    body.lower(),
                )
        except Exception:  # noqa: BLE001 - any failure just means "not a mirror"
            continue
    return domain, None, "", ""


def score(brand: str, title: str, body: str) -> int:
    low_title = title.lower()
    if any(bad in low_title or bad in body[:4000] for bad in BAD_WORDS):
        return 0
    points = 0
    if brand in low_title:
        points += 3
    elif brand in body[:20000]:
        points += 1
    if any(word in low_title for word in STREAM_WORDS):
        points += 2
    if any(word in body[:20000] for word in STREAM_WORDS):
        points += 1
    return points


def brand(domain: str) -> str:
    return domain.split(".")[0].split("-")[0]


def candidates(domain: str) -> list[str]:
    base = brand(domain)
    return [c for c in dict.fromkeys(base + tld for tld in TLDS) if c != domain]


def patch_module(module: str, new_url: str) -> bool:
    """Rewrite `override var mainUrl = "..."` in the provider sources."""
    changed = False
    for source in (ROOT / "providers" / module).rglob("*Provider.kt"):
        text = source.read_text(encoding="utf-8")
        updated = re.sub(
            r'(override var mainUrl\s*=\s*)"[^"]*"',
            lambda m: m.group(1) + f'"{new_url}"',
            text,
            count=1,
        )
        if updated != text:
            source.write_text(updated, encoding="utf-8")
            changed = True
    return changed



def bump_version(module: str) -> int | None:
    """Increase `version = N` in providers/<module>/build.gradle.kts and return the new number."""
    path = ROOT / "providers" / module / "build.gradle.kts"
    if not path.exists():
        return None
    text = path.read_text(encoding="utf-8")
    match = re.search(r"(\bversion\s*=\s*)(\d+)", text)
    if not match:
        return None
    new_version = int(match.group(2)) + 1
    updated = text[: match.start(2)] + str(new_version) + text[match.end(2) :]
    path.write_text(updated, encoding="utf-8")
    return new_version


def release_note(entries: list[tuple[str, str, str]]) -> str | None:
    """Prepend a patch release heading with the applied domain updates."""
    path = ROOT / "CHANGELOG.md"
    if not path.exists() or not entries:
        return None
    text = path.read_text(encoding="utf-8")
    versions = re.findall(r"^## \[(\d+)\.(\d+)\.(\d+)\]", text, re.M)
    if not versions:
        return None
    major, minor, patch = (int(part) for part in versions[0])
    heading = f"## [{major}.{minor}.{patch + 1}] - {datetime.date.today().isoformat()}"
    bullets = "\n".join(
        f"- `{domain}` → `{url}`" + (f" (modül `{module}`, sürüm `{version}`)" if version else "")
        for domain, url, module, version in entries
    )
    block = f"{heading}\n\n### Düzeltildi\n\n{bullets}\n\n"
    first = re.search(r"^## \[", text, re.M)
    updated = text[: first.start()] + block + text[first.start() :]
    path.write_text(updated, encoding="utf-8")
    return heading


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--patch-code", action="store_true")
    parser.add_argument("--domain")
    parser.add_argument("--bump-version", action="store_true")
    parser.add_argument("--release-note", action="store_true")
    parser.add_argument("--allow-unverified", action="store_true")
    args = parser.parse_args()

    data = json.loads(REGISTRY.read_text(encoding="utf-8"))
    providers = data["providers"]

    targets = (
        {args.domain: providers[args.domain]}
        if args.domain and args.domain in providers
        else {d: e for d, e in providers.items() if e.get("health") not in (None, "ok")}
    )
    if not targets:
        print("nothing to revive")
        return 0

    tasks = [(domain, cand) for domain in targets for cand in candidates(domain)]

    def work(task):
        domain, cand = task
        found, status, title, body = probe(cand)
        return domain, found, status, title, score(brand(domain), title, body)

    alive: dict[str, list[tuple[int, str, str]]] = {d: [] for d in targets}
    with cf.ThreadPoolExecutor(max_workers=40) as pool:
        for domain, cand, status, title, points in pool.map(work, tasks):
            if status == 200 and points >= 3:
                alive[domain].append((points, cand, title))

    lines = [
        "# Revival suggestions",
        "",
        "Only pages that mention the brand and look like streaming sites are listed.",
        "`high` = brand in title + streaming words; safe to apply. `low` = brand mention only, review manually.",
        "",
        "| Dead domain | Suggested mirror | Confidence | Title |",
        "|---|---|---|---|",
    ]
    applied = 0
    applied_entries: list[tuple[str, str, str, int | None]] = []
    for domain, hits in sorted(alive.items()):
        hits.sort(key=lambda h: (-h[0], len(h[1])))
        if not hits:
            lines.append(f"| {domain} | — | none | no brand-matching mirror |")
            continue
        points, best, title = hits[0]
        confidence = "high" if points >= 5 else "low"
        lines.append(f"| {domain} | https://{best} | {confidence} | {title} |")
        if args.apply and points >= 5:
            if domain not in VERIFIERS and not args.allow_unverified:
                lines[-1] = lines[-1].replace("| high |", "| skipped: no verifier |")
                continue
            entry = providers[domain]
            new_url = f"https://{best}"
            module = entry.get("module")
            # Patch the code first: a registry-only change would claim a fix the provider never got.
            if args.patch_code and module and not patch_module(module, new_url):
                lines[-1] = lines[-1].replace("| high |", "| kod yaması başarısız |")
                continue
            entry["mainUrl"] = new_url
            entry["mirrors"] = [f"https://{c}" for p, c, t in hits[1:6]]
            entry["health"] = "unknown"
            entry["revivedFrom"] = domain
            applied += 1
            if args.patch_code and module:
                version = bump_version(module) if args.bump_version else None
                applied_entries.append((domain, new_url, module, version))
                suffix = f" v{version}" if version else ""
                lines[-1] = lines[-1].replace("| high |", f"| kod yamalandı{suffix} |")

    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    if args.apply:
        REGISTRY.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    if args.release_note and applied_entries:
        heading = release_note(applied_entries)
        if heading:
            print(f"changelog: {heading}")
    print(f"targets: {len(targets)} · applied: {applied} · report -> {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
