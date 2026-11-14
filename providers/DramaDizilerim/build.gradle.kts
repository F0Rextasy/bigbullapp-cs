version = 8


cloudstream {
    description = "DramaDizilerim — dikey mini dizi & kısa dizi arşivi, Türkçe dublaj/altyazılı (dramadizilerim1.com)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("TvSeries", "Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=dramadizilerim1.com&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
