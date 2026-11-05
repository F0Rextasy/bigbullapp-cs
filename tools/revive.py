#!/usr/bin/env python3
"""Mirror revival helper (100% deterministic, no LLM).

For providers whose domain is dead/blocked or redirected, probes alternative domains
built from the stripped brand token, numeric sequence increments (e.g. dizipal1594 -> 1595..1624),
and known mirror TLDs in parallel. Scores responses using brand, streaming keywords,
clone-redirect guards, and DOM structural fingerprints from the existing Kotlin provider.
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import datetime
import json
import os
import pathlib
import re
import ssl
import urllib.parse
import urllib.request

try:
    from verify import VERIFIERS
except Exception:
    VERIFIERS = {}

_HERE = pathlib.Path(__file__).resolve().parent
ROOT = (
    _HERE.parent
    if (_HERE.parent / "tools" / "providers.json").is_file()
    else pathlib.Path(os.environ.get("BIGBULL_REPO", r"C:\Users\Extasy\Desktop\cloudstream"))
)
REGISTRY = ROOT / "tools" / "providers.json"
OUT = ROOT / "tools" / "revive-suggestions.md"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE
TLDS = [
    ".com", ".net", ".tv", ".org", ".one", ".vip", ".top", ".im", ".ac", ".news",
    ".diy", ".site", ".xyz", ".ltd", ".co", ".me", ".cc", ".info", ".io", ".app",
    ".live", ".club", ".online", ".plus", ".work", ".now", ".shop", ".to", ".so",
    ".icu", ".lat", ".bond", ".cfd", ".sbs", ".cyou", ".mom", ".lol", ".click",
    ".cam", ".rest", ".bar", ".fit", ".biz", ".ws", ".run", ".dev", ".ink",
    ".la", ".in", ".de", ".nl", ".ch", ".pro", ".fun", ".pw", ".monster",
]
BLOCKED_HOSTS = (
    "esb.org.tr", "btk.gov.tr", "guvenlinet.org.tr", "ihbarweb.org.tr",
    "kurul.btk.gov.tr", "dnswarning", "hugedomains.com", "sedoparking.com",
    "afternic.com", "dan.com", "bodis.com",
)
STREAM_WORDS = (
    "izle", "dizi", "film", "anime", "altyaz", "dublaj", "sinema", "belgesel", "çizgi", "bolum", "bölüm",
)
BAD_TITLE_WORDS = (
    "for sale", "parking", "huge domains", "hugedomains", "buy this domain", "domain details",
    "casino", "betting", "slot ", " slot", "escort", "admin panel",
    "cloudflare access", "web server's default page", "coming soon", "porn", "parked",
    "park.io", "sedoparking", "afternic", "is for sale", "erişime engellenmiştir",
    "erisime_engellenmis", "5651 sayılı", "btk.gov.tr", "ihbarweb", "alan adı satılıktır",
)
BAD_BODY_WORDS = (
    "buy this domain", "domain is for sale", "hugedomains.com", "sedoparking.com",
    "afternic.com", "park.io", "cloudflare access", "web server's default page",
    "erişime engellenmiştir", "erisime_engellenmis", "5651 sayılı", "btk.gov.tr",
    "ihbarweb", "alan adı satılıktır",
)
SUB_PREFIX_RE = re.compile(r"^(?:www\d*|m|blog|izle|hd|tr|tv|mobil|new)\.", re.I)


def strip_sub(domain: str) -> str:
    host = re.sub(r"^https?://", "", domain.strip().lower()).split("/")[0]
    while SUB_PREFIX_RE.match(host):
        host = SUB_PREFIX_RE.sub("", host, count=1)
    return host


def brand(domain: str) -> str:
    clean = strip_sub(domain)
    label = clean.split(".")[0].split("-")[0]
    alpha = re.sub(r"\d+$", "", label)
    return alpha if len(alpha) >= 4 else label


def brand_variants(brand_name: str) -> set[str]:
    out = {brand_name}
    # Çift harfle başlayan domainler (ör. ddizimag -> dizimag)
    if len(brand_name) >= 5 and brand_name[0] == brand_name[1]:
        out.add(brand_name[1:])
    return out


def _extract_title(body: str) -> str:
    for m in re.findall(r"<title[^>]*>(.*?)</title>", body, re.S | re.I):
        cleaned = re.sub(r"\s+", " ", m).strip()
        if cleaned:
            return cleaned[:90]
    for pat in (
        r'<meta[^>]+(?:property|name)=["\'](?:og:title|twitter:title|apple-mobile-web-app-title)["\'][^>]+content=["\']([^"\']+)["\']',
        r'<meta[^>]+content=["\']([^"\']+)["\'][^>]+(?:property|name)=["\'](?:og:title|twitter:title|apple-mobile-web-app-title)["\']',
        r'<link[^>]+rel=["\']alternate["\'][^>]+title=["\']([^"\']+)["\']',
        r'<html[^>]+data-theme=["\']([^"\']+)["\']',
    ):
        m = re.search(pat, body, re.I)
        if m and m.group(1).strip():
            return re.sub(r"\s+", " ", m.group(1)).strip()[:90]
    return ""


def probe(domain: str, timeout: int = 8):
    target_host = re.sub(r"^https?://", "", domain.strip().lower()).split("/")[0]
    for scheme in ("https://", "http://"):
        try:
            req = urllib.request.Request(
                scheme + target_host,
                headers={"User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9"},
            )
            with urllib.request.urlopen(req, timeout=timeout, context=CTX) as resp:
                body = resp.read(1_000_000).decode("utf-8", "replace")
                title_str = _extract_title(body)
                final_url = resp.geturl() or (scheme + target_host)
                final_host = re.sub(r"^www\.", "", urllib.parse.urlparse(final_url).netloc.lower()) or target_host
                if any(bh in final_host for bh in BLOCKED_HOSTS):
                    continue
                body_low = body.lower()
                if "erisime_engellenmis" in body_low or "erişime engellenmiştir" in body_low or "5651 sayılı" in body_low:
                    continue
                return (
                    final_host,
                    resp.status,
                    title_str,
                    body_low,
                )
        except Exception:  # noqa: BLE001 - any failure just means "not a mirror"
            continue
    return target_host, None, "", ""


def structural_score(module: str, body: str, repo_root: pathlib.Path | None = None) -> int:
    """Check if the candidate HTML contains DOM/path tokens used by the existing Kotlin provider."""
    if not module or not body:
        return 0
    base_dir = (repo_root or ROOT) / "providers" / module
    if not base_dir.is_dir():
        return 0
    tokens: set[str] = set()
    for kt in base_dir.rglob("*Provider.kt"):
        try:
            src = kt.read_text(encoding="utf-8")
        except Exception:
            continue
        for sel in re.findall(r'select(?:First)?\("([^"]+)"\)', src):
            for cls in re.findall(r"\.([a-zA-Z0-9_-]{4,})", sel):
                tokens.add(cls.lower())
            for id_tok in re.findall(r"#([a-zA-Z0-9_-]{4,})", sel):
                tokens.add(id_tok.lower())
        for path_tok in re.findall(
            r'(?:\$mainUrl|["\'])(/(?:api|dizi|film|tur|kategori|series|movies|anime|bolum|search|ara|storage)[a-zA-Z0-9_/-]*)',
            src,
        ):
            if len(path_tok) >= 4:
                tokens.add(path_tok.lower())
    if not tokens:
        return 0
    hits = sum(1 for tok in tokens if tok in body)
    return 2 if hits >= 2 else (1 if hits == 1 else 0)


def score(
    brand_name: str,
    title: str,
    body: str,
    final_domain: str = "",
    module: str = "",
    repo_root: pathlib.Path | None = None,
) -> int:
    low_title = title.lower()
    if any(bad in low_title for bad in BAD_TITLE_WORDS) or any(bad in body[:4000] for bad in BAD_BODY_WORDS):
        return 0
    variants = brand_variants(brand_name)
    if final_domain:
        final_brand = brand(final_domain)
        # Klon site koruması: yönlenen domain farklı bir kelimeyse (ör. dizibox -> diziboxum)
        # ve sayfa başlığında tam kelime olarak marka geçmiyorsa reddet.
        if final_brand not in variants and not any(
            re.search(rf"\b{re.escape(v)}\b", low_title) for v in variants
        ):
            return 0
    points = 0
    if any(re.search(rf"\b{re.escape(v)}\b", low_title) or v in low_title or v in low_title.replace(" ", "") for v in variants):
        points += 3
    elif any(v in body for v in variants):
        points += 1
    if any(word in low_title for word in STREAM_WORDS):
        points += 2
    if any(word in body for word in STREAM_WORDS):
        points += 1
    if module:
        points += structural_score(module, body, repo_root=repo_root)
    return points


def candidates(domain: str) -> list[str]:
    clean = strip_sub(domain)
    parts = clean.split(".", 1)
    label = parts[0]
    orig_tld = "." + parts[1] if len(parts) > 1 else ".com"
    base = brand(clean)
    out: list[str] = []

    # 1. Sayısal sonekli domainler (ör. dizipal1594.com -> dizipal1595..1624.com)
    m = re.match(r"^([a-z][a-z0-9-]*?[a-z])(\d+)$", label)
    if m and len(m.group(1)) >= 4:
        prefix, num = m.group(1), int(m.group(2))
        num_tlds = list(dict.fromkeys([orig_tld, ".com", ".net", ".org", ".xyz"]))
        for delta in range(1, 31):
            for tld in (num_tlds[:2] if delta > 8 else num_tlds):
                out.append(f"{prefix}{num + delta}{tld}")
        for prev_n in range(max(1, num - 2), num):
            out.append(f"{prefix}{prev_n}{orig_tld}")

    # 2. Standart TLD değişimleri
    for tld in TLDS:
        out.append(base + tld)
        if label != base:
            out.append(label + tld)

    # 3. Sayısız domainin sayı almış olabileceği adaylar (ör. hdfilmcehennemi -> hdfilmcehennemi2.com)
    if not m and len(base) >= 4:
        for n in range(1, 6):
            for tld in dict.fromkeys([orig_tld, ".com", ".net"]):
                out.append(f"{base}{n}{tld}")

    raw_host = re.sub(r"^https?://", "", domain.strip().lower()).split("/")[0]
    seen = {clean, raw_host}
    deduped = []
    for cand in out:
        if cand not in seen:
            seen.add(cand)
            deduped.append(cand)
    return deduped


def external_redirect_candidates(domain: str) -> list[str]:
    """BTK/SNI engelli eski domainin yurt dışından (r.jina.ai) yönlendiği yeni hostları çıkarır."""
    clean = strip_sub(domain)
    b = brand(clean)
    out: list[str] = []
    try:
        req = urllib.request.Request(
            f"https://r.jina.ai/https://{clean}",
            headers={"User-Agent": UA, "Accept": "text/plain"},
        )
        with urllib.request.urlopen(req, timeout=12) as resp:
            txt = resp.read(16384).decode("utf-8", "ignore")
        for m in re.finditer(r"URL Source:\s*https?://([^/\s\)]+)", txt, re.I):
            h = strip_sub(m.group(1))
            if h and h != clean and h not in out:
                out.append(h)
        for m in re.finditer(r"https?://([a-z0-9-]+(?:\.[a-z0-9-]+)+)", txt, re.I):
            h = strip_sub(m.group(1))
            if h and h != clean and b in h and h not in out:
                out.append(h)
    except Exception:
        pass
    return out[:6]


def find_mirror(
    domain: str,
    module: str = "",
    repo_root: pathlib.Path | None = None,
    workers: int = 24,
) -> tuple[str | None, list[dict]]:
    """Probe candidates in parallel and return the best live mirror (score >= 5)."""
    brand_name = brand(domain)
    ext_cands = external_redirect_candidates(domain)
    cand_list = list(dict.fromkeys(ext_cands + candidates(domain)))

    def _work(cand: str) -> dict:
        found, status, title, body = probe(cand)
        pts = (
            score(brand_name, title, body, final_domain=found, module=module, repo_root=repo_root)
            if status == 200
            else 0
        )
        return {"candidate": cand, "domain": found, "http": status, "title": title[:80], "score": pts}

    checks: list[dict] = []
    with cf.ThreadPoolExecutor(max_workers=workers) as pool:
        for res in pool.map(_work, cand_list):
            checks.append(res)

    hits = [c for c in checks if c["http"] == 200 and c["score"] >= 5]
    hits.sort(key=lambda c: (-c["score"], len(c["domain"])))
    best_url = f"https://{hits[0]['domain']}" if hits else None
    checks_sorted = sorted(checks, key=lambda c: (-(c["score"] or 0), c["http"] != 200))
    return best_url, checks_sorted


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


def release_note(entries: list[tuple[str, str, str, int | None]]) -> str | None:
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
        {args.domain: providers.get(args.domain, {"module": ""})}
        if args.domain
        else {d: e for d, e in providers.items() if e.get("health") not in (None, "ok")}
    )
    if not targets:
        print("nothing to revive")
        return 0

    tasks = [(domain, cand) for domain in targets for cand in candidates(domain)]

    def work(task):
        domain, cand = task
        mod = (targets.get(domain) or {}).get("module") or ""
        found, status, title, body = probe(cand)
        pts = score(brand(domain), title, body, final_domain=found, module=mod) if status == 200 else 0
        return domain, found, status, title, pts

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
