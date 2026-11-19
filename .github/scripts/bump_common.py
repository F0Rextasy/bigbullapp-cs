#!/usr/bin/env python3
"""providers/Common değişikliklerinden etkilenen aktif modüllerin sürümünü denetler ve artırır.

Kullanım:
  python .github/scripts/bump_common.py --check [--base HEAD]
  python .github/scripts/bump_common.py --apply [--base HEAD]
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

COMMON_DIR = "providers/Common/src/main/kotlin"
DECL_RE = re.compile(
    r"^(private\s+|internal\s+)?(?:suspend\s+|inline\s+)?"
    r"(?:(?:fun\s+(?:<[^>]+>\s+)?(?:[A-Za-z0-9_?.*<>]+\.)?([A-Za-z_][A-Za-z0-9_]*))"
    r"|(?:(?:data\s+|sealed\s+|enum\s+|open\s+)?class\s+([A-Za-z_][A-Za-z0-9_]*))"
    r"|(?:object\s+([A-Za-z_][A-Za-z0-9_]*))"
    r"|(?:(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)))"
)
HUNK_RE = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", re.M)


def _git(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["git", *args], capture_output=True, text=True, encoding="utf-8", errors="replace")


def _parse_decls(text: str) -> list[tuple[str, bool, int, int, str]]:
    lines = text.splitlines()
    starts: list[tuple[int, str, bool]] = []
    for idx, line in enumerate(lines, 1):
        if line and not line[0].isspace():
            m = DECL_RE.match(line)
            if m:
                is_priv = bool(m.group(1) and "private" in m.group(1))
                name = next(g for g in m.groups()[1:] if g)
                starts.append((idx, name, is_priv))
    out: list[tuple[str, bool, int, int, str]] = []
    for i, (sline, name, is_priv) in enumerate(starts):
        eline = (starts[i + 1][0] - 1) if i + 1 < len(starts) else len(lines)
        body = "\n".join(lines[sline - 1 : eline])
        out.append((name, is_priv, sline, eline, body))
    return out


def extract_public_symbols(path: str) -> set[str]:
    text = Path(path).read_text(encoding="utf-8")
    return {name for name, is_priv, _s, _e, _b in _parse_decls(text) if not is_priv}


def _changed_common_files(base: str, target: str) -> list[str]:
    if target == "HEAD":
        out = _git("diff", "--name-only", base, "HEAD", "--", COMMON_DIR).stdout
    else:
        out = _git("diff", "--name-only", base, "--", COMMON_DIR).stdout
    return [line.strip() for line in out.splitlines() if line.strip().endswith(".kt")]


def _changed_line_ranges(base: str, rel: str, target: str) -> list[tuple[int, int]]:
    if target == "HEAD":
        diff = _git("diff", "-U0", base, "HEAD", "--", rel).stdout
    else:
        diff = _git("diff", "-U0", base, "--", rel).stdout
    ranges: list[tuple[int, int]] = []
    for m in HUNK_RE.finditer(diff):
        start = int(m.group(1))
        count = int(m.group(2) or "1")
        if count == 0:
            ranges.append((max(1, start), max(1, start + 1)))
        else:
            ranges.append((start, start + count - 1))
    return ranges


def affected_public_symbols(base: str, changed_files: list[str] | None = None, target: str = "HEAD") -> set[str]:
    common_files = sorted(str(p).replace("\\", "/") for p in Path(COMMON_DIR).rglob("*.kt"))
    if changed_files is None:
        changed_files = _changed_common_files(base, target)
    else:
        changed_files = [p.replace("\\", "/") for p in changed_files if p.replace("\\", "/").startswith(COMMON_DIR) and p.endswith(".kt")]
    if not changed_files:
        return set()

    all_decls: dict[str, list[tuple[str, bool, int, int, str]]] = {}
    for cf in common_files:
        p = Path(cf)
        if p.is_file():
            all_decls[cf] = _parse_decls(p.read_text(encoding="utf-8"))

    seed_syms: set[str] = set()
    for cf in changed_files:
        decls = all_decls.get(cf, [])
        if not decls:
            continue
        ranges = _changed_line_ranges(base, cf, target)
        matched_any = False
        for name, _priv, sline, eline, _body in decls:
            if any(not (e < sline or s > eline) for s, e in ranges):
                seed_syms.add(name)
                matched_any = True
        if not matched_any:
            seed_syms.update(name for name, priv, _s, _e, _b in decls if not priv)

    closure = set(seed_syms)
    changed = True
    while changed:
        changed = False
        for decls in all_decls.values():
            for name, _priv, _s, _e, body in decls:
                if name in closure:
                    continue
                if any(re.search(rf"\b{re.escape(sym)}\b", body) for sym in closure):
                    closure.add(name)
                    changed = True

    public_names = {name for decls in all_decls.values() for name, priv, _s, _e, _b in decls if not priv}
    return closure & public_names


def _kt_version(text: str) -> int | None:
    m = re.search(r"^\s*version\s*=\s*(\d+)", text, re.M)
    return int(m.group(1)) if m else None


def find_unbumped_dependents(base: str, changed_files: list[str] | None = None, target: str = "HEAD") -> list[tuple[str, int, int, list[str]]]:
    syms = affected_public_symbols(base, changed_files=changed_files, target=target)
    if not syms:
        return []
    sym_pat = re.compile(r"\b(" + "|".join(re.escape(s) for s in sorted(syms)) + r")\b")
    out: list[tuple[str, int, int, list[str]]] = []
    prov_root = Path("providers")
    if not prov_root.is_dir():
        return []
    for mod_dir in sorted(prov_root.iterdir()):
        if not mod_dir.is_dir() or mod_dir.name == "Common":
            continue
        gradle = mod_dir / "build.gradle.kts"
        if not gradle.is_file():
            continue
        g_txt = gradle.read_text(encoding="utf-8")
        if re.search(r"^\s*status\s*=\s*0\b", g_txt, re.M):
            continue
        matched: set[str] = set()
        uses_wildcard = False
        for kt in (mod_dir / "src" / "main").rglob("*.kt"):
            txt = kt.read_text(encoding="utf-8", errors="replace")
            if "import com.bigbull.common.*" in txt:
                uses_wildcard = True
            for m in sym_pat.finditer(txt):
                matched.add(m.group(1))
        if not matched and not uses_wildcard:
            continue
        if target == "HEAD":
            show_cur = _git("show", f"HEAD:providers/{mod_dir.name}/build.gradle.kts")
            yeni = _kt_version(show_cur.stdout) if show_cur.returncode == 0 else _kt_version(g_txt)
        else:
            yeni = _kt_version(g_txt)
        show_old = _git("show", f"{base}:providers/{mod_dir.name}/build.gradle.kts")
        if show_old.returncode != 0:
            continue
        eski = _kt_version(show_old.stdout)
        if eski is not None and yeni is not None and yeni <= eski:
            out.append((mod_dir.name, eski, yeni, sorted(matched or syms)))
    return out


def apply_bumps(base: str) -> list[tuple[str, int, int, list[str]]]:
    pending = find_unbumped_dependents(base, target="WORK")
    for mod, eski, _yeni, _syms in pending:
        gradle = Path("providers") / mod / "build.gradle.kts"
        txt = gradle.read_text(encoding="utf-8")
        new_ver = eski + 1
        updated = re.sub(r"^(\s*version\s*=\s*)\d+", rf"\g<1>{new_ver}", txt, count=1, flags=re.M)
        gradle.write_text(updated, encoding="utf-8")
    return pending


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="HEAD", help="Karşılaştırılacak git ref (varsayılan HEAD)")
    ap.add_argument("--committed", action="store_true", help="Çalışma ağacı yerine <base>..HEAD farkına bak")
    ap.add_argument("--apply", action="store_true", help="Sürümü artmamış bağımlı modülleri +1 artır")
    ap.add_argument("--check", action="store_true", help="Artmamış bağımlı modül varsa hata ile çık")
    args = ap.parse_args()

    if args.apply:
        bumped = apply_bumps(args.base)
        for mod, eski, _y, syms in bumped:
            print(f"bump: {mod} {eski} -> {eski + 1} ({', '.join(syms[:4])})")
        if not bumped:
            print("bump gerekmedi")
        return 0

    target = "HEAD" if args.committed else "WORK"
    pending = find_unbumped_dependents(args.base, target=target)
    for mod, eski, yeni, syms in pending:
        print(f"BEKLEYEN: {mod} ({eski} -> {yeni}) [{', '.join(syms[:4])}]")
    if args.check and pending:
        return 1
    if not pending:
        print("OK: tüm Common bağımlı modüller güncel")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
