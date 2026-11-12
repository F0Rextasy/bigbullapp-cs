version = 1

cloudstream {
    description = "RoketDizi — yabancı dizi & film, Türkçe dublaj/altyazı (roketdizi.life)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("TvSeries", "Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=roketdizi.life&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
