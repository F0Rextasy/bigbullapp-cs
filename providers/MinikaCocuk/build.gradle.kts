version = 7

cloudstream {
    description = "MinikaCocuk — Türkçe çocuk çizgi filmleri"
    authors = listOf("F0Rextasy")
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=minikacocuk.com.tr&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
