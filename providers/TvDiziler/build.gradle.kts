version = 2

cloudstream {
    description = "TvDiziler — Türkçe yerli dizi arşivi (tvdiziler.tv)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("TvSeries")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=tvdiziler.tv&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
