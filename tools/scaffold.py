#!/usr/bin/env python3
"""Scaffold a new provider module from the registry.

Usage:
    python tools/scaffold.py dizipal.com            # creates Dizipal/ skeleton
    python tools/scaffold.py dizipal.com --dry-run
"""
import argparse
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
REGISTRY = ROOT / "tools" / "providers.json"

BUILD = """version = 1

cloudstream {{
    description = "{name} — {domain}"
    authors = listOf("{author}")
    status = 3
    tvTypes = listOf("TvSeries", "Movie")
    language = "{lang}"
    iconUrl = "https://www.google.com/s2/favicons?domain={domain}&sz=%size%"
}}
"""

MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest />
"""

PLUGIN = """package {package}

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class {name}Plugin : Plugin() {{
    override fun load(context: Context) {{
        registerMainAPI({name}Provider())
    }}
}}
"""

PROVIDER = """package {package}

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * {name} — {url}
 *
 * TODO(provider): fill search/load/loadLinks against the live site.
 * Verify with: python tools/check.py --domain {domain}
 * Build with:  ./gradlew {name}:make
 */
class {name}Provider : MainAPI() {{
    override var mainUrl = "{url}"
    override var name = "{name}"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override suspend fun search(query: String): List<SearchResponse> {
        // TODO(provider): parse search results
        return emptyList()
    }}

    override suspend fun load(url: String): LoadResponse? {
        // TODO(provider): parse detail page and episodes
        return null
    }}

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // TODO(provider): resolve player iframes to a stream
        return false
    }}
}}
"""


def slug_to_class(domain: str) -> str:
    token = re.split(r"[.\-]", domain)[0]
    return "".join(part.capitalize() for part in re.split(r"[^a-zA-Z0-9]+", token) if part) or "Provider"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("domain")
    parser.add_argument("--author", default="F0Rextasy")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    data = json.loads(REGISTRY.read_text(encoding="utf-8"))
    entry = data["providers"].get(args.domain)
    if not entry:
        print(f"{args.domain} not in tools/providers.json")
        return 1

    name = slug_to_class(args.domain)
    package = "com.bigbull." + name.lower()
    module = ROOT / name
    files = {
        module / "build.gradle.kts": BUILD.format(
            name=name, domain=args.domain, author=args.author, lang=entry.get("language", "tr")
        ),
        module / "src/main/AndroidManifest.xml": MANIFEST,
        module / f"src/main/kotlin/{package.replace('.', '/')}/{name}Plugin.kt": PLUGIN.format(
            package=package, name=name
        ),
        module / f"src/main/kotlin/{package.replace('.', '/')}/{name}Provider.kt": PROVIDER.format(
            package=package, name=name, url=entry["mainUrl"], domain=args.domain
        ),
    }

    for path, content in files.items():
        print(("would write " if args.dry_run else "write ") + str(path.relative_to(ROOT)))
        if args.dry_run:
            continue
        if path.exists():
            print(f"  exists, skipped: {path.relative_to(ROOT)}")
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    if not args.dry_run:
        entry["module"] = name
        entry["status"] = "scaffold"
        REGISTRY.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"\nnext: implement {name}Provider, then ./gradlew {name}:make")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
