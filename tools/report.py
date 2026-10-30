#!/usr/bin/env python3
"""Build a self-contained status dashboard in the omp stats + Hallmark system.

Reads tools/providers.json, tools/history.jsonl and tools/verify-report.md and
writes tools/dashboard.html (opens offline, shares the omp stats left-sidebar shell).

Usage:
    python tools/report.py
"""
import html
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
TOOLS = ROOT / "tools"

CSS = """/* Hallmark · shape: app screen · genre: modern-minimal · theme: Dusk (deal 2/3, seed cloudstream-jvm-provider-console-724a·97fe3eb7·2026-10-29·r0) · redesign: full
 * nav: omp stats left sidebar + fixed topbar · signature: omp stats stat-grid with sparklines, share-bar and provider health tables · states: empty, full · modes: light+dark
 * axes: dark/grotesk-sans/cool · posture: committed · contrast: pass · v2.0.0 */
/* Hallmark · pre-emit critique: P5 H5 E5 S5 R5 V5 · weakest: S, static report cards upgraded to omp stats shell-panel, stat-grid with sparklines, and meter tables */
/* Hallmark · answered F3 · all h1, h2 and h3 headings carry text-wrap: balance */
:root {
  color-scheme: dark light;
  --ink: #f4f4f6;
  --ink-1: #f4f4f6;
  --ink-2: #b4b4be;
  --ink-3: #8b8b96;
  --ink-4: #71717a;
  --bg: #050507;
  --panel: #09090b;
  --card: #0e0e11;
  --raised: #17171c;
  --hover: rgba(255, 255, 255, 0.04);
  --selected: rgba(255, 255, 255, 0.07);
  --line-1: rgba(255, 255, 255, 0.06);
  --line-2: rgba(255, 255, 255, 0.10);
  --line-3: rgba(255, 255, 255, 0.16);
  --ambient-a: rgba(90, 216, 230, 0.08);
  --ambient-b: rgba(237, 74, 191, 0.07);
  --accent-a: #5ad8e6;
  --accent-b: #ed4abf;
  --accent-grad: linear-gradient(135deg, #5ad8e6 0%, #ed4abf 100%);
  --accent-cyan-soft: rgba(90, 216, 230, 0.14);
  --ok: #3dd68c;
  --ok-soft: rgba(61, 214, 140, 0.14);
  --warn: #f5a524;
  --warn-soft: rgba(245, 165, 36, 0.14);
  --bad: #f2555a;
  --bad-soft: rgba(242, 85, 90, 0.14);
  --space-3xs: 2px;
  --space-2xs: 4px;
  --space-xs: 8px;
  --space-sm: 12px;
  --space-md: 16px;
  --space-lg: 20px;
  --space-xl: 24px;
  --space-2xl: 28px;
  --space-3xl: 32px;
  --r-shell: 14px;
  --r-card: 10px;
  --r-control: 8px;
  --r-chip: 6px;
  --r-pill: 999px;
  --topbar-h: 52px;
  --sidebar-w: 232px;
  --font-sans: "Inter", -apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, sans-serif;
  --font-mono: "JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  --ease: cubic-bezier(0.22, 1, 0.36, 1);
  --dur-1: 120ms;
  --dur-2: 200ms;
  --shadow-card: 0 1px 0 rgba(255, 255, 255, 0.04) inset, 0 10px 30px -18px rgba(0, 0, 0, 0.65);
}
[data-mode="light"] {
  color-scheme: light;
  --ink: #111115;
  --ink-1: #111115;
  --ink-2: #3f3f46;
  --ink-3: #52525b;
  --ink-4: #71717a;
  --bg: #f4f4f7;
  --panel: #ffffff;
  --card: #fafafa;
  --raised: #f0f0f4;
  --hover: rgba(17, 17, 21, 0.04);
  --selected: rgba(17, 17, 21, 0.07);
  --line-1: rgba(17, 17, 21, 0.08);
  --line-2: rgba(17, 17, 21, 0.14);
  --line-3: rgba(17, 17, 21, 0.22);
  --accent-a: #0d6977;
  --accent-b: #a81c7d;
  --accent-cyan-soft: rgba(14, 135, 151, 0.12);
  --ok: #13794b;
  --ok-soft: rgba(19, 121, 75, 0.12);
  --warn: #945800;
  --warn-soft: rgba(180, 109, 0, 0.12);
  --bad: #b91c1c;
  --bad-soft: rgba(209, 43, 47, 0.12);
  --shadow-card: 0 1px 2px rgba(17, 17, 21, 0.05);
}
*, *::before, *::after { box-sizing: border-box; }
html {
  height: 100%;
  overflow-x: clip;
  scrollbar-color: var(--line-3) var(--panel);
}
::selection {
  background: var(--accent-cyan-soft);
  color: var(--ink-1);
}
body {
  margin: 0;
  min-height: 100%;
  overflow-x: clip;
  background: var(--bg);
  color: var(--ink-1);
  font-family: var(--font-sans);
  font-size: 0.875rem;
  line-height: 1.5;
  -webkit-font-smoothing: antialiased;
}
:focus-visible { outline: 2px solid var(--accent-a); outline-offset: 2px; }
h1, h2, h3 {
  margin: 0;
  font-weight: 600;
  letter-spacing: -0.02em;
  line-height: 1.2;
  overflow-wrap: break-word;
  min-width: 0;
  text-wrap: balance;
}
p { margin: 0; text-wrap: pretty; max-width: 68ch; }
button {
  font: inherit;
  color: inherit;
  background: none;
  border: 0;
  cursor: pointer;
}
button:disabled {
  opacity: 0.48;
  cursor: not-allowed;
}
.shell-ambient {
  position: fixed;
  inset: 0;
  pointer-events: none;
  z-index: 0;
  background:
    radial-gradient(640px 280px at 14% -6%, var(--ambient-a), transparent 65%),
    radial-gradient(560px 260px at 86% -8%, var(--ambient-b), transparent 65%);
}
.topbar {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: var(--topbar-h);
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  padding: 0 var(--space-md);
  background: color-mix(in srgb, var(--bg) 86%, transparent);
  backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--line-1);
}
.topbar-left, .topbar-right {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}
.brand {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  color: var(--ink-1);
  text-decoration: none;
  font-weight: 600;
  font-size: 0.875rem;
}
.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 6px;
  background: var(--hover);
  border: 1px solid var(--line-2);
}
.brand-sep { color: var(--ink-3); }
.brand-sub { color: var(--ink-2); font-weight: 500; }
.sidebar {
  position: fixed;
  top: var(--topbar-h);
  left: 0;
  bottom: 0;
  width: var(--sidebar-w);
  z-index: 25;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: var(--space-sm) var(--space-xs);
  background: var(--bg);
}
.nav-section {
  display: flex;
  flex-direction: column;
  gap: var(--space-3xs);
  margin-bottom: var(--space-md);
}
.nav-heading {
  padding: var(--space-2xs) var(--space-sm);
  color: var(--ink-3);
  font-size: 0.75rem;
  font-weight: 600;
}
.nav-link {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 36px;
  padding: var(--space-xs) var(--space-sm);
  border-radius: var(--r-control);
  color: var(--ink-2);
  text-decoration: none;
  font-size: 0.8125rem;
  font-weight: 500;
  transition: background-color var(--dur-1) var(--ease), color var(--dur-1) var(--ease);
}
@media (hover: hover) {
  .nav-link:hover {
    background: var(--hover);
    color: var(--ink-1);
  }
}
.nav-link[aria-current="page"] {
  background: var(--raised);
  color: var(--ink-1);
  box-shadow: inset 2px 0 0 var(--accent-a);
}
.nav-count {
  font-family: var(--font-mono);
  font-size: 0.6875rem;
  color: var(--ink-3);
}
.sidebar-foot {
  padding: var(--space-sm);
  border-radius: var(--r-card);
  border: 1px solid var(--line-1);
  background: var(--panel);
  color: var(--ink-3);
  font-size: 0.6875rem;
}
.shell-panel {
  position: relative;
  z-index: 1;
  margin: var(--topbar-h) var(--space-xs) var(--space-xs) var(--sidebar-w);
  min-height: calc(100vh - var(--topbar-h) - var(--space-xs));
  border-radius: var(--r-shell);
  background: var(--panel);
  border: 1px solid var(--line-1);
  padding: var(--space-xl);
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-md);
  flex-wrap: wrap;
}
.page-title { font-size: 1.25rem; }
.page-sub { color: var(--ink-2); font-size: 0.8125rem; margin-top: var(--space-2xs); }
.chip {
  min-height: 36px;
  height: 36px;
  padding: 0 var(--space-sm);
  background: var(--raised);
  color: var(--ink-1);
  border: 1px solid var(--line-2);
  border-radius: var(--r-control);
  font-size: 0.8125rem;
  font-weight: 500;
  transition: background-color var(--dur-1) var(--ease), border-color var(--dur-1) var(--ease), transform var(--dur-1) var(--ease);
}
.chip:active:not(:disabled) {
  transform: scale(0.98);
}
@media (hover: hover) {
  .chip:hover:not(:disabled) {
    background: var(--selected);
    border-color: var(--line-3);
  }
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  border-radius: var(--r-card);
  border: 1px solid var(--line-1);
  background: var(--card);
  box-shadow: var(--shadow-card);
  overflow: hidden;
}
.stat {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: var(--space-xs);
  padding: var(--space-md);
  border-right: 1px solid var(--line-1);
}
.stat:last-child { border-right: 0; }
.stat-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--ink-2);
  font-size: 0.75rem;
  font-weight: 500;
}
.stat-value {
  font-size: 1.5rem;
  font-weight: 600;
  letter-spacing: -0.025em;
  font-variant-numeric: tabular-nums;
}
.stat-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--ink-3);
  font-size: 0.75rem;
}
.swatch {
  width: 6px;
  height: 6px;
  border-radius: 2px;
  display: inline-block;
}
.ok { color: var(--ok); }
.warn { color: var(--warn); }
.bad { color: var(--bad); }
.accent { color: var(--accent-a); }
.omp-card {
  border-radius: var(--r-card);
  border: 1px solid var(--line-1);
  background: var(--card);
  box-shadow: var(--shadow-card);
  overflow: hidden;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
  padding: var(--space-md) var(--space-md) var(--space-sm);
  border-bottom: 1px solid var(--line-1);
}
.card-title { font-size: 0.875rem; font-weight: 600; }
.card-sub { color: var(--ink-3); font-size: 0.75rem; }
.card-body { padding: var(--space-md); }
.card-body.flush { padding: 0; }
svg.spark {
  display: block;
  width: 100%;
  height: 72px;
}
.table-wrap {
  width: 100%;
  max-height: 480px;
  overflow: auto;
  overscroll-behavior: contain;
  scrollbar-width: thin;
  scrollbar-color: var(--line-3) transparent;
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.8125rem;
}
th, td {
  padding: var(--space-xs) var(--space-md);
  text-align: left;
  border-bottom: 1px solid var(--line-1);
  white-space: nowrap;
}
th {
  color: var(--ink-3);
  font-weight: 500;
  font-size: 0.75rem;
}
tbody tr:last-child td { border-bottom: 0; }
@media (hover: hover) {
  tbody tr:hover { background: var(--hover); }
}
td.mono {
  font-family: var(--font-mono);
  font-size: 0.75rem;
  font-variant-numeric: tabular-nums;
  color: var(--ink-2);
}
.pill {
  display: inline-flex;
  align-items: center;
  padding: var(--space-3xs) var(--space-xs);
  border-radius: var(--r-pill);
  font-size: 0.6875rem;
  font-weight: 500;
}
.pill.ok { color: var(--ok); background: var(--ok-soft); }
.pill.warn { color: var(--warn); background: var(--warn-soft); }
.pill.bad { color: var(--bad); background: var(--bad-soft); }
.foot { color: var(--ink-3); font-size: 0.75rem; }
@media (max-width: 900px) {
  .sidebar { display: none; }
  .shell-panel {
    margin-left: var(--space-xs);
    padding: var(--space-md);
  }
  .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .stat {
    border-bottom: 1px solid var(--line-1);
  }
}
@media (max-width: 600px) {
  thead { display: none; }
  tbody tr {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2xs) var(--space-sm);
    padding: var(--space-xs) var(--space-md);
    border-bottom: 1px solid var(--line-1);
  }
  tbody tr:last-child { border-bottom: 0; }
  td {
    border-bottom: 0;
    padding: 0;
    white-space: normal;
    font-size: 0.75rem;
  }
}
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { transition-duration: 0.01ms !important; animation-duration: 0.01ms !important; }
}
"""


