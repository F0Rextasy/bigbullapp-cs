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
    // Düzeltildi (dizi sayfaları + arama); cihaz/runner doğrulaması bekliyor, geçici pasif.
    "MinikaCocuk",
    // atv.com.tr /izle endpoint'i 2026-11-02'den beri timeout; oynatılabilir VOD yok.
    "ATV",
    // Nuvio tarafinda enabled:true birakilanlar: inat4k06web, inattod,
    // inatyabancidiziler, inatyerlidiziler (nuvio/manifest.json); canli dogrulama
    // Nuvio'dan akiyor, CS tarafi runner'i bekliyor.
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
