version = 1
cloudstream {
    description = "Inat Baks — Yerli Filmler (si̇nema & fi̇lmler, imzalı Inat API)"
    authors = listOf("F0Rextasy")
    status = 3
    tvTypes = listOf("Live", "Movie", "TvSeries")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=diziboxen.help&sz=%size%"
}
android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/InatShared/src/main/kotlin"))
}
