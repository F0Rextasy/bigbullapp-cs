version = 2

cloudstream {
    description = "YerliDiziler — yerlidiziler.com.tr (Güncel Türkçe yerli diziler)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("TvSeries")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=yerlidiziler.com.tr&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
