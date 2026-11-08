version = 1
cloudstream {
    description = "Inat Baks — Çocuk (temati̇k & tv, imzalı Inat API)"
    authors = listOf("F0Rextasy")
    status = 0
    tvTypes = listOf("Live", "Movie", "TvSeries")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=diziboxen.help&sz=%size%"
}
android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/InatShared/src/main/kotlin"))
}
