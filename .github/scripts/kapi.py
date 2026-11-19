#!/usr/bin/env python3
"""CI kapısı: sürüm artışı, .cs3 sayısı, README/envanter senkronu ve Nuvio sayısı.

  kapi.py surum <taban-sha>   providers/<X>/src ve nuvio/providers/<id>.js değişince sürüm artmalı
  kapi.py sayi                providers/*/build/*.cs3 sayısı aktif modül sayısına eşit olmalı
  kapi.py envanter            README tablosu, Settings/openSettings/hasMainPage ve Nuvio=32 uyumu
  kapi.py test                kendi kendine kontrol
"""
import glob
import json
import os
import re
import subprocess
import sys

ZERO = "0" * 40
# Common değişiminde etkilenen aktif modüller bump_common ile denetlenir; InatShared kullanılmıyor.
ORTAK = {"Common", "InatShared"}
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)
import bump_common  # noqa: E402


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
    if any(p.startswith("providers/Common/src/main/kotlin/") for p in degisen):
        for mod, eski, yeni, syms in bump_common.find_unbumped_dependents(taban, changed_files=degisen, target="HEAD"):
            hata.append(
                f"{mod}: Common ({', '.join(syms[:3])}) değişti ama version {eski} -> {yeni} artmadı "
                "(python .github/scripts/bump_common.py --apply)"
            )
    return hata


def sayi():
    beklenen, var = len(aktif_moduller()), len(glob.glob("providers/*/build/*.cs3"))
    return [] if beklenen == var else [f".cs3 sayısı {var}, aktif modül sayısı {beklenen}"]


def readme_satirlari(metin=None):
    if metin is None:
        metin = open("README.md", encoding="utf-8").read()
    aktif, pasif = set(), set()
    for satir in metin.splitlines():
        m = re.match(r"^\|\s*`([^`]+)`(.*)$", satir)
        if not m:
            continue
        ad, kalan = m.group(1).strip(), m.group(2)
        if "**pasif**" in kalan:
            pasif.add(ad)
        else:
            aktif.add(ad)
    return aktif, pasif


def envanter():
    hata = []
    aktif_disk = set(aktif_moduller())
    pasif_disk = devre_disi()
    try:
        r_aktif, r_pasif = readme_satirlari()
    except OSError as exc:
        return [f"README.md okunamadı: {exc}"]

    eksik_readme = sorted(aktif_disk - r_aktif)
    fazla_readme = sorted(r_aktif - aktif_disk)
    if eksik_readme:
        hata.append(f"README.md eksik aktif modüller: {', '.join(eksik_readme)}")
    if fazla_readme:
        hata.append(f"README.md aktif olup diskte olmayanlar: {', '.join(fazla_readme)}")

    eksik_pasif = sorted(pasif_disk - r_pasif)
    fazla_pasif = sorted(r_pasif - pasif_disk)
    if eksik_pasif:
        hata.append(f"README.md eksik pasif modüller: {', '.join(eksik_pasif)}")
    if fazla_pasif:
        hata.append(f"README.md pasif olup diskte olmayanlar: {', '.join(fazla_pasif)}")

    for mod in sorted(aktif_disk):
        kok = f"providers/{mod}"
        has_settings = False
        has_open_settings = False
        has_main_false = False
        for dirpath, _dirs, files in os.walk(kok):
            for fn in files:
                yol = os.path.join(dirpath, fn)
                if fn == "Settings.kt":
                    has_settings = True
                elif fn.endswith("Plugin.kt"):
                    if "openSettings" in open(yol, encoding="utf-8").read():
                        has_open_settings = True
                elif fn.endswith("Provider.kt"):
                    if re.search(r"\bhasMainPage\s*=\s*false\b", open(yol, encoding="utf-8").read()):
                        has_main_false = True
        if not has_settings:
            hata.append(f"{mod}: Settings.kt eksik")
        if not has_open_settings:
            hata.append(f"{mod}: Plugin.kt içinde openSettings eksik")
        if has_main_false:
            hata.append(f"{mod}: hasMainPage = false yasak")

    disk_nuvio = {os.path.splitext(os.path.basename(p))[0] for p in glob.glob("nuvio/providers/*.js")}
    if len(disk_nuvio) < 20:
        hata.append(f"nuvio/providers/*.js sayısı {len(disk_nuvio)}, en az 20 olmalı")
    try:
        with open("nuvio/manifest.json", encoding="utf-8") as mf:
            manifest = json.load(mf)
        manifest_nuvio = {str(p.get("id") or "") for p in manifest.get("scrapers", []) if isinstance(p, dict) and p.get("id")}
        eksik_m = sorted(disk_nuvio - manifest_nuvio)
        fazla_m = sorted(manifest_nuvio - disk_nuvio)
        if eksik_m:
            hata.append(f"nuvio/manifest.json eksik sağlayıcılar: {', '.join(eksik_m)}")
        if fazla_m:
            hata.append(f"nuvio/manifest.json diskte olmayan sağlayıcılar: {', '.join(fazla_m)}")
    except Exception as exc:
        hata.append(f"nuvio/manifest.json okunamadı: {exc}")
    return hata


def test():
    assert kt_surum("// x\nversion = 10\n\ncloudstream {") == 10
    assert kt_surum("cloudstream { version = 3 }") is None  # satır başı değil
    assert js_surum('// @provider {"id":"a", "version":"1.0.12"}\ncode') == "1.0.12"
    assert js_surum("// @provider {}\n") is None
    assert "AsyaLuna" in devre_disi() and "Dizibox" not in devre_disi()
    assert "Dizibox" in aktif_moduller() and "AsyaLuna" not in aktif_moduller()
    ra, rp = readme_satirlari("| `Dizibox` | x |\n| `AsyaLuna` **pasif** | y |\n")
    assert ra == {"Dizibox"} and rp == {"AsyaLuna"}
    common_syms = bump_common.extract_public_symbols("providers/Common/src/main/kotlin/com/bigbull/common/Common.kt")
    assert "pichiveGateNonce" in common_syms and "parseHlsVariants" in common_syms
    env_hatalari = envanter()
    assert not env_hatalari, f"envanter hatası: {env_hatalari}"
    print("kapi.py: testler geçti")


if __name__ == "__main__":
    komut = sys.argv[1] if len(sys.argv) > 1 else ""
    if komut == "test":
        test()
        sys.exit(0)
    hatalar = (
        surum(sys.argv[2] if len(sys.argv) > 2 else "") if komut == "surum"
        else sayi() if komut == "sayi"
        else envanter() if komut == "envanter"
        else None
    )
    if hatalar is None:
        sys.exit(__doc__)
    for h in hatalar:
        print("HATA:", h)
    sys.exit(1 if hatalar else 0)
