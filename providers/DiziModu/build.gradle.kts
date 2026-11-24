version = 6


cloudstream {
    description = "DiziModu — ToroFlix yabancı dizi arşivi (dizimodu.com)"
    status = 0
    authors = listOf("F0Rextasy")
    tvTypes = listOf("TvSeries")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=dizimodu.com&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
