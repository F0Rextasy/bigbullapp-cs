version = 1

cloudstream {
    description = "Sinemetrik — sinemetrik.com Türkçe film/dizi platformu (kendi player'ı, TR altyazı)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=sinemetrik.com&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
