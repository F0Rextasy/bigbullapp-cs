version = 7

cloudstream {
    description = "DiziboxIzle — güncel yabancı dizi (diziboxizle.com)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("TvSeries")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=diziboxizle.com&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
