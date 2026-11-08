#!/usr/bin/env python3
"""CI kapısı: kaynak değişen modülün sürümü artmış mı, .cs3 sayısı modül sayısına eşit mi.

  kapi.py surum <taban-sha>   providers/<X>/src ve nuvio/providers/<id>.js değişince sürüm artmalı
  kapi.py sayi                providers/*/build/*.cs3 sayısı aktif modül sayısına eşit olmalı
  kapi.py test                kendi kendine kontrol
"""
import glob
import re
import subprocess
import sys

ZERO = "0" * 40
# shortcut: ortak modüller (Common, InatShared) hangi modülleri etkilediğini bilmez; sürüm şartı aranmaz
ORTAK = {"Common", "InatShared"}


def git(*a):
    return subprocess.run(["git", *a], capture_output=True, text=True, encoding="utf-8").stdout


def kt_surum(metin):
    m = re.search(r"^\s*version\s*=\s*(\d+)", metin or "", re.M)
    return int(m.group(1)) if m else None


def js_surum(metin):
    m = re.search(r'"version"\s*:\s*"([^"]+)"', (metin or "").split("\n", 1)[0])
    return m.group(1) if m else None


def devre_disi():
    import os
    off = set()
    for d in os.listdir("providers"):
        p = f"providers/{d}/build.gradle.kts"
        if os.path.exists(p) and re.search(r"^\s*status\s*=\s*0\b", open(p, encoding="utf-8").read(), re.M):
            off.add(d)
    return off


def aktif_moduller():
    import os
    off = devre_disi()
    return sorted(d for d in os.listdir("providers")
                  if d not in off and os.path.exists(f"providers/{d}/build.gradle.kts"))


def surum(taban):
    if not taban or taban == ZERO:
        taban = "HEAD~1"
    degisen = [x for x in git("diff", "--name-only", taban, "HEAD").splitlines() if x]
    hata = []
    for mod in sorted({p.split("/")[1] for p in degisen if p.startswith("providers/") and "/src/" in p}):
        if mod in ORTAK:
            continue
        eski = kt_surum(git("show", f"{taban}:providers/{mod}/build.gradle.kts"))
        try:
            yeni = kt_surum(open(f"providers/{mod}/build.gradle.kts", encoding="utf-8").read())
        except FileNotFoundError:
            continue  # silinmiş
        if eski is not None and yeni is not None and yeni <= eski:
            hata.append(f"{mod}: kaynak değişti ama version {eski} -> {yeni} artmadı")
    for p in degisen:
        m = re.fullmatch(r"nuvio/providers/(\w+)\.js", p)
        if not m:
            continue
        eski = js_surum(git("show", f"{taban}:{p}"))
        try:
            yeni = js_surum(open(p, encoding="utf-8").read())
        except FileNotFoundError:
            continue  # silinmiş
        if eski is not None and yeni == eski:
            hata.append(f"nuvio/{m.group(1)}: dosya değişti ama @provider version {eski} aynı")
    return hata


def sayi():
    beklenen, var = len(aktif_moduller()), len(glob.glob("providers/*/build/*.cs3"))
    return [] if beklenen == var else [f".cs3 sayısı {var}, aktif modül sayısı {beklenen}"]


def test():
    assert kt_surum("// x\nversion = 10\n\ncloudstream {") == 10
    assert kt_surum("cloudstream { version = 3 }") is None  # satır başı değil
    assert js_surum('// @provider {"id":"a", "version":"1.0.12"}\ncode') == "1.0.12"
    assert js_surum("// @provider {}\n") is None
    assert "AsyaLuna" in devre_disi() and "Dizibox" not in devre_disi()
    assert "Dizibox" in aktif_moduller() and "AsyaLuna" not in aktif_moduller()
    print("kapi.py: testler geçti")


if __name__ == "__main__":
    komut = sys.argv[1] if len(sys.argv) > 1 else ""
    if komut == "test":
        test()
        sys.exit(0)
    hatalar = surum(sys.argv[2] if len(sys.argv) > 2 else "") if komut == "surum" else sayi() if komut == "sayi" else None
    if hatalar is None:
        sys.exit(__doc__)
    for h in hatalar:
        print("HATA:", h)
    sys.exit(1 if hatalar else 0)
