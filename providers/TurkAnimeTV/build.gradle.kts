version = 1
cloudstream {
    description = "Türk Anime TV — Türkçe altyazılı anime izle (turkanime.live)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=turkanime.live&sz=%size%"
}
android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
