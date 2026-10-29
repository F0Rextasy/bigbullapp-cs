#!/usr/bin/env python3
"""Build a self-contained status dashboard.

Reads tools/providers.json, tools/history.jsonl and tools/verify-report.md and
writes tools/dashboard.html (no external assets, opens offline).

Usage:
    python tools/report.py
"""
import html
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
TOOLS = ROOT / "tools"

CSS = """
:root{--bg:#0f1115;--panel:#171a21;--line:#262b36;--fg:#e7eaf0;--muted:#9aa4b2;
--ok:#3fb950;--warn:#d29922;--bad:#f85149;--accent:#58a6ff}
*{box-sizing:border-box}
body{margin:0;padding:32px;background:var(--bg);color:var(--fg);
font:15px/1.5 ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif}
h1{margin:0 0 4px;font-size:24px}
.sub{color:var(--muted);margin-bottom:24px}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin-bottom:24px}
.card{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:14px 16px}
.card b{display:block;font-size:26px;line-height:1.2}
.card span{color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.06em}
.ok{color:var(--ok)}.warn{color:var(--warn)}.bad{color:var(--bad)}.accent{color:var(--accent)}
h2{font-size:16px;margin:28px 0 10px;color:var(--muted);text-transform:uppercase;letter-spacing:.08em}
table{width:100%;border-collapse:collapse;background:var(--panel);border:1px solid var(--line);border-radius:10px;overflow:hidden}
th,td{padding:8px 12px;text-align:left;border-bottom:1px solid var(--line);font-size:13px}
th{color:var(--muted);font-weight:600;font-size:11px;text-transform:uppercase;letter-spacing:.06em}
tr:last-child td{border-bottom:0}
td.mono{font-variant-numeric:tabular-nums;color:var(--muted)}
.pill{display:inline-block;padding:1px 8px;border-radius:999px;font-size:11px;border:1px solid currentColor}
svg.spark{display:block;background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:10px}
"""


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


def sparkline(values: list[int], width: int = 720, height: int = 64) -> str:
    if not values:
        return '<p class="sub">No history yet. Run tools/check.py.</p>'
    lo, hi = min(values), max(values)
    span = max(1, hi - lo)
    step = width / max(1, len(values) - 1)
    points = " ".join(
        f"{i * step:.1f},{height - 6 - (v - lo) / span * (height - 12):.1f}"
        for i, v in enumerate(values)
    )
    return (
        f'<svg class="spark" viewBox="0 0 {width} {height}" width="100%" height="{height}" '
        f'role="img" aria-label="ok providers over time">'
        f'<polyline fill="none" stroke="var(--ok)" stroke-width="2" points="{points}"/>'
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
    last = history[-1]["ts"] if history else "never"
    verify_pass = verify.count("| pass |")
    verify_fail = verify.count("| FAIL |")

    rows = []
    for domain, entry in sorted(implemented.items()):
        health = entry.get("health", "unknown")
        cls = {"ok": "ok", "blocked": "warn", "dead": "bad"}.get(health, "warn")
        rows.append(
            f"<tr><td>{html.escape(domain)}</td><td>{html.escape(entry.get('module', ''))}</td>"
            f"<td><span class='pill {cls}'>{html.escape(health)}</span></td>"
            f"<td class='mono'>{entry.get('latencyMs') or '-'} ms</td>"
            f"<td>{html.escape(entry.get('title', '')[:70])}</td></tr>"
        )

    dead_rows = [
        f"<tr><td>{html.escape(d)}</td><td>{html.escape(e.get('module', ''))}</td>"
        f"<td><span class='pill bad'>{html.escape(e.get('health') or 'unknown')}</span></td>"
        f"<td class='mono'>{len(e.get('mirrors') or [])}</td></tr>"
        for d, e in sorted(registry.items())
        if e.get("status") == "planned" and e.get("health") not in ("ok", None)
    ]

    demand = sorted(
        ((d, e) for d, e in planned.items() if e.get("poolNames")),
        key=lambda kv: -len(kv[1]["poolNames"]),
    )[:10]
    demand_rows = [
        f"<tr><td>{html.escape(d)}</td><td>{html.escape(e.get('module', ''))}</td>"
        f"<td class='mono'>{len(e['poolNames'])}</td></tr>"
        for d, e in demand
    ]

    doc = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>BigBull CS — provider status</title><style>{CSS}</style></head>
<body>
<h1>BigBull CS — provider status</h1>
<div class="sub">Last health run: {html.escape(last)} · {len(registry)} registered · {len(implemented)} implemented · {len(planned)} planned</div>

<div class="cards">
  <div class="card"><span>Implemented</span><b class="accent">{len(implemented)}</b></div>
  <div class="card"><span>Functional verify</span><b class="ok">{verify_pass}</b><span>{verify_fail} failing</span></div>
  <div class="card"><span>Healthy</span><b class="ok">{counts.get('ok', 0)}</b></div>
  <div class="card"><span>Blocked</span><b class="warn">{counts.get('blocked', 0)}</b></div>
  <div class="card"><span>Dead</span><b class="bad">{counts.get('dead', 0)}</b></div>
</div>

<h2>Health trend (ok providers, last 30 runs)</h2>
{sparkline(ok_series)}

<h2>Implemented providers</h2>
<table><thead><tr><th>Domain</th><th>Module</th><th>Health</th><th>Latency</th><th>Title</th></tr></thead>
<tbody>{''.join(rows)}</tbody></table>

<h2>Needs a mirror</h2>
<table><thead><tr><th>Domain</th><th>Module</th><th>Health</th><th>Mirrors</th></tr></thead>
<tbody>{''.join(dead_rows) or '<tr><td colspan="4">none</td></tr>'}</tbody></table>

<h2>Most requested, still missing</h2>
<table><thead><tr><th>Domain</th><th>Module</th><th>Community plugins</th></tr></thead>
<tbody>{''.join(demand_rows) or '<tr><td colspan="3">none</td></tr>'}</tbody></table>

<p class="sub">Generated by <code>tools/report.py</code>. Health is reachability only; functional status comes from <code>tools/verify.py</code>.</p>
</body></html>"""

    out = TOOLS / "dashboard.html"
    out.write_text(doc, encoding="utf-8")
    print(f"dashboard -> {out.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