def clean_text(val: str) -> str:
    return (
        html.unescape(val or "")
        .replace("\u2014", "-")
        .replace("\u2013", "-")
        .replace("\u2026", "")
        .replace("...", "")
        .replace("\ufffd", "")
        .strip()
    )


def load_jsonl(path: pathlib.Path) -> list[dict]:
    if not path.exists():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return rows


def sparkline(values: list[int], width: int = 720, height: int = 72) -> str:
    if not values:
        return '<p class="card-sub">Henüz sağlık geçmişi yok. Önce tools/check.py çalıştırın.</p>'
    lo, hi = min(values), max(values)
    span = max(1, hi - lo)
    step = width / max(1, len(values) - 1)
    points = " ".join(
        f"{i * step:.1f},{height - 8 - (v - lo) / span * (height - 16):.1f}"
        for i, v in enumerate(values)
    )
    return (
        f'<svg class="spark" viewBox="0 0 {width} {height}" width="100%" height="{height}" '
        f'role="img" aria-label="Zaman içinde sağlıklı sağlayıcı sayısı">'
        f'<polyline fill="none" stroke="var(--accent-a)" stroke-width="2" points="{points}"/>'
        f'</svg>'
    )


def main() -> int:
    registry = json.loads((TOOLS / "providers.json").read_text(encoding="utf-8"))["providers"]
    history = load_jsonl(TOOLS / "history.jsonl")
    verify = (TOOLS / "verify-report.md").read_text(encoding="utf-8") if (TOOLS / "verify-report.md").exists() else ""

    implemented = {d: e for d, e in registry.items() if e.get("status") == "implemented"}
    planned = {d: e for d, e in registry.items() if e.get("status") == "planned"}
    counts = {"ok": 0, "blocked": 0, "error": 0, "dead": 0, "unknown": 0}
    for entry in registry.values():
        counts[entry.get("health") or "unknown"] = counts.get(entry.get("health") or "unknown", 0) + 1

    ok_series = [h.get("ok", 0) for h in history][-30:]
    last = clean_text(history[-1]["ts"]) if history else "çalıştırılmadı"
    verify_pass = verify.count("| pass |")
    verify_fail = verify.count("| FAIL |")

    rows = []
    for domain, entry in sorted(implemented.items()):
        health = clean_text(entry.get("health", "unknown"))
        cls = {"ok": "ok", "blocked": "warn", "dead": "bad"}.get(health, "warn")
        title_clean = clean_text(entry.get("title", ""))[:64]
        rows.append(
            f"<tr><td class='mono'>{html.escape(clean_text(domain))}</td>"
            f"<td>{html.escape(clean_text(entry.get('module', '')))}</td>"
            f"<td><span class='pill {cls}'>{html.escape(health)}</span></td>"
            f"<td class='mono'>{entry.get('latencyMs') or 0}\u00a0ms</td>"
            f"<td>{html.escape(title_clean)}</td></tr>"
        )

    dead_rows = [
        f"<tr><td class='mono'>{html.escape(clean_text(d))}</td>"
        f"<td>{html.escape(clean_text(e.get('module', '')))}</td>"
        f"<td><span class='pill bad'>{html.escape(clean_text(e.get('health') or 'unknown'))}</span></td>"
        f"<td class='mono'>{len(e.get('mirrors') or [])}</td></tr>"
        for d, e in sorted(registry.items())
        if e.get("status") == "planned" and e.get("health") not in ("ok", None)
    ]

    demand = sorted(
        ((d, e) for d, e in planned.items() if e.get("poolNames")),
        key=lambda kv: -len(kv[1]["poolNames"]),
    )[:10]
    demand_rows = [
        f"<tr><td class='mono'>{html.escape(clean_text(d))}</td>"
        f"<td>{html.escape(clean_text(e.get('module', '')))}</td>"
        f"<td class='mono'>{len(e['poolNames'])}</td></tr>"
        for d, e in demand
    ]

    doc = f"""<!doctype html>
<html lang="tr" data-mode="dark"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="description" content="BigBull Cloudstream sağlayıcı sağlık ve doğrulama durumu.">
<title>BigBull Cloudstream sağlayıcı durumu</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet">
<style>{CSS}</style></head>
<body>
<div class="shell-ambient" aria-hidden="true"></div>
<header class="topbar">
  <div class="topbar-left">
    <a class="brand" href="#ozet">
      <span class="brand-mark" aria-hidden="true">CS</span>
      <span>cloudstream</span>
      <span class="brand-sep">/</span>
      <span class="brand-sub">health</span>
    </a>
  </div>
  <div class="topbar-right">
    <button class="chip" id="modeBtn" type="button">Açık mod</button>
  </div>
</header>

<aside class="sidebar" aria-label="Bölüm gezintisi">
  <div>
    <div class="nav-section">
      <div class="nav-heading">Gözlem</div>
      <a class="nav-link" href="#ozet" aria-current="page"><span>Genel Bakış</span><span class="nav-count">{len(implemented)}</span></a>
      <a class="nav-link" href="#aktif"><span>Aktif Sağlayıcılar</span><span class="nav-count">{len(implemented)}</span></a>
      <a class="nav-link" href="#mirror"><span>Mirror Bekleyenler</span><span class="nav-count">{len(dead_rows)}</span></a>
      <a class="nav-link" href="#talep"><span>Planlı Modüller</span><span class="nav-count">{len(demand_rows)}</span></a>
    </div>
  </div>
  <div class="sidebar-foot">
    Son tarama: {html.escape(last)}
  </div>
</aside>

<main class="shell-panel" id="ozet">
  <div class="page-header">
    <div>
      <h1 class="page-title">BigBull Cloudstream sağlayıcı durumu</h1>
      <p class="page-sub">Son tarama: {html.escape(last)}, {len(registry)} kayıtlı, {len(implemented)} aktif, {len(planned)} planlanan</p>
    </div>
  </div>

  <div class="stat-grid">
    <div class="stat">
      <div class="stat-label"><span>Aktif modül</span><span class="swatch" style="background: var(--accent-a)"></span></div>
      <div class="stat-value accent">{len(implemented)}</div>
      <div class="stat-foot"><span>Üretimde</span><span>{len(registry)} kayıt</span></div>
    </div>
    <div class="stat">
      <div class="stat-label"><span>Fonksiyonel test</span><span class="swatch" style="background: var(--ok)"></span></div>
      <div class="stat-value ok">{verify_pass}</div>
      <div class="stat-foot"><span>Geçen</span><span>{verify_fail} hatalı</span></div>
    </div>
    <div class="stat">
      <div class="stat-label"><span>Erişilebilir</span><span class="swatch" style="background: var(--ok)"></span></div>
      <div class="stat-value ok">{counts.get('ok', 0)}</div>
      <div class="stat-foot"><span>HTTP 200</span><span>Aktif</span></div>
    </div>
    <div class="stat">
      <div class="stat-label"><span>Engelli</span><span class="swatch" style="background: var(--warn)"></span></div>
      <div class="stat-value warn">{counts.get('blocked', 0)}</div>
      <div class="stat-foot"><span>Koruma</span><span>HTTP 403</span></div>
    </div>
    <div class="stat">
      <div class="stat-label"><span>Düşmüş</span><span class="swatch" style="background: var(--bad)"></span></div>
      <div class="stat-value bad">{counts.get('dead', 0)}</div>
      <div class="stat-foot"><span>Alan adı</span><span>Mirror bekliyor</span></div>
    </div>
  </div>

  <section class="omp-card">
    <div class="card-header">
      <div>
        <h2 class="card-title">Sağlık eğilimi (son 30 tarama)</h2>
        <p class="card-sub">Erişilebilir sağlayıcı sayısının zaman içindeki değişimi</p>
      </div>
    </div>
    <div class="card-body">
      {sparkline(ok_series)}
    </div>
  </section>

  <section class="omp-card" id="aktif">
    <div class="card-header">
      <div>
        <h2 class="card-title">Aktif sağlayıcılar</h2>
        <p class="card-sub">Üretimdeki Kotlin modüllerinin son HTTP sağlık ve gecikme ölçümleri</p>
      </div>
    </div>
    <div class="card-body flush">
      <div class="table-wrap">
        <table><thead><tr><th>Alan adı</th><th>Modül</th><th>Sağlık</th><th>Gecikme</th><th>Başlık</th></tr></thead>
        <tbody>{''.join(rows)}</tbody></table>
      </div>
    </div>
  </section>

  <section class="omp-card" id="mirror">
    <div class="card-header">
      <div>
        <h2 class="card-title">Mirror bekleyenler</h2>
        <p class="card-sub">Erişilemeyen ve yeni alan adı araması gereken planlı siteler</p>
      </div>
    </div>
    <div class="card-body flush">
      <div class="table-wrap">
        <table><thead><tr><th>Alan adı</th><th>Modül</th><th>Sağlık</th><th>Mirror sayısı</th></tr></thead>
        <tbody>{''.join(dead_rows) or '<tr><td colspan="4">Mirror bekleyen alan adı yok.</td></tr>'}</tbody></table>
      </div>
    </div>
  </section>

  <section class="omp-card" id="talep">
    <div class="card-header">
      <div>
        <h2 class="card-title">En çok talep edilen planlı modüller</h2>
        <p class="card-sub">Topluluk havuzlarında en sık tekrarlanan sağlayıcı adayları</p>
      </div>
    </div>
    <div class="card-body flush">
      <div class="table-wrap">
        <table><thead><tr><th>Alan adı</th><th>Modül</th><th>Topluluk eklentisi</th></tr></thead>
        <tbody>{''.join(demand_rows) or '<tr><td colspan="3">Kayıt yok.</td></tr>'}</tbody></table>
      </div>
    </div>
  </section>

  <p class="foot"><code>tools/report.py</code> tarafından üretildi. Sağlık sütunu erişilebilirliği, fonksiyonel test ise <code>tools/verify.py</code> sonucunu gösterir.</p>
</main>
<script>
const btn = document.getElementById("modeBtn");
btn.addEventListener("click", () => {{
  const next = document.documentElement.dataset.mode === "light" ? "dark" : "light";
  document.documentElement.dataset.mode = next;
  btn.textContent = next === "dark" ? "Açık mod" : "Koyu mod";
}});
</script>
</body></html>"""

    out = TOOLS / "dashboard.html"
    out.write_text(doc, encoding="utf-8")
    print(f"dashboard -> {out.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
