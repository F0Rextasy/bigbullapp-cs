// Use an integer for version numbers
version = 9


cloudstream {
    description = "Yabancıdizi — yabancidizi.watch (dizi, film, anime)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("TvSeries", "Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=yabancidizi.watch&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
