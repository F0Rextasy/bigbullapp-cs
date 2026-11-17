version = 1

cloudstream {
    description = "VenusFilmizle — Türkçe dublaj & altyazılı film arşivi (venusfilmizle.com)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=venusfilmizle.com&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
