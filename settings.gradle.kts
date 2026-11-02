rootProject.name = "CloudstreamPlugins"

// Every directory under providers/ that contains a build.gradle.kts is a plugin module.
// Project path stays :ModuleName, so `./gradlew Dizibox:make` keeps working.

val disabled = listOf(
    // Runner 2026-11-02: pass olmayan Inat platformları; kaynak korunuyor.
    "Inat4k01Exo", "Inat4k01Web", "Inat4k02Web", "Inat4k04Exo", "Inat4k04Web",
    "Inat4k06Web", "Inat4k07Web", "InatBelgesel", "InatCocuk", "InatDini",
    "InatDerbiler", "InatEbaTv", "InatHaber", "InatListe1Tr", "InatListe2Glb",
    "InatListe3Tr", "InatMubi", "InatSinema", "InatPremium", "InatSpor",
    "InatTod", "InatUlusal", "InatYabanciDiziler", "InatYerliDiziler",
    // P2P/WebRTC oynatıcı doğrudan medya vermiyor; yayınlanmıyor.
    "SelcukSports",
)

File(rootDir, "providers").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
        project(":${dir.name}").projectDir = dir
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
