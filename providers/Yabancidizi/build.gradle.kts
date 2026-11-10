// Use an integer for version numbers
version = 12


cloudstream {
    description = "Yabancıdizi — ybncidizi.com (dizi, film, anime)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("TvSeries", "Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=ybncidizi.com&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
