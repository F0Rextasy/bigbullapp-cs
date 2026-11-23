version = 3


cloudstream {
    description = "CizgiVeDizi — Türkçe çizgi dizi, anime, dizi ve film arşivi (cizgivedizi.net)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("TvSeries", "Movie", "Anime")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=cizgivedizi.net&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
