version = 3

cloudstream {
    description = "UltraFilmizle — Türkçe dublaj/altyazılı film arşivi"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=ufilmizle.com&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
