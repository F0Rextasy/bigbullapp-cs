#!/usr/bin/env python3
"""CI kapısı: sürüm artışı, .cs3 sayısı, README/envanter senkronu ve Nuvio sayısı.

  kapi.py surum <taban-sha>   providers/<X>/src ve nuvio/providers/<id>.js değişince sürüm artmalı
  kapi.py sayi                providers/*/build/*.cs3 sayısı aktif modül sayısına eşit olmalı
  kapi.py envanter            README tablosu, Settings/openSettings/hasMainPage ve Nuvio/CanlıTV uyumu
  kapi.py sync-readme         README.md sayılarını ve 6 özellik sütununu koda göre günceller
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


def gradle_tv_types(metin):
    m = re.search(r"tvTypes\s*=\s*listOf\(([^)]*)\)", metin or "", re.S)
    return sorted(re.findall(r'"([^"]+)"', m.group(1))) if m else []


def provider_supported_types(metin):
    m = re.search(r"override\s+val\s+supportedTypes\s*=\s*setOf\(([^)]*)\)", metin or "", re.S)
    return sorted(re.findall(r"TvType\.(\w+)", m.group(1))) if m else []


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


def _strip_kotlin_comments(code):
    code = re.sub(r"/\*[\s\S]*?\*/", "", code)
    return re.sub(r"//[^\n]*", "", code)


def _has_pagination(code):
    for m in re.finditer(r"newHomePageResponse\(([^;\n]+)", code):
        arg = m.group(1)
        if re.search(r"\bhasNext\s*=\s*(?!false\b)", arg):
            return True
        if not re.search(r"\bhasNext\s*=", arg) and arg.count(",") >= 2:
            if arg.rsplit(",", 1)[-1].strip().rstrip(")") != "false":
                return True
    for m in re.finditer(r"newSearchResponseList\(([^;\n]+)", code):
        arg = m.group(1)
        if re.search(r"\bhasNext\s*=\s*(?!false\b)", arg):
            return True
        if not re.search(r"\bhasNext\s*=", arg) and "," in arg:
            if arg.rsplit(",", 1)[-1].strip().rstrip(")") != "false":
                return True
    for m in re.finditer(r"HomePageList\(([^;\n]+)", code):
        if re.search(r"\bhasNext\s*=\s*(?!false\b)", m.group(1)):
            return True
    return False


def modul_ozellikleri(mod):
    kok = f"providers/{mod}/src/main/kotlin"
    parcalar = []
    for dirpath, _dirs, files in os.walk(kok):
        for fn in files:
            if fn.endswith(".kt"):
                parcalar.append(open(os.path.join(dirpath, fn), encoding="utf-8", errors="replace").read())
    c = _strip_kotlin_comments("\n".join(parcalar))
    return (
        _has_pagination(c),
        bool(re.search(r"\bthis\.recommendations\s*=", c)),
        bool(re.search(r"\baddTrailer\s*\(", c)),
        bool(re.search(r"\baddActors\s*\(", c)),
        bool(re.search(r"\bthis\.duration\s*=", c)),
        bool(re.search(r"\bthis\.score\s*=", c)),
    )


def _readme_metrikleri():
    aktif = aktif_moduller()
    pasif = sorted(devre_disi())
    manifest = json.load(open("nuvio/manifest.json", encoding="utf-8"))
    scrapers = [s for s in manifest.get("scrapers", []) if isinstance(s, dict) and s.get("id")]
    nuvio_total = len(scrapers)
    nuvio_active = sum(1 for s in scrapers if s.get("enabled", True))
    live_rows, live_channels = 0, 0
    if os.path.exists("nuvio/addon-live/manifest.json"):
        lm = json.load(open("nuvio/addon-live/manifest.json", encoding="utf-8"))
        live_rows = len(lm.get("catalogs", []))
    if os.path.exists("nuvio/addon-live/catalog/tv/canlitv.json"):
        lc = json.load(open("nuvio/addon-live/catalog/tv/canlitv.json", encoding="utf-8"))
        live_channels = len(lc.get("metas", []))
    return {
        "aktif": aktif,
        "pasif": pasif,
        "nuvio_total": nuvio_total,
        "nuvio_active": nuvio_active,
        "live_rows": live_rows,
        "live_channels": live_channels,
        "scrapers": scrapers,
    }


def _yeni_readme_metni(eski, m):
    yeni = eski
    aktif_n, pasif_l = len(m["aktif"]), m["pasif"]
    pasif_set = set(pasif_l)

    yeni = re.sub(r"(mod%C3%BCl-)\d+(-blue)", rf"\g<1>{aktif_n}\2", yeni)
    if m["live_rows"] and m["live_channels"]:
        yeni = re.sub(
            r"\| Canlı TV \([^|]+\) \|",
            f"| Canlı TV ({m['live_rows']} satır, {m['live_channels']} kanal) |",
            yeni,
        )
        yeni = re.sub(
            r"(- \*\*Nuvio:\*\* `BigBull Canlı TV` addon'u — )\d+ satır, \d+ kanal",
            rf"\g<1>{m['live_rows']} satır, {m['live_channels']} kanal",
            yeni,
        )
    yeni = re.sub(
        r"\| Film/dizi arama ve oynatma \| Plugin deposu \([^|]+\) \|",
        f"| Film/dizi arama ve oynatma | Plugin deposu ({m['nuvio_total']} sağlayıcı; {m['nuvio_active']} aktif) |",
        yeni,
    )
    yeni = re.sub(r"^## Nuvio sağlayıcıları \(\d+\)", f"## Nuvio sağlayıcıları ({m['nuvio_total']})", yeni, flags=re.M)

    # HDFilmCehennemi2 nuvio listesinde yoksa Film & Dizi satirina ekle
    if any(s.get("id") == "hdfilmcehennemi2" for s in m["scrapers"]) and "`HDFilmCehennemi2`" not in yeni.split("## Nuvio sağlayıcıları")[-1].split("## Geliştiriciler")[0]:
        yeni = re.sub(
            r"(- \*\*Film & Dizi \(4\):\*\* `PuhuTV`, `RecTV`, `Sinemetrik`, `TurkceDizi`)",
            "- **Film & Dizi (5):** `PuhuTV`, `RecTV`, `Sinemetrik`, `TurkceDizi`, `HDFilmCehennemi2`",
            yeni,
        )

    # Kapsam notunu guncelle
    pasif_str = ", ".join(f"`{p}`" for p in pasif_l)
    yeni = re.sub(
        r"\*\*Kapsam notu:\*\* Depoda aktif \*\*\d+ modül\*\*[^\n]*(?:\n[^\n*][^\n]*)*",
        f"**Kapsam notu:** Depoda aktif **{aktif_n} modül** var ve tamamı doğrulanmış durumda (`status = 1`).\n"
        f"`status = 0` ile pasif **{len(pasif_l)} modül**: {pasif_str}.\n"
        "İnatTV/İnatBox ve SineWix upstream sorunları nedeniyle kaldırıldı; düzeldiğinde tekrar eklenir.\n"
        "Bu tablo ve özellik matrisi her sağlayıcı değişikliğinde güncellenir.",
        yeni,
    )

    # 6 ozellik sutununu ve pasif etiketini guncelle
    out_lines = []
    for satir in yeni.splitlines():
        row_m = re.match(r"^\|\s*`([^`]+)`([^|]*)\|\s*([^|]*)\|\s*([^|]*)\|\s*([^|]*)\|\s*([^|]*)\|\s*([^|]*)\|\s*([^|]*)\|\s*([^|]*)\|\s*([^|]*)\|", satir)
        if row_m:
            ad = row_m.group(1).strip()
            kaynak = row_m.group(3).strip()
            tur = row_m.group(4).strip()
            eski_sure = row_m.group(9).strip()
            durum = " **pasif**" if ad in pasif_set else ""
            if ad not in pasif_set and os.path.isdir(f"providers/{ad}/src/main/kotlin"):
                flags = modul_ozellikleri(ad)
                sem = ["✓" if v else "—" for v in flags]
                if flags[4] and eski_sure == "film":
                    sem[4] = "film"
            else:
                sem = [row_m.group(i).strip() for i in range(5, 11)]
            satir = f"| `{ad}`{durum} | {kaynak} | {tur} | {' | '.join(sem)} |"
        out_lines.append(satir)
    return "\n".join(out_lines) + ("\n" if eski.endswith("\n") else "")


def sync_readme():
    m = _readme_metrikleri()
    eski = open("README.md", encoding="utf-8").read()
    yeni = _yeni_readme_metni(eski, m)
    if yeni != eski:
        open("README.md", "w", encoding="utf-8", newline="").write(yeni)
        print(f"README.md güncellendi (aktif={len(m['aktif'])}, pasif={len(m['pasif'])}, nuvio={m['nuvio_total']}, canlı={m['live_channels']})")
    else:
        print("README.md zaten güncel")
    return []


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
        g_types = gradle_tv_types(open(f"{kok}/build.gradle.kts", encoding="utf-8").read())
        k_types = []
        for dirpath, _dirs, files in os.walk(kok):
            for fn in files:
                yol = os.path.join(dirpath, fn)
                if fn == "Settings.kt":
                    has_settings = True
                elif fn.endswith("Plugin.kt"):
                    if "openSettings" in open(yol, encoding="utf-8").read():
                        has_open_settings = True
                elif fn.endswith("Provider.kt"):
                    ptxt = open(yol, encoding="utf-8").read()
                    if re.search(r"\bhasMainPage\s*=\s*false\b", ptxt):
                        has_main_false = True
                    k_types = provider_supported_types(ptxt)
        if not has_settings:
            hata.append(f"{mod}: Settings.kt eksik")
        if not has_open_settings:
            hata.append(f"{mod}: Plugin.kt içinde openSettings eksik")
        if has_main_false:
            hata.append(f"{mod}: hasMainPage = false yasak")
        if g_types != k_types:
            hata.append(f"{mod}: tvTypes {g_types} != supportedTypes {k_types}")

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

    try:
        mevcut_readme = open("README.md", encoding="utf-8").read()
        beklenen_readme = _yeni_readme_metni(mevcut_readme, _readme_metrikleri())
        if mevcut_readme != beklenen_readme:
            hata.append("README.md sayıları veya özellik matrisi güncel değil (python .github/scripts/kapi.py sync-readme çalıştır)")
    except OSError:
        pass
    return hata


def test():
    assert kt_surum("// x\nversion = 10\n\ncloudstream {") == 10
    assert kt_surum("cloudstream { version = 3 }") is None  # satır başı değil
    assert js_surum('// @provider {"id":"a", "version":"1.0.12"}\ncode') == "1.0.12"
    assert js_surum("// @provider {}\n") is None
    assert "Dizibox" not in devre_disi()
    assert "Dizibox" in aktif_moduller()
    ra, rp = readme_satirlari("| `Dizibox` | x |\n| `AsyaLuna` **pasif** | y |\n")
    assert ra == {"Dizibox"} and rp == {"AsyaLuna"}
    common_syms = bump_common.extract_public_symbols("providers/Common/src/main/kotlin/com/bigbull/common/Common.kt")
    assert "pichiveGateNonce" in common_syms and "parseHlsVariants" in common_syms
    assert gradle_tv_types('tvTypes = listOf("TvSeries", "Movie")') == ["Movie", "TvSeries"]
    assert provider_supported_types("override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)") == ["Movie", "TvSeries"]
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
        else sync_readme() if komut == "sync-readme"
        else None
    )
    if hatalar is None:
        sys.exit(__doc__)
    for h in hatalar:
        print("HATA:", h)
    sys.exit(1 if hatalar else 0)
