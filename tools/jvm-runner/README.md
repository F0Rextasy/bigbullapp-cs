# JVM provider runner (spike)

Runs real provider Kotlin logic on the desktop JVM with a small Cloudstream API stub.
No emulator needed for search/detail/link checks.

## Run CLI

```powershell
python tools/jvm-runner/sync.py
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot'
.\gradlew.bat -p tools/jvm-runner run
```

## Web UI

```powershell
.\gradlew.bat -p tools/jvm-runner run --args="--serve"
# http://127.0.0.1:8099
```

Endpoints:
- `/search?module=AllCalidad&q=amor`
- `/load?module=AllCalidad&url=...`
- `/links?module=AllCalidad&data=...`

## Scope

- `sync.py` copies **only** `*Provider.kt` from `providers/<Module>` into `generated/`
  and creates a stub `Settings` per package. No source duplication in git.
- Supported so far: AllCalidad, DiziMom, Monoschinos.
- `loadExtractor` is a stub returning false; providers that rely on external extractors
  (e.g. Monoschinos mirrors) report no links here. Direct HTTP/HLS providers work.
- Add a module: extend `MODULES` in `sync.py`, the `modules()` map in `Main.kt`,
  and run again. Missing API stubs are added to `src/main/kotlin/com/lagradost/cloudstream3`.
