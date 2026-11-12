version = 1

cloudstream {
    description = "TrDiziIzle — trdiziizle.tv (Türkçe dizi arşivi, son bölümler, IMDb sıralı liste)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("TvSeries")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=trdiziizle.tv&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
