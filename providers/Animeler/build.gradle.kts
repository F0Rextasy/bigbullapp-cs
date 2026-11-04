version = 5

cloudstream {
    description = "Animeler — Türkçe altyazılı anime arşivi (animeler.live)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("Anime")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=animeler.live&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
