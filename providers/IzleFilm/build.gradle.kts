version = 1

cloudstream {
    description = "IzleFilm — izlefilm.net Full HD Türkçe dublaj/altyazılı film arşivi"
    authors = listOf("F0Rextasy")
    status = 3
    tvTypes = listOf("Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=izlefilm.net&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
