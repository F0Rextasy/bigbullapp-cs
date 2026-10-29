import pathlib, shutil, re

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = pathlib.Path(__file__).resolve().parent / "generated"
if OUT.exists():
    shutil.rmtree(OUT)
OUT.mkdir(parents=True)

MODULES = {
    "AllCalidad": ("com.allcalidad", "amor"),
    "DiziMom": ("com.dizimom", "yalan"),
    "Monoschinos": ("com.monoschinos", "slime"),
}

for module, (pkg, _) in MODULES.items():
    src = next((ROOT / "providers" / module).rglob("*Provider.kt"))
    text = src.read_text(encoding="utf-8")
    dest = OUT / pkg.replace(".", "/") / src.name
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(text, encoding="utf-8")

    settings = OUT / pkg.replace(".", "/") / "Settings.kt"
    settings.write_text(
        "package " + pkg + "\n\n"
        "object Settings {\n"
        "    fun qualityAllowed(quality: Int): Boolean = true\n"
        "    fun subtitleAllowed(lang: String): Boolean = true\n"
        "}\n",
        encoding="utf-8",
    )

print("generated", len(MODULES), "providers")
